# Chat Screen

## Purpose

Main chat/conversation screen. The most complex screen in the app with message display, composition, search, effects, and many features.

## Files

| File | Description |
|------|-------------|
| `CallMethodDropdown.kt` | Dropdown for selecting call method |
| `ChatAudioHelper.kt` | Voice memo recording/playback state management |
| `ChatOverflowMenu.kt` | Overflow menu actions |
| `ChatScreen.kt` | Main chat screen composable |
| `ChatScreenDialogs.kt` | Dialog composables used in chat |
| `ChatScreenUtils.kt` | Utility functions for chat screen |
| `ChatScrollHelper.kt` | Scroll-related effects and state |
| `ChatStateCache.kt` | Cache for chat state |
| `ChatTopBar.kt` | Top app bar for chat |
| `ChatUiState.kt` | UI state models |
| `ChatViewModel.kt` | Main ViewModel orchestrating delegates |
| `MessageCache.kt` | Message caching utilities |
| `MessageTransformationUtils.kt` | Transform messages for display |
| `SendModeToggleState.kt` | iMessage/SMS toggle state |

## Architecture

```
ChatViewModel Delegate Pattern:

ChatViewModel (orchestrator)
├── ChatSendDelegate         - Send, retry, forward
├── ChatSearchDelegate       - In-chat search
├── ChatOperationsDelegate   - Archive, star, delete
├── ChatEffectsDelegate      - Message effects
├── ChatThreadDelegate       - Thread overlay
├── ChatSyncDelegate         - Adaptive polling, resume sync
├── ChatSendModeManager      - iMessage/SMS mode switching
├── ChatAttachmentDelegate   - Attachment handling
├── ChatComposerDelegate     - Composer state
├── ChatMessageListDelegate  - Message list state
├── ChatScheduledMessageDelegate - Scheduled messages
├── ChatEtaSharingDelegate   - ETA sharing
├── ChatConnectionDelegate   - Connection state
└── ChatInfoDelegate         - Chat info state
```

## Required Patterns

### ViewModel Initialization

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendDelegate: ChatSendDelegate,
    private val searchDelegate: ChatSearchDelegate,
    // ... other delegates
) : ViewModel() {

    private val chatGuid: String = savedStateHandle.get<String>("chatGuid")!!

    init {
        // Initialize all delegates with context
        sendDelegate.initialize(chatGuid, viewModelScope)
        searchDelegate.initialize(chatGuid, viewModelScope)
        // ...
    }
}
```

### Screen Composition

```kotlin
@Composable
fun ChatScreen(
    chatGuid: String,
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToDetails: () -> Unit
) {
    // Collect state from delegates
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()

    // Effects overlay
    ScreenEffectOverlay(effectsState = viewModel.effectsState)

    ChatScreenContent(
        messages = messages,
        sendState = sendState,
        onSendMessage = viewModel::sendMessage,
        // ...
    )
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `components/` | Chat-specific components |
| `composer/` | Message composer |
| `delegates/` | ViewModel delegates |
| `details/` | Conversation details screen |
| `paging/` | Message pagination |
| `state/` | State models |

## Best Practices

1. Use delegates to decompose large ViewModel
2. Keep screen composable thin, delegate to content
3. Use LazyColumn for message list (performance)
4. Cache message transformations
5. Handle keyboard/IME properly
6. Use helper classes to extract complex state logic (see ChatAudioHelper, ChatScrollHelper)

## Helper Classes

### ChatScrollHelper

Encapsulates scroll-related side effects:
- `ChatScrollEffects()` - Combined keyboard hiding, load more, scroll position tracking
- `ScrollToSafetyEffect()` - Ensures selected message is visible for tapback menu
- `rememberIsScrolledAwayFromBottom()` - Derived state for jump-to-bottom indicator

### ChatAudioHelper

Encapsulates voice memo recording/playback:
- `rememberChatAudioState()` - State holder with MediaRecorder/MediaPlayer lifecycle
- `ChatAudioEffects()` - Recording duration timer and playback position tracker
- Methods: `startRecording()`, `stopRecording()`, `togglePlayback()`, etc.
