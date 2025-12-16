package com.bothbubbles.ui.conversations

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.categorization.MessageCategory
import com.bothbubbles.ui.components.common.ConnectionBannerState
import com.bothbubbles.ui.components.common.ConnectionStatusBanner
import com.bothbubbles.ui.components.common.ConversationListSkeleton
import com.bothbubbles.ui.components.common.SmsBannerState
import com.bothbubbles.ui.components.common.SmsStatusBanner
import com.bothbubbles.ui.components.dialogs.ContactInfo
import com.bothbubbles.ui.components.dialogs.ContactQuickActionsPopup
import com.bothbubbles.ui.components.dialogs.SnoozeDurationDialog
import com.bothbubbles.ui.components.conversation.SwipeActionType
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

    // Determine if banners should be visible (for padding calculation)
    val isConnectionBannerVisible = when (uiState.connectionBannerState) {
        is ConnectionBannerState.NotConfigured,
        is ConnectionBannerState.Reconnecting -> true
        else -> false
    }
    val isSmsBannerVisible = uiState.smsBannerState is SmsBannerState.NotDefaultApp

    // Banner height for padding (approximate: 56dp content + 16dp padding per banner + nav bar)
    val singleBannerHeight = 72.dp
    val bannerPadding = when {
        isConnectionBannerVisible && isSmsBannerVisible -> singleBannerHeight * 2
        isConnectionBannerVisible || isSmsBannerVisible -> singleBannerHeight
        else -> 0.dp
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

    // Pull-to-search state
    val pullToSearchState = rememberPullToSearchState(
        listState = listState,
        isSearchActive = isSearchActive,
        onSearchActivated = { isSearchActive = true }
    )

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkerBackground)
    ) {
        // Main content
        Scaffold(
            containerColor = Color.Transparent,
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
                // Calculate bottom padding based on unified progress bar visibility
                // Single bar is ~80dp (or ~168dp when expanded), animate the offset so FAB stays above
                val showProgressBar = uiState.unifiedSyncProgress != null && !isSearchActive
                val isExpanded = uiState.unifiedSyncProgress?.isExpanded == true
                val stageCount = uiState.unifiedSyncProgress?.stages?.size ?: 0
                val progressBarHeight = if (isExpanded && stageCount > 1) {
                    80.dp + (32.dp * stageCount) // Base + per-stage rows
                } else {
                    80.dp
                }
                val fabBottomPadding by animateDpAsState(
                    targetValue = if (showProgressBar) progressBarHeight else 0.dp,
                    animationSpec = tween(durationMillis = 300),
                    label = "fabPadding"
                )

                // MD3 Extended FAB with animated expansion
                // Uses standard MD3 shape (16dp corner radius) and elevation
                // The `expanded` parameter animates the text label with a slide transition
                ExtendedFloatingActionButton(
                    onClick = onNewMessageClick,
                    expanded = isFabExpanded,
                    modifier = Modifier.padding(bottom = fabBottomPadding),
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Message,
                            contentDescription = "Start chat"
                        )
                    },
                    text = {
                        Text(
                            text = "Start chat",
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        ) { padding ->
            // Rounded card shape for the conversation list - rounded at top, meets edges
            val cardShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                shape = cardShape,
                color = MaterialTheme.colorScheme.surface
            ) {
        // Determine screen state for animated transitions
        val screenState = when {
            uiState.isLoading -> ConversationScreenState.LOADING
            uiState.conversations.isEmpty() -> ConversationScreenState.EMPTY
            else -> ConversationScreenState.CONTENT
        }

        AnimatedContent(
            targetState = screenState,
            transitionSpec = {
                fadeIn(tween(250)) togetherWith fadeOut(tween(200))
            },
            label = "conversationStateTransition"
        ) { state ->
            when (state) {
                ConversationScreenState.LOADING -> {
                    ConversationListSkeleton(
                        count = 8,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                ConversationScreenState.EMPTY -> {
                    EmptyConversationsState(
                        modifier = Modifier.fillMaxSize(),
                        isSearching = uiState.searchQuery.isNotBlank()
                    )
                }
                ConversationScreenState.CONTENT -> {
                // Apply conversation filter
                // By default, hide spam conversations unless the SPAM filter is active
                val filteredConversations = uiState.conversations.filter { conv ->
                    // Apply status filter first
                    val matchesStatus = when (conversationFilter) {
                        ConversationFilter.ALL -> !conv.isSpam
                        ConversationFilter.UNREAD -> !conv.isSpam && conv.unreadCount > 0
                        ConversationFilter.SPAM -> conv.isSpam
                        ConversationFilter.UNKNOWN_SENDERS -> !conv.isSpam && !conv.hasContact
                        ConversationFilter.KNOWN_SENDERS -> !conv.isSpam && conv.hasContact
                    }

                    // Apply category filter if set
                    val matchesCategory = categoryFilter?.let { category ->
                        conv.category?.equals(category.name, ignoreCase = true) == true
                    } ?: true

                    matchesStatus && matchesCategory
                }

                // Show empty state if filter returns no results
                val hasActiveFilter = conversationFilter != ConversationFilter.ALL || categoryFilter != null
                val showFilterEmptyState = filteredConversations.isEmpty() && hasActiveFilter

                if (showFilterEmptyState) {
                    if (categoryFilter != null) {
                        EmptyCategoryState(
                            category = categoryFilter!!,
                            onClearFilter = {
                                viewModel.setCategoryFilter(null)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        EmptyFilterState(
                            filter = conversationFilter,
                            onClearFilter = { viewModel.setConversationFilter("all") },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    val pinnedConversations = filteredConversations.filter { it.isPinned }
                    val regularConversations = filteredConversations.filter { !it.isPinned }

                    // Animated pull indicator offset
                    val animatedPullOffset by animateFloatAsState(
                        targetValue = pullToSearchState.pullOffset,
                        label = "pullOffset"
                    )
                    val pullThreshold = with(density) { 80.dp.toPx() }

                    // State for dragged pin overlay (renders on top of everything)
                    var draggedPinConversation by remember { mutableStateOf<ConversationUiModel?>(null) }
                    var draggedPinStartPosition by remember { mutableStateOf(Offset.Zero) }
                    var draggedPinOffset by remember { mutableStateOf(Offset.Zero) }
                    var isPinDragging by remember { mutableStateOf(false) }
                    val unpinThresholdPx = with(density) { 60.dp.toPx() }
                    // Track container position for correct overlay positioning
                    var containerRootPosition by remember { mutableStateOf(Offset.Zero) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coordinates ->
                                containerRootPosition = coordinates.positionInRoot()
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(pullToSearchState.nestedScrollConnection)
                        ) {
                            // Pull-to-search indicator
                            if (animatedPullOffset > 0) {
                                PullToSearchIndicator(
                                    progress = (animatedPullOffset / pullThreshold).coerceIn(0f, 1f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(density) { animatedPullOffset.toDp() })
                                )
                            }

                            // Main conversation list
                            ConversationsList(
                                pinnedConversations = pinnedConversations,
                                regularConversations = regularConversations,
                                listState = listState,
                                swipeConfig = uiState.swipeConfig,
                                selectedConversations = selectedConversations,
                                isSelectionMode = isSelectionMode,
                                isLoadingMore = uiState.isLoadingMore,
                                bottomPadding = bannerPadding,
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
                                onDragOverlayStart = { conversation, position ->
                                    draggedPinConversation = conversation
                                    draggedPinStartPosition = position
                                    draggedPinOffset = Offset.Zero
                                    isPinDragging = true
                                },
                                onDragOverlayMove = { offset ->
                                    draggedPinOffset = offset
                                },
                                onDragOverlayEnd = {
                                    isPinDragging = false
                                    draggedPinConversation = null
                                    draggedPinOffset = Offset.Zero
                                }
                            )
                        }

                        // Drag overlay - renders dragged pin on top of everything
                        PinnedDragOverlay(
                            conversation = draggedPinConversation,
                            isDragging = isPinDragging,
                            startPosition = draggedPinStartPosition,
                            dragOffset = draggedPinOffset,
                            containerRootPosition = containerRootPosition,
                            unpinThresholdPx = unpinThresholdPx
                        )
                    }
                } // End of else (showFilterEmptyState)
                } // End of CONTENT state
            } // End of when
        } // End of AnimatedContent
        } // End of Surface
        } // End of Scaffold content

        // Full-screen search overlay
        AnimatedVisibility(
            visible = isSearchActive,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SearchOverlay(
                query = uiState.searchQuery,
                onQueryChange = { viewModel.updateSearchQuery(it) },
                selectedFilter = selectedFilter,
                onFilterSelected = { filter ->
                    selectedFilter = filter
                },
                onFilterCleared = {
                    selectedFilter = null
                },
                onClose = {
                    isSearchActive = false
                    selectedFilter = null
                    viewModel.updateSearchQuery("")
                    focusManager.clearFocus()
                },
                conversations = uiState.conversations,
                messageSearchResults = uiState.messageSearchResults,
                onConversationClick = { guid, mergedGuids ->
                    onConversationClick(guid, mergedGuids)
                    isSearchActive = false
                    selectedFilter = null
                    viewModel.updateSearchQuery("")
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
            AlertDialog(
                onDismissRequest = { /* Not dismissable */ },
                icon = {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text("Data Issue Detected")
                },
                text = {
                    Text(
                        "The app encountered a data issue that cannot be automatically fixed. " +
                        "To continue, the app data needs to be reset. Your messages on the server are safe."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.resetAppData {
                                // Navigate to setup
                                onSettingsNavigate("setup", false)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Reset App")
                    }
                }
            )
        }

        // Swipe action confirmation dialog
        pendingSwipeAction?.let { (chatGuid, action) ->
            val isDelete = action == SwipeActionType.DELETE
            AlertDialog(
                onDismissRequest = { pendingSwipeAction = null },
                icon = {
                    Icon(
                        if (isDelete) Icons.Default.Delete else Icons.Default.Archive,
                        contentDescription = null,
                        tint = if (isDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(if (isDelete) "Delete Conversation?" else "Archive Conversation?")
                },
                text = {
                    Text(
                        if (isDelete) {
                            "This conversation will be permanently deleted. This cannot be undone."
                        } else {
                            "This conversation will be moved to the archive."
                        }
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.handleSwipeAction(chatGuid, action)
                            pendingSwipeAction = null
                        },
                        colors = if (isDelete) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Text(if (isDelete) "Delete" else "Archive")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingSwipeAction = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Batch action confirmation dialog (for selection mode)
        pendingBatchAction?.let { action ->
            val isDelete = action == SwipeActionType.DELETE
            val count = selectedConversations.size
            AlertDialog(
                onDismissRequest = { pendingBatchAction = null },
                icon = {
                    Icon(
                        if (isDelete) Icons.Default.Delete else Icons.Default.Archive,
                        contentDescription = null,
                        tint = if (isDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        if (isDelete) {
                            "Delete $count Conversation${if (count > 1) "s" else ""}?"
                        } else {
                            "Archive $count Conversation${if (count > 1) "s" else ""}?"
                        }
                    )
                },
                text = {
                    Text(
                        if (isDelete) {
                            "These conversations will be permanently deleted. This cannot be undone."
                        } else {
                            "These conversations will be moved to the archive."
                        }
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (isDelete) {
                                selectedConversations.forEach { viewModel.deleteChat(it) }
                            } else {
                                selectedConversations.forEach { viewModel.archiveChat(it) }
                            }
                            selectedConversations = emptySet()
                            pendingBatchAction = null
                        },
                        colors = if (isDelete) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Text(if (isDelete) "Delete" else "Archive")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBatchAction = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Scroll to top button - slides up from bottom when scrolled
        AnimatedVisibility(
            visible = showScrollToTop && !isSearchActive && !isSelectionMode,
            enter = slideInVertically(
                initialOffsetY = { it }, // Start from below the screen
                animationSpec = tween(durationMillis = 300)
            ) + fadeIn(animationSpec = tween(durationMillis = 300)),
            exit = slideOutVertically(
                targetOffsetY = { it }, // Exit to below the screen
                animationSpec = tween(durationMillis = 300)
            ) + fadeOut(animationSpec = tween(durationMillis = 300)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp) // Aligned vertically with FAB
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
        AnimatedVisibility(
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

        // Stacked status banners at the bottom
        // Sync progress, SMS import progress, and banners stack on top of each other
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Unified sync progress bar (combines iMessage sync, SMS import, and categorization)
            AnimatedVisibility(
                visible = uiState.unifiedSyncProgress != null && !isSearchActive,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                uiState.unifiedSyncProgress?.let { progress ->
                    UnifiedSyncProgressBar(
                        progress = progress,
                        onExpandToggle = { viewModel.toggleSyncProgressExpanded() },
                        onRetry = { viewModel.retrySyncOperation() },
                        onDismiss = { viewModel.dismissSyncError() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // SMS status banner (on top when both visible)
            SmsStatusBanner(
                state = uiState.smsBannerState,
                onSetAsDefaultClick = {
                    // Navigate to SMS settings
                    onSettingsNavigate("sms", false)
                },
                onDismiss = {
                    viewModel.dismissSmsBanner()
                }
            )

            // Connection status banner (at the bottom)
            ConnectionStatusBanner(
                state = uiState.connectionBannerState,
                onSetupClick = {
                    // Navigate to setup flow (skip welcome and SMS setup)
                    onSettingsNavigate("setup", false)
                },
                onDismiss = {
                    viewModel.dismissSetupBanner()
                },
                onRetryClick = {
                    viewModel.retryConnection()
                }
            )
        }
    }
}
