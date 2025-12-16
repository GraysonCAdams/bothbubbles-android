# Conversations Screen

## Purpose

Main conversation list screen showing all chats. Supports filtering, search, pinned chats, and swipe actions.

## Files

| File | Description |
|------|-------------|
| `ConversationEmptyStates.kt` | Empty state UIs |
| `ConversationFilters.kt` | Filter chips (All, iMessage, SMS) |
| `ConversationFormatters.kt` | Text formatting utilities |
| `ConversationMappers.kt` | Map entities to UI models |
| `ConversationProgressBars.kt` | Sync progress indicators |
| `ConversationScrollHelpers.kt` | Scroll state utilities |
| `ConversationSearch.kt` | Search bar and results |
| `ConversationTile.kt` | Individual conversation row |
| `ConversationsScreen.kt` | Main screen composable |
| `ConversationsUiState.kt` | UI state models |
| `ConversationsViewModel.kt` | Main ViewModel with delegates |
| `PinnedConversations.kt` | Pinned conversations section |
| `SelectionModeHeader.kt` | Multi-select mode header |

## Architecture

```
ConversationsViewModel Delegate Pattern:

ConversationsViewModel
├── ConversationLoadingDelegate   - Data loading, pagination
├── ConversationActionsDelegate   - Pin, mute, archive, delete
├── ConversationObserverDelegate  - DB/socket change observers
└── UnifiedGroupMappingDelegate   - iMessage/SMS merging

Screen Structure:
ConversationsScreen
├── TopAppBar (search, filter)
├── SyncProgressIndicator
├── PinnedConversations (horizontal)
└── ConversationList (LazyColumn)
    └── SwipeableConversationTile
```

## Required Patterns

### Screen Structure

```kotlin
@Composable
fun ConversationsScreen(
    viewModel: ConversationsViewModel = hiltViewModel(),
    onNavigateToChat: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToNewChat: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val pinnedConversations by viewModel.pinnedConversations.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ConversationsTopBar(
                onSearch = viewModel::search,
                onSettings = onNavigateToSettings
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToNewChat) {
                Icon(Icons.Default.Edit, "New chat")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            // Sync progress
            if (syncState.isLoading) {
                item { LinearProgressIndicator() }
            }

            // Pinned conversations
            if (pinnedConversations.isNotEmpty()) {
                item {
                    PinnedConversations(pinnedConversations, onNavigateToChat)
                }
            }

            // Conversation list
            items(conversations, key = { it.guid }) { conversation ->
                SwipeableConversationTile(
                    conversation = conversation,
                    onClick = { onNavigateToChat(conversation.guid) }
                )
            }
        }
    }
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `components/` | Conversation-specific components |
| `delegates/` | ViewModel delegates |

## Best Practices

1. Use LazyColumn for performance
2. Provide stable keys for list items
3. Support swipe actions with customization
4. Show sync state clearly
5. Support multi-select mode for bulk operations
