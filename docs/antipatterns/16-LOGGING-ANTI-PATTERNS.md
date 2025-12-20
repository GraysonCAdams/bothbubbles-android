# Logging and Observability Anti-Patterns

**Scope:** Sensitive data, debug code, log levels
**Analysis:** 622 files, 1,568 Timber statements

---

## Critical Issues (Sensitive Data Exposure)

### 1. Password Partially Logged

**Location:** `services/socket/SocketIOConnection.kt` (Line 113)

**Issue:**
```kotlin
Timber.d("Password length: ${password.length}, first 4 chars: ${password.take(4)}...")
```

**Problem:** Logging first 4 characters of server password exposes authentication credential.

**Fix:**
```kotlin
Timber.d("Password configured (${password.length} chars)")
```

---

### 2. Phone Numbers and Email Addresses Logged

**Locations (8+ files):**

| File | Line | Example |
|------|------|---------|
| ChatCreatorViewModel.kt | - | `address=$address` |
| ChatCreationDelegate.kt | - | `selectContact: ${contact.address}` |
| RecipientSelectionDelegate.kt | - | `address=$address` |
| Life360Repository.kt | - | `address=$address, normalized=$normalized` |
| IMessageAvailabilityService.kt | - | `address=$address, normalizedAddress=$normalizedAddress` |
| FcmMessageHandler.kt | 212 | `address='$senderAddress'` |
| SpamDetector.kt | 234 | `for $address` |
| NameInferenceService.kt | - | `handle ${handle.address}` |

**Problem:** Full phone numbers and email addresses logged. Accessible via `adb logcat`.

**Fix:**
```kotlin
// Instead of:
Timber.d("Address: $address")

// Use:
Timber.d("Address: ${address.take(3)}***")
// Or omit entirely
```

---

## High Severity Issues (Debug Code in Production)

### 3. [LOCATION_DEBUG] Tags (5 files)

**Locations:**
- `services/location/VLocationService.kt` (Lines 65, 68, 72, 82, 90)
- `ui/chat/delegates/ChatComposerDelegate.kt`
- `ui/components/attachment/LocationAttachment.kt`
- `services/eta/EtaSharingManager.kt`
- `services/messaging/AttachmentPersistenceManager.kt`

**Issue:**
```kotlin
Timber.d("[LOCATION_DEBUG] VCF content:\n$vcfContent")  // Full file dumped!
Timber.d("[LOCATION_DEBUG] File written to: ${file.absolutePath}")
```

**Fix:** Remove or gate with `if (BuildConfig.DEBUG)`.

---

### 4. [FCM_DEBUG] Data Dumps

**Location:** `services/fcm/FcmMessageHandler.kt` (Lines 115, 125, 137, 149)

**Issue:**
```kotlin
Timber.d("FCM_DEBUG: Raw data keys=${data.keys}, values=${data.entries.joinToString...}")
Timber.d("FCM_DEBUG: dataJsonString (first 500 chars)=${dataJsonString.take(500)}")
```

**Problem:** FCM message content (potentially sensitive) dumped to logs.

**Fix:** Remove FCM_DEBUG logs entirely.

---

### 5. [SEND_TRACE] Logs (108 statements!)

**Locations:** MessageSendWorker.kt, MessageSendingService.kt, IMessageSenderStrategy.kt, ChatViewModel.kt, ChatSendDelegate.kt, PendingMessageRepository.kt

**Issue:**
```kotlin
Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
Timber.i("[SEND_TRACE] MessageSendWorker.doWork() STARTED at $workerStartTime")
Timber.i("[SEND_TRACE] chatGuid=$chatGuid, text=\"${text.take(30)}...\"")
```

**Problem:** 108 trace statements throughout send pipeline. Clearly development-only.

**Fix:** Remove all [SEND_TRACE] or wrap in `if (BuildConfig.DEBUG)`.

---

### 6. [DUPLICATE_DETECT] Warnings

**Location:** `ui/chat/delegates/ChatSendDelegate.kt`

**Issue:**
```kotlin
Timber.w("[DUPLICATE_DETECT] ⚠️ POTENTIAL DUPLICATE MESSAGE DETECTED!")
Timber.w("[DUPLICATE_DETECT] ⚠️ Text: \"$textPreview...\"")
```

**Fix:** Remove or make conditional.

---

### 7. [VM_SEND] Trace Logs

**Location:** `ui/chat/ChatViewModel.kt` (Lines 641-699)

**Issue:**
```kotlin
Timber.i("[VM_SEND] ══════════════════════════════════════════════════════════════")
Timber.i("[VM_SEND] sendMessage() TRIGGERED at $sendCallTime")
Timber.i("[VM_SEND] Text length: ${text.length}, Preview: \"${text.take(30)}...\"")
```

**Fix:** Remove debug traces.

---

## Medium Severity Issues

### 8. Timber.e() Without Exception Parameter

**Locations (15+ occurrences):**

| File | Issue |
|------|-------|
| ImageCompressor.kt | `Timber.e("Failed to decode image dimensions")` |
| VideoCompressor.kt | `Timber.e("No video track found")` |
| SmsStatusReceiver.kt | `Timber.e("SMS send failed...")` |
| HeadlessSmsSendService.kt | Multiple |

**Problem:** Exception logged without stack trace.

**Fix:**
```kotlin
// Instead of:
Timber.e("Error message")

// Use:
Timber.e(e, "Error message")
```

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

| Issue | Severity | Count | Category |
|-------|----------|-------|----------|
| Password logging | CRITICAL | 1 | Credentials |
| PII (phone/email) | CRITICAL | 8+ files | Privacy |
| [LOCATION_DEBUG] | HIGH | 5 files | Debug code |
| [FCM_DEBUG] | HIGH | 4 | Debug code |
| [SEND_TRACE] | HIGH | 108 | Debug code |
| [DUPLICATE_DETECT] | HIGH | 5 | Debug code |
| [VM_SEND] | HIGH | 7+ | Debug code |
| Timber.e() no exception | MEDIUM | 15+ | Best practice |
| Socket raw data | MEDIUM | 1 | Privacy |
| Message text previews | LOW | 10+ | Privacy |
| Missing Timber.tag() | LOW | Most | Filtering |

---

## Immediate Actions

1. **CRITICAL:** Remove password logging from SocketIOConnection.kt
2. **CRITICAL:** Sanitize or remove PII (phone/email) from all logs
3. **HIGH:** Remove all debug tags ([LOCATION_DEBUG], [FCM_DEBUG], [SEND_TRACE], etc.)
4. **MEDIUM:** Add exception parameter to Timber.e() calls
5. **RECOMMENDED:** Consider crash reporting tree for production

---

## Positive Findings

- TimberInitializer properly gates debug tree with `BuildConfig.DEBUG`
- No Timber tree planted in release builds by default
- Many logs properly use `.take()` to truncate long content
