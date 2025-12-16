# Bubble UI

## Purpose

Android bubble (floating chat) implementation. Allows users to continue conversations in floating windows.

## Files

| File | Description |
|------|-------------|
| `BubbleActivity.kt` | Activity launched when bubble is expanded |
| `BubbleChatScreen.kt` | Compose UI for bubble chat |
| `BubbleChatViewModel.kt` | ViewModel for bubble conversations |

## Architecture

```
Bubble Flow:

Notification with Bubble → User taps bubble
                        → BubbleActivity launches
                        → BubbleChatScreen displayed
                        → User can send/receive messages
```

## Required Patterns

### Bubble Activity

```kotlin
class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID)
            ?: return finish()

        setContent {
            BothBubblesTheme {
                BubbleChatScreen(chatGuid = chatGuid)
            }
        }
    }
}
```

### Bubble-Specific ViewModel

```kotlin
@HiltViewModel
class BubbleChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository
) : ViewModel() {
    // Simplified version of ChatViewModel for bubble context
    // Focused on sending/receiving only
}
```

## Best Practices

1. Keep bubble UI minimal (limited space)
2. Handle configuration changes gracefully
3. Sync state with main app
4. Test bubble launch from notifications
5. Handle bubble collapse/expand lifecycle
