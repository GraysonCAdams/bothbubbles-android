# Chat State Models

## Purpose

State models for various aspects of the chat screen. Used by delegates to expose state to the UI.

## Files

| File | Description |
|------|-------------|
| `ChatConnectionState.kt` | Connection status state |
| `ChatInfoState.kt` | Chat metadata state |
| `EffectsState.kt` | Message effects playback state |
| `OperationsState.kt` | Chat operations state |
| `ScheduledMessagesState.kt` | Scheduled messages state |
| `SearchState.kt` | In-chat search state |
| `SendState.kt` | Message sending state |
| `SyncState.kt` | Sync progress state |
| `ThreadState.kt` | Thread/reply overlay state |

## Required Patterns

### State Class Definition

```kotlin
data class SendState(
    val isSending: Boolean = false,
    val pendingCount: Int = 0,
    val lastError: String? = null,
    val replyTo: Message? = null,
    val selectedEffect: MessageEffect? = null
)

data class SearchState(
    val isSearching: Boolean = false,
    val query: String = "",
    val results: List<Message> = emptyList(),
    val currentIndex: Int = 0,
    val totalCount: Int = 0
)

data class EffectsState(
    val activeEffect: MessageEffect? = null,
    val targetMessageGuid: String? = null,
    val isPlaying: Boolean = false
)
```

### State Updates

```kotlin
// In delegate
private val _sendState = MutableStateFlow(SendState())
val sendState: StateFlow<SendState> = _sendState.asStateFlow()

fun startSending() {
    _sendState.update { it.copy(isSending = true) }
}

fun finishSending(error: String? = null) {
    _sendState.update { it.copy(isSending = false, lastError = error) }
}
```

### State Observation

```kotlin
// In Composable
val sendState by viewModel.sendState.collectAsStateWithLifecycle()

if (sendState.isSending) {
    CircularProgressIndicator()
}

sendState.lastError?.let { error ->
    ErrorSnackbar(error)
}
```

## Best Practices

1. Use data classes for immutability
2. Provide sensible defaults
3. Keep state classes focused
4. Use `update { }` for atomic updates
5. Expose as `StateFlow` (not MutableStateFlow)
