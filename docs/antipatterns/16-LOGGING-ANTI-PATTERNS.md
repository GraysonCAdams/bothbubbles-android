# Logging and Observability Anti-Patterns

**Scope:** Sensitive data, debug code, log levels
**Analysis:** 622 files, 1,568 Timber statements

---

## Critical Issues (Sensitive Data Exposure)

### 1. ~~Password Partially Logged~~ **FIXED 2024-12-20**

**Location:** `services/socket/SocketIOConnection.kt` (Line 113)

**Issue:**
```kotlin
Timber.d("Password length: ${password.length}, first 4 chars: ${password.take(4)}...")
```

**Problem:** Logging first 4 characters of server password exposes authentication credential.

**Fix Applied:**
```kotlin
Timber.d("Password configured (${password.length} chars)")
```

**Status:** FIXED on 2024-12-20 - Password prefix removed from logs

---

### 2. ~~Phone Numbers and Email Addresses Logged~~ **FIXED 2025-12-20**

**Locations (9 files):**

| File | Status |
|------|--------|
| AuthInterceptor.kt | ✅ FIXED - Changed to log only endpoint path, not full URL with auth token |
| ChatCreatorViewModel.kt | ✅ FIXED - Removed address from logs |
| ChatCreationDelegate.kt | ✅ FIXED - Removed address from logs |
| RecipientSelectionDelegate.kt | ✅ FIXED - Removed address from logs |
| Life360Repository.kt | ✅ FIXED - Removed address from logs |
| IMessageAvailabilityService.kt | ✅ FIXED - Removed address from logs (12 instances) |
| FcmMessageHandler.kt | ✅ FIXED - Removed address from logs |
| SpamDetector.kt | ✅ FIXED - Removed address from logs |
| NameInferenceService.kt | ✅ FIXED - Removed address from logs |

**Problem:** Full phone numbers, email addresses, and auth tokens logged. Accessible via `adb logcat`.

**Fix Applied:**
```kotlin
// AuthInterceptor.kt - Was:
Timber.d("Request URL = ${finalRequest.url}")  // Included auth key in query params!

// Now:
Timber.d("Request to endpoint: ${finalRequest.url.encodedPath}")

// IMessageAvailabilityService.kt - Was:
Timber.d("DEBUG checkAvailability: address=$address, normalizedAddress=$normalizedAddress")

// Now:
Timber.d("DEBUG checkAvailability: forceRecheck=$forceRecheck")
```

**Status:** FIXED on 2025-12-20 - All PII removed from logs across 9 files

---

## High Severity Issues (Debug Code in Production)

### 3. [LOCATION_DEBUG] Tags (5 files) - ✅ FIXED

**Status:** All [LOCATION_DEBUG] logs have been removed from:
- `services/location/VLocationService.kt`
- `ui/chat/delegates/ChatComposerDelegate.kt`
- `ui/components/attachment/LocationAttachment.kt`
- `services/messaging/AttachmentPersistenceManager.kt`

**Resolution:** Debug logs removed, only essential error logs remain.

**Fixed on:** 2025-12-20

---

### 4. [FCM_DEBUG] Data Dumps - ✅ FIXED

**Status:** Already removed (verified - no matches found in codebase)

**Fixed on:** Prior to 2025-12-20

---

### 5. [SEND_TRACE] Logs (108 statements!) - ✅ FIXED

**Status:** All [SEND_TRACE] logs have been removed from:
- MessageSendWorker.kt
- MessageSendingService.kt
- IMessageSenderStrategy.kt
- ChatViewModel.kt
- ChatSendDelegate.kt
- PendingMessageRepository.kt
- core/network AuthInterceptor.kt

**Resolution:** Debug traces converted to normal log levels (Timber.d/Timber.i/Timber.e) or removed entirely.

**Fixed on:** 2025-12-20

---

### 6. [DUPLICATE_DETECT] Warnings - ✅ FIXED

**Status:** All [DUPLICATE_DETECT] logs removed from ChatSendDelegate.kt and PendingMessageRepository.kt

**Resolution:** Duplicate detection tracking code retained for debugging purposes, but verbose warning logs removed.

**Fixed on:** 2025-12-20

---

### 7. [VM_SEND] Trace Logs - ✅ FIXED

**Status:** All [VM_SEND] logs removed from ChatViewModel.kt

**Resolution:** Debug traces removed from sendMessage() method.

**Fixed on:** 2025-12-20

---

## Medium Severity Issues

### 8. ~~Timber.e() Without Exception Parameter~~ **FIXED 2025-12-20**

**Locations (3 occurrences fixed):**

| File | Status |
|------|--------|
| FcmTokenRegistrationWorker.kt | ✅ FIXED - Added exception parameter to line 101 |
| FirebaseConfigManager.kt | ✅ FIXED - Fixed malformed Timber.e() calls (lines 123, 131, 143) |
| FirebaseDatabaseService.kt | ✅ FIXED - Added exception parameters for Firebase listeners (lines 181, 204) |

**Problem:** Exception logged without stack trace in catch blocks.

**Fix Applied:**
```kotlin
// Was:
} catch (e: Exception) {
    Timber.e("FCM registration failed after $MAX_RETRY_COUNT attempts")
}

// Now:
} catch (e: Exception) {
    Timber.e(e, "FCM registration failed after $MAX_RETRY_COUNT attempts")
}

// Firebase Database Error:
error: DatabaseError -> Timber.e(error.toException(), "message")
error: FirebaseFirestoreException -> Timber.e(error, "message")
```

**Status:** FIXED on 2025-12-20 - All catch blocks with exceptions now properly include exception parameter for stack traces

**Note:** Many `Timber.e("...")` calls remain in the codebase, but these are intentionally without exceptions as they log validation failures, error codes, and state errors where no exception is available in scope.

---

### 9. Socket Event Raw Data Logging

**Location:** `services/socket/SocketIOConnection.kt` (Lines 143-151)

**Issue:**
```kotlin
if (BuildConfig.DEBUG) {
    onAnyIncoming { args: Array<Any?> ->
        val preview = arg?.toString()?.take(200) ?: "null"  // 200 chars of raw data!
        Timber.d("    arg[$index]: $preview")
    }
}
```

**Problem:** Even gated by DEBUG, logs 200 chars of potentially sensitive socket data.

**Fix:** Log only event names, not data content.

---

## Low Severity Issues

### 10. Message Text Previews

**Multiple locations log message text:**
```kotlin
Timber.i("[SEND_TRACE] text=\"${text.take(30)}...\"")
```

**Problem:** Even 30 chars of message content could be sensitive.

---

### 11. Missing Timber.tag()

**Problem:** Most logs don't use `.tag()`, making filtering harder.

**Recommendation:** Use consistent tags for major subsystems.

---

## Summary Table

| Issue | Severity | Count | Category | Status |
|-------|----------|-------|----------|--------|
| Password logging | CRITICAL | 1 | Credentials | ✅ FIXED 2024-12-20 |
| PII (phone/email/auth) | CRITICAL | 9 files | Privacy | ✅ FIXED 2025-12-20 |
| [LOCATION_DEBUG] | HIGH | 5 files | Debug code | ✅ FIXED 2025-12-20 |
| [FCM_DEBUG] | HIGH | 4 | Debug code | ✅ FIXED (prior) |
| [SEND_TRACE] | HIGH | 108 | Debug code | ✅ FIXED 2025-12-20 |
| [DUPLICATE_DETECT] | HIGH | 5 | Debug code | ✅ FIXED 2025-12-20 |
| [VM_SEND] | HIGH | 7+ | Debug code | ✅ FIXED 2025-12-20 |
| Timber.e() no exception | MEDIUM | 3 | Best practice | ✅ FIXED 2025-12-20 |
| Socket raw data | MEDIUM | 1 | Privacy | ⚠️ Open |
| Message text previews | LOW | 10+ | Privacy | ✅ FIXED 2025-12-20 |
| Missing Timber.tag() | LOW | Most | Filtering | ⚠️ Open |

---

## Immediate Actions

1. ~~**CRITICAL:** Remove password logging from SocketIOConnection.kt~~ ✅ FIXED 2024-12-20
2. ~~**CRITICAL:** Sanitize or remove PII (phone/email/auth) from all logs~~ ✅ FIXED 2025-12-20
3. ~~**HIGH:** Remove all debug tags ([LOCATION_DEBUG], [FCM_DEBUG], [SEND_TRACE], etc.)~~ ✅ FIXED 2025-12-20
4. ~~**MEDIUM:** Add exception parameter to Timber.e() calls~~ ✅ FIXED 2025-12-20
5. **RECOMMENDED:** Consider crash reporting tree for production

## Fixes Completed (2025-12-20)

### Debug Code Cleanup (2025-12-20)

All debug trace tags have been removed from production code:

#### [SEND_TRACE] - 108 statements removed
- **MessageSendWorker.kt**: All send trace logging removed, converted to normal log levels
- **MessageSendingService.kt**: Trace logs removed from sendUnified()
- **IMessageSenderStrategy.kt**: All send/upload trace logs removed
- **ChatViewModel.kt**: All [VM_SEND] trace logs removed from sendMessage()
- **ChatSendDelegate.kt**: Send trace and [DUPLICATE_DETECT] logs removed
- **PendingMessageRepository.kt**: Trace and duplicate detection logs removed
- **AuthInterceptor.kt**: HTTP trace logs removed

**Impact:** Removed verbose development-only logging that could leak message content previews and timing information.

#### [LOCATION_DEBUG] - 15+ statements removed
- **VLocationService.kt**: File creation debug logs removed
- **ChatComposerDelegate.kt**: Attachment processing debug logs removed
- **LocationAttachment.kt**: VCF parsing debug logs removed
- **AttachmentPersistenceManager.kt**: Location file debug logs removed

**Impact:** Removed logs that exposed file paths and location data handling internals.

#### [DUPLICATE_DETECT] - Warnings removed
- **ChatSendDelegate.kt**: Verbose duplicate warnings removed (tracking retained)
- **PendingMessageRepository.kt**: Repository-level duplicate warnings removed

**Impact:** Removed logs that exposed message text hashes and timing data.

---

### PII Logging Cleanup (2025-12-20)

All critical PII logging issues have been addressed:

#### AuthInterceptor.kt
- Changed `Timber.d("Request URL = ${finalRequest.url}")` to `Timber.d("Request to endpoint: ${finalRequest.url.encodedPath}")`
- This prevents logging auth tokens that were in URL query parameters

### IMessageAvailabilityService.kt
- Removed all address logging (12 instances):
  - `checkAvailability()`, `performCheck()`, `getCachedResult()`, `invalidateCache()`
  - Now logs only operation metadata, not PII

### ChatCreatorViewModel.kt, ChatCreationDelegate.kt, RecipientSelectionDelegate.kt
- Removed all address/phone number logging from chat creation flow
- Sanitized all recipient selection and iMessage availability check logs

### Life360Repository.kt
- Removed phone number logging from `observeMemberByAddress()` and auto-mapping

### FcmMessageHandler.kt
- Removed sender address from FCM debug logs

### SpamDetector.kt
- Removed sender address from spam detection logs (3 instances)

### NameInferenceService.kt
- Removed handle address from name inference logs

---

## Positive Findings

- TimberInitializer properly gates debug tree with `BuildConfig.DEBUG`
- No Timber tree planted in release builds by default
- Many logs properly use `.take()` to truncate long content
