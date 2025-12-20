# Core Modules Anti-Patterns

**Modules:**
- `core/model/` - Entity definitions
- `core/network/` - Retrofit, OkHttp, API
- `core/data/` - Shared data contracts
- `core/design/` - Shared UI components

---

## Critical Issues

### 1. Unsafe SSL/TLS Certificate Verification (SECURITY)

**Location:** `core/network/src/main/kotlin/com/bothbubbles/core/network/di/CoreNetworkModule.kt` (Lines 63-98)

**Issue:**
```kotlin
@Provides
@Singleton
fun provideTrustManager(): X509TrustManager {
    return object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // EMPTY - Accepts all!
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // EMPTY - Accepts all!
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}

// Line 98:
.hostnameVerifier { _, _ -> true }  // Accepts any hostname!
```

**Why Problematic:**
- Completely disables SSL/TLS certificate validation
- Enables Man-in-the-Middle (MITM) attacks
- Any attacker on the network can intercept traffic
- Violates Android security best practices
- Comment says "for self-signed BlueBubbles servers" but this is still dangerous

**Fix:**
Implement certificate pinning or user-provided trust:
```kotlin
// Option 1: Certificate pinning
val certificatePinner = CertificatePinner.Builder()
    .add(serverHost, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    .build()

// Option 2: User-installed certificate
// Show user how to add server certificate to device trust store

// Option 3: Trust on first use (TOFU)
// Store server certificate hash on first connection, verify on subsequent
```

---

## High Severity Issues

### 2. runBlocking on OkHttp Thread ✅ FIXED

**Location:** `core/network/src/main/kotlin/com/bothbubbles/core/network/api/AuthInterceptor.kt` (Lines 114-144)

**Issue:**
```kotlin
private fun getCredentialsBlocking(): CachedCredentials {
    if (_initialized.value) {
        return _credentials.value
    }

    // Slow path: fetch from provider with timeout
    return runBlocking {  // BLOCKING on OkHttp dispatcher thread!
        withTimeoutOrNull(INIT_TIMEOUT_MS) {  // 3 seconds
            val serverAddress = credentialsProvider.getServerAddress()
            val authKey = credentialsProvider.getAuthKey()
            val customHeaders = credentialsProvider.getCustomHeaders()
            // ...
        }
    }
}
```

**Why Problematic:**
- `runBlocking` on OkHttp's thread pool causes thread starvation
- 3-second timeout is significant - can slow ALL network requests
- Other requests wait while credentials load
- Can cause cascading delays across the app

**Fix Applied:**
Pre-initialize credentials during app startup with fail-fast pattern:
```kotlin
@Singleton
class AuthInterceptor @Inject constructor(...) {

    @Volatile
    private var cachedCredentials: CachedCredentials? = null

    suspend fun preInitialize() {
        val serverAddress = credentialsProvider.getServerAddress()
        val authKey = credentialsProvider.getAuthKey()
        val customHeaders = credentialsProvider.getCustomHeaders()

        cachedCredentials = CachedCredentials(
            serverAddress = serverAddress,
            authKey = authKey,
            customHeaders = customHeaders
        )
        Timber.d("AuthInterceptor initialized with server: ${serverAddress.take(30)}...")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val credentials = cachedCredentials
            ?: throw IllegalStateException(
                "AuthInterceptor not initialized. Call preInitialize() before making network requests."
            )
        // ...
    }
}
```

**Initialization:**
Called in `BothBubblesApp.onCreate()`:
```kotlin
private fun initializeAuthInterceptor() {
    applicationScope.launch(ioDispatcher) {
        try {
            authInterceptor.preInitialize()
            Timber.d("AuthInterceptor credentials cache initialized")
        } catch (e: Exception) {
            Timber.w(e, "Error initializing AuthInterceptor")
        }
    }
}
```

**Benefits:**
- No blocking on OkHttp thread pool
- Credentials loaded once on app startup
- Fail-fast error if not initialized (helps catch misuse early)
- `invalidateCache()` method for refreshing when settings change

---

## Medium Severity Issues

### 3. Hardcoded Placeholder Base URL

**Location:** `core/network/src/main/kotlin/com/bothbubbles/core/network/di/CoreNetworkModule.kt` (Line 111)

**Issue:**
```kotlin
return Retrofit.Builder()
    .baseUrl("http://localhost/")  // Placeholder, dynamically replaced
    .client(okHttpClient)
    .build()
```

**Why Problematic:**
- If dynamic URL replacement fails, requests go to localhost
- Leaks implementation detail
- Could be confused with actual configuration
- No validation that URL was actually replaced

**Fix:**
```kotlin
// Use a sentinel URL and validate
private const val PLACEHOLDER_URL = "http://placeholder.invalid/"

return Retrofit.Builder()
    .baseUrl(PLACEHOLDER_URL)
    .client(okHttpClient)
    .build()

// In interceptor, validate URL was replaced:
if (request.url.host == "placeholder.invalid") {
    throw IllegalStateException("Server URL not configured")
}
```

---

### 4. Missing Retry Logic for Network Requests

**Location:** `core/network/src/main/kotlin/com/bothbubbles/core/network/api/BothBubblesApi.kt`

**Issue:**
All API endpoints have no built-in retry logic:
```kotlin
@GET("api/v1/ping")
suspend fun ping(): Response<ApiResponse<Unit>>

@GET("api/v1/server/info")
suspend fun getServerInfo(): Response<ApiResponse<ServerInfoDto>>
// etc.
```

**Why Problematic:**
- Network transient failures not handled
- No exponential backoff for rate limiting
- Caller must implement retries manually
- Inconsistent retry behavior across codebase

**Fix:**
Add retry interceptor:
```kotlin
class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var lastException: IOException? = null

        while (attempt < maxRetries) {
            try {
                val response = chain.proceed(chain.request())
                if (response.isSuccessful || response.code !in 500..599) {
                    return response
                }
            } catch (e: IOException) {
                lastException = e
            }
            attempt++
            Thread.sleep(1000L * (1 shl attempt))  // Exponential backoff
        }
        throw lastException ?: IOException("Max retries exceeded")
    }
}
```

---

## Low Severity Issues

### 5. Unsafe !! Operator in AttachmentEntity

**Location:** `core/model/src/main/kotlin/com/bothbubbles/core/model/entity/AttachmentEntity.kt` (Line 197)

**Issue:**
```kotlin
val aspectRatio: Float
    get() = if (hasValidSize) width!!.toFloat() / height!!.toFloat() else 1f
```

**Why Problematic:**
- `!!` assumes width/height are non-null if `hasValidSize` is true
- If there's a bug in `hasValidSize`, this crashes
- Could cause NPE in production

**Fix:**
```kotlin
val aspectRatio: Float
    get() = if (width != null && height != null && width > 0 && height > 0) {
        width.toFloat() / height.toFloat()
    } else 1f
```

---

### 6. System.currentTimeMillis() in Lazy Properties

**Location:** `core/model/src/main/kotlin/com/bothbubbles/core/model/entity/ChatEntity.kt` (Line 198)

**Issue:**
```kotlin
val isSnoozed: Boolean
    get() = snoozeUntil != null && (snoozeUntil == -1L || snoozeUntil > System.currentTimeMillis())
```

**Why Problematic:**
- Called every time property is accessed
- In a list of 100 chats, this runs 100 times per render
- Hard to test (time-dependent)

**Fix:**
```kotlin
// Pass current time from caller or use injectable clock
fun isSnoozedAt(currentTimeMillis: Long): Boolean {
    return snoozeUntil != null && (snoozeUntil == -1L || snoozeUntil > currentTimeMillis)
}
```

---

### 7. Entity Computed Properties with Business Logic

**Locations:**
- `core/model/.../MessageEntity.kt` (Lines 168-204)
- `core/model/.../AttachmentEntity.kt` (Lines 118-215)
- `core/model/.../ChatEntity.kt` (Lines 161-199)

**Issue:**
```kotlin
// MessageEntity
val isSent: Boolean
    get() = error == 0 && !guid.startsWith("temp-")

val isReaction: Boolean
    get() = isReactionDb || ReactionClassifier.isReaction(associatedMessageGuid, associatedMessageType)

// ChatEntity
val isTextForwarding: Boolean
    get() = guid.startsWith("SMS;")

val isSmsChat: Boolean
    get() = isLocalSms || isTextForwarding || isRcs
```

**Why Problematic:**
- Blurs line between data and logic
- Business logic scattered across entities
- ReactionClassifier.isReaction() is a static call with logic
- Makes entities harder to reason about

**Note:** This is actually a MINOR issue - these are simple read-only computations. Acceptable pattern for convenience properties.

---

### 8. Cross-Layer Import in Design Module

**Location:** `core/design/.../SettingsComponents.kt` (Line 45)

**Issue:**
```kotlin
import com.bothbubbles.core.data.ConnectionState
```

**Why Problematic:**
- Design (UI) module imports from data module
- Creates dependency from design → data
- ConnectionState enum could live in core/model instead

**Note:** Acceptable since ConnectionState is a simple enum. Consider moving to core/model if this dependency becomes problematic.

---

## Module Architecture (POSITIVE)

The core modules have **excellent** dependency organization:
- `core/model` has NO external dependencies
- `core/network` depends only on `core/model`
- `core/data` depends only on `core/model`
- **No circular dependencies detected**
- **No core modules depend on app module**

This is correct clean architecture.

---

## Summary Table

| Issue | Severity | File | Lines | Category |
|-------|----------|------|-------|----------|
| Unsafe SSL/TLS | CRITICAL | CoreNetworkModule.kt | 63-98 | Security |
| runBlocking on OkHttp | HIGH | AuthInterceptor.kt | 114-144 | Threading |
| Placeholder Base URL | MEDIUM | CoreNetworkModule.kt | 111 | Implementation |
| Missing Retry Logic | MEDIUM | BothBubblesApi.kt | - | Reliability |
| Unsafe !! Operator | LOW | AttachmentEntity.kt | 197 | Null Safety |
| System.currentTimeMillis() | LOW | ChatEntity.kt | 198 | Performance |
| Business Logic in Entities | LOW | Multiple | Various | Architecture |
| Cross-Layer Import | LOW | SettingsComponents.kt | 45 | Layering |

---

## Positive Findings

- **Excellent module boundaries** - no cycles, correct layering
- All DTOs properly annotated with `@JsonClass(generateAdapter = true)`
- Good use of sealed classes (AttachmentErrorState)
- Interface-based abstraction for core services (ServerConnectionProvider, etc.)
- Consistent use of immutable data classes
- ReactionClassifier centralization is good
- ProgressRequestBody and FileStreamingRequestBody well-implemented
