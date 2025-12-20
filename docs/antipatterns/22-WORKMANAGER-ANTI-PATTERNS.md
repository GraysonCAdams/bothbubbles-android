# WorkManager Anti-Patterns

**Scope:** Background jobs, constraints, retries, expedited work

---

## Medium Severity Issues

### 1. Missing Backoff Configuration for Periodic Workers

**Locations:**
- `BackgroundSyncWorker.kt` (lines 80-99)
- `MlModelUpdateWorker.kt` (lines 48-67)
- `Life360SyncWorker.kt` (lines 46-66)

**Issue:** Periodic workers call `Result.retry()` without explicit backoff policy.

**Fix:**
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

### 3. MessageSendWorker Long-Running Confirmation Wait

**Location:** `MessageSendWorker.kt` (lines 304-339)

**Issue:** 2-minute blocking confirmation wait without `isStopped` check.

**Fix:**
```kotlin
while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
    if (isStopped) {
        return kotlin.Result.failure(Exception("Worker was stopped"))
    }
    // ... rest of logic
}
```

---

### 4. ScheduledMessageWorker Missing Network Constraint

**Location:** `ChatScheduledMessageDelegate.kt` (lines 111-116)

**Issue:** No network constraint, unlike `MessageSendWorker` which requires connected network.

**Fix:**
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

### 7. Missing Work Tags for Management

**Locations:** All workers

**Issue:** Workers define `TAG` constants but never use `.addTag()`.

**Fix:**
```kotlin
val workRequest = OneTimeWorkRequestBuilder<MessageSendWorker>()
    .addTag("message_sending")
    .addTag("critical")
    .build()
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

### 10. BackgroundSyncWorker Missing Battery Constraint

**Location:** `BackgroundSyncWorker.kt` (lines 80-85)

**Issue:** Only checks network, not battery health.

**Fix:**
```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .setRequiresBatteryNotLow(true)
    .build()
```

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

| Issue | Severity | File | Problem |
|-------|----------|------|---------|
| Missing backoff config | MEDIUM | 3 workers | Aggressive retries |
| Retry on 4xx errors | MEDIUM | FcmTokenRegistrationWorker | Wasted attempts |
| 2-minute blocking wait | MEDIUM | MessageSendWorker | No isStopped check |
| Missing network constraint | MEDIUM | ScheduledMessageWorker | Offline failures |
| Incomplete attachment handling | MEDIUM | ScheduledMessageWorker | Attachments ignored |
| Uncontrolled retries | MEDIUM | MlModelUpdateWorker | No error classification |
| Missing work tags | LOW | All workers | Harder management |
| UPDATE policy risk | LOW | Life360SyncWorker | Cancels in-flight work |
| Missing expedited flag | LOW | ScheduledMessageWorker | Delayed execution |
| Missing battery constraint | LOW | BackgroundSyncWorker | Battery drain |
