
# Multi-Message Selection Feature Implementation

## Summary

Implemented a complete multi-message selection system for the chat view that allows users to select one or more messages and perform bulk operations (copy, delete, forward).

### Entry Points
- **Long-press** on a message opens the tapback/reactions menu → tap "Select" to enter selection mode
- **Overflow menu** → "Select messages" to enter selection mode (selects newest message)
- In selection mode, **tapping messages** toggles their selection

### Visual Feedback
- Selected messages have a highlighted background (15% primary container color)
- Animated checkbox appears on the left of each message row
- Selection header replaces the normal chat header, showing selected count

### Available Actions
1. **Copy** - Combines selected messages into clipboard (sorted by date, sender names only when different senders). Shows toast if only attachments selected.
2. **Share** - Opens Android system share sheet with formatted message text
3. **Forward** - Opens forward dialog to send selected messages to other chats (sorted by date)
4. **Delete** - Soft-deletes selected messages locally with confirmation dialog
5. **Close** (X button) - Exits selection mode
6. **Select All** (overflow) - Selects all messages in the chat

---

## Affected Files

### State Management

| File | Changes |
|------|---------|
| `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScreenState.kt` | Added `selectedMessageGuids`, `isMessageSelectionMode`, `showDeleteMessagesDialog`, `messagesToForward`, and methods: `enterMessageSelectionMode()`, `toggleMessageSelection()`, `clearMessageSelection()`, `selectAllMessages()` |

### UI Components - Message Bubbles

| File | Changes |
|------|---------|
| `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageBubble.kt` | Added `isSelectionMode`, `isSelected`, `onSelectionToggle` parameters; passes to child components |
| `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageSimpleBubble.kt` | Added selection checkbox with `AnimatedVisibility`, selection background highlight with `animateColorAsState`, wrapped content in Row for checkbox layout |
| `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageSegmentedBubble.kt` | Same selection UI as MessageSimpleBubble; also updated `TextBubbleSegment` to accept selection parameters |

### UI Components - Tapback Menu

| File | Changes |
|------|---------|
| `app/src/main/kotlin/com/bothbubbles/ui/components/message/focus/FocusMenuCard.kt` | Added "Select" action button with `Icons.Outlined.CheckBox` and `onSelect` callback |
| `app/src/main/kotlin/com/bothbubbles/ui/components/message/focus/MessageFocusOverlay.kt` | Added `onSelect` parameter, wires to FocusMenuCard, dismisses overlay after selection |
| `app/src/main/kotlin/com/bothbubbles/ui/chat/components/MessageListOverlays.kt` | Added `onEnterSelectionMode` to `MessageListOverlayCallbacks`, wires "Select" action to enter selection mode |

### UI Components - Message List

| File | Changes |
|------|---------|
| `app/src/main/kotlin/com/bothbubbles/ui/chat/components/MessageListItem.kt` | Modified long-press behavior: in selection mode toggles selection, otherwise opens tapback menu; passes selection state to MessageBubble |
| `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatMessageList.kt` | Added `onEnterSelectionMode` callback wiring to `chatScreenState::enterMessageSelectionMode`; uses `derivedStateOf` for efficient per-item selection check |

### UI Components - Selection Header

| File | Changes |
|------|---------|
| `app/src/main/kotlin/com/bothbubbles/ui/chat/components/MessageSelectionHeader.kt` | **NEW FILE** - Selection mode header with close button, selected count, and action buttons (Copy, Share, Forward, Delete, Select All) |

### Screen-Level Integration

| File | Changes |
|------|---------|
| `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatOverflowMenu.kt` | Added `SELECT_MESSAGES` action to enum and menu for entry point discoverability |
| `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatScreen.kt` | Added `AnimatedContent` for header swap; `BackHandler` for exit; delete dialog; `formatSelectedMessagesForCopy()` helper; Share action with system intent; overflow menu handler; forward sorting by date |
| `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatDialogsHost.kt` | Added `messagesToForward`, `onClearMessagesToForward`, `onClearMessageSelection` parameters for multi-message forward support |

### Data Layer

| File | Changes |
|------|---------|
| `app/src/main/kotlin/com/bothbubbles/data/local/db/dao/MessageDao.kt` | Added `softDeleteMessages(guids: List<String>, timestamp: Long)` batch delete query |
| `app/src/main/kotlin/com/bothbubbles/data/repository/MessageRepository.kt` | Added `softDeleteMessages(guids: List<String>)` method with tombstone recording to prevent resurrection during sync |

### Delegates

| File | Changes |
|------|---------|
| `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatOperationsDelegate.kt` | Added `MessageRepository` dependency; added `deleteMessages(guids: List<String>)` method for batch deletion |
| `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/ChatSendDelegate.kt` | Added `forwardMessages(messageGuids: List<String>, targetChatGuid: String)` method for multi-message forwarding |

---

## Key Implementation Details

### Efficient Selection State
```kotlin
// In ChatMessageList.kt - uses derivedStateOf to prevent full LazyColumn recomposition
val isSelected by remember(message.guid) {
    derivedStateOf { message.guid in chatScreenState.selectedMessageGuids }
}
```

### Selection Visual Feedback
```kotlin
// In MessageSimpleBubble.kt / MessageSegmentedBubble.kt
val selectionBackgroundColor by animateColorAsState(
    targetValue = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
    } else {
        Color.Transparent
    },
    label = "selectionBackground"
)

// Checkbox with animated visibility
AnimatedVisibility(
    visible = isSelectionMode,
    enter = expandHorizontally() + fadeIn(),
    exit = shrinkHorizontally() + fadeOut()
) {
    Checkbox(
        checked = isSelected,
        onCheckedChange = { onSelectionToggle?.invoke() },
        // ...
    )
}
```

### Copy Formatting
```kotlin
// In ChatScreen.kt
private fun formatSelectedMessagesForCopy(
    selectedGuids: Set<String>,
    messages: List<MessageUiModel>
): String {
    val selectedMessages = messages
        .filter { it.guid in selectedGuids }
        .sortedBy { it.dateCreated }

    // Only show sender names when messages are from different senders
    val senderIdentities = selectedMessages.map { message ->
        if (message.isFromMe) "Me" else (message.senderName ?: "Unknown")
    }
    val allFromSameSender = senderIdentities.distinct().size == 1

    return selectedMessages.mapIndexed { index, message ->
        val senderPrefix = if (!allFromSameSender) {
            "${senderIdentities[index]}: "
        } else { "" }
        senderPrefix + (message.text ?: "[Attachment]")
    }.joinToString("\n")
}
```

### Batch Delete with Tombstones
```kotlin
// In MessageRepository.kt
suspend fun softDeleteMessages(guids: List<String>) {
    if (guids.isEmpty()) return
    // Record tombstones to prevent resurrection during sync
    guids.forEach { guid ->
        tombstoneDao.recordDeletedMessage(guid)
    }
    messageDao.softDeleteMessages(guids)
}

// In MessageDao.kt
@Query("UPDATE messages SET date_deleted = :timestamp WHERE guid IN (:guids)")
suspend fun softDeleteMessages(guids: List<String>, timestamp: Long = System.currentTimeMillis())
```

---

## Testing Checklist

### Entry Points
- [ ] Long-press message → tapback menu opens with "Select" option
- [ ] Tap "Select" → enters selection mode with message selected
- [ ] Overflow menu → "Select messages" enters selection mode
- [ ] In selection mode, tap messages → toggles selection
- [ ] In selection mode, long-press → toggles selection (not tapback)

### Header & Actions
- [ ] Header shows correct selected count
- [ ] "Select All" (in overflow) selects all messages
- [ ] "Copy" copies formatted text to clipboard
- [ ] "Copy" with only attachments shows "no text to copy" toast
- [ ] "Share" opens Android share sheet with formatted text
- [ ] "Share" with only attachments shows "no text to share" toast
- [ ] "Forward" opens forward dialog with messages sorted by date
- [ ] "Delete" shows confirmation dialog, then soft-deletes

### Navigation & Exit
- [ ] Back press exits selection mode
- [ ] X button exits selection mode
- [ ] Checkbox animation is smooth
- [ ] Selection highlight is visible but subtle

---

## Edge Cases Handled

- **Empty text messages**: Show "[Attachment]" placeholder in copy
- **Attachment-only messages**: Included in copy with placeholder, can be deleted
- **Selection during scroll**: Selection state persists (uses derivedStateOf)
- **Back press**: Clears selection, exits selection mode
- **Leave chat**: Selection automatically cleared (state resets with screen)
- **Tapback menu interaction**: Dismissed and clears selection state when entering selection mode

---

## Future Improvements (V2)

### Drag-to-Select
Allow users to drag their finger down the checkboxes to quickly select a range of messages (similar to Google Photos or Telegram).

**Implementation notes:**
- Requires custom gesture handling with `pointerInput` and `detectDragGestures`
- Track vertical drag position and map to LazyColumn visible items
- Challenge: LazyColumn items are virtualized, so need to track positions of visible items
- Consider using `LazyListState.layoutInfo.visibleItemsInfo` to map drag Y to message index
- Haptic feedback on each new selection

**Reference patterns:**
- Telegram: Drag from checkbox area selects range
- Google Photos: Long-press + drag selects multiple items

### Other V2 Ideas
- ~~**System Share Action**: Android share sheet for mixed media/text~~ ✅ Implemented
- ~~**Overflow menu entry**: "Select Messages" for discoverability~~ ✅ Implemented
- **Copy with timestamps**: Optional detailed format with timestamps
- **Bulk retry**: Retry multiple failed messages at once
