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

**MANDATORY**: Follow `docs/COMPOSE_BEST_PRACTICES.md` and the push-down state architecture.

### Core Principle

> "Push Down State, Pull Data Locally."

`ChatScreen` acts as a **Coordinator** that passes stable delegate references to children. Child composables collect their own state internally to prevent parent recomposition.

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

### Screen Composition (Push-Down Pattern)

```kotlin
@Composable
fun ChatScreen(
    chatGuid: String,
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToDetails: () -> Unit
) {
    // ChatScreen passes DELEGATES, not collected state
    ChatMessageList(
        searchDelegate = viewModel.searchDelegate,
        syncDelegate = viewModel.syncDelegate,
        attachmentDelegate = viewModel.attachmentDelegate,
        // ...
    )

    ChatInputUI(
        sendDelegate = viewModel.sendDelegate,
        composerDelegate = viewModel.composer,
        // ...
    )
}

@Composable
fun ChatMessageList(
    searchDelegate: ChatSearchDelegate,
    syncDelegate: ChatSyncDelegate,
    attachmentDelegate: ChatAttachmentDelegate
) {
    // PERF FIX: Collect state internally to avoid ChatScreen recomposition
    val searchState by searchDelegate.state.collectAsStateWithLifecycle()
    val syncState by syncDelegate.state.collectAsStateWithLifecycle()
    // Each message bubble collects its own download progress
}
```

### State Consolidation in ChatScreenState

Effect and animation tracking sets are consolidated in `ChatScreenState` (not created in composition):

```kotlin
// IN ChatScreenState.kt - single source of truth
val processedEffectMessages = mutableSetOf<String>()
val animatedMessageGuids = mutableSetOf<String>()
var revealedInvisibleInkMessages by mutableStateOf(setOf<String>())

// Helper methods
fun markEffectProcessed(guid: String) = processedEffectMessages.add(guid)
fun isEffectProcessed(guid: String) = guid in processedEffectMessages
```

**NEVER create these sets in composition body** - use `ChatScreenState` instead.

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

1. **Push-down state**: Pass delegates to children, let them collect their own state
2. Use delegates to decompose large ViewModel
3. Use `collectAsStateWithLifecycle()` (not `collectAsState()`)
4. Add `// PERF FIX:` comments when adding internal state collection
5. Use `rememberUpdatedState` for values read inside `LaunchedEffect(Unit)`
6. Use `derivedStateOf` for computed values dependent on collected state
7. Guard all logging with `if (BuildConfig.DEBUG)`
8. Use LazyColumn for message list (performance)
9. Cache message transformations
10. Handle keyboard/IME properly
11. Use helper classes to extract complex state logic (see ChatAudioHelper, ChatScrollHelper)

### Stale Capture Prevention

```kotlin
// CORRECT - Use rememberUpdatedState for LaunchedEffect(Unit)
val currentMessages by rememberUpdatedState(messages)
LaunchedEffect(Unit) {
    flow.collect { currentMessages.firstOrNull { ... } }
}

// WRONG - captures stale value at launch time
LaunchedEffect(Unit) {
    flow.collect { messages.firstOrNull { ... } }  // STALE!
}
```

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
