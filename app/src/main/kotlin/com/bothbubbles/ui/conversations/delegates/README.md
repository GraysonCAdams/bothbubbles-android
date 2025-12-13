# ConversationsViewModel Delegates

This directory contains delegate classes that decompose the ConversationsViewModel following the composition pattern established in `ui/chat/delegates/`.

## Architecture Overview

The ConversationsViewModel was originally over 2,100 lines. It has been broken down into focused delegates:

```
ConversationsViewModel (748 lines)
├── UnifiedGroupMappingDelegate (302 lines) - Unified group mapping logic
├── ConversationObserverDelegate (306 lines) - Database/socket observation
├── ConversationActionsDelegate (460 lines) - User actions (pin, mute, etc.)
└── ConversationLoadingDelegate (369 lines) - Data loading and pagination
```

Total delegate code: ~1,437 lines
Main ViewModel: 748 lines (65% reduction)

## Delegate Pattern

All delegates follow this pattern:

### 1. Constructor Injection
```kotlin
class MyDelegate @Inject constructor(
    private val repository: Repository,
    // ... dependencies
) {
```

### 2. Initialization
```kotlin
private lateinit var scope: CoroutineScope

fun initialize(scope: CoroutineScope, /* callbacks */) {
    this.scope = scope
    // Setup observers, etc.
}
```

### 3. State Exposure
```kotlin
private val _someState = MutableStateFlow(initialValue)
val someState: StateFlow<Type> = _someState.asStateFlow()
```

### 4. Callbacks for ViewModel Updates
Delegates use callbacks to notify the ViewModel of state changes:
```kotlin
private var onDataChanged: (suspend () -> Unit)? = null
```

## Delegate Responsibilities

### UnifiedGroupMappingDelegate
**Purpose**: Handle unified group mapping logic that merges iMessage/SMS conversations

**Responsibilities**:
- Convert unified chat groups to UI models
- Determine message types and statuses
- Generate preview text
- Get reaction/link preview data
- Optimized batch queries to avoid N+1 problems

**Key Methods**:
- `unifiedGroupToUiModel()` - Convert group entity to UI model with pre-fetched data

### ConversationObserverDelegate
**Purpose**: Observe database and socket changes for real-time updates

**Responsibilities**:
- Track typing indicators
- Monitor connection state
- Observe sync progress
- Handle socket events (new messages, updates, chat read)
- Manage startup grace period for banners

**Exposed State**:
- `typingChats` - Set of chat GUIDs with active typing
- `isConnected` - Current connection status
- `connectionState` - Detailed connection state
- `isSyncing` - Whether sync is in progress
- `syncProgress` - Sync progress (0.0 - 1.0)
- Various sync state fields

**Key Methods**:
- `retryConnection()` - Retry socket connection
- `wasEverConnected()` - Check if connection was ever established

### ConversationActionsDelegate
**Purpose**: Handle user actions on conversations

**Responsibilities**:
- Pin/unpin conversations
- Mute/unmute notifications
- Snooze/unsnooze notifications
- Archive/delete conversations
- Mark read/unread
- Block contacts
- Set group photos
- Reorder pins

**Exposed Events**:
- `scrollToIndexEvent` - Emits scroll target when chat is pinned

**Key Methods**:
- `handleSwipeAction()` - Handle swipe gesture actions
- `togglePin()` - Toggle pin status
- `reorderPins()` - Reorder pinned conversations
- `markAsRead()`, `markAsUnread()` - Update read status
- `snoozeChat()`, `unsnoozeChat()` - Manage snooze
- All actions use optimistic UI updates with background persistence

### ConversationLoadingDelegate
**Purpose**: Handle data loading and pagination

**Responsibilities**:
- Load initial conversations on startup
- Refresh currently loaded pages
- Paginate conversations on scroll
- Build conversation lists from entities
- Optimized batch queries for performance

**Exposed State**:
- `isLoading` - Initial load state
- `isLoadingMore` - Pagination state
- `canLoadMore` - Whether more data exists
- `currentPage` - Current pagination page
- `error` - Load error message

**Key Methods**:
- `loadInitialConversations()` - Load first page
- `refreshAllLoadedPages()` - Refresh current data
- `loadMoreConversations()` - Load next page

**Performance Optimizations**:
- Batch fetches all chat GUIDs upfront
- Single query for all latest messages
- Single query for all participants
- Pre-fetched data passed to UI model builders

## Usage in ViewModel

### Initialization
```kotlin
class ConversationsViewModel @Inject constructor(
    // ... repositories
    private val loadingDelegate: ConversationLoadingDelegate,
    private val observerDelegate: ConversationObserverDelegate,
    private val actionsDelegate: ConversationActionsDelegate
) : ViewModel() {

    init {
        loadingDelegate.initialize(viewModelScope)
        observerDelegate.initialize(
            scope = viewModelScope,
            onDataChanged = { refreshAllLoadedPages() },
            onNewMessage = { refreshAllLoadedPages() },
            onMessageUpdated = { refreshAllLoadedPages() },
            onChatRead = { chatGuid -> optimisticallyMarkChatRead(chatGuid) }
        )
        actionsDelegate.initialize(
            scope = viewModelScope,
            onConversationsUpdated = { updated -> updateConversations(updated) }
        )
    }
}
```

### State Composition
```kotlin
private fun observeDelegateStates() {
    viewModelScope.launch {
        combine(
            observerDelegate.isConnected,
            observerDelegate.isSyncing,
            loadingDelegate.isLoading,
            // ... more state
        ) { isConnected, isSyncing, isLoading, ... ->
            _uiState.update {
                it.copy(
                    isConnected = isConnected,
                    isSyncing = isSyncing,
                    isLoading = isLoading
                )
            }
        }.collect()
    }
}
```

### Delegation
```kotlin
fun togglePin(chatGuid: String) {
    actionsDelegate.togglePin(chatGuid, _uiState.value.conversations)
}

fun loadMoreConversations() {
    viewModelScope.launch {
        val result = loadingDelegate.loadMoreConversations(
            currentConversations = _uiState.value.conversations,
            typingChats = observerDelegate.typingChats.value
        )
        // Handle result...
    }
}
```

## Benefits

1. **Focused Responsibilities**: Each delegate has a single, well-defined purpose
2. **Easier Testing**: Delegates can be tested in isolation
3. **Better Maintainability**: Changes are localized to specific delegates
4. **Reusability**: Delegates can potentially be reused in other ViewModels
5. **Clearer Architecture**: Composition over inheritance makes relationships explicit

## Guidelines for Future Delegates

1. Keep delegates under 500 lines when possible
2. Use constructor injection for dependencies
3. Require explicit initialization with scope
4. Expose state via StateFlow, not mutable state
5. Use callbacks for notifying ViewModel of updates
6. Document responsibilities clearly
7. Follow the established naming pattern: `{Feature}{Purpose}Delegate`
