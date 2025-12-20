# WorkManager Anti-Patterns

**Scope:** Background jobs, constraints, retries, expedited work

---

## Medium Severity Issues

### 1. Missing Backoff Configuration for Periodic Workers - **FIXED**

**Locations:**
- `BackgroundSyncWorker.kt` (lines 80-99) - **FIXED**
- `MlModelUpdateWorker.kt` (lines 48-67) - **FIXED**
- `Life360SyncWorker.kt` (lines 46-66) - **FIXED**

**Issue:** Periodic workers call `Result.retry()` without explicit backoff policy.

**Fix Applied:**
```kotlin
val workRequest = PeriodicWorkRequestBuilder<MlModelUpdateWorker>(...)
    .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        15, TimeUnit.MINUTES
    )
    .build()
```

---

### 2. FcmTokenRegistrationWorker Retries on Unrecoverable Errors

**Location:** `FcmTokenRegistrationWorker.kt` (lines 82-104)

**Issue:** Retries on ALL error codes including 4xx client errors:
```kotlin
if (errorCode >= 500 && runAttemptCount < MAX_RETRY_COUNT) {
    Result.retry()
} else if (runAttemptCount < MAX_RETRY_COUNT) {  // Includes 4xx!
    Result.retry()
}
```

**Fix:** Only retry on 5xx server errors. Fail immediately on 4xx client errors.

---

### 3. MessageSendWorker Long-Running Confirmation Wait - **FIXED**

**Location:** `MessageSendWorker.kt` (lines 304-339) - **FIXED**

**Issue:** 2-minute blocking confirmation wait without `isStopped` check.

**Fix Applied:**
```kotlin
while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
    if (isStopped) {
        return kotlin.Result.failure(Exception("Worker was stopped"))
    }
    // ... rest of logic
}
```

---

### 4. ScheduledMessageWorker Missing Network Constraint - **FIXED**

**Location:** `ChatScheduledMessageDelegate.kt` (lines 111-116) - **FIXED**

**Issue:** No network constraint, unlike `MessageSendWorker` which requires connected network.

**Fix Applied:**
```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

val workRequest = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
    .setConstraints(constraints)
    // ...
```

---

### 5. ScheduledMessageWorker Incomplete Attachment Handling

**Location:** `ScheduledMessageWorker.kt` (lines 60-75)

**Issue:** Parses attachment URIs but doesn't use them:
```kotlin
val attachmentUris = scheduledMessage.attachmentUris  // Parsed but...
messageSendingService.sendMessage(chatGuid, text)     // ...never passed!
```

**Fix:** Use `sendUnified()` and pass attachments.

---

### 6. MlModelUpdateWorker Uncontrolled Retries

**Location:** `MlModelUpdateWorker.kt` (lines 97-113)

**Issue:** Retries on all exceptions without classifying error types.

**Fix:**
```kotlin
val shouldRetry = e is java.io.IOException ||
                 e is java.net.SocketException
if (shouldRetry && runAttemptCount < 3) {
    Result.retry()
} else {
    Result.failure()
}
```

---

## Low Severity Issues

### 7. Missing Work Tags for Management - **FIXED**

**Locations:** All workers - **FIXED**

**Issue:** Workers define `TAG` constants but never use `.addTag()`.

**Fix Applied:**
```kotlin
// Tags added to all workers:
// - MessageSendWorker: "message_sending"
// - BackgroundSyncWorker: "background_sync"
// - ScheduledMessageWorker: "scheduled_message"
// - Life360SyncWorker: "life360_sync"
// - MlModelUpdateWorker: "ml_model_update"
// - FcmTokenRegistrationWorker: "fcm_token_registration"
```

---

### 8. Life360SyncWorker Uses UPDATE Policy

**Location:** `Life360SyncWorker.kt` (line 61)

**Issue:** `ExistingPeriodicWorkPolicy.UPDATE` cancels in-flight work when interval changes.

**Fix:** Use `ExistingPeriodicWorkPolicy.KEEP` for safer behavior.

---

### 9. ScheduledMessageWorker Missing Expedited Flag

**Location:** `ChatScheduledMessageDelegate.kt` (lines 111-116)

**Issue:** User-initiated time-sensitive work without expedited flag.

**Fix:**
```kotlin
.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
```

---

### 10. BackgroundSyncWorker Missing Battery Constraint - **ALREADY FIXED**

**Location:** `BackgroundSyncWorker.kt` (lines 80-85)

**Issue:** Only checks network, not battery health.

**Status:** This was already fixed in the code. BackgroundSyncWorker already has `.setRequiresBatteryNotLow(true)` on line 83.

---

## Positive Findings

The codebase demonstrates strong WorkManager practices:

- **Proper Unique Work** - `enqueueUniqueWork()` with `KEEP` prevents duplicates
- **Duplicate Detection** - Global duplicate detection at repository level
- **Database-First** - Messages persisted to Room before WorkManager enqueuing
- **Network Constraints** - MessageSendWorker requires `NetworkType.CONNECTED`
- **Expedited Work** - MessageSendWorker uses expedited with fallback
- **Error Recovery** - Stale SENDING messages reset on startup
- **Foreground Compliance** - Proper `getForegroundInfo()` for Android 12+
- **State Checks** - Workers verify setup before executing

---

## Summary Table

| Issue | Severity | File | Problem | Status |
|-------|----------|------|---------|--------|
| Missing backoff config | MEDIUM | 3 workers | Aggressive retries | **FIXED** |
| Retry on 4xx errors | MEDIUM | FcmTokenRegistrationWorker | Wasted attempts | Open |
| 2-minute blocking wait | MEDIUM | MessageSendWorker | No isStopped check | **FIXED** |
| Missing network constraint | MEDIUM | ScheduledMessageWorker | Offline failures | **FIXED** |
| Incomplete attachment handling | MEDIUM | ScheduledMessageWorker | Attachments ignored | Open |
| Uncontrolled retries | MEDIUM | MlModelUpdateWorker | No error classification | Open |
| Missing work tags | LOW | All workers | Harder management | **FIXED** |
| UPDATE policy risk | LOW | Life360SyncWorker | Cancels in-flight work | Open |
| Missing expedited flag | LOW | ScheduledMessageWorker | Delayed execution | Open |
| Missing battery constraint | LOW | BackgroundSyncWorker | Battery drain | **Already Fixed** |
