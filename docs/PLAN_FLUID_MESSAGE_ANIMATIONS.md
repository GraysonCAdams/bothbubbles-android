# Plan: Fluid Message Animations & Performance Improvements

## Problem Analysis

Users are experiencing UI hangs when sending or receiving messages, and the arrival of new messages lacks fluid animation (items "snap" into place).

### 1. UI Hangs (Main Thread Blocking)

The "hang" is caused by heavy data transformation occurring on the Main thread.

- **Current Flow:**
  1.  `MessagePagingController` runs on `viewModelScope` (Main Thread).
  2.  It calls `RoomMessageDataSource.load()`.
  3.  `RoomMessageDataSource.load()` calls `messageRepository.getMessagesByPosition()` (Room DAO). This correctly suspends and moves to `Dispatchers.IO`.
  4.  **CRITICAL ISSUE:** When the Room call returns, execution resumes on the Main thread.
  5.  The subsequent steps in `load()` are performed on the Main thread:
      - `attachmentRepository.getAttachmentsForMessages` (DB call, suspends to IO, returns to Main)
      - `buildReplyPreviewMap` (Iterates messages, potentially fetches more from DB)
      - `handleRepository.getHandlesByIds` (DB call)
      - `messageCache.updateAndBuild` (Iterates all loaded messages)
      - `entity.toUiModel` (Heavy object mapping, date formatting, text processing)

For a batch of messages (even 20-50), this transformation logic is too heavy for the Main thread, causing frame drops ("hangs").

### 2. Lack of Fluid Animation

The `LazyColumn` in `ChatScreen.kt` does not use `Modifier.animateItemPlacement()`.

- When a new message arrives, it is inserted at index 0.
- All existing items shift index by +1.
- Without `animateItemPlacement`, Compose instantly redraws items at their new positions, resulting in a "snap" effect.

---

## Proposed Solution

### 1. Fix Threading (Eliminate Hangs)

We must ensure that the entire `load` operation in `RoomMessageDataSource` runs on a background dispatcher.

**Changes in `RoomMessageDataSource.kt`:**
Wrap the body of `load()` and `loadByKey()` in `withContext(Dispatchers.IO)`.

```kotlin
override suspend fun load(start: Int, count: Int): List<MessageUiModel> = withContext(Dispatchers.IO) {
    // ... existing logic ...
    // All DB calls and transformations now run on IO thread
    return@withContext transformToUiModels(dedupedEntities)
}
```

### 2. Implement Fluid Animations

We will use Jetpack Compose's `animateItemPlacement` modifier to animate message insertion and reordering.

**Changes in `ChatScreen.kt`:**
Apply the modifier to the `MessageItem` wrapper or the item content.

```kotlin
itemsIndexed(
    items = uiState.messages,
    key = { _, message -> message.guid }
) { index, message ->
    Box(
        modifier = Modifier.animateItemPlacement(
            animationSpec = tween(durationMillis = 300)
        )
    ) {
        MessageItem(...)
    }
}
```

### 3. Conditional Animation (New vs. Old)

The user requested that _synced_ messages (historical) load instantly, while _new_ messages animate in.

- `MessagePagingController` already exposes `initialLoadComplete` StateFlow.
- We can pass this to `ChatScreen`.
- However, `animateItemPlacement` is a modifier. Changing modifiers can be expensive (recomposition).
- A better approach is to always have the modifier, but rely on the fact that `animateItemPlacement` only animates _changes_.
  - **Initial Load:** The list is populated from empty -> full. Items "appear". We can customize the `enter` transition if we use `AnimatedVisibility`, but `animateItemPlacement` handles reordering.
  - **Pagination (Loading Older):** Items are appended to the end. Existing items don't move (relative to viewport, if handled correctly).
  - **New Message:** Item inserted at 0. Existing items move down. `animateItemPlacement` will smooth this shift.

**Refinement:**
If we want the _new_ message to slide in, and others to slide down:

- `animateItemPlacement` handles the "others slide down".
- For the "new message slides in", we can use `AnimatedVisibility` or simply let `animateItemPlacement` handle the appearance if it supports it (it mainly handles placement changes).
- For a true "slide in" of the new message, we might need `Modifier.animateContentSize` on the list or specific enter transitions. But `animateItemPlacement` is the most important fix for the "snap".

**Constraint:**
We need to ensure `animateItemPlacement` doesn't run during the initial load if it causes a "fly in" effect for 50 messages.
Usually, `animateItemPlacement` doesn't animate the _initial_ appearance of the list, only subsequent changes. So it should be safe to add permanently.

---

## Implementation Steps

1.  **Modify `RoomMessageDataSource.kt`**:

    - Inject `CoroutineDispatcher` (default to `Dispatchers.IO`).
    - Wrap `load` and `loadByKey` in `withContext(dispatcher)`.

2.  **Modify `ChatScreen.kt`**:

    - Add `Modifier.animateItemPlacement()` to the item container in `LazyColumn`.

3.  **Verify `MessagePagingController`**:
    - Ensure `shiftPositions` is fast enough. (It runs on `Default` dispatcher via `scope.launch`? No, it runs on `viewModelScope` which is Main).
    - **Optimization:** `shiftPositions` iterates the map. We should wrap this in `withContext(Dispatchers.Default)` inside the `launch` block to avoid blocking Main if the map is huge.

## Detailed Code Changes

### `RoomMessageDataSource.kt`

```kotlin
// Add dispatcher to constructor
class RoomMessageDataSource(
    // ...
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : MessageDataSource {

    override suspend fun load(start: Int, count: Int): List<MessageUiModel> = withContext(dispatcher) {
        // Existing logic
    }

    override suspend fun loadByKey(guid: String): MessageUiModel? = withContext(dispatcher) {
        // Existing logic
    }
}
```

### `MessagePagingController.kt`

```kotlin
private fun onSizeChanged(newSize: Int) {
    // ...
    scope.launch {
        // Move heavy shifting logic off main thread
        withContext(Dispatchers.Default) {
            when {
                newSize > oldSize -> {
                    val addedCount = newSize - oldSize
                    shiftPositions(addedCount)
                    // ...
                }
                // ...
            }
        }
    }
}
```

### `ChatScreen.kt`

```kotlin
itemsIndexed(
    items = uiState.messages,
    key = { _, message -> message.guid }
) { index, message ->
    // ...
    MessageItem(
        modifier = Modifier.animateItemPlacement(), // Add this
        message = message,
        // ...
    )
}
```
