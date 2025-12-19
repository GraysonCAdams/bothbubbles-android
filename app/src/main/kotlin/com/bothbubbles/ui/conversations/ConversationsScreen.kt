package com.bothbubbles.ui.conversations

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.categorization.MessageCategory
import com.bothbubbles.ui.components.dialogs.ContactInfo
import com.bothbubbles.ui.components.dialogs.ContactQuickActionsPopup
import com.bothbubbles.ui.components.dialogs.SnoozeDurationDialog
import com.bothbubbles.ui.components.conversation.SwipeActionType
import com.bothbubbles.ui.conversations.components.BatchActionConfirmationDialog
import com.bothbubbles.ui.conversations.components.ConversationFab
import com.bothbubbles.ui.conversations.components.ConversationMainContent
import com.bothbubbles.ui.conversations.components.ConversationStatusBanners
import com.bothbubbles.ui.conversations.components.ConversationTopBarWrapper
import com.bothbubbles.ui.conversations.components.CorruptionDetectedDialog
import com.bothbubbles.ui.conversations.components.ScrollToTopButton
import com.bothbubbles.ui.conversations.components.SwipeActionConfirmationDialog
import com.bothbubbles.ui.settings.SettingsPanel



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (chatGuid: String, mergedGuids: List<String>) -> Unit,
    onNewMessageClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSettingsNavigate: (String, Boolean) -> Unit = { _, _ -> },
    onSetupServerClick: () -> Unit = {},
    reopenSettingsPanel: Boolean = false,
    onSettingsPanelHandled: () -> Unit = {},
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val context = LocalContext.current


    // Refresh state when screen resumes (to catch permission/default app changes)
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSmsState()
                // Refresh contact info in case permission was granted or contacts changed
                viewModel.onPermissionStateChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var isSearchActive by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf<SearchFilter?>(null) }
    // Filter state - derived from persisted settings
    val conversationFilter = remember(uiState.conversationFilter) {
        ConversationFilter.entries.find { it.name.lowercase() == uiState.conversationFilter.lowercase() } ?: ConversationFilter.ALL
    }
    val categoryFilter = remember(uiState.categoryFilter) {
        uiState.categoryFilter?.let { savedCategory ->
            MessageCategory.entries.find { it.name.equals(savedCategory, ignoreCase = true) }
        }
    }
    val enabledCategories = remember(
        uiState.transactionsEnabled,
        uiState.deliveriesEnabled,
        uiState.promotionsEnabled,
        uiState.remindersEnabled
    ) {
        buildSet {
            if (uiState.transactionsEnabled) add(MessageCategory.TRANSACTIONS)
            if (uiState.deliveriesEnabled) add(MessageCategory.DELIVERIES)
            if (uiState.promotionsEnabled) add(MessageCategory.PROMOTIONS)
            if (uiState.remindersEnabled) add(MessageCategory.REMINDERS)
        }
    }
    var showFilterDropdown by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Settings panel state
    var isSettingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(reopenSettingsPanel) {
        if (reopenSettingsPanel) {
            isSettingsOpen = true
            onSettingsPanelHandled()
        }
    }

    // Collect saved scroll position for restoration after process death
    val savedScrollIndex by viewModel.savedScrollIndex.collectAsStateWithLifecycle()
    val savedScrollOffset by viewModel.savedScrollOffset.collectAsStateWithLifecycle()
    val savedSearchActive by viewModel.savedSearchActive.collectAsStateWithLifecycle()

    // Initialize search state from saved state (process death restoration)
    LaunchedEffect(savedSearchActive) {
        if (savedSearchActive && !isSearchActive) {
            isSearchActive = true
        }
    }

    // Pull-to-search state - initialize with saved position for restoration
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollIndex,
        initialFirstVisibleItemScrollOffset = savedScrollOffset
    )
    val coroutineScope = rememberCoroutineScope()

    // Save scroll position when it changes (debounced via snapshotFlow)
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.saveScrollPosition(index, offset)
            }
    }

    // Save search active state when it changes
    LaunchedEffect(isSearchActive) {
        viewModel.saveSearchActive(isSearchActive)
    }

    // Scroll-to-top button visibility - show when scrolled past first item
    val showScrollToTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 100
        }
    }

    // FAB expanded state - collapse when scrolled down
    val isFabExpanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 50
        }
    }

    // Trigger load more when scrolled near bottom
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            val totalItems = listState.layoutInfo.totalItemsCount
            val shouldLoad = lastVisibleItem != null && totalItems > 0 && lastVisibleItem.index >= totalItems - 5
            Triple(lastVisibleItem?.index ?: -1, totalItems, shouldLoad)
        }
        .distinctUntilChanged()
        .collect { (_, _, shouldLoadMore) ->
            if (shouldLoadMore) {
                viewModel.loadMoreConversations()
            }
        }
    }

    // Auto-scroll to top when new conversations are created
    val conversationCount = uiState.conversations.size
    var previousConversationCount by remember { mutableIntStateOf(conversationCount) }

    LaunchedEffect(conversationCount) {
        // Only scroll if a new conversation was added (count increased)
        if (conversationCount > previousConversationCount && previousConversationCount > 0) {
            listState.animateScrollToItem(0)
        }
        previousConversationCount = conversationCount
    }

    // Scroll to position when a chat is pinned
    LaunchedEffect(Unit) {
        viewModel.scrollToIndexEvent.collect { index ->
            listState.animateScrollToItem(index)
        }
    }

    // Auto-scroll to top on new message if user is near the top (within top 20%)
    LaunchedEffect(Unit) {
        viewModel.newMessageEvent.collect {
            val totalItems = listState.layoutInfo.totalItemsCount
            val firstVisibleIndex = listState.firstVisibleItemIndex
            // Scroll if in top 20% of the list (or if list is small, use threshold of 5 items)
            val threshold = maxOf(5, (totalItems * 0.2).toInt())
            if (firstVisibleIndex < threshold) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val density = LocalDensity.current

    // Selection mode state
    var selectedConversations by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedConversations.isNotEmpty()
    var showBatchSnoozeDialog by remember { mutableStateOf(false) }

    // Contact quick actions popup state
    var quickActionsContact by remember { mutableStateOf<ContactInfo?>(null) }

    // Swipe action confirmation state
    var pendingSwipeAction by remember { mutableStateOf<Pair<String, SwipeActionType>?>(null) }
    // Batch action confirmation state (for selection mode)
    var pendingBatchAction by remember { mutableStateOf<SwipeActionType?>(null) }
    var isQuickActionContactStarred by remember { mutableStateOf(false) }

    // Group photo picker state
    var pendingGroupPhotoChat by remember { mutableStateOf<String?>(null) }
    val groupPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            pendingGroupPhotoChat?.let { chatGuid ->
                viewModel.setGroupPhoto(chatGuid, selectedUri)
            }
        }
        pendingGroupPhotoChat = null
    }

    // Update starred status when contact popup opens
    LaunchedEffect(quickActionsContact) {
        quickActionsContact?.let { contact ->
            if (contact.hasContact && !contact.isGroup) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    isQuickActionContactStarred = viewModel.isContactStarred(contact.address)
                }
            } else {
                isQuickActionContactStarred = false
            }
        }
    }

    // Pure grayscale background for header (no color hue)
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val darkerBackground = if (isDarkTheme) Color(0xFF1A1A1A) else Color(0xFFEDEDED)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(darkerBackground)
    ) {
        // Main content area - takes remaining space above sync progress bar
        Box(modifier = Modifier.weight(1f)) {
            Scaffold(
            containerColor = Color.Transparent,
            // Exclude bottom insets - progress bar handles nav bar padding
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(
                    color = darkerBackground,
                    tonalElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.statusBarsPadding()
                    ) {
                        if (isSelectionMode) {
                            // Determine if "Add contact" should be shown
                            // Only show for single non-group conversation without existing contact
                            val selectedConversation = if (selectedConversations.size == 1) {
                                uiState.conversations.find { it.guid == selectedConversations.first() }
                            } else null
                            val showAddContact = selectedConversation != null &&
                                !selectedConversation.isGroup &&
                                !selectedConversation.hasContact

                            // Check if any selected conversation can be pinned
                            // A conversation can be pinned if it has a saved contact, or unpinned if already pinned
                            val selectedConvos = uiState.conversations.filter { it.guid in selectedConversations }
                            val canPinAny = selectedConvos.any { it.hasContact || it.isPinned }

                            // Calculate total selectable count (only non-pinned conversations are selectable)
                            val selectableConversations = uiState.conversations.filter { !it.isPinned }
                            val totalSelectableCount = selectableConversations.size

                            // Determine if majority of selected conversations are unread
                            val unreadCount = selectedConvos.count { it.unreadCount > 0 }
                            val majorityUnread = unreadCount > selectedConvos.size / 2

                            val context = LocalContext.current

                            // Selection mode header
                            SelectionModeHeader(
                                selectedCount = selectedConversations.size,
                                totalSelectableCount = totalSelectableCount,
                                majorityUnread = majorityUnread,
                                onClose = { selectedConversations = emptySet() },
                                onSelectAll = {
                                    // Toggle between select all and deselect all
                                    val allSelectableGuids = selectableConversations.map { it.guid }.toSet()
                                    selectedConversations = if (selectedConversations.size >= totalSelectableCount) {
                                        emptySet() // Deselect all
                                    } else {
                                        allSelectableGuids // Select all
                                    }
                                },
                                onPin = {
                                    selectedConversations.forEach { viewModel.togglePin(it) }
                                    selectedConversations = emptySet()
                                },
                                onSnooze = { showBatchSnoozeDialog = true },
                                onArchive = { pendingBatchAction = SwipeActionType.ARCHIVE },
                                onDelete = { pendingBatchAction = SwipeActionType.DELETE },
                                onMarkAsRead = {
                                    viewModel.markChatsAsRead(selectedConversations)
                                    selectedConversations = emptySet()
                                },
                                onMarkAsUnread = {
                                    viewModel.markChatsAsUnread(selectedConversations)
                                    selectedConversations = emptySet()
                                },
                                onBlock = {
                                    viewModel.blockChats(selectedConversations)
                                    selectedConversations = emptySet()
                                },
                                onAddContact = if (showAddContact && selectedConversation != null) {
                                    {
                                        // Launch Android's add contact intent
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_INSERT,
                                            android.provider.ContactsContract.Contacts.CONTENT_URI
                                        ).apply {
                                            putExtra(
                                                android.provider.ContactsContract.Intents.Insert.PHONE,
                                                selectedConversation.address
                                            )
                                        }
                                        context.startActivity(intent)
                                        selectedConversations = emptySet()
                                    }
                                } else null,
                                isPinEnabled = canPinAny
                            )
                        } else {
                            ConversationsTopBar(
                                useSimpleAppTitle = uiState.useSimpleAppTitle,
                                conversationFilter = conversationFilter,
                                categoryFilter = categoryFilter,
                                categorizationEnabled = uiState.categorizationEnabled,
                                enabledCategories = enabledCategories,
                                hasSettingsWarning = uiState.hasSettingsWarning,
                                totalUnreadCount = uiState.totalUnreadCount,
                                onFilterSelected = { filter ->
                                    viewModel.setConversationFilter(filter.name.lowercase())
                                },
                                onCategorySelected = { category ->
                                    viewModel.setCategoryFilter(category?.name)
                                },
                                onSearchClick = { isSearchActive = true },
                                onSettingsClick = { isSettingsOpen = true },
                                onTitleClick = {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(0)
                                    }
                                }
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                // Add navigation bar padding when sync progress bar is not visible
                // (the sync bar handles its own nav bar padding when shown)
                val syncBarVisible = uiState.unifiedSyncProgress != null && !isSearchActive
                ConversationFab(
                    onClick = onNewMessageClick,
                    isExpanded = isFabExpanded,
                    modifier = if (!syncBarVisible) {
                        Modifier.navigationBarsPadding()
                    } else {
                        Modifier
                    }
                )
            }
        ) { padding ->
            ConversationMainContent(
                conversations = uiState.conversations,
                isLoading = uiState.isLoading,
                isLoadingMore = uiState.isLoadingMore,
                searchQuery = uiState.searchQuery,
                conversationFilter = conversationFilter,
                categoryFilter = categoryFilter,
                swipeConfig = uiState.swipeConfig,
                selectedConversations = selectedConversations,
                isSelectionMode = isSelectionMode,
                listState = listState,
                onConversationClick = onConversationClick,
                onConversationLongClick = { guid ->
                    selectedConversations = if (guid in selectedConversations) {
                        selectedConversations - guid
                    } else {
                        selectedConversations + guid
                    }
                },
                onAvatarClick = { contactInfo ->
                    quickActionsContact = contactInfo
                },
                onSwipeAction = { chatGuid, action ->
                    when (action) {
                        SwipeActionType.ARCHIVE, SwipeActionType.DELETE -> {
                            pendingSwipeAction = chatGuid to action
                        }
                        else -> viewModel.handleSwipeAction(chatGuid, action)
                    }
                },
                onPinReorder = { reorderedGuids ->
                    viewModel.reorderPins(reorderedGuids)
                },
                onUnpin = { guid ->
                    viewModel.togglePin(guid)
                },
                onClearConversationFilter = { viewModel.setConversationFilter("all") },
                onClearCategoryFilter = { viewModel.setCategoryFilter(null) },
                padding = padding
            )
        } // End of Scaffold content

        // Full-screen search overlay
        androidx.compose.animation.AnimatedVisibility(
            visible = isSearchActive,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SearchOverlay(
                query = uiState.searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                selectedFilter = selectedFilter,
                onFilterSelected = { filter ->
                    selectedFilter = filter
                },
                onFilterCleared = {
                    selectedFilter = null
                },
                startDate = uiState.searchStartDate,
                endDate = uiState.searchEndDate,
                onStartDateChange = viewModel::updateSearchStartDate,
                onEndDateChange = viewModel::updateSearchEndDate,
                onClose = {
                    isSearchActive = false
                    selectedFilter = null
                    viewModel.updateSearchQuery("")
                    viewModel.updateSearchStartDate(null)
                    viewModel.updateSearchEndDate(null)
                    focusManager.clearFocus()
                },
                conversations = uiState.conversations,
                messageSearchResults = uiState.messageSearchResults,
                onConversationClick = { guid, mergedGuids ->
                    // Navigate to chat but keep search state - back will return to search
                    onConversationClick(guid, mergedGuids)
                },
                focusRequester = focusRequester
            )

            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
        }

        // Contact quick actions popup
        quickActionsContact?.let { contact ->
            ContactQuickActionsPopup(
                contactInfo = contact.copy(isStarred = isQuickActionContactStarred),
                onDismiss = { quickActionsContact = null },
                onMessageClick = {
                    // Look up conversation to get merged guids
                    val conversation = uiState.conversations.find { it.guid == contact.chatGuid }
                    val mergedGuids = conversation?.mergedChatGuids ?: listOf(contact.chatGuid)
                    onConversationClick(contact.chatGuid, mergedGuids)
                },
                onDismissInferredName = {
                    viewModel.dismissInferredName(contact.address)
                },
                onContactAdded = {
                    viewModel.refreshContactInfo(contact.address)
                },
                onSetGroupPhoto = {
                    pendingGroupPhotoChat = contact.chatGuid
                    groupPhotoPickerLauncher.launch("image/*")
                }
            )
        }

        // Batch snooze dialog for multi-select
        SnoozeDurationDialog(
            visible = showBatchSnoozeDialog,
            currentSnoozeUntil = null,
            onDismiss = { showBatchSnoozeDialog = false },
            onDurationSelected = { duration ->
                selectedConversations.forEach { guid ->
                    viewModel.snoozeChat(guid, duration.durationMs)
                }
                showBatchSnoozeDialog = false
                selectedConversations = emptySet()
            },
            onUnsnooze = { }
        )

        // Corruption detected dialog - non-dismissable
        if (uiState.unifiedSyncProgress?.isCorrupted == true) {
            CorruptionDetectedDialog(
                onResetAppData = {
                    viewModel.resetAppData {
                        // Navigate to setup
                        onSettingsNavigate("setup", false)
                    }
                }
            )
        }

        // Swipe action confirmation dialog
        pendingSwipeAction?.let { (chatGuid, action) ->
            SwipeActionConfirmationDialog(
                action = action,
                onConfirm = {
                    viewModel.handleSwipeAction(chatGuid, action)
                    pendingSwipeAction = null
                },
                onDismiss = { pendingSwipeAction = null }
            )
        }

        // Batch action confirmation dialog (for selection mode)
        pendingBatchAction?.let { action ->
            BatchActionConfirmationDialog(
                action = action,
                count = selectedConversations.size,
                onConfirm = {
                    if (action == SwipeActionType.DELETE) {
                        selectedConversations.forEach { viewModel.deleteChat(it) }
                    } else {
                        selectedConversations.forEach { viewModel.archiveChat(it) }
                    }
                    selectedConversations = emptySet()
                    pendingBatchAction = null
                },
                onDismiss = { pendingBatchAction = null }
            )
        }

        // Scroll to top button - slides up from bottom when scrolled
        androidx.compose.animation.AnimatedVisibility(
            visible = showScrollToTop && !isSearchActive && !isSelectionMode,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 300)
            ) + fadeIn(animationSpec = tween(durationMillis = 300)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 300)
            ) + fadeOut(animationSpec = tween(durationMillis = 300)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            ScrollToTopButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                }
            )
        }

        // Sliding settings panel from right
        androidx.compose.animation.AnimatedVisibility(
            visible = isSettingsOpen,
            enter = slideInHorizontally(
                initialOffsetX = { it }, // Start from right (full width)
                animationSpec = tween(durationMillis = 300)
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { it }, // Exit to right (full width)
                animationSpec = tween(durationMillis = 300)
            )
        ) {
            SettingsPanel(
                onClose = { isSettingsOpen = false },
                onNavigate = onSettingsNavigate
            )
        }
        } // End of main content Box

        // Sync progress bar at the bottom - stacked layout (content shrinks to accommodate)
        ConversationStatusBanners(
            unifiedSyncProgress = uiState.unifiedSyncProgress,
            isSearchActive = isSearchActive,
            onExpandToggle = viewModel::toggleSyncProgressExpanded,
            onRetrySync = viewModel::retrySyncOperation,
            onDismissSyncError = viewModel::dismissSyncError
        )
    }
}
