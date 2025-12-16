# Message Components

## Purpose

Components for displaying messages including bubbles, reactions, delivery indicators, and tapback menus.

## Files

| File | Description |
|------|-------------|
| `ChatIndicators.kt` | Typing indicators, read receipts |
| `MessageActionMenu.kt` | Long-press action menu |
| `MessageBubble.kt` | Main message bubble component |
| `MessageBubblePreviews.kt` | Preview functions for bubbles |
| `MessageDeliveryIndicators.kt` | Sent, delivered, read indicators |
| `MessageModels.kt` | UI models for messages |
| `MessagePlaceholder.kt` | Loading placeholder |
| `MessageSegment.kt` | Segment within a message |
| `MessageSegmentedBubble.kt` | Multi-segment bubble |
| `MessageSimpleBubble.kt` | Simple text-only bubble |
| `MessageSpotlightOverlay.kt` | Highlight a specific message |
| `MessageSwipeContainer.kt` | Swipe-to-reply container |
| `MessageSwipeGestures.kt` | Swipe gesture handling |
| `MessageTransformations.kt` | Transform message data for display |
| `ReactionPill.kt` | Reaction/tapback display |
| `TapbackCard.kt` | Tapback selection card |
| `TapbackMenu.kt` | Tapback selection menu |
| `TapbackOverlay.kt` | Full-screen tapback overlay |
| `TapbackPopup.kt` | Tapback popup variant |
| `ThreadOverlay.kt` | Thread/reply view overlay |

## Architecture

```
Message Display:

MessageBubble
├── MessageSimpleBubble (text only)
│   └── Text with annotations
└── MessageSegmentedBubble (multi-part)
    ├── MessageSegment (text)
    ├── AttachmentContent
    └── LinkPreview

├── ReactionPill
│   └── Tapback reactions
├── MessageDeliveryIndicators
│   └── Sent/Delivered/Read
└── MessageSwipeContainer
    └── Swipe-to-reply
```

## Required Patterns

### Message Bubble

```kotlin
@Composable
fun MessageBubble(
    message: MessageUi,
    isFromMe: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onReactionTap: () -> Unit,
    onSwipeToReply: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleColor = if (isFromMe) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val alignment = if (isFromMe) Alignment.End else Alignment.Start

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.align(alignment),
            horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = bubbleColor,
                shape = getBubbleShape(isFromMe),
                modifier = Modifier.combinedClickable(
                    onClick = onTap,
                    onLongClick = onLongPress
                )
            ) {
                // Message content
            }

            // Reactions
            if (message.reactions.isNotEmpty()) {
                ReactionPill(message.reactions, onClick = onReactionTap)
            }

            // Delivery indicator
            if (isFromMe) {
                MessageDeliveryIndicators(message.deliveryState)
            }
        }
    }
}
```

### Tapback Menu

```kotlin
@Composable
fun TapbackMenu(
    onTapback: (Tapback) -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Tapback.entries.forEach { tapback ->
            IconButton(onClick = { onTapback(tapback) }) {
                Text(tapback.emoji, fontSize = 24.sp)
            }
        }
    }
}
```

## Best Practices

1. Handle different message types (text, attachment, link)
2. Support accessibility for all interactions
3. Animate reactions and delivery state changes
4. Use gesture detection for swipe-to-reply
5. Show context menu on long press
