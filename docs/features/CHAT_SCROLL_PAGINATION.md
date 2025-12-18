# Chat Message Scrolling & Pagination System

This document explains how chat message scrolling and pagination works in BothBubbles, and identifies potential causes of scroll position issues.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ChatScreen.kt                                      │
│  - Owns LazyListState                                                        │
│  - Passes scroll state to ChatScrollEffects                                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      ChatScrollHelper.kt                                     │
│  - KeyboardHideOnScroll: Hides keyboard after ~250dp scroll                 │
│  - LoadMoreOnScroll: Triggers load at lastVisibleItem >= totalItems - 25    │
│  - ScrollPositionTracker: Reports position changes to delegate              │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ChatMessageListDelegate.kt                                │
│  - Owns MessagePagingController                                              │
│  - Bridges sparse messages → sequential list via toList()                    │
│  - Handles scroll position tracking & caching                                │
│  - Triggers server sync for older messages                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                   MessagePagingController.kt                                 │
│  - Signal-style BitSet pagination                                            │
│  - Sparse data storage (position → MessageUiModel)                           │
│  - Position shifting when new messages arrive                                │
│  - Generation system to invalidate stale loads                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    RoomMessageDataSource.kt                                  │
│  - Queries Room database by position offset                                  │
│  - Uses MessageCache for object identity preservation                        │
│  - Triggers server sync via SyncTrigger when gaps detected                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Key Concepts

### 1. Reverse Layout (reverseLayout = true)

The LazyColumn uses `reverseLayout = true`, meaning:
- **Position 0 = newest message** (at the visual bottom)
- **Higher positions = older messages** (at the visual top)
- When scrolling UP visually, you're moving to HIGHER indices (older messages)
- When a new message arrives, it goes to position 0 and ALL existing positions shift by +1

```
Visual Layout:         Index Mapping:
┌──────────────┐
│ [Oldest]     │  ← index N (highest)
│    ...       │
│ [Old]        │  ← index 5
│ [Recent]     │  ← index 2
│ [Newer]      │  ← index 1
│ [Newest]     │  ← index 0 (always newest)
└──────────────┘
```

### 2. Signal-Style BitSet Pagination

Unlike traditional pagination (load page 1, then page 2...), this uses sparse loading:

```kotlin
// BitSet tracks which positions are loaded
loadStatus = BitSet()           // true = loaded, false = not loaded
sparseData = Map<Int, Message>  // Only loaded positions have entries
guidToPosition = Map<GUID, Int> // Fast lookup from GUID to position
```

**Benefits:**
- Can jump to any position without loading everything in between
- Efficient for search results and deep links
- Only loads data for visible + prefetch range

**Drawbacks:**
- Position shifting is complex (O(N) operation)
- Race conditions possible between shifts and loads
- Sparse-to-sequential conversion can lose position information

### 3. Position Shifting on New Messages

When a new message arrives at position 0, ALL existing positions must shift:

```
BEFORE:  [A, B, C, D, E]  at positions [0, 1, 2, 3, 4]
         ↓ New message arrives ↓
AFTER:   [NEW, A, B, C, D, E]  at positions [0, 1, 2, 3, 4, 5]
         All positions shifted by +1
```

This is implemented in `shiftPositions()` (MessagePagingController.kt:534-587):

```kotlin
private suspend fun shiftPositions(shiftBy: Int) {
    // Step 1: Snapshot current data under mutex (fast)
    // Step 2: Build new data structures on background thread (O(N))
    // Step 3: Atomic swap under mutex (fast)
}
```

### 4. Generation System

To handle race conditions between position shifts and data loads, a generation number is used:

```kotlin
state.generation: Long  // Incremented on each shift

// When loading data:
val loadGeneration = state.generation  // Capture at start
// ... async database query ...
if (state.generation != loadGeneration) {
    // Discard stale data - positions no longer valid
    return
}
```

### 5. Load Trigger Threshold

Pagination is triggered when scrolling near the "top" (older messages):

```kotlin
// ChatScrollHelper.kt:137-150
val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
val shouldLoadMore = lastVisibleItem >= totalItems - 25 && totalItems > 0
```

With `reverseLayout=true`, this means: "load more when the last visible item index is within 25 items of the total count."

---

## Data Flow: User Scrolls Up

1. **User scrolls up** (towards older messages, higher indices)

2. **ScrollPositionTracker** reports position change via snapshotFlow:
   ```kotlin
   // ChatScrollHelper.kt:161-174
   snapshotFlow { ... }.collect { (index, offset, lastVisibleIndex) ->
       onScrollPositionChanged(index, offset, lastVisibleIndex - index + 1)
   }
   ```

3. **ChatMessageListDelegate** receives position change:
   ```kotlin
   // ChatMessageListDelegate.kt:256-258
   fun onScrollPositionChanged(firstVisibleIndex: Int, lastVisibleIndex: Int) {
       pagingController.onDataNeededAroundIndex(firstVisibleIndex, lastVisibleIndex)
   }
   ```

4. **MessagePagingController** debounces and loads:
   ```kotlin
   // MessagePagingController.kt:137-144
   fun onDataNeededAroundIndex(firstVisibleIndex: Int, lastVisibleIndex: Int) {
       scrollDebounceJob?.cancel()
       scrollDebounceJob = scope.launch {
           delay(config.scrollDebounceMs)  // Default: 50ms
           loadAroundRange(firstVisibleIndex, lastVisibleIndex)
       }
   }
   ```

5. **loadAroundRange** calculates gaps and loads them:
   ```kotlin
   // MessagePagingController.kt:589-616
   val loadStart = maxOf(0, firstVisible - config.prefetchDistance)  // Default: 20
   val loadEnd = minOf(lastVisible + config.prefetchDistance, state.totalSize)
   val gaps = findGaps(loadStart, loadEnd, loadStatus)
   gaps.forEach { gap -> loadRange(gap.first, gap.last + 1) }
   ```

6. **LoadMoreOnScroll** may trigger server sync:
   ```kotlin
   // ChatScrollHelper.kt:141-148
   if (shouldLoadMore && canLoadMore && !isLoadingMore) {
       onLoadMore()  // → ChatMessageListDelegate.loadMoreMessages()
   }
   ```

---

## Data Flow: New Message Arrives

1. **Socket event** received by `ChatMessageListDelegate.observeNewMessages()`

2. **Notify paging controller**:
   ```kotlin
   // ChatMessageListDelegate.kt:578
   pagingController.onNewMessageInserted(event.message.guid)
   ```

3. **Size observer** detects change via Room Flow:
   ```kotlin
   // MessagePagingController.kt:106-110
   dataSource.observeSize().collect { newSize ->
       onSizeChanged(newSize)
   }
   ```

4. **onSizeChanged** triggers position shift:
   ```kotlin
   // MessagePagingController.kt:496-510
   if (newSize > oldSize) {
       val addedCount = newSize - oldSize
       shiftPositions(addedCount)  // O(N) operation
       loadRange(0, minOf(addedCount + prefetchDistance, newSize))
   }
   ```

5. **shiftPositions** rebuilds all data structures:
   - Snapshot data under mutex
   - Build new maps on background thread
   - Atomic swap under mutex

6. **emitMessagesLocked** emits new SparseMessageList

7. **ChatMessageListDelegate** bridges to UI:
   ```kotlin
   // ChatMessageListDelegate.kt:524-527
   pagingController.messages
       .map { sparseList -> sparseList.toList() }  // ← CRITICAL CONVERSION
       .conflate()
       .collect { ... }
   ```

---

## Known Problem Areas

### Problem 1: Position Shift Race Condition

**Symptom:** Scroll position jumps when new messages arrive while scrolling

**Root Cause:**
When the user scrolls up (viewing older messages), `onDataNeededAroundIndex()` is called with position X. Meanwhile, a new message arrives and triggers `shiftPositions()`. The position X is now X+1, but the pending load request still uses X.

**Location:**
- [MessagePagingController.kt:534-587](app/src/main/kotlin/com/bothbubbles/ui/chat/paging/MessagePagingController.kt#L534-L587) - shiftPositions
- [MessagePagingController.kt:642-713](app/src/main/kotlin/com/bothbubbles/ui/chat/paging/MessagePagingController.kt#L642-L713) - loadRange

**Timeline:**
```
T0: User at scroll position 50 (viewing messages at positions 50-60)
T1: User scrolls → onDataNeededAroundIndex(70, 80)
T2: Debounce delay (50ms)
T3: loadRange(50, 100) starts, captures generation=5
T4: New message arrives → shiftPositions(1) → generation=6
T5: loadRange completes, but generation changed → data discarded
T6: User's visible range now shows stale/missing data
```

### Problem 2: Sparse-to-List Conversion Ordering

**Symptom:** Same messages appear multiple times, or messages appear out of order

**Root Cause:**
`SparseMessageList.toList()` sorts by position key:

```kotlin
// MessagePagingState.kt:44-58
fun toList(): List<MessageUiModel> {
    return loadedData.entries
        .sortedBy { it.key }  // Sort by position
        .mapNotNull { ... }
}
```

If `sparseData` has entries at both position 5 (from before shift) and position 6 (same message after shift), both appear in the sorted list. The deduplication by GUID catches this, but the position-to-list-index mapping can still be off.

**Location:**
- [MessagePagingState.kt:44-58](app/src/main/kotlin/com/bothbubbles/ui/chat/paging/MessagePagingState.kt#L44-L58)

### Problem 3: LoadMoreOnScroll Threshold Instability

**Symptom:** Load triggers multiple times, or triggers at wrong scroll position

**Root Cause:**
The load-more trigger uses `totalItems` which changes as data loads:

```kotlin
// ChatScrollHelper.kt:137-141
val totalItems = layoutInfo.totalItemsCount
val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
lastVisibleItem >= totalItems - 25 && totalItems > 0
```

During a shift:
1. `totalItems` increases
2. Load-more threshold shifts
3. Can trigger duplicate loads or miss the trigger entirely

**Location:**
- [ChatScrollHelper.kt:121-150](app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScrollHelper.kt#L121-L150)

### Problem 4: GUID Conflict During Shift

**Symptom:** Duplicate messages visible briefly

**Root Cause:**
If a message exists at position 5 (pre-shift) and the same GUID gets loaded at position 6 (post-shift) before cleanup runs, both entries exist temporarily in `sparseData`.

The defensive code at [MessagePagingController.kt:683-690](app/src/main/kotlin/com/bothbubbles/ui/chat/paging/MessagePagingController.kt#L683-L690) tries to catch this:

```kotlin
val existingPosition = guidToPosition[model.guid]
if (existingPosition != null && existingPosition != position) {
    sparseData.remove(existingPosition)
    loadStatus.clear(existingPosition)
}
```

But timing windows exist where both can be visible.

### Problem 5: Scroll Position Restoration Too Early

**Symptom:** Jumping to wrong position when re-opening chat

**Root Cause:**
Cached scroll position is restored before paging controller has loaded data at that position:

```kotlin
// ChatMessageListDelegate.kt:229-235
val cachedState = chatStateCache.get(chatGuid)
if (cachedState != null) {
    lastScrollPosition = cachedState.scrollPosition
    _cachedScrollPosition.value = Pair(...)
}
// ... pagingController.initialize() called later
```

If the cached position is 100 but only positions 0-50 are initially loaded, scroll restoration points to empty data.

---

## Configuration Constants

| Constant | Value | Location | Description |
|----------|-------|----------|-------------|
| `scrollDebounceMs` | 50ms | PagingConfig.kt | Debounce scroll events |
| `prefetchDistance` | 20 | PagingConfig.kt | Load N items before/after visible |
| `initialLoadSize` | 50 | PagingConfig.kt | First batch size |
| `pageSize` | 30 | PagingConfig.kt | Subsequent batch sizes |
| `disableEviction` | true | PagingConfig.kt | Never evict loaded messages |
| Load-more threshold | 25 | ChatScrollHelper.kt:141 | Trigger at `totalItems - 25` |
| `POLL_INTERVAL_MS` | 2000ms | ChatMessageListDelegate.kt | Adaptive polling interval |
| `scrollSaveDebounceMs` | 1000ms | ChatMessageListDelegate.kt | Debounce scroll saves |

---

## Potential Fixes (Investigation Needed)

### Fix 1: Position-Stable Keys

Instead of using raw positions that shift, use stable message GUIDs as the primary key throughout:

```kotlin
// Instead of: sparseData[position] = message
// Use: messages stored by GUID, separate position index
```

### Fix 2: Snapshot Scroll Position Before Shift

Capture the GUID of the first visible message before shifting, then restore scroll to that GUID after shift:

```kotlin
val visibleGuid = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
// ... shift ...
val newPosition = guidToPosition[visibleGuid]
listState.scrollToItem(newPosition ?: 0)
```

### Fix 3: Mutex-Protected Scroll State

Extend the mutex to cover scroll position reads/writes to prevent racing with shifts:

```kotlin
stateMutex.withLock {
    // Read current scroll position
    // Apply shift
    // Compute new scroll position
    // Emit together
}
```

### Fix 4: Debounce Emissions During Shift

Suppress UI updates during active shift operations to prevent showing intermediate states:

```kotlin
private val isShifting = MutableStateFlow(false)
// In bridging code:
if (!isShifting.value) {
    _messagesState.value = messages
}
```

### Fix 5: Use LazyColumn Key-Based Scroll Restoration

Leverage LazyColumn's ability to scroll to items by key (GUID) rather than index:

```kotlin
LazyColumn(
    state = rememberLazyListState(
        initialFirstVisibleItemKey = targetGuid
    )
)
```

---

## Debugging

Enable scroll debug logs in debug builds:

```kotlin
// ChatMessageList.kt:66-70
private inline fun scrollDebugLog(message: () -> String) {
    if (BuildConfig.DEBUG) {
        Timber.tag(SCROLL_DEBUG_TAG).d(message())
    }
}
```

Look for these log tags:
- `ChatScroll` - Scroll position changes, load-more triggers
- `[SEND_TRACE]` - Message insertion and emission timing
- `DEDUP` - Duplicate GUID detection
- `GUID CONFLICT` - Same GUID at different positions

---

## Files Reference

| File | Purpose |
|------|---------|
| [ChatMessageList.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/ChatMessageList.kt) | LazyColumn rendering, auto-scroll effects |
| [ChatScrollHelper.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScrollHelper.kt) | Scroll effects (keyboard hide, load-more, position tracking) |
| [ChatMessageListDelegate.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatMessageListDelegate.kt) | Message list state, server sync, scroll caching |
| [MessagePagingController.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/paging/MessagePagingController.kt) | BitSet pagination, position shifting, data loading |
| [MessagePagingState.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/paging/MessagePagingState.kt) | SparseMessageList, toList() conversion |
| [RoomMessageDataSource.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/paging/RoomMessageDataSource.kt) | Database queries, MessageCache integration |
| [PagingConfig.kt](app/src/main/kotlin/com/bothbubbles/ui/chat/paging/PagingConfig.kt) | Configuration constants |
