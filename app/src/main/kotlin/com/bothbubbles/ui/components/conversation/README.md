# Conversation Components

## Purpose

Components for the conversation list screen including tiles, badges, and swipe actions.

## Files

| File | Description |
|------|-------------|
| `ConversationBadges.kt` | Unread count, muted, pinned badges |
| `ConversationSwipeComponents.kt` | Swipe action backgrounds |
| `PinnedConversationTile.kt` | Pinned conversation variant |
| `SwipeActionConfig.kt` | Swipe action configuration |
| `SwipeGestureHandler.kt` | Swipe gesture detection |
| `SwipeableConversationTile.kt` | Conversation tile with swipe |

## Architecture

```
SwipeableConversationTile
├── SwipeGestureHandler
│   ├── Left swipe → Archive/Delete
│   └── Right swipe → Pin/Mute
├── SwipeActionBackground
│   └── Action icon and color
└── ConversationTile
    ├── Avatar/GroupAvatar
    ├── Title and preview
    ├── Timestamp
    └── Badges (unread, muted, pinned)
```

## Required Patterns

### Swipeable Tile

```kotlin
@Composable
fun SwipeableConversationTile(
    conversation: ConversationUi,
    leftAction: SwipeAction,
    rightAction: SwipeAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberDismissState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                DismissValue.DismissedToEnd -> leftAction.onAction()
                DismissValue.DismissedToStart -> rightAction.onAction()
                else -> false
            }
            false // Reset position
        }
    )

    SwipeToDismiss(
        state = dismissState,
        background = {
            SwipeActionBackground(dismissState, leftAction, rightAction)
        },
        dismissContent = {
            ConversationTile(conversation, onClick)
        }
    )
}
```

### Badges

```kotlin
@Composable
fun ConversationBadges(
    unreadCount: Int,
    isMuted: Boolean,
    isPinned: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (isPinned) {
            Icon(Icons.Default.PushPin, "Pinned", modifier = Modifier.size(12.dp))
        }
        if (isMuted) {
            Icon(Icons.Default.NotificationsOff, "Muted", modifier = Modifier.size(12.dp))
        }
        if (unreadCount > 0) {
            Badge { Text(unreadCount.toString()) }
        }
    }
}
```

## Best Practices

1. Support customizable swipe actions
2. Show haptic feedback on swipe threshold
3. Animate badge changes
4. Handle very long preview text
5. Support accessibility for all actions
