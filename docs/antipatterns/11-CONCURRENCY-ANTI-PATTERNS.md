# Concurrency Anti-Patterns

**Scope:** Race conditions, thread safety, deadlocks, coroutine issues

---

## Critical Issues

### 1. SimpleDateFormat Shared Across Threads ✅ FIXED

**Status:** FIXED - All SimpleDateFormat instances replaced with thread-safe DateTimeFormatter

**Fixed in files:**
- `util/parsing/DateFormatters.kt` - Replaced all SimpleDateFormat with DateTimeFormatter
- `util/parsing/AbsoluteDateParser.kt` - Updated to use java.time APIs (LocalDateTime, LocalDate, LocalTime)
- `ui/chat/delegates/CursorChatMessageListDelegate.kt` - Added companion object DateTimeFormatters
- `ui/chat/ChatScreenUtils.kt` - Added companion object DateTimeFormatters
- `ui/chat/details/MediaGalleryViewModel.kt` - Added companion object DateTimeFormatter
- `ui/components/common/SnoozeDuration.kt` - Added companion object DateTimeFormatters

**Original Issue:**
SimpleDateFormat was shared across threads and is NOT thread-safe. Two threads calling `.parse()` simultaneously corrupt internal state, causing:
- Incorrect parsing results
- ArrayIndexOutOfBoundsException
- NumberFormatException

**Solution Applied:**
All SimpleDateFormat instances replaced with `java.time.format.DateTimeFormatter`, which is immutable and thread-safe:
```kotlin
// Before (NOT thread-safe)
val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
val date = dateFormat.parse(dateString)

// After (thread-safe)
val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    .withResolverStyle(ResolveStyle.STRICT)
val instant = Instant.ofEpochMilli(timestamp)
val formatted = DATE_FORMAT.format(instant.atZone(ZoneId.systemDefault()))
```

---

### 2. runBlocking in Notification Builder ✅ FIXED

**Location:** `services/notifications/NotificationBuilder.kt` (Lines 92-99)

**Issue:**
```kotlin
fun buildMessageNotification(...): android.app.Notification {
    val mergedGuids: String? = try {
        runBlocking(ioDispatcher) {  // BLOCKING!
            val unifiedChat = unifiedChatDao.getBySourceId(chatGuid)
            // ...
        }
    }
}
```

**Problem:**
- `runBlocking()` blocks the calling thread
- Defeats purpose of coroutines
- Can cause deadlock if called from coroutine context
- Blocks notification thread, delays other notifications

**Fix Applied:**
Implemented caching pattern with background refresh:
```kotlin
// Cache unified group mappings (chatGuid -> merged guids string)
private val unifiedGroupCache = mutableMapOf<String, String?>()
private val unifiedGroupCacheMutex = Mutex()

init {
    // Pre-populate unified group cache on initialization
    applicationScope.launch(ioDispatcher) {
        refreshUnifiedGroupCache()
    }
}

fun buildMessageNotification(...): android.app.Notification {
    // Use cached value to avoid blocking the notification thread
    val mergedGuids: String? = unifiedGroupCache[chatGuid]
    // ...
}
```

**Benefits:**
- No blocking on notification thread
- Cache is pre-populated on app startup
- Public `invalidateUnifiedGroupCache()` method for cache refresh when groups change
- Falls back gracefully if cache miss (null merged guids)

---

### 3. Race Condition in ExoPlayerPool.acquire() ✅ FIXED

**Location:** `services/media/ExoPlayerPool.kt` (Lines 58-80)

**Issue:**
Check-then-act pattern outside of synchronization allowed race condition where two threads could acquire different players for the same attachmentGuid, causing memory leak.

**Fix Applied:**
Moved entire acquire() logic inside synchronized block for atomic operation:
```kotlin
fun acquire(attachmentGuid: String): ExoPlayer = synchronized(lock) {
    activePlayers[attachmentGuid]?.let { return it }

    val player = if (availablePlayers.isNotEmpty()) {
        availablePlayers.removeAt(availablePlayers.size - 1)
    } else {
        createPlayer()
    }

    if (activePlayers.size >= MAX_ACTIVE_PLAYERS) {
        evictOldestPlayer()
    }

    activePlayers[attachmentGuid] = player
    return player
}
```

**Status:** ✅ Fixed - All operations now atomic within synchronized block.

---

### 4. Duplicate Detection Race in PendingMessageRepository ✅ FIXED

**Location:** `data/repository/PendingMessageRepository.kt` (Lines 110-148)

**Issue:**
UUID generated before synchronized block allowed double-tap to create duplicate messages despite duplicate detection logic.

**Fix Applied:**
Moved UUID generation inside synchronized block and added early return on duplicate detection:
```kotlin
val clientGuid = synchronized(globalRecentSends) {
    val guid = forcedLocalId ?: "temp-${UUID.randomUUID()}"

    val textForHash = text?.trim() ?: ""
    if (textForHash.isNotBlank()) {
        val textHash = textForHash.hashCode()
        val cutoffTime = createdAt - globalDuplicateWindowMs
        globalRecentSends.removeAll { it.timestamp < cutoffTime }

        // Check for duplicate (same chat + same text hash + within window)
        val duplicate = globalRecentSends.find {
            it.chatGuid == chatGuid && it.textHash == textHash
        }
        if (duplicate != null) {
            // Return existing localId to prevent duplicate
            return@runCatching duplicate.localId
        }

        globalRecentSends.add(GlobalRecentSend(...))
    }

    guid
}
```

**Status:** ✅ Fixed - UUID generation and duplicate check now atomic. Duplicates prevented by early return.

---

## High Priority Issues

### 5. Non-Atomic State Updates in ActiveConversationManager ✅ FIXED

**Location:** `services/ActiveConversationManager.kt` (Lines 45-108)

**Issue:**
Two separate volatile fields updated non-atomically allowed readers to see inconsistent state (old chatGuid with new mergedGuids or vice versa).

**Fix Applied:**
Replaced two volatile fields with single immutable data class for atomic state updates:
```kotlin
data class ActiveConversationState(
    val chatGuid: String,
    val mergedGuids: Set<String>
)

@Volatile
private var activeConversation: ActiveConversationState? = null

fun setActiveConversation(chatGuid: String, mergedGuids: Set<String> = emptySet()) {
    // Single atomic write - prevents readers from seeing inconsistent state
    activeConversation = ActiveConversationState(
        chatGuid = chatGuid,
        mergedGuids = mergedGuids + chatGuid
    )
}

fun isConversationActive(chatGuid: String): Boolean {
    val current = activeConversation ?: return false
    return current.chatGuid == chatGuid || current.mergedGuids.contains(chatGuid)
}
```

**Status:** ✅ Fixed - Single volatile reference ensures atomic visibility of both fields.

---

### 6. Race in AttachmentDownloadQueue.setActiveChat() ✅ FIXED

**Location:** `services/media/AttachmentDownloadQueue.kt` (Lines 133-151)

**Issue:**
Read-compare-write pattern without synchronization allowed race condition where reprioritization could be skipped or run incorrectly.

**Fix Applied:**
Added synchronization around entire operation:
```kotlin
// Lock for setActiveChat to prevent race condition
private val activeChatLock = Any()

fun setActiveChat(chatGuid: String?) = synchronized(activeChatLock) {
    val previousActive = activeChatGuid
    activeChatGuid = chatGuid

    // If changed, reprioritize existing queue items for the new active chat
    if (chatGuid != null && chatGuid != previousActive) {
        reprioritizeForChat(chatGuid)
    }
}
```

**Status:** ✅ Fixed - Read-compare-write now atomic within synchronized block.

---

### 7. Contact Cache Lookup Race ✅ FIXED

**Location:** `services/socket/handlers/MessageEventHandler.kt` (Lines 286-340)

**Issue:**
Between releasing lock after cache miss and acquiring for update, multiple threads could perform expensive Android Contacts queries for the same address simultaneously (common during message burst after reconnect).

**Fix Applied:**
Added "lookup in progress" tracking set to prevent duplicate queries:
```kotlin
// Track lookups in progress to prevent duplicate expensive queries
private val lookupInProgress = mutableSetOf<String>()

private suspend fun lookupContactWithCache(address: String): Pair<String?, String?> {
    val now = System.currentTimeMillis()

    // Check in-memory cache first and register lookup intent (mutex-protected)
    contactCacheMutex.withLock {
        // Check cache
        contactCache[address]?.let { cached ->
            if (now - cached.timestamp < CONTACT_CACHE_TTL_MS) {
                return cached.displayName to cached.avatarUri
            }
            contactCache.remove(address)
        }

        // Check if another thread is already looking up this address
        if (address in lookupInProgress) {
            // Another thread is querying - return null to avoid duplicate work
            return null to null
        }

        // Mark lookup as in progress
        lookupInProgress.add(address)
    }

    // Perform Android Contacts lookup (expensive I/O operation)
    val contactName: String?
    val photoUri: String?
    try {
        contactName = androidContactsService.getContactDisplayName(address)
        photoUri = if (contactName != null) {
            androidContactsService.getContactPhotoUri(address)
        } else {
            null
        }
    } finally {
        // Always clean up lookup-in-progress flag
        contactCacheMutex.withLock {
            lookupInProgress.remove(address)
        }
    }

    // Store result in cache
    contactCacheMutex.withLock {
        contactCache[address] = CachedContactInfo(contactName, photoUri, now)
    }

    return contactName to photoUri
}
```

**Status:** ✅ Fixed - Lookup-in-progress tracking prevents duplicate expensive queries during message bursts.

---

## Medium Priority Issues

### 8. SoundManager Init Race

**Location:** `services/sound/SoundManager.kt` (Lines 66-98)

**Issue:**
```kotlin
private var isLoaded = false

init {
    soundPool = SoundPool.Builder().build().apply {
        setOnLoadCompleteListener { _, _, status ->
            if (status == 0) {
                isLoaded = true  // Async callback
            }
        }
    }
}

private fun playSound(soundId: Int) {
    if (!isLoaded || soundId == 0) {  // Check races with callback
        return
    }
}
```

**Problem:** `isLoaded` check races with async callback.

---

### 9. MessageDeduplicator Check-Then-Act

**Location:** `util/MessageDeduplicator.kt` (Lines 56-81)

**Issue:**
```kotlin
suspend fun shouldNotifyForMessage(guid: String): Boolean {
    synchronized(lock) {
        if (guid in memoryCache) return false
    }

    val existsInDb = seenMessageDao.exists(guid)  // DB check (lock released!)

    if (existsInDb) {
        synchronized(lock) { addToMemoryCache(guid) }
        return false
    }

    markAsHandled(guid)  // Mark AFTER returning true
    return true
}
```

**Race Scenario:**
1. Message arrives via Socket (Thread A)
2. Same message arrives via FCM (Thread B)
3. Both check memory cache (empty)
4. Both check DB (not found)
5. Both return true
6. **Result:** Duplicate notification

---

### 10. MutableSharedFlow Buffer Overflow

**Location:** `services/media/AttachmentDownloadQueue.kt` (Line 130)

```kotlin
private val _downloadCompletions = MutableSharedFlow<String>(extraBufferCapacity = 64)
```

**Problem:** If 100 downloads complete rapidly, 36 events dropped.

---

## Summary Table

| Issue | Severity | Type | Status | File |
|-------|----------|------|--------|------|
| SimpleDateFormat shared | CRITICAL | Thread-Safety | ✅ FIXED | DateFormatters.kt |
| runBlocking in notification | CRITICAL | Deadlock | ✅ FIXED | NotificationBuilder.kt |
| ExoPlayerPool.acquire() race | CRITICAL | Race Condition | ✅ FIXED | ExoPlayerPool.kt |
| Duplicate detection race | CRITICAL | Race Condition | ✅ FIXED | PendingMessageRepository.kt |
| Non-atomic state updates | HIGH | Race Condition | ✅ FIXED | ActiveConversationManager.kt |
| setActiveChat() race | HIGH | Race Condition | ✅ FIXED | AttachmentDownloadQueue.kt |
| Contact cache lookup race | HIGH | Race Condition | ✅ FIXED | MessageEventHandler.kt |
| SoundManager init race | MEDIUM | Race Condition | ⚠️ Open | SoundManager.kt |
| MessageDeduplicator race | MEDIUM | Race Condition | ⚠️ Open | MessageDeduplicator.kt |
| SharedFlow buffer overflow | MEDIUM | Event Loss | ⚠️ Open | AttachmentDownloadQueue.kt |

---

## Recommendations

### Completed ✅
1. **SimpleDateFormat thread-safety** - All instances replaced with DateTimeFormatter (6 files)
2. **runBlocking in NotificationBuilder** - Implemented caching pattern with background refresh
3. **ExoPlayerPool.acquire() race** - Moved all operations inside synchronized block
4. **PendingMessageRepository duplicate detection** - UUID generation and duplicate check now atomic
5. **ActiveConversationManager state updates** - Single immutable data class for atomic state
6. **AttachmentDownloadQueue.setActiveChat()** - Added synchronization for read-compare-write
7. **MessageEventHandler contact cache** - Added lookup-in-progress tracking to prevent duplicate queries

### Remaining Work

1. **Short-term (Medium):**
   - Fix SoundManager initialization race (use AtomicBoolean or StateFlow)
   - Fix MessageDeduplicator check-then-act race
   - Review SharedFlow buffer sizes for high-throughput scenarios

2. **Long-term:**
   - Add @ThreadSafe, @GuardedBy annotations to document thread-safety contracts
   - Add concurrency stress tests for critical paths
   - Consider using kotlin.concurrent.Atomics for lock-free data structures
