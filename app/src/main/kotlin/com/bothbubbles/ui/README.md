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

## Required Patterns

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

## Best Practices

1. Use `hiltViewModel()` for ViewModel injection
2. Use `collectAsStateWithLifecycle()` for StateFlow
3. Split Screen and Content composables (stateless content)
4. Use delegate pattern for complex ViewModels
5. Follow Material 3 design guidelines
6. Use theme colors and typography
