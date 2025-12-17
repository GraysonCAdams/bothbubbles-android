# Pin Drag/Drop Reordering Technical Analysis

This document explains the current implementation of pinned conversation drag/drop reordering and identifies potential issues affecting its behavior.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ConversationsScreen                                │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                    ConversationMainContent                               ││
│  │  ┌───────────────────────────────────────────────────────────────────┐  ││
│  │  │                    ConversationsList                               │  ││
│  │  │  ┌─────────────────────────────────────────────────────────────┐  │  ││
│  │  │  │              PinnedConversationsRow (LazyRow)               │  │  ││
│  │  │  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           │  │  ││
│  │  │  │  │ Item 0  │ │ Item 1  │ │ Item 2  │ │ Item 3  │           │  │  ││
│  │  │  │  │(pinned) │ │(pinned) │ │(pinned) │ │(pinned) │           │  │  ││
│  │  │  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘           │  │  ││
│  │  │  └─────────────────────────────────────────────────────────────┘  │  ││
│  │  │                                                                    │  ││
│  │  │  ┌─────────────────────────────────────────────────────────────┐  │  ││
│  │  │  │           PinnedDragOverlay (floats above all)              │  │  ││
│  │  │  └─────────────────────────────────────────────────────────────┘  │  ││
│  │  └───────────────────────────────────────────────────────────────────┘  ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
```

## Key Files

| File | Purpose |
|------|---------|
| [PinnedConversations.kt](../app/src/main/kotlin/com/bothbubbles/ui/conversations/PinnedConversations.kt) | Drag gesture handling, visual feedback, reorder logic |
| [ConversationsList.kt](../app/src/main/kotlin/com/bothbubbles/ui/conversations/ConversationsList.kt) | Container that passes callbacks to PinnedConversationsRow |
| [ConversationMainContent.kt](../app/src/main/kotlin/com/bothbubbles/ui/conversations/components/ConversationMainContent.kt) | Filters pinned conversations, manages drag overlay state |
| [ConversationActionsDelegate.kt](../app/src/main/kotlin/com/bothbubbles/ui/conversations/delegates/ConversationActionsDelegate.kt) | Persists reorder to database, emits optimistic UI updates |
| [ConversationsViewModel.kt](../app/src/main/kotlin/com/bothbubbles/ui/conversations/ConversationsViewModel.kt) | Public API (`reorderPins()`), connects delegate to UI |

## Data Flow

### 1. Initial State
```
ConversationsUiState.conversations (sorted by isPinned desc, pinIndex asc, timestamp desc)
        │
        ▼
ConversationMainContent filters: { it.isPinned }
        │
        ▼
pinnedConversations list (preserves sorted order)
        │
        ▼
PinnedConversationsRow receives sorted list
        │
        ▼
currentOrder = conversations.map { it.guid }  // Local mutable state
```

### 2. Drag Gesture Flow
```
User long-presses item
        │
        ▼
detectDragGesturesAfterLongPress triggers onDragStart
        │
        ▼
Sets: isDragging=true, draggedItemIndex, draggedItemGuid
        │
        ▼
Notifies overlay: onDragOverlayStart(conversation, position)
        │
        ▼
User drags horizontally
        │
        ▼
onDrag callback accumulates dragOffsetX, dragOffsetY
        │
        ▼
Calculates swap position: offsetInItems = (dragOffsetX / itemWidthPx).toInt()
        │
        ▼
If newPosition != draggedPosition:
  - Swaps items in currentOrder
  - Updates draggedItemIndex
  - Adjusts dragOffsetX to compensate for swap
        │
        ▼
User releases
        │
        ▼
onDragEnd checks:
  - If dragOffsetY >= unpinThresholdPx: calls onUnpin(guid)
  - Else if order changed: calls onReorder(currentOrder)
        │
        ▼
Resets all drag state
```

### 3. Persistence Flow
```
onReorder(currentOrder: List<String>)
        │
        ▼
ConversationsViewModel.reorderPins(reorderedGuids)
        │
        ▼
ConversationActionsDelegate.reorderPins()
        │
        ├──► Optimistic UI Update:
        │      - Maps conversations with new pinIndex values
        │      - Sorts by isPinned/pinIndex/timestamp
        │      - Emits ConversationEvent.ConversationsUpdated
        │
        └──► Database Update (background):
               - For each guid in reorderedGuids:
                   - chatRepository.setPinned(guid, true, index)
                   - unifiedChatGroupRepository.updatePinStatus(groupId, true, index)
```

## Drag State Variables

| Variable | Type | Purpose |
|----------|------|---------|
| `draggedItemIndex` | `Int` | Current index of item being dragged (-1 when not dragging) |
| `draggedItemGuid` | `String?` | GUID of dragged item |
| `dragOffsetX` | `Float` | Horizontal drag distance from start |
| `dragOffsetY` | `Float` | Vertical drag distance from start |
| `isDragging` | `Boolean` | Whether a drag is in progress |
| `currentOrder` | `List<String>` | Current order of GUIDs (mutated during drag) |
| `itemPositions` | `Map<String, Offset>` | Root positions of each item for overlay |

## Visual Feedback

### During Drag
- **Scale**: `1.08f - (unpinProgress * 0.15f)` (scales up, reduces near unpin threshold)
- **Alpha**: `1f - (unpinProgress * 0.5f)` (fades when approaching unpin)
- **Elevation**: `8.dp` shadow
- **Original item**: `alpha = 0f` (invisible, overlay renders instead)

### Drag-to-Unpin
- **Threshold**: `60.dp` downward drag
- **Progress**: `dragOffsetY / unpinThresholdPx` (0 to 1)
- When released past threshold, calls `onUnpin(guid)` instead of `onReorder()`

## Identified Issues

### Issue 1: State Reset Race Condition

**Location**: [PinnedConversations.kt:97-102](../app/src/main/kotlin/com/bothbubbles/ui/conversations/PinnedConversations.kt#L97-L102)

```kotlin
var currentOrder by remember(conversations) { mutableStateOf(conversations.map { it.guid }) }

LaunchedEffect(conversations) {
    currentOrder = conversations.map { it.guid }
}
```

**Problem**: The `remember(conversations)` key and `LaunchedEffect(conversations)` both trigger when the conversations list changes. After a reorder:
1. User completes drag, `onReorder(currentOrder)` is called
2. Delegate emits optimistic UI update
3. `conversations` parameter changes, triggering both `remember` recreation and `LaunchedEffect`
4. `currentOrder` is reset to the new (correctly ordered) list
5. **However**, if any Flow from the database emits slightly different data (e.g., stale data from a slow query), the visual order can briefly jump around

**Symptom**: Pins may visually "snap back" briefly or appear to not reorder correctly.

### Issue 2: No Sorting After Filter

**Location**: [ConversationMainContent.kt:162](../app/src/main/kotlin/com/bothbubbles/ui/conversations/components/ConversationMainContent.kt#L162)

```kotlin
val pinnedConversations = filteredConversations.filter { it.isPinned }
```

**Context**: The filter operation preserves order from `filteredConversations`, which comes from `conversations` in the UI state. The conversations ARE sorted in [ConversationMappers.kt:267-269](../app/src/main/kotlin/com/bothbubbles/ui/conversations/ConversationMappers.kt#L267-L269):

```kotlin
.sortedWith(
    compareByDescending<ConversationUiModel> { it.isPinned }
        .thenBy { it.pinIndex }
        .thenByDescending { it.lastMessageTimestamp }
)
```

**However**, this relies on the entire pipeline maintaining sorted order. If any intermediate step introduces disorder (e.g., optimistic update followed by database refresh), the pins could appear out of order until the next full refresh.

### Issue 3: Swap Calculation Assumes Fixed Item Width

**Location**: [PinnedConversations.kt:188-191](../app/src/main/kotlin/com/bothbubbles/ui/conversations/PinnedConversations.kt#L188-L191)

```kotlin
val offsetInItems = (dragOffsetX / itemWidthPx).toInt()
val newPosition = (draggedPosition + offsetInItems).coerceIn(0, currentOrder.size - 1)
```

**Problem**:
- `itemWidthPx` is calculated as `112.dp` (100dp item + 12dp spacing)
- The swap only triggers when `newPosition != draggedPosition`
- Integer truncation means small drags don't register until crossing the 50% point of an item width
- With the offset adjustment after swap (`dragOffsetX -= offsetInItems * itemWidthPx`), there can be visual discontinuities

**Symptom**: Reordering may feel "sticky" or require dragging farther than expected.

### Issue 4: LazyRow Scroll State Not Preserved

**Location**: [PinnedConversations.kt:111](../app/src/main/kotlin/com/bothbubbles/ui/conversations/PinnedConversations.kt#L111)

```kotlin
userScrollEnabled = !isDragging
```

**Problem**: While scroll is disabled during drag (good), there's no mechanism to preserve or restore scroll position after the `currentOrder` list is rebuilt. If the user had scrolled horizontally to see pins 4-6, after a reorder the scroll position may reset.

### Issue 5: Optimistic Update vs Database Flow Conflict

**Location**: [ConversationActionsDelegate.kt:198-224](../app/src/main/kotlin/com/bothbubbles/ui/conversations/delegates/ConversationActionsDelegate.kt#L198-L224)

```kotlin
fun reorderPins(reorderedGuids: List<String>, conversations: List<ConversationUiModel>) {
    scope.launch {
        // Optimistic update
        val updated = conversations.map { ... }
        _events.emit(ConversationEvent.ConversationsUpdated(updated))

        // Database update (sequential, could be slow)
        reorderedGuids.forEachIndexed { index, guid ->
            chatRepository.setPinned(guid, true, index)
            val group = unifiedChatGroupRepository.getGroupForChat(guid)
            if (group != null) {
                unifiedChatGroupRepository.updatePinStatus(group.id, true, index)
            }
        }
    }
}
```

**Problem**:
1. The database updates happen sequentially (one by one)
2. Each `setPinned` call may trigger a Room Flow emission
3. These emissions could cause intermediate states where some pins have updated indices and others don't
4. The UI may show flickering or incorrect ordering during this window

**Symptom**: During reorder of 3+ pins, the order may briefly appear incorrect.

### Issue 6: No Debouncing for Rapid Reorders

**Problem**: If the user quickly drags and drops multiple times in succession, each `onReorder()` call triggers the full persistence flow. There's no debouncing to coalesce rapid reorders.

**Symptom**: Performance issues or state inconsistencies with very fast drag operations.

## Database Schema

### Chats Table
```sql
is_pinned INTEGER NOT NULL DEFAULT 0
pin_index INTEGER DEFAULT NULL
```

### UnifiedChatGroups Table
```sql
is_pinned INTEGER NOT NULL DEFAULT 0
pin_index INTEGER DEFAULT NULL
```

**Note**: Both tables are updated during reorder to maintain consistency for merged conversations (iMessage + SMS to same contact).

## Recommendations

1. **Add explicit sorting after filter**: Ensure `pinnedConversations` is always sorted by `pinIndex`
2. **Use stable list comparison**: Instead of `remember(conversations)`, compare GUIDs to detect actual changes
3. **Batch database updates**: Use a transaction to update all pin indices atomically
4. **Add debouncing**: Debounce rapid reorder calls (e.g., 300ms)
5. **Improve swap threshold**: Use center-point distance instead of integer truncation for smoother reordering
6. **Preserve scroll state**: Remember scroll position across reorders
