# Concurrency Anti-Patterns

**Scope:** Race conditions, thread safety, deadlocks, coroutine issues

---

## Critical Issues

### 1. SimpleDateFormat Shared Across Threads

**Location:** `util/parsing/DateFormatters.kt`

**Issue:**
```kotlin
internal object DateFormatters {
    val WITH_YEAR = listOf(
        SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.US),
        SimpleDateFormat("MMMM d, yyyy 'at' h:mma", Locale.US),
        // ... 16 more formatters
    ).onEach { it.isLenient = false }
}
```

**Usage:** `util/parsing/AbsoluteDateParser.kt`:
```kotlin
private fun tryParseWithFormats(dateString: String, formats: List<SimpleDateFormat>): Calendar? {
    for (format in formats) {
        try {
            val date = format.parse(dateString)  // NOT THREAD-SAFE!
```

**Race Condition:** SimpleDateFormat is NOT thread-safe. Two threads calling `.parse()` simultaneously corrupt internal state.

**Symptoms:**
- Incorrect parsing results
- ArrayIndexOutOfBoundsException
- NumberFormatException

**Fix:**
```kotlin
// Use java.time.format.DateTimeFormatter (thread-safe)
val WITH_YEAR = listOf(
    DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.US),
)

// Or synchronize access
synchronized(format) {
    format.parse(dateString)
}
```

---

### 2. runBlocking in Notification Builder

**Location:** `services/notifications/NotificationBuilder.kt` (Lines 92-99)

**Issue:**
```kotlin
fun buildMessageNotification(...): android.app.Notification {
    val mergedGuids: String? = try {
        runBlocking(ioDispatcher) {  // BLOCKING!
            val group = unifiedChatGroupDao.getGroupForChat(chatGuid)
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

**Fix:**
```kotlin
suspend fun buildMessageNotification(...): Notification {
    val group = unifiedChatGroupDao.getGroupForChat(chatGuid)
    // ...
}
```

---

### 3. Race Condition in ExoPlayerPool.acquire()

**Location:** `services/media/ExoPlayerPool.kt` (Lines 58-81)

**Issue:**
```kotlin
fun acquire(attachmentGuid: String): ExoPlayer {
    activePlayers[attachmentGuid]?.let { return it }  // CHECK (outside lock)

    val player = synchronized(lock) {                  // GET FROM POOL
        if (availablePlayers.isNotEmpty()) {
            availablePlayers.removeAt(availablePlayers.size - 1)
        } else {
            createPlayer()
        }
    }

    if (activePlayers.size >= MAX_ACTIVE_PLAYERS) {   // CHECK (stale)
        evictOldestPlayer()
    }

    activePlayers[attachmentGuid] = player             // ACT (race!)
    return player
}
```

**Race Scenario:**
1. Thread A: Gets player from pool (inside synchronized)
2. Thread B: Calls acquire() with same attachmentGuid
3. Thread B: Check finds nothing in activePlayers
4. Thread B: Gets another player from pool
5. Thread A: Adds player to activePlayers
6. Thread B: Overwrites with different player
7. **Result:** Thread A's player is orphaned, memory leak

**Fix:**
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

---

### 4. Duplicate Detection Race in PendingMessageRepository

**Location:** `data/repository/PendingMessageRepository.kt` (Lines 125-164)

**Issue:**
```kotlin
val clientGuid = forcedLocalId ?: "temp-${UUID.randomUUID()}"

synchronized(globalRecentSends) {
    globalRecentSends.removeAll { it.timestamp < cutoffTime }

    val sameChatDuplicate = globalRecentSends.find {
        it.chatGuid == chatGuid && it.textHash == textHash
    }
    if (sameChatDuplicate != null) {
        // Log warning but CONTINUE (don't prevent sending!)
    }

    globalRecentSends.add(GlobalRecentSend(...))
}
```

**Race Scenario (double-tap):**
1. User taps send twice rapidly
2. Two threads call `queueMessage()` simultaneously
3. Both pass synchronized block at nearly same time
4. Both create separate UUIDs before synchronization
5. Both get queued and sent
6. **Result:** Duplicate messages despite detection code

**Fix:** Generate UUID inside synchronized block, or actually prevent duplicate sends.

---

## High Priority Issues

### 5. Non-Atomic State Updates in ActiveConversationManager

**Location:** `services/ActiveConversationManager.kt` (Lines 46-65)

**Issue:**
```kotlin
@Volatile
private var activeChatGuid: String? = null

@Volatile
private var activeMergedGuids: Set<String> = emptySet()

fun setActiveConversation(chatGuid: String, mergedGuids: Set<String> = emptySet()) {
    activeChatGuid = chatGuid                    // WRITE 1
    activeMergedGuids = mergedGuids + chatGuid   // WRITE 2 (not atomic!)
}
```

**Problem:** Reader thread could see:
- Old activeChatGuid with new activeMergedGuids
- New activeChatGuid with old activeMergedGuids

**Fix:**
```kotlin
@Volatile
private var activeConversation: ActiveConversationState? = null

data class ActiveConversationState(
    val chatGuid: String,
    val mergedGuids: Set<String>
)

fun setActiveConversation(chatGuid: String, mergedGuids: Set<String>) {
    activeConversation = ActiveConversationState(chatGuid, mergedGuids + chatGuid)
}
```

---

### 6. Race in AttachmentDownloadQueue.setActiveChat()

**Location:** `services/media/AttachmentDownloadQueue.kt` (Lines 140-147)

**Issue:**
```kotlin
@Volatile
private var activeChatGuid: String? = null

fun setActiveChat(chatGuid: String?) {
    val previousActive = activeChatGuid   // READ
    activeChatGuid = chatGuid             // WRITE
    if (chatGuid != null && chatGuid != previousActive) {
        reprioritizeForChat(chatGuid)     // ACT
    }
}
```

**Problem:** Between read and comparison, another thread could change activeChatGuid.

**Fix:**
```kotlin
private val activeChatLock = Any()

fun setActiveChat(chatGuid: String?) = synchronized(activeChatLock) {
    val previousActive = activeChatGuid
    activeChatGuid = chatGuid
    if (chatGuid != null && chatGuid != previousActive) {
        reprioritizeForChat(chatGuid)
    }
}
```

---

### 7. Contact Cache Lookup Race

**Location:** `services/socket/handlers/MessageEventHandler.kt` (Lines 286-312)

**Issue:**
```kotlin
private suspend fun lookupContactWithCache(address: String): Pair<String?, String?> {
    contactCacheMutex.withLock {
        contactCache[address]?.let { cached ->
            if (now - cached.timestamp < TTL) {
                return cached.displayName to cached.avatarUri  // EARLY RETURN
            }
            contactCache.remove(address)  // CLEANUP
        }
    }
    // LOCK RELEASED HERE

    // Multiple threads could all reach here simultaneously!
    val contactName = androidContactsService.getContactDisplayName(address)

    contactCacheMutex.withLock {
        contactCache[address] = CachedContactInfo(...)  // UPDATE
    }
}
```

**Problem:** Between releasing lock after cleanup and acquiring for update, multiple threads perform expensive contact lookup for same address.

**Fix:** Use "lookup in progress" flag:
```kotlin
private val lookupInProgress = mutableSetOf<String>()

contactCacheMutex.withLock {
    if (address in lookupInProgress) {
        // Wait or return cached
    }
    lookupInProgress.add(address)
}
try {
    val result = androidContactsService.getContactDisplayName(address)
    // ...
} finally {
    contactCacheMutex.withLock {
        lookupInProgress.remove(address)
    }
}
```

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

| Issue | Severity | Type | File |
|-------|----------|------|------|
| SimpleDateFormat shared | CRITICAL | Thread-Safety | DateFormatters.kt |
| runBlocking in notification | CRITICAL | Deadlock | NotificationBuilder.kt |
| ExoPlayerPool.acquire() race | CRITICAL | Race Condition | ExoPlayerPool.kt |
| Duplicate detection race | CRITICAL | Race Condition | PendingMessageRepository.kt |
| Non-atomic state updates | HIGH | Race Condition | ActiveConversationManager.kt |
| setActiveChat() race | HIGH | Race Condition | AttachmentDownloadQueue.kt |
| Contact cache lookup race | HIGH | Race Condition | MessageEventHandler.kt |
| SoundManager init race | MEDIUM | Race Condition | SoundManager.kt |
| MessageDeduplicator race | MEDIUM | Race Condition | MessageDeduplicator.kt |
| SharedFlow buffer overflow | MEDIUM | Event Loss | AttachmentDownloadQueue.kt |

---

## Recommendations

1. **Immediate:**
   - Fix SimpleDateFormat thread-safety (use java.time)
   - Fix ExoPlayerPool.acquire() synchronization
   - Convert runBlocking to suspend

2. **Short-term:**
   - Add @ThreadSafe, @GuardedBy annotations
   - Review all Mutex/Lock usage
   - Add CAS for compound operations

3. **Long-term:**
   - Use immutable state patterns
   - Test concurrent scenarios
   - Document thread-safety contracts
