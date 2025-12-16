# Preview Utilities

## Purpose

Compose preview utilities for development including sample data and preview wrappers.

## Files

| File | Description |
|------|-------------|
| `PreviewData.kt` | Sample data for Compose previews |
| `PreviewWrapper.kt` | Theme wrapper for previews |

## Required Patterns

### Preview Data

```kotlin
object PreviewData {
    val sampleMessage = MessageUi(
        guid = "preview-1",
        text = "Hello, this is a sample message!",
        date = System.currentTimeMillis(),
        isFromMe = true,
        deliveryState = DeliveryState.DELIVERED
    )

    val sampleChat = ChatUi(
        guid = "chat-preview",
        displayName = "John Doe",
        lastMessage = sampleMessage,
        unreadCount = 2
    )

    val sampleConversations = listOf(
        sampleChat,
        sampleChat.copy(guid = "chat-2", displayName = "Jane Smith"),
        sampleChat.copy(guid = "chat-3", displayName = "Group Chat")
    )
}
```

### Preview Wrapper

```kotlin
@Composable
fun PreviewWrapper(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    BothBubblesTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
```

### Usage in Previews

```kotlin
@Preview(name = "Light")
@Preview(name = "Dark", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun MessageBubblePreview() {
    PreviewWrapper {
        MessageBubble(
            message = PreviewData.sampleMessage,
            isFromMe = true,
            onTap = {},
            onLongPress = {}
        )
    }
}
```

## Best Practices

1. Provide realistic sample data
2. Include both light and dark previews
3. Use preview wrapper for consistent theming
4. Create previews for different states
5. Keep preview data centralized
