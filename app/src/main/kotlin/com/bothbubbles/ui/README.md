# UI Layer

## Purpose

Presentation layer using Jetpack Compose. Contains screens, ViewModels, components, and navigation.

## Architecture

```
UI Layer Organization:

┌─────────────────────────────────────────────────────────────┐
│                      Screens                                │
│  chat/, conversations/, settings/, setup/, chatcreator/     │
├─────────────────────────────────────────────────────────────┤
│                    ViewModels                               │
│  - StateFlow for UI state                                   │
│  - Delegate pattern for large VMs                           │
├─────────────────────────────────────────────────────────────┤
│                    Components                               │
│  components/ - Reusable UI components                       │
├─────────────────────────────────────────────────────────────┤
│                    Navigation                               │
│  navigation/ - Type-safe Compose navigation                 │
├─────────────────────────────────────────────────────────────┤
│                    Theme                                    │
│  theme/ - Material 3 theming                                │
└─────────────────────────────────────────────────────────────┘
```

## Mandatory Rules

### Screen Structure

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ChatScreenContent(
        uiState = uiState,
        onSendMessage = viewModel::sendMessage,
        onNavigateBack = onNavigateBack
    )
}

@Composable
private fun ChatScreenContent(
    uiState: ChatUiState,
    onSendMessage: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    // Stateless composable - easier to preview
}
```

### ViewModel with Delegates

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendDelegate: ChatSendDelegate,
    private val searchDelegate: ChatSearchDelegate,
    private val syncDelegate: ChatSyncDelegate
) : ViewModel() {

    // Expose delegate state
    val searchState = searchDelegate.searchState
    val sendState = sendDelegate.sendState

    init {
        sendDelegate.initialize(chatGuid, viewModelScope)
        searchDelegate.initialize(chatGuid, viewModelScope)
    }

    // Delegate methods
    fun sendMessage(text: String) = sendDelegate.sendMessage(text)
    fun search(query: String) = searchDelegate.search(query)
}
```

### State Management

```kotlin
// UI State sealed class
sealed interface ChatUiState {
    object Loading : ChatUiState
    data class Success(
        val messages: List<MessageUi>,
        val chat: ChatUi
    ) : ChatUiState
    data class Error(val message: String) : ChatUiState
}

// In ViewModel
private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
val uiState = _uiState.asStateFlow()
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `bubble/` | Android bubble (floating chat) UI |
| `call/` | Incoming call UI |
| `camera/` | In-app camera screen |
| `chat/` | Chat screen and related components |
| `chatcreator/` | New chat/group creation |
| `components/` | Shared UI components |
| `conversations/` | Conversation list screen |
| `effects/` | Message effects (confetti, etc.) |
| `media/` | Media viewer screen |
| `modifiers/` | Custom Compose modifiers |
| `navigation/` | Navigation setup |
| `preview/` | Compose preview utilities |
| `settings/` | Settings screens |
| `setup/` | Initial setup wizard |
| `share/` | Share picker screen |
| `theme/` | Material 3 theme |
| `util/` | UI utilities |

## Compose Rules (MANDATORY)

**RULE**: You MUST read and follow `docs/COMPOSE_BEST_PRACTICES.md` before writing any Compose code. Violations break performance.

### Core Rules (NEVER BREAK THESE)

1. **Leaf-Node State Collection**: Never collect state in a parent just to pass it down. Push state collection to the lowest possible child composable.

2. **Immutable Collections**: Always use `ImmutableList` / `ImmutableMap` from `kotlinx.collections.immutable` in UI state classes.

3. **Stable Callbacks**: Use method references (`viewModel::method`) instead of lambdas that capture state.

4. **No Logic in Composition**: Keep the composition path pure. No logging, I/O, or complex calculations during composition.

5. Use `collectAsStateWithLifecycle()` for StateFlow (not `collectAsState()`)

6. Use `hiltViewModel()` for ViewModel injection

7. Use delegate pattern for complex ViewModels

8. Follow Material 3 design guidelines

### Push-Down State Pattern

For complex screens, pass delegates to child composables instead of collected state:

```kotlin
// GOOD: Child collects its own state
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    ChatMessageList(
        searchDelegate = viewModel.searchDelegate,
        syncDelegate = viewModel.syncDelegate
    )
}

@Composable
fun ChatMessageList(
    searchDelegate: ChatSearchDelegate,
    syncDelegate: ChatSyncDelegate
) {
    // PERF FIX: Collect state internally to avoid parent recomposition
    val searchState by searchDelegate.state.collectAsStateWithLifecycle()
    val syncState by syncDelegate.state.collectAsStateWithLifecycle()
}
```

See `docs/COMPOSE_BEST_PRACTICES.md` for detailed rules and examples.
