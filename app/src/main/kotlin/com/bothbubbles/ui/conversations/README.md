# Conversations Screen

## Purpose

Main conversation list screen showing all chats. Supports filtering, search, pinned chats, and swipe actions.

## Files

| File | Description |
|------|-------------|
| `ConversationEmptyStates.kt` | Empty state UIs for filters and no conversations |
| `ConversationFilters.kt` | Filter enums (ConversationFilter, SearchFilter) |
| `ConversationFormatters.kt` | Text formatting utilities |
| `ConversationMappers.kt` | Map entities to UI models |
| `ConversationProgressBars.kt` | Sync progress indicators |
| `ConversationScrollHelpers.kt` | PullToSearchIndicator, ScrollToTopButton |
| `ConversationSearch.kt` | Search overlay and results |
| `ConversationTile.kt` | GoogleStyleConversationTile row component |
| `ConversationsList.kt` | Main LazyColumn list with pinned section |
| `ConversationsScreen.kt` | Main screen coordinator |
| `ConversationsTopBar.kt` | Top bar with filter dropdown |
| `ConversationsUiState.kt` | UI state models |
| `ConversationsViewModel.kt` | Main ViewModel with delegates |
| `PinnedConversations.kt` | PinnedConversationsRow, PinnedDragOverlay |
| `PullToSearchLogic.kt` | rememberPullToSearchState hook |
| `SelectionModeHeader.kt` | Multi-select mode header |

## Architecture

```
ConversationsViewModel Delegate Pattern:

ConversationsViewModel
├── ConversationLoadingDelegate   - Data loading, pagination
├── ConversationActionsDelegate   - Pin, mute, archive, delete
├── ConversationObserverDelegate  - DB/socket change observers
└── UnifiedGroupMappingDelegate   - iMessage/SMS merging

Screen Structure (Decomposed):

ConversationsScreen (coordinator)
├── ConversationsTopBar           - App title, filter, search, settings
│   └── FilterDropdownMenu        - Conversation and category filters
├── SelectionModeHeader           - Multi-select actions
├── PullToSearchLogic             - NestedScrollConnection for pull gesture
├── ConversationsList             - Main LazyColumn
│   ├── PinnedConversationsRow    - Horizontal pinned section
│   └── SwipeableConversationTile - Swipeable list items
│       └── GoogleStyleConversationTile
├── PinnedDragOverlay             - Floating overlay during pin drag
├── SearchOverlay                 - Full-screen search
└── Status Banners                - Connection, SMS, sync progress
```

## Required Patterns

### Pull-to-Search State

```kotlin
// Use rememberPullToSearchState for pull-to-search behavior
val pullToSearchState = rememberPullToSearchState(
    listState = listState,
    isSearchActive = isSearchActive,
    onSearchActivated = { isSearchActive = true }
)

// Apply nested scroll connection to container
Column(
    modifier = Modifier.nestedScroll(pullToSearchState.nestedScrollConnection)
) {
    // Show indicator when pulling
    if (pullToSearchState.pullOffset > 0) {
        PullToSearchIndicator(
            progress = (pullToSearchState.pullOffset / threshold).coerceIn(0f, 1f)
        )
    }
    // Content...
}
```

### ConversationsList Usage

```kotlin
ConversationsList(
    pinnedConversations = pinnedList,
    regularConversations = regularList,
    listState = listState,
    swipeConfig = uiState.swipeConfig,
    selectedConversations = selectedSet,
    isSelectionMode = isSelectionMode,
    isLoadingMore = uiState.isLoadingMore,
    bottomPadding = bannerPadding,
    onConversationClick = { guid, mergedGuids -> /* navigate */ },
    onConversationLongClick = { guid -> /* toggle selection */ },
    onAvatarClick = { contactInfo -> /* show popup */ },
    onSwipeAction = { guid, action -> /* handle action */ },
    onPinReorder = { guids -> viewModel.reorderPins(guids) },
    onUnpin = { guid -> viewModel.togglePin(guid) },
    onDragOverlayStart = { conv, pos -> /* start drag */ },
    onDragOverlayMove = { offset -> /* update drag */ },
    onDragOverlayEnd = { /* end drag */ }
)
```

### ConversationsTopBar Usage

```kotlin
ConversationsTopBar(
    useSimpleAppTitle = uiState.useSimpleAppTitle,
    conversationFilter = conversationFilter,
    categoryFilter = categoryFilter,
    categorizationEnabled = uiState.categorizationEnabled,
    onFilterSelected = { filter -> viewModel.setConversationFilter(filter.name) },
    onCategorySelected = { category -> viewModel.setCategoryFilter(category?.name) },
    onSearchClick = { isSearchActive = true },
    onSettingsClick = { isSettingsOpen = true },
    onTitleClick = { /* scroll to top */ }
)
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
