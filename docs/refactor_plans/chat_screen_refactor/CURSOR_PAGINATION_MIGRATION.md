# Cursor-Based Pagination Migration Plan

This document outlines the architectural shift from the current "BitSet/Shifting Index" pagination to a standard "Cursor-Based Pagination" model using Room as the Single Source of Truth.

## The Core Problem

The current architecture relies on **mutable indices** in a `reverseLayout` list. When a new message arrives at index 0, all existing messages shift indices (N -> N+1). This creates a permanent race condition between the UI (viewing index 50) and the data controller (shifting index 50 to 51), requiring complex O(N) shifting logic and generation tracking to patch.

## The Solution: Cursor-Based Pagination

We will move to a system where:
1.  **GUID is the only identifier** (No position indices).
2.  **Room is the Single Source of Truth** (SSOT).
3.  **LazyColumn handles diffing** via stable keys.
4.  **Pagination is reactive** (Growing query limit).

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        LazyColumn                               │
│  - reverseLayout = true                                         │
│  - key = { message.guid }  ← Stable, never changes              │
│  - Items diffed by Compose automatically                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ViewModel / Delegate                        │
│  - Holds state: queryLimit (Int)                                │
│  - Exposes Flow<List<MessageUiModel>>                           │
│  - loadMore() increments queryLimit                             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Room Database                            │
│  - Query: ORDER BY dateCreated DESC LIMIT :limit                │
│  - Flow automatically emits on INSERT/UPDATE                    │
│  - New messages = INSERT, Room notifies observers               │
└─────────────────────────────────────────────────────────────────┘
```

## Implementation Details

### 1. The "Growing Query" Pattern

Instead of appending pages manually in memory, we simply ask Room for "more items". Room handles the merging and ordering.

**ViewModel Logic:**

> Keep the expanding `queryLimit` per-chat and persist it (saved state or cache) so configuration changes do not collapse the list back to the initial 50 items.

```kotlin
// State for how many messages we want to show
private val queryLimit = MutableStateFlow(50)

// The main stream of messages
val messages: StateFlow<List<MessageUiModel>> = queryLimit
    .flatMapLatest { limit ->
        // When limit changes, restart the Room query
        messageDao.observeRecentMessages(chatGuids, limit)
    }
    .map { entities -> 
        entities.map { it.toUiModel() } 
    }
    .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

fun loadMore() {
    if (loadingMore.getAndSet(true)) return // Prevent overlapping requests

    // 1. Increase the window size
    val newLimit = queryLimit.value + 50
    queryLimit.value = newLimit
    
    // 2. Check if we need to fetch from server
    scope.launch {
        val localCount = messageDao.countMessages(chatGuids)
        if (localCount < newLimit) {
            val oldestMsg = messages.value.lastOrNull()
            // Fetch older messages from server, insert into DB
            // Room Flow will automatically emit the new data
            repository.fetchMessagesBefore(oldestMsg?.dateCreated)
        }

        loadingMore.set(false)
    }
}
```

### 2. DAO Updates

We need queries that support the dynamic limit and cursor-based fetching.

```kotlin
@Dao
interface MessageDao {
    // The main driver for the chat list
    // Note: Uses snake_case column names to match actual schema
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid IN (:chatGuids)
        AND date_deleted IS NULL
        ORDER BY date_created DESC, guid DESC
        LIMIT :limit
    """)
    fun observeRecentMessages(chatGuids: List<String>, limit: Int): Flow<List<MessageEntity>>

    // For server sync checks
    @Query("SELECT COUNT(*) FROM messages WHERE chat_guid IN (:chatGuids) AND date_deleted IS NULL")
    suspend fun countMessages(chatGuids: List<String>): Int
}
```

### 3. Handling "Jump to Message"

Jumping to a specific message (search result, reply tap) requires a different mode, as we can't just load "Recent + 5000".

**Strategy:**
The ViewModel maintains a `mode` state.

*   **`Mode.Recent`**: Standard view. Query anchored to NOW.
*   **`Mode.Archive`**: History view. Query anchored to TARGET.

```kotlin
sealed class ChatViewMode {
    object Recent : ChatViewMode()
    data class Archive(
        val targetGuid: String,
        val targetTimestamp: Long,
        val windowMs: Long = 12 * 60 * 60 * 1000L  // ±12 hours
    ) : ChatViewMode()
}

// IMPORTANT: Use flatMapLatest to flatten Flow<Flow<List>> → Flow<List>
val messages: StateFlow<List<MessageUiModel>> = combine(viewMode, queryLimit) { mode, limit ->
    Pair(mode, limit)
}.flatMapLatest { (mode, limit) ->
    when (mode) {
        is ChatViewMode.Recent -> messageDao.observeRecentMessages(chatGuids, limit)
        is ChatViewMode.Archive -> messageDao.observeMessagesInWindow(
            chatGuids = chatGuids,
            windowStart = mode.targetTimestamp - mode.windowMs,
            windowEnd = mode.targetTimestamp + mode.windowMs
        )
    }
}.map { entities ->
    entities.map { messageCache.getOrCreate(it) { it.toUiModel() } }
}.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

// When the user scrolls to the live edge (index 0) while in Archive mode, flip back to Recent so new messages stream in automatically.
```

### 4. LazyColumn Configuration

Crucial for performance and scroll stability. We also need to automatically trigger `loadMore()` when the user scrolls near the end of the loaded list.

```kotlin
val listState = rememberLazyListState()

// Auto-pagination trigger
val shouldLoadMore by remember {
    derivedStateOf {
        val totalItems = listState.layoutInfo.totalItemsCount
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        // Trigger when within 10 items of the end (history)
        totalItems > 0 && lastVisible > totalItems - 10
    }
}

LaunchedEffect(shouldLoadMore) {
    if (shouldLoadMore) viewModel.loadMore()
}

LazyColumn(
    reverseLayout = true,
    state = listState
) {
    items(
        items = messages,
        key = { it.key }, // CRITICAL: Must be stable (guid for messages, date string for headers)
        contentType = { it.type }
    ) { item ->
        when (item) {
            is ChatListItem.Message -> MessageBubble(item.message)
            is ChatListItem.DateSeparator -> DateHeader(item.date)
        }
    }
}
```

### 5. Handling Date Separators & UI Models

Chat lists require date headers (e.g., "Today", "Yesterday"). These should be inserted during the mapping phase.

*   **Stable Keys:** Headers must have stable keys (e.g., `header_2023_10_27`) to prevent UI glitches.
*   **Sealed Interface:** Use a sealed interface for the list items.

```kotlin
sealed interface ChatListItem {
    val key: String
    val type: Int

    data class Message(val message: MessageUiModel) : ChatListItem {
        override val key = message.guid
        override val type = 1
    }
    data class DateSeparator(val date: String) : ChatListItem {
        override val key = "date_$date"
        override val type = 2
    }
}
```

### 6. Ephemeral State (Typing Indicators)

Typing indicators are transient and shouldn't be stored in Room. Combine the Room flow with a memory-only flow.

```kotlin
val uiList = combine(
    messagesFlow,
    typingIndicatorsFlow
) { msgs, indicators ->
    // Prepend/Append indicators to the list (depending on layout direction)
    // For reverseLayout, indicators usually go at index 0 (bottom)
    indicators + msgs 
}
```

### 7. Performance Optimizations

*   **IO Dispatcher:** Ensure the database query and the heavy `map` operation (Entity -> UiModel) run on `Dispatchers.IO` or `Dispatchers.Default`.
*   **distinctUntilChanged:** Apply `distinctUntilChanged()` to the Flow to prevent recomposition if the data hasn't structurally changed.
*   **Debouncing:** The `loadMore` action should be debounced or guarded by a `loading` flag (as shown in the ViewModel example) to prevent spamming the DB.

#### MessageCache for Object Identity

The existing `MessageCache` preserves object identity for unchanged messages - this is important for Compose performance (skipping recomposition). **Keep this pattern:**

```kotlin
val messages = queryLimit
    .flatMapLatest { limit -> messageDao.observeRecentMessages(chatGuids, limit) }
    .map { entities ->
        entities.map { entity ->
            // MessageCache returns same object reference if unchanged
            messageCache.getOrCreate(entity) { it.toUiModel() }
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(...)
```

This ensures that when Room emits a new list (e.g., one message updated), only the changed message gets a new object - all others keep the same reference, and Compose skips recomposing them.

## Migration Steps

### Phase 1: Database & DAO
1.  Update `MessageDao` to include `observeRecentMessages` with dynamic limit.
2.  Ensure `dateCreated` is indexed for performance.

### Phase 2: ViewModel / Delegate
1.  Create a new `CursorChatMessageListDelegate` (or refactor existing).
2.  Implement the `queryLimit` StateFlow.
3.  Implement `loadMore()` to increment limit and trigger sync.

### Phase 3: Cleanup (The Great Deletion)
Once the new system is verified, delete the legacy paging infrastructure:
*   `MessagePagingController.kt` (800+ lines of complexity)
*   `SparseMessageList.kt`
*   `MessagePagingState.kt`
*   `MessagePagingHelpers.kt`
*   `RoomMessageDataSource.kt` (replaced by direct DAO usage)

### 8. Query Limit Persistence

The `queryLimit` must survive configuration changes (rotation) and navigation (back/forward). Strategy:

```kotlin
class ChatMessageListDelegate(
    private val chatStateCache: ChatStateCache,  // Existing LRU cache
    private val savedStateHandle: SavedStateHandle  // For config changes
) {
    // Restore from SavedStateHandle first (config change), then cache (navigation)
    private val queryLimit = MutableStateFlow(
        savedStateHandle.get<Int>("queryLimit")
            ?: chatStateCache.get(chatGuid)?.queryLimit
            ?: 50
    )

    init {
        // Persist to SavedStateHandle on every change
        scope.launch {
            queryLimit.collect { limit ->
                savedStateHandle["queryLimit"] = limit
            }
        }
    }

    fun saveStateToCache() {
        chatStateCache.put(chatGuid, queryLimit = queryLimit.value, ...)
    }
}
```

### 9. Scroll Position Restoration

When re-opening a chat, we need enough messages loaded to restore scroll position:

```kotlin
fun initialize(cachedScrollIndex: Int?) {
    // Ensure queryLimit is large enough to restore scroll position
    if (cachedScrollIndex != null && cachedScrollIndex > queryLimit.value - 10) {
        queryLimit.value = cachedScrollIndex + 20  // Buffer for smooth scroll
    }
}
```

### 10. Optimistic Message Insertion

For instant display of sent messages without waiting for Room write:

```kotlin
// Ephemeral optimistic messages (not in Room yet)
private val optimisticMessages = MutableStateFlow<List<MessageUiModel>>(emptyList())

val messages = combine(
    roomMessagesFlow,
    optimisticMessages
) { roomMsgs, optimistic ->
    // Prepend optimistic, dedupe by GUID when Room catches up
    val roomGuids = roomMsgs.map { it.guid }.toSet()
    val pending = optimistic.filterNot { it.guid in roomGuids }
    pending + roomMsgs
}

fun onMessageSent(message: MessageUiModel) {
    optimisticMessages.update { it + message }
    // Room insert happens async, Flow dedupes automatically
}
```

### 11. Socket Event Bridge

Socket events still need to trigger Room inserts:

```kotlin
private fun observeSocketEvents() {
    scope.launch {
        socketConnection.events
            .filterIsInstance<SocketEvent.NewMessage>()
            .filter { isEventForThisChat(it.chatGuid) }
            .collect { event ->
                // Message already in Room (IncomingMessageHandler inserted it)
                // Room Flow will emit automatically - nothing to do here

                // But we DO need to emit for the "new messages" indicator
                _socketNewMessage.tryEmit(event.message.guid)
            }
    }
}
```

### 12. Determining "Has More Messages"

Track whether we've reached the beginning of the conversation by comparing `queryLimit` against the total count in the database. This is deterministic and robust:

```kotlin
private val _hasMoreMessages = MutableStateFlow(true)
val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

fun loadMore() {
    if (isLoadingMore.getAndSet(true)) return

    scope.launch {
        try {
            val totalInDb = messageDao.countMessages(chatGuids)
            val newLimit = queryLimit.value + 50
            queryLimit.value = newLimit

            // If we have enough locally, update flag now
            if (newLimit <= totalInDb) {
                _hasMoreMessages.value = true  // More available locally
                return@launch
            }

            // Need to fetch from server - keep hasMoreMessages=true until fetch completes
            val oldestMsg = messages.value.lastOrNull()
            if (oldestMsg == null) {
                _hasMoreMessages.value = false
                return@launch
            }

            repository.fetchMessagesBefore(oldestMsg.dateCreated)
                .onSuccess { fetchedMessages ->
                    // Only set false if server returned fewer than requested (end of history)
                    _hasMoreMessages.value = fetchedMessages.size >= 50
                }
                .onFailure { error ->
                    _loadError.value = error
                    // Keep hasMoreMessages=true so user can retry
                }
        } finally {
            isLoadingMore.set(false)
        }
    }
}
```

### 13. Archive Mode Transitions

Handle the edge case of new messages arriving while in Archive mode:

```kotlin
// Track unread count while in Archive mode
private val newMessagesSinceArchive = MutableStateFlow(0)

fun observeNewMessagesInArchiveMode() {
    scope.launch {
        combine(viewMode, socketNewMessage.asFlow()) { mode, _ ->
            if (mode is Mode.Archive) {
                newMessagesSinceArchive.update { it + 1 }
            }
        }.collect()
    }
}

// UI shows "X new messages" banner, tap returns to Recent mode
fun returnToRecentMode() {
    viewMode.value = Mode.Recent
    newMessagesSinceArchive.value = 0
}
```

**Important:** When switching from Archive to Recent, the list contents change drastically. LazyColumn won't automatically scroll to index 0. Add explicit scroll handling in the UI:

```kotlin
// In ChatMessageList.kt
val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()

LaunchedEffect(viewMode) {
    if (viewMode is Mode.Recent) {
        // Snap to bottom (index 0) when returning to live view
        listState.scrollToItem(0)
    }
}
```

### 14. Migration Verification Checklist

Before deleting the old system, verify:

- [ ] Scroll up through 500+ messages without position jumps
- [ ] New message arrives while scrolled up → no jump, badge shows "1 new message"
- [ ] Send message while scrolled up → auto-scroll to bottom
- [ ] Jump to search result in history → loads correctly, can scroll both directions
- [ ] Return to Recent from Archive → new messages visible, scrolled to bottom
- [ ] Rotate device mid-scroll → position preserved
- [ ] Background app, receive 10 messages, foreground → all visible
- [ ] Kill app, reopen chat → scroll position restored
- [ ] Merged iMessage/SMS conversation → both sources in correct order
- [ ] Network disconnect during `loadMore()` → error shown, "Tap to retry" works
- [ ] Airplane mode toggle → graceful degradation, local messages still visible

## Implementation Notes

### Index Strategy for Room Queries

The `ORDER BY date_created DESC, guid DESC LIMIT :limit` pattern needs a composite index to stay performant with multi-chat queries.

**Note:** The actual schema uses snake_case column names (`chat_guid`, `date_created`), not camelCase.

**Existing index to modify:**
```kotlin
// Current index in MessageEntity:
Index(value = ["chat_guid", "date_created", "date_deleted"])

// Modify to add guid for tie-breaker (don't create a duplicate overlapping index):
Index(value = ["chat_guid", "date_created", "date_deleted", "guid"])
```

This covers the main query pattern while also supporting soft-delete filtering.

Verify with `EXPLAIN QUERY PLAN` that the query uses the index scan rather than a table scan + sort.

### Cache Eviction Safety

`ChatStateCache` uses LRU eviction. If the cache evicts a chat's state during a long session, `queryLimit` falls back to 50 on next open. Mitigation options:

1. **Increase cache size** for power users with many active chats
2. **Persist queryLimit to DataStore** as a fallback (slower but durable)
3. **Accept the tradeoff** - scrolling up again is fast with cursor pagination

Recommendation: Option 3 is acceptable since re-pagination is now O(1) per page, not O(N) shifts.

### Archive Mode: Window Definition & Deleted Messages

The `observeMessagesAround()` query needs deterministic boundaries:

```kotlin
@Query("""
    SELECT * FROM messages
    WHERE chat_guid IN (:chatGuids)
    AND date_deleted IS NULL
    AND date_created BETWEEN :targetTimestamp - :windowMs AND :targetTimestamp + :windowMs
    ORDER BY date_created DESC, guid DESC
""")
fun observeMessagesAround(
    chatGuids: List<String>,
    targetTimestamp: Long,
    windowMs: Long = 24 * 60 * 60 * 1000  // ±24 hours default
): Flow<List<MessageEntity>>
```

**Deleted message handling:** If the target GUID no longer exists (deleted on server), fall back gracefully:

```kotlin
suspend fun jumpToMessage(targetGuid: String): Boolean {
    val message = messageDao.getByGuid(targetGuid)
    if (message == null) {
        // Message deleted - show toast and stay in Recent mode
        _toastMessage.emit("Message no longer exists")
        return false
    }
    viewMode.value = ChatViewMode.Archive(
        targetGuid = targetGuid,
        targetTimestamp = message.dateCreated,
        windowMs = 12 * 60 * 60 * 1000L  // ±12 hours
    )
    return true
}
```

### Optimistic List Hygiene

Prevent stale optimistic items from accumulating if Room writes fail:

```kotlin
private val optimisticMessages = MutableStateFlow<List<OptimisticMessage>>(emptyList())

data class OptimisticMessage(
    val model: MessageUiModel,
    val insertedAt: Long = System.currentTimeMillis()
)

// Periodic cleanup of stale optimistic items (e.g., >30 seconds old and not in Room)
fun cleanupStaleOptimistic() {
    val cutoff = System.currentTimeMillis() - 30_000
    optimisticMessages.update { list ->
        list.filter { it.insertedAt > cutoff }
    }
}

// Call on every Room emission
val messages = combine(roomMessagesFlow, optimisticMessages) { roomMsgs, optimistic ->
    val roomGuids = roomMsgs.map { it.guid }.toSet()
    // Remove items that made it to Room OR are stale
    val pending = optimistic
        .filterNot { it.model.guid in roomGuids }
        .filter { it.insertedAt > System.currentTimeMillis() - 30_000 }

    // Update the flow to clean up
    if (pending.size < optimistic.size) {
        optimisticMessages.value = pending
    }

    pending.map { it.model } + roomMsgs
}
```

### Error Handling UI Pattern

For `loadMore()` failures, use a **list footer** pattern rather than a blocking banner:

```kotlin
// In LazyColumn
items(messages, key = { it.guid }) { ... }

// Error footer (only shows when error exists AND at end of list)
if (loadError != null) {
    item(key = "load_error") {
        LoadErrorFooter(
            error = loadError,
            onRetry = { viewModel.loadMore() },
            onDismiss = { viewModel.clearLoadError() }
        )
    }
}
```

This keeps scroll interaction unblocked while surfacing the retry option at the natural scroll endpoint.

### Scroll Restoration Timing

Ensure `initialize()` sets `queryLimit` **before** the first Room collection starts:

```kotlin
class ChatMessageListDelegate(...) {

    // queryLimit initialized synchronously in constructor
    private val queryLimit = MutableStateFlow(
        savedStateHandle.get<Int>("queryLimit")
            ?: chatStateCache.get(chatGuid)?.queryLimit
            ?: 50
    )

    init {
        // Adjust for scroll position BEFORE starting collection
        val cachedScrollIndex = chatStateCache.get(chatGuid)?.scrollPosition
        if (cachedScrollIndex != null && cachedScrollIndex > queryLimit.value - 10) {
            queryLimit.value = cachedScrollIndex + 20
        }

        // NOW start collecting - first emission will have correct limit
        startMessageCollection()
    }

    private fun startMessageCollection() {
        scope.launch {
            queryLimit
                .flatMapLatest { limit -> messageDao.observeRecentMessages(chatGuids, limit) }
                .collect { ... }
        }
    }
}
```

The key is that `queryLimit` adjustment is **synchronous** in `init`, before `startMessageCollection()` launches the coroutine.

### Deep Scroll Performance Cliff

**Risk:** If user scrolls back 5,000 messages, every new message triggers a query for all 5,000 items, re-maps them, and diffs them.

**Mitigations:**
1. **MessageCache is vital** - Preserves object identity, prevents churn
2. **Fast toUiModel()** - Keep mapping lightweight, avoid allocations
3. **distinctUntilChanged()** - Skip identical emissions

**Future optimization (if needed):** If `queryLimit > 1000`, switch to a sliding window and detach from live bottom:

```kotlin
sealed class ChatViewMode {
    object Recent : ChatViewMode()
    data class Archive(val targetGuid: String, val targetTimestamp: Long) : ChatViewMode()
    data class DeepHistory(val windowStart: Long, val windowEnd: Long) : ChatViewMode()  // Future
}
```

Start with simple growing limit. Only add windowing if profiling shows bottlenecks.

### Date Separator Determinism

Date separators must be stable and deterministic. Use ISO date format for keys:

```kotlin
fun List<MessageUiModel>.withDateSeparators(): List<ChatListItem> {
    val result = mutableListOf<ChatListItem>()
    var lastDateKey: String? = null

    for (message in this) {
        // Use ISO date (YYYY-MM-DD) for deterministic keys
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(message.dateCreated))

        if (dateKey != lastDateKey) {
            result.add(ChatListItem.DateSeparator(
                date = formatDisplayDate(message.dateCreated),  // "Today", "Yesterday", etc.
                key = "date_$dateKey"  // Stable key
            ))
            lastDateKey = dateKey
        }
        result.add(ChatListItem.Message(message))
    }
    return result
}
```

**Important:** Run this mapping on `Dispatchers.Default`, not main thread.

### Archive Mode Window Bounds

Ensure the `BETWEEN` clause doesn't accidentally include "Now" for old archives:

```kotlin
@Query("""
    SELECT * FROM messages
    WHERE chat_guid IN (:chatGuids)
    AND date_deleted IS NULL
    AND date_created >= :windowStart
    AND date_created <= :windowEnd
    ORDER BY date_created DESC, guid DESC
""")
fun observeMessagesInWindow(
    chatGuids: List<String>,
    windowStart: Long,
    windowEnd: Long
): Flow<List<MessageEntity>>

// When entering Archive mode, the window is defined by targetTimestamp ± windowMs
// The DAO query uses these bounds directly, so new messages (with timestamps > windowEnd) are excluded
fun enterArchiveMode(targetGuid: String, targetTimestamp: Long) {
    viewMode.value = ChatViewMode.Archive(
        targetGuid = targetGuid,
        targetTimestamp = targetTimestamp,
        windowMs = 12 * 60 * 60 * 1000L  // ±12 hours
    )
    // Note: windowEnd is computed in the flatMapLatest as targetTimestamp + windowMs
    // Since windowMs is fixed at entry time, new messages won't appear in Archive mode
}
```

New messages arriving won't appear in Archive mode because their timestamp is outside the fixed window.

### Scroll Restoration Bounds Checking

Always clamp scroll target to actual list size to prevent crashes:

```kotlin
// In ChatMessageList.kt
LaunchedEffect(cachedScrollIndex, messages) {
    if (cachedScrollIndex != null && messages.isNotEmpty()) {
        // Clamp to valid range
        val safeIndex = cachedScrollIndex.coerceIn(0, messages.lastIndex)

        // Only restore if we have enough items
        if (messages.size > safeIndex) {
            listState.scrollToItem(safeIndex, cachedScrollOffset)
        }
    }
}
```

Also handle the case where messages were deleted between sessions:

```kotlin
fun initialize(cachedScrollIndex: Int?, cachedScrollGuid: String?) {
    scope.launch {
        // Wait for first emission
        val firstMessages = messagesFlow.first { it.isNotEmpty() }

        // Try to find the cached message by GUID (more stable than index)
        val targetIndex = if (cachedScrollGuid != null) {
            firstMessages.indexOfFirst { it.guid == cachedScrollGuid }
                .takeIf { it >= 0 }
        } else null

        // Fall back to index, clamped to valid range
        val safeIndex = targetIndex
            ?: cachedScrollIndex?.coerceIn(0, firstMessages.lastIndex)
            ?: 0

        _initialScrollTarget.value = safeIndex
    }
}
```

**Best practice:** Cache scroll position by GUID, not just index. GUIDs are stable across deletions.

## Operational Considerations

### Instrumentation & Testing Plan

**Manual Test Matrix:**

| Scenario | Steps | Expected Result |
|----------|-------|-----------------|
| Basic scroll | Open chat, scroll up 500 msgs | No jumps, smooth pagination |
| New message while scrolled | Scroll up, have friend send msg | Badge appears, no scroll jump |
| Send while scrolled | Scroll up, send message | Auto-scroll to bottom |
| Jump to search | Search "hello", tap result | Jumps to message, can scroll both ways |
| Archive → Recent | Jump to old msg, scroll to bottom | Flips to Recent, new msgs visible |
| Config change | Rotate mid-scroll | Position preserved |
| Process death | Kill app mid-scroll, reopen | Position restored (within tolerance) |
| Merged chat | Open iMessage+SMS merged chat | Both sources interleaved correctly |
| Network failure | Airplane mode, scroll to history | Error footer, local msgs visible |
| Retry | Tap retry after failure | Resumes loading |

**Automated Tests to Add:**

```kotlin
// In ChatMessageListDelegateTest.kt
@Test fun `queryLimit increases on loadMore`()
@Test fun `hasMoreMessages false when server returns empty`()
@Test fun `optimistic message dedupes when Room catches up`()
@Test fun `archive mode excludes new messages`()
@Test fun `scroll position restore clamps to list bounds`()
```

### Telemetry & Debug Hooks

Add temporary logging during rollout (remove after stabilization):

```kotlin
// In ChatMessageListDelegate
private fun logPaginationState(event: String) {
    if (BuildConfig.DEBUG) {
        Timber.tag("CursorPagination").d(
            "$event | limit=${queryLimit.value} | " +
            "hasMore=${hasMoreMessages.value} | " +
            "msgCount=${messages.value.size} | " +
            "mode=${viewMode.value::class.simpleName}"
        )
    }
}

// Call at key points:
// - loadMore() entry/exit
// - viewMode changes
// - hasMoreMessages toggles
// - scroll restoration
```

### Feature Flag / Rollback Lever

Gate the new implementation behind a feature flag for gradual rollout:

```kotlin
// In FeatureFlags.kt
object FeatureFlags {
    val USE_CURSOR_PAGINATION = BuildConfig.DEBUG  // Start with debug only
}

// In ChatViewModel
private val messageListDelegate: ChatMessageListDelegate by lazy {
    if (FeatureFlags.USE_CURSOR_PAGINATION) {
        cursorMessageListDelegateFactory.create(...)  // New
    } else {
        legacyMessageListDelegateFactory.create(...)  // Old (BitSet)
    }
}
```

**Rollback procedure:**
1. Set `USE_CURSOR_PAGINATION = false`
2. Rebuild and deploy
3. Old delegate activates, no data migration needed (Room is shared)

### Schema & Index Audit

After adding the composite index, verify query plan:

```kotlin
// In a test or debug menu
@Test
fun `verify message query uses index`() {
    val db = Room.databaseBuilder(...).build()
    val cursor = db.query(
        SimpleSQLiteQuery(
            "EXPLAIN QUERY PLAN SELECT * FROM messages " +
            "WHERE chat_guid IN ('test') " +
            "AND date_deleted IS NULL " +
            "ORDER BY date_created DESC, guid DESC LIMIT 50"
        )
    )
    cursor.moveToFirst()
    val plan = cursor.getString(3)  // "detail" column

    // Should see "USING INDEX idx_messages_chat_guid_date_created_date_deleted_guid"
    assertThat(plan).contains("USING INDEX")
    assertThat(plan).doesNotContain("SCAN")  // No full table scan
}
```

Run this after migration to confirm the index is being used.

### Concurrent Fetch & Retry Behavior

Define behavior for overlapping/failed fetches:

```kotlin
// State machine for load operations
enum class LoadState {
    IDLE,
    LOADING_LOCAL,   // Expanding queryLimit, waiting for Room
    LOADING_SERVER,  // Fetching from BlueBubbles server
    ERROR            // Last fetch failed, retry available
}

private val loadState = MutableStateFlow(LoadState.IDLE)

fun loadMore() {
    // Prevent concurrent loads
    if (!loadState.compareAndSet(LoadState.IDLE, LoadState.LOADING_LOCAL)) {
        Timber.d("loadMore skipped - already in state ${loadState.value}")
        return
    }

    scope.launch {
        try {
            // ... expand limit, check if server fetch needed ...

            if (needsServerFetch) {
                loadState.value = LoadState.LOADING_SERVER
                repository.fetchMessagesBefore(cursor)
                    .onSuccess {
                        loadState.value = LoadState.IDLE
                        _hasMoreMessages.value = it.size >= 50
                    }
                    .onFailure { error ->
                        loadState.value = LoadState.ERROR
                        _loadError.value = error
                        // hasMoreMessages stays true for retry
                    }
            } else {
                loadState.value = LoadState.IDLE
            }
        } catch (e: Exception) {
            loadState.value = LoadState.ERROR
            _loadError.value = e.toAppError()
        }
    }
}

fun retryLoad() {
    if (loadState.value == LoadState.ERROR) {
        _loadError.value = null
        loadState.value = LoadState.IDLE
        loadMore()
    }
}
```

**Retry policy:** Simple manual retry (tap footer). No automatic retry or exponential backoff - keeps behavior predictable and avoids battery drain.

## Benefits

| Feature | Old Architecture | New Architecture |
| :--- | :--- | :--- |
| **Source of Truth** | In-Memory Sparse Map | Room Database |
| **New Message Handling** | O(N) Shift + Rebuild | O(1) Insert + Auto-Emit |
| **Scroll Stability** | Manual Index Calculation | Framework (Key-Based Diffing) |
| **Code Complexity** | High (Custom Paging Logic) | Low (Standard Android Patterns) |
| **Race Conditions** | Frequent (Index vs Data) | None (Transactional DB) |
| **Memory Footprint** | Mixed (evictions/manual diffs) | Grows with `queryLimit`; mitigate by resetting limit or windowing when deep in history |
