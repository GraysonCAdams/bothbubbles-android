# Services Layer Anti-Patterns

**Layer:** `app/src/main/kotlin/com/bothbubbles/services/`
**Files Scanned:** All service, handler, worker, and receiver files

---

## High Severity Issues

### 1. runBlocking on Main/Service Threads ✅ FIXED

**Locations:**
- `services/fcm/FirebaseDatabaseService.kt` (Lines 119-122) ✅ FIXED
- `services/notifications/NotificationBuilder.kt` (Lines 92-100) ✅ FIXED

**Issue (FirebaseDatabaseService):**
```kotlin
fun stopListening() {
    realtimeDbListener?.let { listener ->
        try {
            val databaseUrl = runCatching {
                kotlinx.coroutines.runBlocking {  // BLOCKING!
                    settingsDataStore.firebaseDatabaseUrl.first()
                }
            }.getOrNull()
            // ...
        }
    }
}
```

**Issue (NotificationBuilder):**
```kotlin
val mergedGuids: String? = try {
    runBlocking(ioDispatcher) {  // BLOCKING on main thread!
        val group = unifiedChatGroupDao.getGroupForChat(chatGuid)
        // ...
    }
}
```

**Why Problematic:**
- `runBlocking` on main thread causes ANR (Application Not Responding)
- Notification building happens on main thread
- Service cleanup can block entire app
- Violates Android threading guidelines

**Fix Applied (FirebaseDatabaseService):**
```kotlin
// Cache the URL when starting
private var cachedDatabaseUrl: String? = null

fun startListening() {
    applicationScope.launch(ioDispatcher) {
        val databaseUrl = settingsDataStore.firebaseDatabaseUrl.first()
        cachedDatabaseUrl = databaseUrl.takeIf { it.isNotBlank() }
        // Start listener...
    }
}

fun stopListening() {
    realtimeDbListener?.let { listener ->
        try {
            // Use cached database URL to avoid blocking
            if (!cachedDatabaseUrl.isNullOrBlank()) {
                val database = FirebaseDatabase.getInstance(cachedDatabaseUrl!!)
                database.getReference(REALTIME_DB_PATH).removeEventListener(listener)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error removing Realtime Database listener")
        }
        realtimeDbListener = null
    }
    // Clear cached URL
    cachedDatabaseUrl = null
}
```

**Fix Applied (NotificationBuilder):**
Implemented caching pattern with background refresh. See `docs/antipatterns/11-CONCURRENCY-ANTI-PATTERNS.md` for full details.

---

### 2. ~~Memory Leaks from Uncancelled Flow Collectors~~ **[FIXED]**

**Location:** `services/socket/SocketEventHandler.kt` (Lines 92-112)

**Status:** ✅ FIXED - Job references now stored and properly cancelled in stopListening()

**Previous Issue:**
- Collector Jobs not stored, couldn't cancel them
- `stopListening()` didn't actually stop the collectors
- Multiple calls to `startListening()` created duplicate collectors
- Memory leak as collectors accumulated

**Fix Applied:**
```kotlin
private var socketEventsJob: Job? = null
private var connectionStateJob: Job? = null

fun startListening() {
    if (isListening) return
    isListening = true

    socketEventsJob = applicationScope.launch(ioDispatcher) {
        socketService.events.collect { event ->
            handleEvent(event)
        }
    }

    connectionStateJob = applicationScope.launch(ioDispatcher) {
        socketService.connectionState.collect { state ->
            if (state == ConnectionState.CONNECTED) {
                handleSocketConnected()
            }
        }
    }
}

fun stopListening() {
    isListening = false
    socketEventsJob?.cancel()
    connectionStateJob?.cancel()
    socketEventsJob = null
    connectionStateJob = null
}
```

---

## Medium Severity Issues

### 3. Listener Registration Without Cleanup

**Location:** `services/fcm/FirebaseDatabaseService.kt` (Lines 162-192)

**Issue:**
```kotlin
private fun startRealtimeDatabaseListener(databaseUrl: String) {
    realtimeDbListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) { ... }
        override fun onCancelled(error: DatabaseError) { ... }
    }

    ref.addValueEventListener(realtimeDbListener!!)  // Registered
}

fun stopListening() {
    // Cleanup is fragile:
    // - Needs blocking I/O to get URL again
    // - May fail if URL changed
    // - May never be called if service destroyed
}
```

**Why Problematic:**
- Listener persists across service lifecycle
- Cleanup requires blocking I/O to get database URL
- If service is destroyed without cleanup, listener leaks
- Firebase listeners can prevent garbage collection

**Fix:**
Store database reference at registration time, use in cleanup.

---

### 4. ~~Unsafe Collector Patterns in Singleton Services~~ **[FIXED]**

**Locations:**
- `services/messaging/ChatFallbackTracker.kt` (Lines 53-65)
- `services/imessage/IMessageAvailabilityService.kt`
- `services/notifications/BadgeManager.kt`
- `services/developer/ConnectionModeManager.kt`

**Status:** ✅ FIXED - All services now store Job references and provide cleanup methods for testing

**Previous Issue:**
- Collectors launched in `init` couldn't be cancelled
- Made services harder to test
- If singleton was ever recreated (testing), collectors would accumulate

**Fix Applied:**
All affected services now:
1. Store Job references as private fields
2. Provide `cleanup()` method for testing scenarios
3. Cancel and nullify Jobs in cleanup

Example from ChatFallbackTracker:
```kotlin
@Singleton
class ChatFallbackTracker @Inject constructor(...) {
    private var restoreJob: Job? = null
    private var connectionStateJob: Job? = null

    init {
        restoreJob = applicationScope.launch(ioDispatcher) {
            restorePersistedFallbacks()
        }

        connectionStateJob = applicationScope.launch(ioDispatcher) {
            socketService.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    onServerReconnected()
                }
            }
        }
    }

    fun cleanup() {
        restoreJob?.cancel()
        connectionStateJob?.cancel()
        restoreJob = null
        connectionStateJob = null
    }
}
```

**Files Fixed:**
- ✅ ChatFallbackTracker.kt - Added Job storage and cleanup()
- ✅ IMessageAvailabilityService.kt - Added Job storage and cleanup()
- ✅ BadgeManager.kt - Added Job storage and cleanup()
- ✅ ConnectionModeManager.kt - Added Job storage and cleanup()
- ✅ ShortcutService.kt - Already had proper Job management

---

### 5. Missing Handler Interface Abstraction

**Locations:**
- `services/socket/handlers/ChatEventHandler.kt`
- `services/socket/handlers/SystemEventHandler.kt`

**Issue:**
```kotlin
@Singleton
class ChatEventHandler @Inject constructor(...) {
    // No interface - tightly coupled
    suspend fun handleTypingIndicator(event: SocketEvent.TypingIndicator)
    suspend fun handleChatRead(event: SocketEvent.ChatRead, ...)
}
```

**Why Problematic:**
- Can't mock for testing
- Other services depend on concrete implementation
- Harder to swap implementations

**Fix:**
```kotlin
interface ChatEventProcessor {
    suspend fun handleTypingIndicator(event: SocketEvent.TypingIndicator)
    suspend fun handleChatRead(event: SocketEvent.ChatRead, uiRefreshEvents: MutableSharedFlow<UiRefreshEvent>)
}

@Singleton
class ChatEventHandler @Inject constructor(...) : ChatEventProcessor
```

---

### 6. Unlimited Socket Reconnection Attempts

**Location:** `services/socket/SocketIOConnection.kt` (Lines 118-126)

**Issue:**
```kotlin
val options = IO.Options().apply {
    reconnection = true
    reconnectionAttempts = Int.MAX_VALUE  // Forever!
    reconnectionDelay = 5000
    reconnectionDelayMax = 60000
    timeout = 20000  // 20s - quite short
}
```

**Why Problematic:**
- Retries forever with no user feedback
- Exponential backoff spaces retries far apart eventually
- User sees "Disconnected" indefinitely
- No mechanism to alert user to check server
- 20s timeout may be too short for slow connections

**Fix:**
```kotlin
val options = IO.Options().apply {
    reconnection = true
    reconnectionAttempts = 15  // Reasonable limit
    reconnectionDelay = 5000
    reconnectionDelayMax = 60000
    timeout = 30000  // Increase to 30s
}

// After max attempts, emit error event
socket?.on(Socket.EVENT_RECONNECT_FAILED) {
    _connectionState.value = ConnectionState.FAILED_PERMANENTLY
    // Notify user to check server
}
```

---

### 7. Notification ID Collision Risk

**Location:** `services/notifications/NotificationChannelManager.kt` (Lines 43-50)

**Issue:**
```kotlin
companion object {
    const val SUMMARY_NOTIFICATION_ID = 0  // ID = 0 (reserved on some versions)
    const val FACETIME_NOTIFICATION_ID_PREFIX = 1000000
    const val SYNC_COMPLETE_NOTIFICATION_ID = 2000001
}

// NotificationService.kt line 152:
val notificationId = FACETIME_NOTIFICATION_ID_PREFIX + callUuid.hashCode()
// hashCode() can collide!
```

**Why Problematic:**
- ID = 0 is reserved on some Android versions
- `hashCode()` can produce collisions for different UUIDs
- Two FaceTime calls with same hash = one notification replaced
- Chat notifications also use `hashCode()` - could collide

**Fix:**
```kotlin
companion object {
    const val SUMMARY_NOTIFICATION_ID = 1  // Avoid 0
    const val MESSAGE_NOTIFICATION_START = 100000
    const val FACETIME_NOTIFICATION_START = 200000
    const val SYNC_NOTIFICATION_START = 300000
}

// Use modulo to bound IDs
val notificationId = FACETIME_NOTIFICATION_START + abs(callUuid.hashCode() % 99999)
```

---

### 8. Silent Parse Failures in Socket Events

**Location:** `services/socket/SocketEventParser.kt` (Lines 31-61)

**Issue:**
```kotlin
val onNewMessage = Emitter.Listener { args ->
    try {
        val data = args.firstOrNull() as? JSONObject ?: run {
            Timber.w("new-message: first arg is not JSONObject")
            return@Listener  // Silent return - event lost!
        }

        val message = messageAdapter.fromJson(data.toString())
        if (message == null) {
            Timber.w("new-message: Failed to parse message")
            return@Listener  // Silent return - event lost!
        }
    } catch (e: Exception) {
        Timber.e(e, "Error parsing new message")  // Logged only
    }
}
```

**Why Problematic:**
- Parse failures only logged, not reported to app
- User sees notification but message doesn't appear in chat
- No mechanism to retry or notify of degraded state
- Hard to debug in production

**Fix:**
```kotlin
val onNewMessage = Emitter.Listener { args ->
    try {
        // ... parse ...
    } catch (e: Exception) {
        Timber.e(e, "Error parsing new message")
        _events.tryEmit(SocketEvent.ParseError(
            eventType = "new-message",
            rawData = args.firstOrNull()?.toString(),
            exception = e
        ))
    }
}
```

---

### 9. Large Unbounded SharedFlow Buffer

**Location:** `services/socket/SocketService.kt` (Line 108)

**Issue:**
```kotlin
private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 100)
```

**Why Problematic:**
- 100 events can buffer without collector
- If UI isn't ready, events accumulate in memory
- Burst of socket events fills memory
- Consider if this is intentional behavior

**Fix (if unintentional):**
```kotlin
private val _events = MutableSharedFlow<SocketEvent>(
    extraBufferCapacity = 10,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
```

---

### 10. Unbounded Contact Cache

**Location:** `services/socket/handlers/MessageEventHandler.kt` (Lines 77-79)

**Issue:**
```kotlin
private val contactCacheMutex = Mutex()
private val contactCache = mutableMapOf<String, CachedContactInfo>()
// Cache grows unbounded!
```

**Why Problematic:**
- Cache has no size limit
- Thousands of conversations = thousands of cached entries
- No periodic cleanup
- Memory leak over time

**Fix:**
```kotlin
private val contactCache = LruCache<String, CachedContactInfo>(500)
```

---

## Low Severity Issues

### 11. Race Condition in Delivery Mode Determination

**Location:** `services/messaging/MessageSendingService.kt` (Lines 491-556)

**Issue:**
```kotlin
private suspend fun determineDeliveryMode(...): MessageDeliveryMode {
    val chat = chatDao.getChatByGuid(chatGuid)  // Read 1

    if (settingsDataStore.smsOnlyMode.first()) { ... }  // Read 2

    if (chatFallbackTracker.isInFallbackMode(chatGuid)) { ... }  // Check 3

    val isConnected = socketService.connectionState.value == ConnectionState.CONNECTED  // Read 4
    // State could change between any of these reads!
}
```

**Why Problematic:**
- Multiple async reads from different sources
- State could change between reads
- TOCTOU (Time-of-Check-Time-of-Use) race condition
- Delivery mode could be stale by send time

**Fix:**
Document behavior or capture all state atomically at start.

---

### 12. Inefficient Polling for Message Confirmation

**Location:** `services/messaging/MessageSendWorker.kt` (Lines 304-339)

**Issue:**
```kotlin
private suspend fun waitForServerConfirmation(...): kotlin.Result<Unit> {
    while (System.currentTimeMillis() - startTime < SERVER_CONFIRMATION_TIMEOUT_MS) {
        val message = messageDao.getMessageByGuid(serverGuid)
        // ... check status
        delay(CONFIRMATION_POLL_INTERVAL_MS)  // 2 second polls
    }
}
```

**Why Problematic:**
- 2-minute timeout with 2-second polls = up to 60 DB queries
- No exponential backoff
- Socket event could arrive between polls
- Wasteful compared to event-driven approach

**Fix:**
Subscribe to message updates via Flow instead of polling.

---

### 13. Missing Backoff in Background Sync Worker

**Location:** `services/sync/BackgroundSyncWorker.kt` (Lines 80-98)

**Issue:**
```kotlin
fun schedule(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    val workRequest = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        // No backoff criteria!
        .build()
}
```

**Why Problematic:**
- No exponential backoff on failure
- WorkManager may retry aggressively
- Battery drain on repeated failures

**Fix:**
```kotlin
.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
```

---

## Summary Table

| Issue | Severity | File | Lines | Category | Status |
|-------|----------|------|-------|----------|--------|
| runBlocking on Main Thread | HIGH | NotificationBuilder.kt | 92-100 | Threading | 🔴 Open |
| runBlocking in Service | HIGH | FirebaseDatabaseService.kt | 119-122 | Threading | 🔴 Open |
| Uncancelled Collectors | HIGH | SocketEventHandler.kt | 92-112 | Memory Leak | ✅ **FIXED** |
| Listener Without Cleanup | MEDIUM | FirebaseDatabaseService.kt | 162-192 | Memory Leak | 🔴 Open |
| Singleton Init Collectors | MEDIUM | Multiple files | - | Testing | ✅ **FIXED** |
| Missing Handler Interface | MEDIUM | ChatEventHandler.kt | - | Testability | 🔴 Open |
| Unlimited Reconnects | MEDIUM | SocketIOConnection.kt | 118-126 | UX | 🔴 Open |
| Notification ID Collision | MEDIUM | NotificationChannelManager.kt | 43-50 | Logic | 🔴 Open |
| Silent Parse Failures | MEDIUM | SocketEventParser.kt | 31-61 | Error Handling | 🔴 Open |
| Large SharedFlow Buffer | MEDIUM | SocketService.kt | 108 | Memory | 🔴 Open |
| Unbounded Contact Cache | LOW | MessageEventHandler.kt | 77-79 | Memory | 🔴 Open |
| TOCTOU Race Condition | LOW | MessageSendingService.kt | 491-556 | Race Condition | 🔴 Open |
| Inefficient Polling | LOW | MessageSendWorker.kt | 304-339 | Performance | 🔴 Open |
| Missing Worker Backoff | LOW | BackgroundSyncWorker.kt | 80-98 | Reliability | 🔴 Open |

---

## Positive Findings

- Good use of interfaces for testability (MessageSender, SocketConnection, etc.)
- Proper PendingIntent flags in SocketForegroundService
- Good decomposition with handler delegation pattern
- Correct use of WorkManager constraints
