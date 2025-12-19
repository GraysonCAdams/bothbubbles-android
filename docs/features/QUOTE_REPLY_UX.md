# Quote/Reply UX Implementation

This document describes the technical implementation of the enhanced Quote/Reply UX features in BothBubbles.

## Overview

The Quote/Reply system provides a rich inline experience for viewing and interacting with quoted messages in conversation threads. Key features include:

- **Tap-to-scroll**: Tap a quote to scroll to the original message
- **Long-press for thread**: Long-press opens the full thread overlay
- **Attachment thumbnails**: Visual preview of quoted images/videos
- **Inline expansion**: Double-tap to expand truncated quotes
- **Nested depth indicator**: Visual cue for reply-to-reply chains
- **Full accessibility**: Screen reader support with semantic actions

## Architecture

### Data Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CursorChatMessageListDelegate                     │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ buildReplyPreviewMap()                                       │    │
│  │   - Loads original messages for reply GUIDs                  │    │
│  │   - Fetches attachment thumbnails                            │    │
│  │   - Calculates quote depth                                   │    │
│  │   - Returns Map<String, ReplyPreviewData>                    │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         MessageUiModel                               │
│  - replyPreview: ReplyPreviewData?                                  │
│  - threadOriginatorGuid: String?                                    │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          MessageBubble                               │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ ReplyQuoteIndicator                                          │    │
│  │   - Renders quote preview above message                      │    │
│  │   - Handles tap/double-tap/long-press                        │    │
│  │   - Shows thumbnail and depth indicators                     │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Data Models

#### ReplyPreviewData

Located in `ui/components/message/MessageModels.kt`:

```kotlin
@Immutable
data class ReplyPreviewData(
    val originalGuid: String,         // GUID of quoted message
    val previewText: String?,         // Truncated to ~50 chars
    val senderName: String?,          // Display name or null
    val isFromMe: Boolean,            // Whether quoted msg is from user
    val hasAttachment: Boolean,       // Has any attachments
    val thumbnailUri: String?,        // Local path for image/video preview
    val quoteDepth: Int = 1,          // 1 = direct reply, 2+ = nested
    val isNotLoaded: Boolean = false  // Original not in recent messages
)
```

#### MessagePreview (Composer)

Located in `ui/chat/composer/ComposerState.kt`:

```kotlin
@Immutable
data class MessagePreview(
    val guid: String,
    val isFromMe: Boolean,
    val senderName: String?,
    val text: String?,
    val hasAttachments: Boolean = false,
    val thumbnailUri: String? = null
)
```

## Feature Implementation

### 1. Scroll-to-Original on Tap

**Callback Chain:**
```
ReplyQuoteIndicator.onTap
    → MessageBubble.onScrollToOriginal
    → MessageListItem.callbacks.onScrollToOriginal
    → MessageListCallbacks.onScrollToOriginal
    → ChatScreen (implementation)
```

**Implementation in ChatScreen.kt:**
```kotlin
onScrollToOriginal = { originGuid ->
    val index = currentMessages.indexOfFirst { it.guid == originGuid }
    if (index >= 0) {
        viewModel.highlightMessage(originGuid)
        currentState.coroutineScope.launch {
            val viewportHeight = currentState.listState.layoutInfo.viewportSize.height
            val centerOffset = -(viewportHeight / 3)
            currentState.listState.animateScrollToItem(index, scrollOffset = centerOffset)
        }
    } else {
        // Fallback: open thread overlay if not in view
        viewModel.thread.loadThread(originGuid)
    }
}
```

The scroll positions the original message roughly 1/3 from the top of the viewport and triggers the existing highlight animation.

### 2. Attachment Thumbnails

**Loading in CursorChatMessageListDelegate:**
```kotlin
// Load attachments for original messages that have them
val originGuidsWithAttachments = allMessagesMap.values
    .filter { it.hasAttachments }
    .map { it.guid }

val attachmentsByMessage = attachmentRepository
    .getAttachmentsForMessages(originGuidsWithAttachments)
    .groupBy { it.messageGuid }

// Extract thumbnail from first visual attachment
val thumbnailUri = attachments
    .firstOrNull {
        it.mimeType?.startsWith("image/") == true ||
        it.mimeType?.startsWith("video/") == true
    }
    ?.let { it.thumbnailPath ?: it.localPath }
```

**Display:**
- ReplyPreviewBar (composer): 40x40dp thumbnail on left
- ReplyQuoteIndicator (message): 48x48dp thumbnail on right, max width 240dp (matches bubble width)

Both use Coil's `AsyncImage` with `ContentScale.Crop` and rounded corners.

### 3. Inline Quote Expansion

**State Management:**
```kotlin
var isExpanded by remember { mutableStateOf(false) }
```

**Gesture Handling:**
```kotlin
.combinedClickable(
    onClick = { onTap() },           // Scroll to original
    onDoubleClick = {
        isExpanded = !isExpanded     // Toggle expansion
    },
    onLongClick = { onLongPress() }  // Open thread
)
```

**Visual Changes When Expanded:**
- `animateContentSize()` for smooth transition
- `maxLines` changes from 1 to `Int.MAX_VALUE`
- Accent bar height increases from 32dp to 48dp
- Row alignment changes from `CenterVertically` to `Top`

### 4. Nested Quote Depth Indicator

**Depth Calculation:**
```kotlin
val quoteDepth = if (originalMessage.threadOriginatorGuid != null) 2 else 1
```

**Visual Representation:**
```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    repeat(replyPreview.quoteDepth.coerceIn(1, 3)) { index ->
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (isExpanded) 48.dp else 32.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(
                    if (index == 0) accentColor
                    else accentColor.copy(alpha = 0.5f)
                )
        )
    }
}
```

Shows 1-3 vertical bars, with secondary bars at 50% opacity.

### 5. Accessibility

**ReplyPreviewBar:**
```kotlin
val contentDescription = buildString {
    append("Replying to $senderDescription")
    message.text?.let { append(": $it") }
    if (message.hasAttachments) append(". Contains attachment")
}

Surface(
    modifier = Modifier
        .semantics { this.contentDescription = contentDescription }
)
```

**ReplyQuoteIndicator:**
```kotlin
.semantics {
    contentDescription = "Quote from $sender: $text. Tap to scroll, double-tap to expand, hold for thread"
    onClick(label = "Scroll to original message") { onTap(); true }
    onLongClick(label = "Open thread view") { onLongPress(); true }
}
```

## Gesture Summary

| Gesture | Action | Fallback |
|---------|--------|----------|
| Tap | Scroll to original message | Opens thread if not loaded |
| Double-tap | Expand/collapse quote | - |
| Long-press | Open thread overlay | - |
| Swipe (on message) | Reply to message | - |

## Files Modified

| File | Changes |
|------|---------|
| `MessageModels.kt` | Added `thumbnailUri`, `quoteDepth` to `ReplyPreviewData` |
| `ComposerState.kt` | Added `thumbnailUri` to `MessagePreview` |
| `MessageBubble.kt` | Rewrote `ReplyQuoteIndicator` with all new features |
| `ReplyPreviewBar.kt` | Added thumbnail display and accessibility |
| `MessageListItem.kt` | Added `onScrollToOriginal` callback |
| `ChatMessageList.kt` | Added `onScrollToOriginal` to callbacks |
| `ChatScreen.kt` | Implemented scroll-to-original logic |
| `CursorChatMessageListDelegate.kt` | Load thumbnails and calculate depth |
| `ThreadOverlay.kt` | Added `onScrollToOriginal = null` parameter |

## Performance Considerations

1. **Thumbnail Loading**: Attachments for reply originals are batch-loaded in `buildReplyPreviewMap()` using a single SQL query:
   ```kotlin
   // AttachmentDao.kt
   @Query("SELECT * FROM attachments WHERE message_guid IN (:messageGuids)")
   suspend fun getAttachmentsForMessages(messageGuids: List<String>): List<AttachmentEntity>
   ```
   Room compiles this to `SELECT ... WHERE message_guid IN (?, ?, ...)`, avoiding N+1 query issues regardless of how many replies exist in the conversation.

2. **State Isolation**: The `isExpanded` state is local to each `ReplyQuoteIndicator` instance, preventing unnecessary recompositions.

3. **Lazy Evaluation**: Quote depth is calculated only when building the preview map, not on every render.

4. **Animation**: Uses `animateContentSize()` which is optimized for Compose and doesn't trigger layout thrashing.

## Future Enhancements

1. **Full Text Loading**: Currently expansion shows the truncated preview text (50 chars). Could fetch full text from database on expansion.

2. **Deeper Nesting**: Currently caps at depth 3 for visual clarity. Could support arbitrary depth with scrolling indicator.

3. **Quote Search**: Allow searching within quoted content.

4. **Multi-message Quoting**: Server support required (Phase 8 in original plan, deferred).
