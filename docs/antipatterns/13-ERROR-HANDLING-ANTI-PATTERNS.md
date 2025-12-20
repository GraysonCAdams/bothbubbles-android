# Error Handling and Recovery Anti-Patterns

**Scope:** Crashes, recovery, timeouts, retries, error UI

---

## Critical Issues (App Crashes)

### 1. Force Unwrap on Empty Collections ✅ FIXED

**Location:** `ui/chat/details/ConversationDetailsLife360.kt` (Lines 947-967)

**Issue:**
```kotlin
val centerLat = (latitudes.minOrNull()!! + latitudes.maxOrNull()!!) / 2
val centerLon = (longitudes.minOrNull()!! + longitudes.maxOrNull()!!) / 2
val latSpread = latitudes.maxOrNull()!! - latitudes.minOrNull()!!
```

**Problem:** If `latitudes` or `longitudes` are empty, `minOrNull()` returns null. The `!!` causes NPE crash.

**Fix Applied:**
```kotlin
// Safe collection operations with early return if empty
if (latitudes.isEmpty() || longitudes.isEmpty()) {
    return@apply
}

val minLat = latitudes.minOrNull() ?: return@apply
val maxLat = latitudes.maxOrNull() ?: return@apply
val minLon = longitudes.minOrNull() ?: return@apply
val maxLon = longitudes.maxOrNull() ?: return@apply

val centerLat = (minLat + maxLat) / 2
val centerLon = (minLon + maxLon) / 2
val latSpread = maxLat - minLat
val lonSpread = maxLon - minLon
```

---

### 2. Unsafe Casts in Flow Combine ✅ FIXED

**Location:** `ui/settings/sms/SmsSettingsViewModel.kt` (Lines 59-63)

**Issue:**
```kotlin
combine(smsEnabled, preferSms, simSlot, autoSwitch) { values: Array<Any?> ->
    @Suppress("UNCHECKED_CAST")
    val enabled = values[0] as? Boolean ?: false  // SAFE
    // ...
}.collect { values ->
    val enabled = values[0] as Boolean      // UNSAFE!
    val preferSms = values[1] as Boolean    // UNSAFE!
}
```

**Problem:** First lambda uses safe casts, but second uses unsafe casts. StateFlow values can be null during initialization.

**Fix Applied:**
```kotlin
.collect { values ->
    val enabled = values.getOrNull(0) as? Boolean ?: false
    val preferSms = values.getOrNull(1) as? Boolean ?: false
    val simSlot = values.getOrNull(2) as? Int ?: -1
    val autoSwitch = values.getOrNull(3) as? Boolean ?: true
}
```

---

### 3. Unsafe Collection Operations ✅ FIXED

**Location:** `services/categorization/MessageCategorizer.kt` (Line 328)

**Issue:**
```kotlin
val bestCategory = categoryCounts.maxByOrNull { it.value }!!
```

**Problem:** `maxByOrNull()` returns null if collection is empty. Force unwrap crashes.

**Fix Applied:**
```kotlin
val bestCategory = categoryCounts.maxByOrNull { it.value }
    ?: return CategoryResult(null, 0, listOf("No best category found"))
```

---

## High Severity Issues

### 4. Silent Failures with No Feedback

**Location:** `ui/settings/server/ServerSettingsViewModel.kt` (Lines 81-83)

**Issue:**
```kotlin
} catch (e: Exception) {
    // Silently fail - server info is optional
}
```

**Problem:** Exception caught and completely ignored. No logging, no user feedback, no state update.

**Fix:**
```kotlin
} catch (e: Exception) {
    Timber.w(e, "Failed to fetch server info")
    _uiState.update { it.copy(serverInfoError = true) }
}
```

---

### 5. Ignored Exceptions in Audio Cleanup

**Location:** `ui/chat/ChatAudioHelper.kt` (Multiple locations)

**Issue:**
```kotlin
} catch (_: Exception) {}
} catch (_: Exception) {}
} catch (_: Exception) {}
```

**Problem:** Audio resource cleanup failures silently ignored. Could cause subsequent audio failures.

**Fix:**
```kotlin
} catch (e: Exception) {
    Timber.w(e, "Failed to clean up audio resources")
}
```

---

### 6. Non-Idempotent Retry Logic

**Location:** `util/RetryHelper.kt` (Lines 124-171)

**Issue:**
```kotlin
repeat(times) { attempt ->
    val response = block()  // Called EVERY attempt, even on rate-limit!
    if (response.code() == 429) {
        delay(delayMs)
        // Then loops and calls block() AGAIN
    }
}
```

**Problem:** Retry on 429 (rate limit) makes MORE requests, worsening the problem.

**Fix:**
```kotlin
if (response.code() == 429) {
    val retryAfter = response.headers()["Retry-After"]?.toLongOrNull() ?: delayMs
    delay(retryAfter * 1000)
} else if (response.code() in 500..599) {
    delay(currentDelay)
    currentDelay *= 2  // Exponential backoff
} else {
    return response  // Non-retryable
}
```

---

### 7. Generic Error Messages to User

**Location:** `ui/settings/templates/QuickReplyTemplatesViewModel.kt`

**Issue:**
```kotlin
} catch (e: Exception) {
    _uiState.update { it.copy(error = e.message) }  // Raw exception message!
}
```

**Problem:** Shows "java.io.IOException: Connection reset" to user.

**Fix:**
```kotlin
} catch (e: Exception) {
    val userMessage = when (e) {
        is IOException -> "Network error - check your connection"
        is DatabaseException -> "Storage error - try again"
        else -> "Something went wrong"
    }
    _uiState.update { it.copy(error = userMessage) }
    Timber.e(e, "Template operation failed")
}
```

---

## Medium Severity Issues

### 8. No Recovery State After Error

**Location:** `ui/settings/sms/SmsSettingsViewModel.kt` (Lines 155-184)

**Issue:**
```kotlin
} catch (e: Exception) {
    _uiState.update {
        it.copy(
            isResyncing = false,
            error = "Re-sync failed: ${e.message}"
        )
    }
    // No retry mechanism provided!
}
```

**Problem:** After error, no way to retry without navigating away.

**Fix:**
```kotlin
data class SmsSettingsUiState(
    val canRetry: Boolean = false,
    // ...
)

// In catch:
_uiState.update { it.copy(error = message, canRetry = true) }

// Provide retry function
fun retryResyncSms() {
    _uiState.update { it.copy(canRetry = false) }
    resyncSms()
}
```

---

### 9. No Offline Mode Fallback

**Location:** `ui/settings/server/ServerSettingsViewModel.kt`

**Problem:** When server is unreachable, no cached/fallback data used. Feature just unavailable.

**Fix:**
```kotlin
try {
    val response = api.getServerInfo()
    // ...
} catch (e: Exception) {
    val cachedInfo = settingsDataStore.cachedServerInfo.first()
    if (cachedInfo != null) {
        _uiState.update { it.copy(serverInfo = cachedInfo, isUsingCached = true) }
    } else {
        _uiState.update { it.copy(error = "Server unavailable") }
    }
}
```

---

### 10. Missing Retry Button in UI

**Problem:** Multiple screens show errors but no retry button. User must navigate away and back.

**Fix:**
```kotlin
@Composable
fun ErrorView(
    error: String,
    onRetry: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(error)
        Button(onClick = onRetry) { Text("Retry") }
    }
}
```

---

### 11. Unsafe Flow.first() ✅ FIXED

**Location:** `ui/chat/details/ConversationDetailsViewModel.kt` (Line 384)

**Issue:**
```kotlin
val currentMembers = life360MembersFlow.first()  // Throws if empty!
```

**Problem:** `first()` throws `NoSuchElementException` if flow completes without emitting a value.

**Fix Applied:**
```kotlin
val currentMembers = life360MembersFlow.firstOrNull() ?: emptyList()
```

---

### 12. Unsafe Nullable Force Unwrap in Property ✅ FIXED

**Location:** `core/model/src/main/kotlin/com/bothbubbles/core/model/entity/AttachmentEntity.kt` (Line 197)

**Issue:**
```kotlin
val aspectRatio: Float
    get() = if (hasValidSize) width!!.toFloat() / height!!.toFloat() else 1f
```

**Problem:** Even with `hasValidSize` check, the force unwrap `!!` on `width` and `height` can crash if either is null due to race conditions or data corruption.

**Fix Applied:**
```kotlin
val aspectRatio: Float
    get() {
        val w = width
        val h = height
        return if (w != null && h != null && w > 0 && h > 0) {
            w.toFloat() / h.toFloat()
        } else {
            1f
        }
    }
```

---

### 13. No Cancellation Handling

**Location:** `ui/chat/composer/AttachmentEditScreen.kt`

**Problem:** Image compression doesn't check for cancellation. If user navigates away, operation continues wasting resources.

**Fix:**
```kotlin
withContext(Dispatchers.IO) {
    if (!isActive) return@withContext input.uri

    imageCompressor.compress(uri, quality) { progress ->
        if (!isActive) return@compress
        updateProgress(progress)
    }
}
```

---

## Summary Table

| Issue | Severity | Category | Status | File |
|-------|----------|----------|--------|------|
| Force unwrap on empty list | CRITICAL | Crash | ✅ FIXED | ConversationDetailsLife360.kt |
| Unsafe casts in combine | CRITICAL | Crash | ✅ FIXED | SmsSettingsViewModel.kt |
| maxByOrNull()!! | CRITICAL | Crash | ✅ FIXED | MessageCategorizer.kt |
| Silent exception catch | HIGH | Debug | ⚠️ OPEN | ServerSettingsViewModel.kt |
| Ignored audio cleanup | HIGH | Resource | ⚠️ OPEN | ChatAudioHelper.kt |
| Non-idempotent retry | HIGH | API | ⚠️ OPEN | RetryHelper.kt |
| Generic error message | HIGH | UX | ⚠️ OPEN | QuickReplyTemplatesViewModel.kt |
| No recovery state | MEDIUM | UX | ⚠️ OPEN | SmsSettingsViewModel.kt |
| No offline fallback | MEDIUM | UX | ⚠️ OPEN | ServerSettingsViewModel.kt |
| Missing retry button | MEDIUM | UX | ⚠️ OPEN | Multiple |
| Unsafe Flow.first() | MEDIUM | Crash | ✅ FIXED | ConversationDetailsViewModel.kt |
| Unsafe nullable force unwrap | MEDIUM | Crash | ✅ FIXED | AttachmentEntity.kt |
| No cancellation check | MEDIUM | Resource | ⚠️ OPEN | AttachmentEditScreen.kt |
