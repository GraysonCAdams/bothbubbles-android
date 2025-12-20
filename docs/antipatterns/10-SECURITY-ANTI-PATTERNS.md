# Security Anti-Patterns (Extended)

**Scope:** Data exposure, authentication, cryptography, IPC, network security

**Note:** This extends the findings in `05-CORE-ANTI-PATTERNS.md` (SSL/TLS issues).

---

## Critical Issues

### 1. Authorization Key Exposed in Debug Logs

**Locations:**
- `core/network/.../AuthInterceptor.kt` (Line 100)
- `services/imessage/IMessageAvailabilityService.kt` (Line 200)

**Issue (AuthInterceptor):**
```kotlin
Timber.d("Request URL = ${finalRequest.url}")
// Logs full URL including ?guid=AUTH_KEY
```

**Issue (IMessageAvailabilityService):**
```kotlin
val urlString = "$serverAddress/api/v1/handle/availability/imessage?address=$encodedAddress&guid=$encodedAuth"
Timber.d("DEBUG performCheck: URL = $urlString")
```

**Attack Scenario:**
- Device logs accessible via `adb logcat` on unlocked device
- Physical access allows auth key extraction
- Attacker can impersonate user to BlueBubbles server
- Full access to all messages

**Fix:**
```kotlin
Timber.d("Request to endpoint: ${finalRequest.url.encodedPath}")
// Or create URL sanitizer utility
```

---

### 2. Certificate Pinning Disabled (Duplicate of Core Finding)

**Locations:**
- `core/network/.../CoreNetworkModule.kt` (Lines 63-98)
- `services/imessage/IMessageAvailabilityService.kt` (Lines 207-215)

**Issue:**
```kotlin
// CoreNetworkModule.kt
.hostnameVerifier { _, _ -> true }  // Accepts ANY hostname

// IMessageAvailabilityService.kt
override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
```

**Attack Scenario:**
- MITM attack on open networks
- Attacker intercepts all API traffic
- Messages, auth keys, personal data exposed

---

## High Severity Issues

### 3. Cleartext Traffic Permitted

**Location:** `app/src/main/res/xml/network_security_config.xml`

```xml
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">localhost</domain>
    <domain includeSubdomains="true">127.0.0.1</domain>
    <domain includeSubdomains="true">10.0.2.2</domain>
</domain-config>
```

**Problem:**
- Intended for debugging but ships in release?
- Compromised local network routing could exploit

**Fix:**
- Use debug-only build variant
- Remove from release APK

---

### 4. android:allowBackup="true" with Sensitive Data

**Location:** `app/src/main/AndroidManifest.xml` (Line 84)

```xml
<application
    android:allowBackup="true"
```

**Attack Scenario:**
- `adb backup` extracts all app data
- SQLite database with messages
- DataStore with server address and auth keys
- Contact information

**Fix:**
```xml
<application
    android:allowBackup="false"
```

Or implement `BackupAgent` that excludes sensitive data.

---

### 5. Exported Components Without Permission Protection

**Location:** `app/src/main/AndroidManifest.xml`

| Line | Component | Status |
|------|-----------|--------|
| 106 | MainActivity | `exported="true"` without permission |
| 235 | BothBubblesCarAppService | `exported="true"` without permission |
| 268 | SmsProviderChangedReceiver | `exported="true"` without permission |
| 245 | SmsBroadcastReceiver | Protected with permission |
| 256 | MmsBroadcastReceiver | Protected with permission |

**Issue (SmsProviderChangedReceiver):**
```xml
<receiver
    android:name=".services.sms.SmsProviderChangedReceiver"
    android:exported="true">  <!-- No permission! -->
    <intent-filter>
        <action android:name="android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED" />
    </intent-filter>
</receiver>
```

**Attack Scenario:**
- Any app can broadcast to this receiver
- Potential SMS default app status interference
- Intent injection through MainActivity extras

**Fix:**
```xml
<receiver
    android:name=".services.sms.SmsProviderChangedReceiver"
    android:exported="false">
```

---

### 6. Excessive Sensitive Data Logging

**Location:** `services/imessage/IMessageAvailabilityService.kt` (Lines 97-282)

```kotlin
Timber.d("DEBUG checkAvailability: address=$address, normalizedAddress=$normalizedAddress")
Timber.d("DEBUG: cached result for $normalizedAddress: $cached")
Timber.d("DEBUG performCheck: URL = $urlString")  // Contains auth key!
Timber.d("DEBUG performCheck: responseBody=$responseBody")
```

**Problem:**
- Device logs accessible via adb
- Crash reports may include logs
- Third-party analytics might capture

---

## Medium Severity Issues

### 7. HTTP Logging at HEADERS Level

**Location:** `core/network/.../CoreNetworkModule.kt` (Lines 41-48)

```kotlin
fun provideLoggingInterceptor(): HttpLoggingInterceptor {
    return HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS
    }
}
```

**Problem:**
- HEADERS level logs authorization headers
- Exposed in release builds if not gated

**Fix:**
```kotlin
level = if (BuildConfig.DEBUG) Level.HEADERS else Level.NONE
```

---

### 8. Missing URI Input Validation

**Location:** `services/sms/HeadlessSmsSendService.kt` (Lines 93-100)

```kotlin
private fun getRecipientFromUri(uri: Uri): String? {
    val schemeSpecificPart = uri.schemeSpecificPart
    if (!schemeSpecificPart.isNullOrBlank()) {
        return schemeSpecificPart.split("?")[0]
    }
    return null
}
```

**Problem:**
- No validation that recipient is valid phone number
- No sanitization before SmsManager
- Could accept malformed URIs

**Fix:**
```kotlin
private fun getRecipientFromUri(uri: Uri): String? {
    val raw = uri.schemeSpecificPart?.split("?")?.firstOrNull() ?: return null
    return PhoneNumberFormatter.normalize(raw)
}
```

---

### 9. FileProvider Root Path Exposure

**Location:** `app/src/main/res/xml/file_paths.xml`

```xml
<paths>
    <cache-path name="cache" path="." />
    <files-path name="files" path="." />
    <external-files-path name="external_files" path="." />
</paths>
```

**Problem:**
- `path="."` exposes root directories
- Other apps could access sensitive cached files

**Fix:**
```xml
<cache-path name="attachments" path="attachments" />
<files-path name="exports" path="exports" />
```

---

## Summary Table

| Issue | Severity | Risk | File |
|-------|----------|------|------|
| Auth key in logs | CRITICAL | Key theft | AuthInterceptor, IMessageAvailabilityService |
| Certificate bypass | CRITICAL | MITM | CoreNetworkModule |
| Cleartext traffic | HIGH | Interception | network_security_config.xml |
| allowBackup=true | HIGH | Data theft | AndroidManifest.xml |
| Exported components | HIGH | Intent injection | AndroidManifest.xml |
| Excessive logging | HIGH | Data exposure | IMessageAvailabilityService |
| HTTP HEADERS logging | MEDIUM | Header exposure | CoreNetworkModule |
| Missing URI validation | MEDIUM | Injection | HeadlessSmsSendService |
| FileProvider paths | MEDIUM | File access | file_paths.xml |

---

## Immediate Action Items

1. **CRITICAL:** Sanitize auth keys from all Timber logs
2. **CRITICAL:** Implement certificate pinning
3. **HIGH:** Set `android:allowBackup="false"`
4. **HIGH:** Add permission checks to exported components
5. **HIGH:** Gate debug logging with BuildConfig.DEBUG
6. **MEDIUM:** Restrict FileProvider paths
7. **MEDIUM:** Add input validation to URI handling
