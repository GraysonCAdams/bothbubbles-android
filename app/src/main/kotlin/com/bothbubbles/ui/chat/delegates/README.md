# Chat ViewModel Delegates

## Purpose

Delegate classes that decompose ChatViewModel into focused, testable units. Each delegate handles a specific domain of functionality.

## Files

| File | Description |
|------|-------------|
| `ChatAttachmentDelegate.kt` | Attachment selection, preview, and management |
| `ChatComposerDelegate.kt` | Composer text, reply state, effects |
| `ChatConnectionDelegate.kt` | Connection status and reconnection |
| `ChatEffectsDelegate.kt` | Message effect playback |
| `ChatEtaSharingDelegate.kt` | ETA sharing feature |
| `ChatInfoDelegate.kt` | Chat metadata and participants |
| `ChatMessageListDelegate.kt` | Message list loading and display |
| `ChatOperationsDelegate.kt` | Archive, star, delete, spam, block |
| `ChatScheduledMessageDelegate.kt` | Scheduled messages |
| `ChatSearchDelegate.kt` | In-chat message search |
| `ChatSendDelegate.kt` | Send, retry, forward operations |
| `ChatSendModeManager.kt` | iMessage/SMS mode switching |
| `ChatSyncDelegate.kt` | Adaptive polling and resume sync |
| `ChatThreadDelegate.kt` | Thread/reply overlay |

## Architecture

```
Delegate Pattern:

ChatViewModel
├── Initialize delegates with context
├── Expose delegate state via composition
└── Forward method calls to delegates

Each Delegate:
├── @Inject constructor (dependencies)
├── initialize(chatGuid, scope) method
├── StateFlow properties for state
└── Methods for operations
```

## Required Patterns

### Delegate Structure

```kotlin
class ChatSendDelegate @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private var chatGuid: String = ""
    private var scope: CoroutineScope? = null

    // State
    private val _sendState = MutableStateFlow(SendState())
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    // Initialize with context from ViewModel
    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid
        this.scope = scope
    }

    // Operations
    fun sendMessage(text: String, attachments: List<PendingAttachmentInput>) {
        scope?.launch(ioDispatcher) {
            _sendState.update { it.copy(isSending = true) }
            val result = messageSender.sendMessage(chatGuid, text, attachments)
            _sendState.update { it.copy(isSending = false) }
        }
    }
}
```

### ViewModel Integration

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendDelegate: ChatSendDelegate,
    private val searchDelegate: ChatSearchDelegate
) : ViewModel() {

    // Expose delegate state
    val sendState = sendDelegate.sendState
    val searchState = searchDelegate.searchState

    init {
        sendDelegate.initialize(chatGuid, viewModelScope)
        searchDelegate.initialize(chatGuid, viewModelScope)
    }

    // Forward method calls
    fun sendMessage(text: String) = sendDelegate.sendMessage(text, emptyList())
    fun search(query: String) = searchDelegate.search(query)
}
```

## Best Practices

1. Keep delegates focused on single responsibility
2. Use constructor injection for dependencies
3. Initialize with context from ViewModel
4. Expose state via StateFlow
5. Handle cleanup if needed
6. Unit test delegates independently

## Composable State Collection Pattern

Following the push-down state architecture from `docs/COMPOSE_BEST_PRACTICES.md`, delegates are passed directly to composables which collect their own state internally:

```kotlin
// ChatScreen passes delegates, not collected values
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    ChatMessageList(
        searchDelegate = viewModel.searchDelegate,
        syncDelegate = viewModel.syncDelegate
    )
}

// Child composable collects state internally
@Composable
fun ChatMessageList(
    searchDelegate: ChatSearchDelegate,
    syncDelegate: ChatSyncDelegate
) {
    // PERF FIX: Collect searchState internally to avoid ChatScreen recomposition
    val searchState by searchDelegate.state.collectAsStateWithLifecycle()
    val syncState by syncDelegate.state.collectAsStateWithLifecycle()

    // Use the state...
}
```

### Delegate-to-Composable Mapping

| Delegate | Primary Composable Consumer |
|----------|---------------------------|
| `ChatSearchDelegate` | `ChatMessageList`, `ChatTopBar` |
| `ChatSyncDelegate` | `ChatMessageList` |
| `ChatOperationsDelegate` | `ChatMessageList`, `ChatTopBar` |
| `ChatAttachmentDelegate` | `ChatMessageList`, `MessageBubble` |
| `ChatSendDelegate` | `ChatInputUI` |
| `ChatComposerDelegate` | `ChatInputUI` |
| `ChatEffectsDelegate` | `ChatMessageList` |
| `ChatConnectionDelegate` | `ChatDialogsHost` |
| `ChatThreadDelegate` | `AnimatedThreadOverlay` |
| `ChatEtaSharingDelegate` | `ChatMessageList` |

This pattern prevents "God Composables" where a parent collects many StateFlows just to pass values down, causing full-tree recomposition on minor state changes.
