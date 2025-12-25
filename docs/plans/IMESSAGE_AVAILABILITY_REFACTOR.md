# iMessage Availability Refactoring Plan

## Executive Summary

The codebase has **fragmented iMessage availability logic** across 6+ locations with inconsistent behavior, duplicate code, and unclear responsibility boundaries. This plan consolidates everything into a **single source of truth** with a clear priority hierarchy, consistent retry logic, and proper lifecycle management.

---

## Current State Analysis

### Two Parallel Systems

| System | Location | Used By | Cache | HTTP Client |
|--------|----------|---------|-------|-------------|
| `IMessageAvailabilityService` | `services/imessage/` | ChatSendModeManager, VoiceMessageService, AutoResponderService | 24h TTL | HttpURLConnection |
| Inline API Checks | ChatCreationDelegate, RecipientDelegate | New chat flows | None | Retrofit |

### ALL Message Sending Entry Points (Complete List)

| Entry Point | File | iMessage Check? | Notes |
|-------------|------|-----------------|-------|
| **Start Chat** | `ChatCreationDelegate.kt:86` | Inline API (3s timeout) | New 1-on-1 chat creation |
| **Existing Chat** | `ChatSendModeManager.kt:140` | IMessageAvailabilityService | Send mode toggle for open chats |
| **Compose Chips** | `RecipientDelegate.kt:215` | Inline API (no timeout) | Apple-style compose screen |
| **Push Reply** | `NotificationReceiver.kt:81` | None (AUTO mode) | Notification direct reply |
| **Forward** | `MessageSendingService.kt:584` | None (AUTO mode) | Forward to another chat |
| **Voice (Google Assistant)** | `VoiceMessageService.kt:417` | IMessageAvailabilityService | Headless voice commands |
| **Auto Responder** | `AutoResponderService.kt:99` | IMessageAvailabilityService | Auto-reply to SMS senders |
| **Scheduled Messages** | `ScheduledMessageWorker.kt:72` | None (existing chatGuid) | Sends to pre-determined chat |
| **ETA Sharing** | `EtaSharingManager.kt:717` | None (existing chatGuid) | User already selected recipient |
| **Share Sheet** | `MainActivity.kt` → Compose | Via ComposeViewModel | Routes to Compose or direct chat |
| **Android Auto** | `BothBubblesCarAppService.kt` | Via notifications | Uses MessagingStyle notification replies |
| **Background Send** | `MessageSendWorker.kt` | Via determineDeliveryMode() | Deferred message queue |

### Entry Points with Availability Logic (Detailed)

| Entry Point | File | Method | Has Timeout | Has Cache |
|-------------|------|--------|-------------|-----------|
| Start Chat | `ChatCreationDelegate.kt:86` | `determineServiceForAddress()` | 3s | No |
| Existing Chat | `ChatSendModeManager.kt:140` | `checkIMessageAvailability()` | No | Yes (via service) |
| Compose Chips | `RecipientDelegate.kt:215` | `checkAndUpdateService()` | No | Partial (handles) |
| Voice Commands | `VoiceMessageService.kt:417` | `resolvePhoneNumberToChatGuid()` | No | Yes (via service) |
| Auto Responder | `AutoResponderService.kt:99` | `maybeAutoRespond()` | No | Yes (via service) |
| Push Reply | `NotificationReceiver.kt` | Uses AUTO mode | N/A | N/A |
| Forward | `MessageSendingService.kt:584` | Uses AUTO mode | N/A | N/A |
| Send (final) | `MessageSendingService.kt:636` | `determineDeliveryMode()` | N/A | N/A |

### What's Already Correct (No Changes Needed)

These entry points already use `IMessageAvailabilityService` or properly delegate to `determineDeliveryMode()`:

| Entry Point | Current Behavior | Assessment |
|-------------|------------------|------------|
| **VoiceMessageService** | Uses `IMessageAvailabilityService.checkAvailability()` | **GOOD** - Already uses central service |
| **AutoResponderService** | Uses `IMessageAvailabilityService.checkAvailability()` | **GOOD** - Already uses central service |
| **Push Reply** | Uses AUTO → `determineDeliveryMode()` | **GOOD** - Final routing handles it |
| **Forward** | Uses AUTO → `determineDeliveryMode()` | **GOOD** - Final routing handles it |
| **Scheduled Messages** | Sends to existing chatGuid | **OK** - Chat already exists, user chose it |
| **ETA Sharing** | Sends to user-selected chat | **OK** - User explicitly chose recipient |
| **Android Auto** | Via notification replies | **OK** - Uses NotificationReceiver flow |
| **Background Send** | Via `determineDeliveryMode()` | **GOOD** - Final routing handles it |

### What Needs Refactoring

| Entry Point | Current Problem | Proposed Fix |
|-------------|-----------------|--------------|
| **ChatCreationDelegate** | Inline API call with custom timeout, no cache | Use `AddressServiceResolver` |
| **RecipientDelegate** | Inline API call, no timeout, partial cache (handles only) | Use `AddressServiceResolver` |
| **ChatSendModeManager** | Uses service but also has server stability logic | Keep stability logic, use `AddressServiceResolver` for checks |
| **IMessageAvailabilityService** | Uses HttpURLConnection instead of Retrofit | Refactor to use Retrofit via new resolver |

### Critical Inconsistencies

1. **Email Validation** - 3 different implementations:
   - `contains("@")` (ChatCreationDelegate, ChatSendModeManager)
   - `contains("@") && contains(".") && length > 5` (RecipientDelegate)

2. **Connection State Freshness** - Using stale `.value` reads instead of Flow observation

3. **HTTP Clients** - IMessageAvailabilityService uses HttpURLConnection while everything else uses Retrofit

4. **Fallback Mode Lifecycle** - MessageSendingService enters but never exits fallback mode

---

## Proposed Architecture

### New Component: `AddressServiceResolver`

A **single source of truth** for determining the service (iMessage/SMS) for any address.

```
┌─────────────────────────────────────────────────────────────────────┐
│                     AddressServiceResolver                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    Service Resolution                         │   │
│  │  1. Format validation (email/phone)                          │   │
│  │  2. Settings check (SMS-only mode)                           │   │
│  │  3. Cache lookup (IMessageAvailabilityCache)                 │   │
│  │  4. Local handle lookup (HandleRepository)                   │   │
│  │  5. Server API check (with timeout + retry)                  │   │
│  │  6. Activity history fallback (ChatRepository)               │   │
│  │  7. Default (SMS for phones, iMessage for emails)            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  Dependencies:                                                       │
│  - BothBubblesApi (Retrofit - unified HTTP client)                  │
│  - IMessageAvailabilityCache (Room DAO - replaces current service)  │
│  - HandleRepository (local handle lookup)                           │
│  - ChatRepository (activity history)                                │
│  - SettingsDataStore (SMS-only mode)                                │
│  - SocketConnection (connection state)                              │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    All Entry Points Use This                         │
│  - ChatCreationDelegate.determineServiceForAddress()                │
│  - RecipientDelegate.checkAndUpdateService()                        │
│  - ChatSendModeManager.checkIMessageAvailability()                  │
│  - MessageSendingService.determineDeliveryMode() (for new chats)    │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Priority Hierarchy (Final Design)

The service resolution follows this **strict priority order**:

```kotlin
suspend fun resolveService(
    address: String,
    options: ResolutionOptions = ResolutionOptions()
): ServiceResolution {

    // ═══════════════════════════════════════════════════════════════
    // TIER 1: IMMEDIATE RETURNS (No network, no cache lookup)
    // ═══════════════════════════════════════════════════════════════

    // 1.1 Format validation
    val addressType = AddressValidator.validate(address)
    if (addressType == AddressType.INVALID) {
        return ServiceResolution.Invalid("Invalid address format")
    }

    // 1.2 Email → Always iMessage (no lookup needed)
    if (addressType == AddressType.EMAIL) {
        return ServiceResolution.Resolved(Service.IMESSAGE, Source.EMAIL_RULE)
    }

    // 1.3 SMS-only mode → Always SMS
    if (settingsDataStore.smsOnlyMode.first()) {
        return ServiceResolution.Resolved(Service.SMS, Source.SMS_ONLY_MODE)
    }

    // ═══════════════════════════════════════════════════════════════
    // TIER 2: LOCAL LOOKUPS (Fast, no network)
    // ═══════════════════════════════════════════════════════════════

    val normalized = AddressValidator.normalize(address)

    // 2.1 Fresh cache hit (within TTL, not from previous session)
    if (!options.forceRecheck) {
        val cached = cache.getFreshResult(normalized)
        if (cached != null) {
            return ServiceResolution.Resolved(
                if (cached.available) Service.IMESSAGE else Service.SMS,
                Source.CACHE
            )
        }
    }

    // 2.2 Local handle with known iMessage status
    val handle = handleRepository.getHandleByAddressAny(normalized)
    if (handle?.isIMessage == true) {
        return ServiceResolution.Resolved(Service.IMESSAGE, Source.LOCAL_HANDLE)
    }

    // ═══════════════════════════════════════════════════════════════
    // TIER 3: SERVER CHECK (Network required)
    // ═══════════════════════════════════════════════════════════════

    // 3.1 Check server connection
    if (!isServerConnected()) {
        // Mark as UNREACHABLE in cache (will re-check on reconnect)
        cache.markUnreachable(normalized)
        // Fall through to Tier 4
    } else {
        // 3.2 API check with timeout and retry
        val apiResult = checkWithServer(normalized, options.timeout)

        when (apiResult) {
            is ApiResult.Available -> {
                cache.cacheResult(normalized, available = true)
                return ServiceResolution.Resolved(Service.IMESSAGE, Source.SERVER_API)
            }
            is ApiResult.NotAvailable -> {
                cache.cacheResult(normalized, available = false)
                return ServiceResolution.Resolved(Service.SMS, Source.SERVER_API)
            }
            is ApiResult.Timeout, is ApiResult.Error -> {
                // Don't cache timeouts (server instability)
                // Fall through to Tier 4
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TIER 4: FALLBACK STRATEGIES (When server unavailable)
    // ═══════════════════════════════════════════════════════════════

    // 4.1 Stale cache (expired but still useful as hint)
    val staleCache = cache.getStaleResult(normalized)
    if (staleCache != null && options.allowStaleCache) {
        return ServiceResolution.Resolved(
            if (staleCache.available) Service.IMESSAGE else Service.SMS,
            Source.STALE_CACHE
        )
    }

    // 4.2 Activity history (most recent chat with messages)
    val activityService = chatRepository.getServiceFromMostRecentActiveChat(normalized)
    if (activityService != null) {
        return ServiceResolution.Resolved(
            if (activityService.equals("iMessage", ignoreCase = true))
                Service.IMESSAGE else Service.SMS,
            Source.ACTIVITY_HISTORY
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // TIER 5: DEFAULT (Last resort)
    // ═══════════════════════════════════════════════════════════════

    // Phone numbers default to SMS (safer default)
    return ServiceResolution.Resolved(Service.SMS, Source.DEFAULT)
}
```

---

## Data Types

### ServiceResolution

```kotlin
sealed class ServiceResolution {
    data class Resolved(
        val service: Service,
        val source: ResolutionSource,
        val confidence: Confidence = Confidence.HIGH
    ) : ServiceResolution()

    data class Invalid(val reason: String) : ServiceResolution()

    data class Pending(val initialService: Service) : ServiceResolution()
}

enum class Service {
    IMESSAGE,
    SMS
}

enum class ResolutionSource {
    EMAIL_RULE,        // Email addresses are always iMessage
    SMS_ONLY_MODE,     // User has SMS-only mode enabled
    CACHE,             // Fresh cache hit
    LOCAL_HANDLE,      // Local handle database
    SERVER_API,        // Server availability check
    STALE_CACHE,       // Expired cache (fallback)
    ACTIVITY_HISTORY,  // Most recent chat with this address
    DEFAULT            // No data, using default (SMS for phones)
}

enum class Confidence {
    HIGH,              // Direct server confirmation or email rule
    MEDIUM,            // Cache or local handle
    LOW                // Stale cache, activity history, or default
}
```

### ResolutionOptions

```kotlin
data class ResolutionOptions(
    val forceRecheck: Boolean = false,      // Bypass cache
    val timeout: Duration = 3.seconds,       // API timeout
    val allowStaleCache: Boolean = true,     // Use expired cache as fallback
    val requireServerStability: Boolean = false  // For mode switching
)
```

---

## Address Validation (Unified)

Create a single `AddressValidator` utility:

```kotlin
object AddressValidator {

    sealed class AddressType {
        object Email : AddressType()
        object Phone : AddressType()
        object Invalid : AddressType()
    }

    /**
     * Validate and classify an address.
     *
     * Email rules:
     * - Contains "@"
     * - Contains "." after "@"
     * - At least 5 characters (a@b.c)
     *
     * Phone rules:
     * - After removing non-digits (except +):
     *   - Starts with "+" OR
     *   - Has 10+ digits
     */
    fun validate(address: String): AddressType {
        val trimmed = address.trim()

        // Email check (most restrictive)
        if (isEmail(trimmed)) return AddressType.Email

        // Phone check
        if (isPhone(trimmed)) return AddressType.Phone

        return AddressType.Invalid
    }

    fun isEmail(address: String): Boolean {
        val atIndex = address.indexOf('@')
        if (atIndex < 1) return false  // No @ or @ at start

        val afterAt = address.substring(atIndex + 1)
        if (!afterAt.contains('.')) return false  // No . after @

        return address.length >= 5  // Minimum: a@b.c
    }

    fun isPhone(address: String): Boolean {
        val cleaned = address.replace(Regex("[^0-9+]"), "")
        return cleaned.startsWith("+") ||
               (cleaned.length >= 10 && cleaned.all { it.isDigit() })
    }

    /**
     * Normalize address for consistent cache keys and lookups.
     * - Emails: lowercase
     * - Phones: E.164 format (+1234567890)
     */
    fun normalize(address: String): String {
        val trimmed = address.trim()

        if (isEmail(trimmed)) {
            return trimmed.lowercase()
        }

        return PhoneNumberFormatter.normalize(trimmed) ?: trimmed
    }
}
```

---

## Cache Strategy

### IMessageAvailabilityCache

Replace `IMessageAvailabilityService` with a simpler cache + resolver pattern:

```kotlin
@Singleton
class IMessageAvailabilityCache @Inject constructor(
    private val cacheDao: IMessageCacheDao,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val dispatcher: CoroutineDispatcher
) {
    // Session ID for detecting stale cache from previous app session
    private val sessionId = UUID.randomUUID().toString()

    companion object {
        val FRESH_TTL = 24.hours
        val ERROR_TTL = 5.minutes
        val STALE_GRACE_PERIOD = 7.days  // Keep stale entries for fallback
    }

    /**
     * Get a fresh (non-expired) cache result.
     */
    suspend fun getFreshResult(address: String): CacheResult? {
        val entry = cacheDao.getCache(address) ?: return null

        // UNREACHABLE entries are not valid cache hits
        if (entry.checkResult == CheckResult.UNREACHABLE.name) return null

        // Check expiration
        if (entry.isExpired()) return null

        return CacheResult(
            available = entry.isIMessageAvailable ?: false,
            fromPreviousSession = entry.sessionId != sessionId
        )
    }

    /**
     * Get a stale (expired) cache result for fallback.
     */
    suspend fun getStaleResult(address: String): CacheResult? {
        val entry = cacheDao.getCache(address) ?: return null

        if (entry.checkResult == CheckResult.UNREACHABLE.name) return null

        // Return even if expired (for fallback)
        return CacheResult(
            available = entry.isIMessageAvailable ?: false,
            fromPreviousSession = entry.sessionId != sessionId,
            isStale = entry.isExpired()
        )
    }

    /**
     * Cache a successful availability check result.
     */
    suspend fun cacheResult(address: String, available: Boolean) {
        val entity = if (available) {
            IMessageAvailabilityCacheEntity.createAvailable(address, sessionId)
        } else {
            IMessageAvailabilityCacheEntity.createNotAvailable(address, sessionId)
        }
        cacheDao.insertOrUpdate(entity)
    }

    /**
     * Mark an address as unreachable (server disconnected).
     * These entries are re-checked when server reconnects.
     */
    suspend fun markUnreachable(address: String) {
        val entity = IMessageAvailabilityCacheEntity.createUnreachable(address, sessionId)
        cacheDao.insertOrUpdate(entity)
    }

    /**
     * Get all unreachable entries for re-checking on reconnect.
     */
    suspend fun getUnreachableAddresses(): List<String> {
        return cacheDao.getUnreachableAddresses().map { it.normalizedAddress }
    }

    /**
     * Invalidate cache for an address.
     */
    suspend fun invalidate(address: String) {
        cacheDao.delete(address)
    }
}

data class CacheResult(
    val available: Boolean,
    val fromPreviousSession: Boolean = false,
    val isStale: Boolean = false
)
```

---

## Retry Logic

### ServerAvailabilityChecker

Encapsulate server API calls with timeout and retry:

```kotlin
class ServerAvailabilityChecker @Inject constructor(
    private val api: BothBubblesApi,
    private val socketConnection: SocketConnection
) {

    sealed class CheckResult {
        data class Available(val address: String) : CheckResult()
        data class NotAvailable(val address: String) : CheckResult()
        data class Timeout(val address: String) : CheckResult()
        data class Error(val address: String, val cause: Throwable) : CheckResult()
        object ServerDisconnected : CheckResult()
    }

    /**
     * Check iMessage availability with timeout.
     *
     * @param address Normalized address to check
     * @param timeout Maximum time to wait for response
     * @param retryOnTimeout Whether to retry once on timeout
     */
    suspend fun check(
        address: String,
        timeout: Duration = 3.seconds,
        retryOnTimeout: Boolean = false
    ): CheckResult {
        // Pre-check connection
        if (socketConnection.connectionState.value != ConnectionState.CONNECTED) {
            return CheckResult.ServerDisconnected
        }

        return try {
            val result = withTimeout(timeout.inWholeMilliseconds) {
                val response = api.checkIMessageAvailability(address)

                if (response.isSuccessful) {
                    val available = response.body()?.data?.available == true
                    if (available) CheckResult.Available(address)
                    else CheckResult.NotAvailable(address)
                } else {
                    CheckResult.Error(address, Exception("HTTP ${response.code()}"))
                }
            }
            result
        } catch (e: TimeoutCancellationException) {
            if (retryOnTimeout) {
                // Single retry with longer timeout
                check(address, timeout * 2, retryOnTimeout = false)
            } else {
                CheckResult.Timeout(address)
            }
        } catch (e: Exception) {
            CheckResult.Error(address, e)
        }
    }
}
```

---

## Fallback Mode Lifecycle

### Clear Responsibility Assignment

| Component | Can Enter | Can Exit | When |
|-----------|-----------|----------|------|
| `MessageSendingService` | YES | NO | Server disconnect during send |
| `ChatSendModeManager` | NO | YES | User toggles, server stable, API confirms |
| `ChatFallbackTracker` | YES (internal) | YES | Persistence, server reconnect (for DISCONNECT reason) |
| `AddressServiceResolver` | NO | NO | Read-only |

### Fallback Mode Flow

```
SERVER DISCONNECTED DURING SEND
│
├─ MessageSendingService.determineDeliveryMode()
│  └─ chatFallbackTracker.enterFallbackMode(chatGuid, SERVER_DISCONNECTED)
│
└─ Later: Server reconnects
   └─ ChatFallbackTracker.onServerReconnected()
      └─ exitFallbackMode() for all SERVER_DISCONNECTED chats

IMESSAGE SEND FAILED
│
├─ User taps "Retry as SMS"
│  └─ MessageSendingService.retryAsSms()
│     └─ chatFallbackTracker.enterFallbackMode(chatGuid, IMESSAGE_FAILED)
│
└─ Later: User opens chat, ChatSendModeManager checks availability
   └─ If iMessage available + server stable:
      └─ chatFallbackTracker.exitFallbackMode(chatGuid)

USER TOGGLES TO SMS
│
└─ ChatSendModeManager.setSendMode(SMS)
   └─ Does NOT enter fallback mode (just UI state)
   └─ Messages routed based on currentSendMode, not fallback

USER TOGGLES TO IMESSAGE
│
└─ ChatSendModeManager.setSendMode(IMESSAGE)
   └─ chatFallbackTracker.exitFallbackMode(chatGuid) // Clear any existing
```

---

## Migration Plan

### Phase 1: Create New Components (Non-Breaking)

1. Create `AddressValidator` in `util/parsing/`
2. Create `IMessageAvailabilityCache` in `services/imessage/`
3. Create `ServerAvailabilityChecker` in `services/imessage/`
4. Create `AddressServiceResolver` in `services/imessage/`
5. Add comprehensive unit tests for each component

### Phase 2: Migrate Entry Points (One at a Time)

**Order of migration** (lowest risk to highest):

1. **RecipientDelegate** (Compose screen chips)
   - Low traffic, good for testing
   - Replace inline API call with `AddressServiceResolver.resolveService()`

2. **ChatCreationDelegate** (Start chat)
   - Replace `determineServiceForAddress()` with `AddressServiceResolver.resolveService()`
   - Remove timeout logic (now in resolver)

3. **ChatSendModeManager** (Existing chat toggle)
   - Replace `IMessageAvailabilityService` usage with `AddressServiceResolver`
   - Keep server stability logic (specific to mode switching)

4. **MessageSendingService** (Send flow)
   - Use `AddressServiceResolver` for new chat service detection
   - Keep existing fallback entry logic

### Phase 3: Cleanup

1. Delete `IMessageAvailabilityService` (replaced by Cache + Resolver)
2. Remove duplicate email/phone validation functions
3. Update DI module (`ServiceModule.kt`)
4. Update tests

---

## File Changes Summary

### New Files

| File | Purpose |
|------|---------|
| `util/parsing/AddressValidator.kt` | Unified address validation |
| `services/imessage/IMessageAvailabilityCache.kt` | Cache-only, no API calls |
| `services/imessage/ServerAvailabilityChecker.kt` | API calls with timeout/retry |
| `services/imessage/AddressServiceResolver.kt` | Single source of truth |
| `services/imessage/ServiceResolution.kt` | Data types |

### Modified Files

| File | Change |
|------|--------|
| `ChatCreationDelegate.kt` | Use AddressServiceResolver |
| `RecipientDelegate.kt` | Use AddressServiceResolver |
| `ChatSendModeManager.kt` | Use AddressServiceResolver |
| `di/ServiceModule.kt` | Add new bindings |

### Deleted Files

| File | Reason |
|------|--------|
| `IMessageAvailabilityService.kt` | Replaced by Cache + Resolver |

---

## Testing Strategy

### Unit Tests

1. **AddressValidator**
   - Email edge cases: `a@b.c`, `user@domain.com`, `@invalid`, `no-at-sign`
   - Phone edge cases: `+1234567890`, `1234567890`, `(123) 456-7890`, `123`

2. **IMessageAvailabilityCache**
   - Fresh vs stale cache detection
   - Session ID handling
   - TTL expiration
   - UNREACHABLE state handling

3. **ServerAvailabilityChecker**
   - Successful check
   - Timeout handling
   - Retry logic
   - Server disconnected pre-check

4. **AddressServiceResolver**
   - Full priority hierarchy
   - Each tier in isolation
   - Edge cases (no cache, no server, no history)

### Integration Tests

1. **End-to-end service resolution** with mock server
2. **Cache persistence** across app restarts
3. **Fallback mode lifecycle** scenarios

---

## Rollout Plan

1. **Feature flag**: Add `use_unified_resolver` flag in `SettingsDataStore`
2. **A/B test**: 10% → 50% → 100% rollout
3. **Monitoring**: Log resolution sources to track behavior changes
4. **Rollback**: Flag can disable new resolver instantly

---

## Success Metrics

1. **Consistency**: Same address resolves to same service across all entry points
2. **Latency**: Service resolution < 100ms (cache hit), < 3s (server check)
3. **Accuracy**: iMessage availability matches actual send success rate
4. **Code reduction**: ~40% less duplicate code

---

## Open Questions

1. Should stale cache be used for mode switching? (Currently: no)
2. Should we retry failed API checks in background? (Currently: only on reconnect)
3. Should activity history consider SMS chats? (Currently: yes, returns most recent)
