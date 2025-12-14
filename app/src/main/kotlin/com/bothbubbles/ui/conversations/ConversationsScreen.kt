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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.R
import com.bothbubbles.services.categorization.MessageCategory
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.components.common.ConnectionBannerState
import com.bothbubbles.ui.components.common.ConnectionStatusBanner
import com.bothbubbles.ui.components.common.ConversationListSkeleton
import com.bothbubbles.ui.components.common.GroupAvatar
import com.bothbubbles.ui.components.common.SmsBannerState
import com.bothbubbles.ui.components.common.SmsStatusBanner
import com.bothbubbles.ui.components.common.staggeredEntrance
import com.bothbubbles.ui.components.dialogs.ContactInfo
import com.bothbubbles.ui.components.dialogs.ContactQuickActionsPopup
import com.bothbubbles.ui.components.dialogs.SnoozeDurationDialog
import com.bothbubbles.ui.components.conversation.SwipeActionType
import com.bothbubbles.ui.components.conversation.SwipeableConversationTile
import com.bothbubbles.ui.settings.SettingsPanel
import com.bothbubbles.ui.theme.KumbhSansFamily



@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    var pullOffset by remember { mutableFloatStateOf(0f) }
    // Track if the current gesture started at the top (prevents flings from triggering pull-to-search)
    var gestureStartedAtTop by remember { mutableStateOf(false) }

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
    var previousConversationCount by remember { mutableStateOf(conversationCount) }

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
    val pullThreshold = with(density) { 80.dp.toPx() } // Distance to pull before triggering search

    // Nested scroll connection to detect overscroll at top
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0

                // Only allow pull-to-search if it's a drag that started at the top
                // This prevents flings from below carrying through and triggering pull-to-search
                if (source == NestedScrollSource.UserInput && isAtTop && !gestureStartedAtTop) {
                    gestureStartedAtTop = true
                }

                // When scrolling up (pulling down) at the top of the list
                // Only allow if the gesture started at the top (not from a fling)
                if (available.y > 0 && isAtTop && gestureStartedAtTop && source == NestedScrollSource.UserInput) {
                    pullOffset = (pullOffset + available.y * 0.5f).coerceIn(0f, pullThreshold * 1.5f)
                    return Offset(0f, available.y)
                }
                // Reset pull offset when scrolling down
                if (available.y < 0 && pullOffset > 0) {
                    val consumed = minOf(-available.y, pullOffset)
                    pullOffset -= consumed
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0

                // Handle overscroll at top - only for user input when gesture started at top
                if (available.y > 0 && isAtTop && gestureStartedAtTop && source == NestedScrollSource.UserInput) {
                    pullOffset = (pullOffset + available.y * 0.5f).coerceIn(0f, pullThreshold * 1.5f)
                    return available
                }
                return Offset.Zero
            }
        }
    }

    // Trigger search when pull threshold is reached
    LaunchedEffect(pullOffset) {
        if (pullOffset >= pullThreshold && !isSearchActive) {
            isSearchActive = true
            pullOffset = 0f
        }
    }

    // Reset pull offset and gesture state when finger is released (detected via lack of scrolling)
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            if (pullOffset > 0 && pullOffset < pullThreshold) {
                pullOffset = 0f
            }
            gestureStartedAtTop = false
        }
    }

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
                            // Main header row - tappable to scroll to top
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(0)
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // App title
                                Text(
                                    text = if (uiState.useSimpleAppTitle) {
                                        buildAnnotatedString { append("Messages") }
                                    } else {
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                append("Both")
                                            }
                                            append("Bubbles")
                                        }
                                    },
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = KumbhSansFamily,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 22.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                // Filter dropdown
                                Box {
                                    val hasActiveFilter = conversationFilter != ConversationFilter.ALL || categoryFilter != null
                                    IconButton(onClick = { showFilterDropdown = true }) {
                                        Icon(
                                            imageVector = if (hasActiveFilter) {
                                                Icons.Default.FilterList
                                            } else {
                                                Icons.Outlined.FilterList
                                            },
                                            contentDescription = "Filter conversations",
                                            tint = if (hasActiveFilter) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showFilterDropdown,
                                        onDismissRequest = { showFilterDropdown = false }
                                    ) {
                                        // Status filters
                                        ConversationFilter.entries.forEach { filter ->
                                            val isSelected = conversationFilter == filter && categoryFilter == null
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = filter.icon,
                                                            contentDescription = null,
                                                            tint = if (isSelected) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            },
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                        Text(
                                                            text = filter.label,
                                                            color = if (isSelected) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurface
                                                            },
                                                            fontWeight = if (isSelected) {
                                                                FontWeight.Medium
                                                            } else {
                                                                FontWeight.Normal
                                                            }
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    viewModel.setConversationFilter(filter.name.lowercase())
                                                    showFilterDropdown = false
                                                }
                                            )
                                        }

                                        // Category filters (only shown when categorization is enabled)
                                        if (uiState.categorizationEnabled) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                            Text(
                                                text = "Categories",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                            )

                                            MessageCategory.entries.forEach { category ->
                                                val isSelected = categoryFilter == category
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = category.icon,
                                                                contentDescription = null,
                                                                tint = if (isSelected) {
                                                                    MaterialTheme.colorScheme.primary
                                                                } else {
                                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                                },
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                            Text(
                                                                text = category.displayName,
                                                                color = if (isSelected) {
                                                                    MaterialTheme.colorScheme.primary
                                                                } else {
                                                                    MaterialTheme.colorScheme.onSurface
                                                                },
                                                                fontWeight = if (isSelected) {
                                                                    FontWeight.Medium
                                                                } else {
                                                                    FontWeight.Normal
                                                                }
                                                            )
                                                        }
                                                    },
                                                    onClick = {
                                                        viewModel.setCategoryFilter(category.name)
                                                        showFilterDropdown = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Search button
                                IconButton(onClick = {
                                    isSearchActive = true
                                }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = stringResource(R.string.search_conversations),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Settings button
                                IconButton(onClick = { isSettingsOpen = true }) {
                                    Icon(
                                        Icons.Outlined.Settings,
                                        contentDescription = "Settings",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                // Calculate bottom padding based on visible progress bars
                // Each bar is ~80dp, animate the offset so FAB stays above them
                val progressBarHeight = 80.dp
                val visibleProgressBars = listOf(
                    uiState.isSyncing && !isSearchActive,
                    uiState.isImportingSms
                ).count { it }
                val fabBottomPadding by animateDpAsState(
                    targetValue = progressBarHeight * visibleProgressBars,
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
                    targetValue = pullOffset,
                    label = "pullOffset"
                )

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
                        .nestedScroll(nestedScrollConnection)
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

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 88.dp + bannerPadding)
                    ) {
                    // Pinned section (iOS-style horizontal row)
                    // Always show pinned at top - in selection mode they are view-only (not selectable)
                    if (pinnedConversations.isNotEmpty()) {
                        item {
                            PinnedConversationsRow(
                                conversations = pinnedConversations,
                                onConversationClick = { conversation ->
                                    // In selection mode, pinned items just navigate (not selectable)
                                    onConversationClick(conversation.guid, conversation.mergedChatGuids)
                                },
                                onConversationLongClick = { guid ->
                                    // Disable long-click selection in selection mode for pinned items
                                    if (!isSelectionMode) {
                                        selectedConversations = selectedConversations + guid
                                    }
                                },
                                onUnpin = { guid ->
                                    // Disable unpin action in selection mode
                                    if (!isSelectionMode) {
                                        viewModel.togglePin(guid)
                                    }
                                },
                                onReorder = { reorderedGuids ->
                                    // Disable reordering in selection mode
                                    if (!isSelectionMode) {
                                        viewModel.reorderPins(reorderedGuids)
                                    }
                                },
                                onAvatarClick = { conversation ->
                                    // Disable avatar popup in selection mode
                                    if (!isSelectionMode) {
                                        quickActionsContact = ContactInfo(
                                            chatGuid = conversation.guid,
                                            displayName = conversation.displayName,
                                            rawDisplayName = conversation.rawDisplayName,
                                            avatarPath = conversation.avatarPath,
                                            address = conversation.address,
                                            isGroup = conversation.isGroup,
                                            participantNames = conversation.participantNames,
                                            hasContact = conversation.hasContact,
                                            hasInferredName = conversation.hasInferredName
                                        )
                                    }
                                },
                                selectedConversations = emptySet(), // Never show selection state for pinned items
                                isSelectionMode = isSelectionMode,
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
                                },
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        // Divider between pinned and regular
                        item {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    // Always show only regular (non-pinned) conversations in the list below
                    // Pinned items are always shown in the row above and are not selectable
                    val displayConversations = regularConversations

                    // Regular conversations with staggered entrance animation
                    itemsIndexed(
                        items = displayConversations,
                        key = { _, conv -> conv.guid }
                    ) { index, conversation ->
                        if (isSelectionMode) {
                            // In selection mode, use regular tile without swipe
                            GoogleStyleConversationTile(
                                conversation = conversation,
                                isSelected = conversation.guid in selectedConversations,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    selectedConversations = if (conversation.guid in selectedConversations) {
                                        selectedConversations - conversation.guid
                                    } else {
                                        selectedConversations + conversation.guid
                                    }
                                },
                                onLongClick = {
                                    selectedConversations = selectedConversations + conversation.guid
                                },
                                modifier = Modifier
                                    .staggeredEntrance(index)
                                    .animateItem()
                            )
                        } else {
                            // Use swipeable tile when not in selection mode
                            // Modify swipe config to disable PIN for unsaved contacts that aren't already pinned
                            // Memoized to avoid recreating on every recomposition
                            val conversationSwipeConfig = remember(
                                conversation.hasContact,
                                conversation.isPinned,
                                uiState.swipeConfig
                            ) {
                                if (!conversation.hasContact && !conversation.isPinned) {
                                    // Disable PIN action for unsaved contacts
                                    uiState.swipeConfig.copy(
                                        leftAction = if (uiState.swipeConfig.leftAction == SwipeActionType.PIN) SwipeActionType.NONE else uiState.swipeConfig.leftAction,
                                        rightAction = if (uiState.swipeConfig.rightAction == SwipeActionType.PIN) SwipeActionType.NONE else uiState.swipeConfig.rightAction
                                    )
                                } else {
                                    uiState.swipeConfig
                                }
                            }
                            SwipeableConversationTile(
                                isPinned = conversation.isPinned,
                                isMuted = conversation.isMuted,
                                isRead = conversation.unreadCount == 0,
                                isSnoozed = conversation.isSnoozed,
                                onSwipeAction = { action ->
                                    when (action) {
                                        SwipeActionType.ARCHIVE, SwipeActionType.DELETE -> {
                                            pendingSwipeAction = conversation.guid to action
                                        }
                                        else -> viewModel.handleSwipeAction(conversation.guid, action)
                                    }
                                },
                                swipeConfig = conversationSwipeConfig,
                                modifier = Modifier
                                    .staggeredEntrance(index)
                                    .animateItem()
                            ) { hasRoundedCorners ->
                                GoogleStyleConversationTile(
                                    conversation = conversation,
                                    onClick = { onConversationClick(conversation.guid, conversation.mergedChatGuids) },
                                    onLongClick = { selectedConversations = selectedConversations + conversation.guid },
                                    hasRoundedCorners = hasRoundedCorners,
                                    onAvatarClick = {
                                        quickActionsContact = ContactInfo(
                                            chatGuid = conversation.guid,
                                            displayName = conversation.displayName,
                                            rawDisplayName = conversation.rawDisplayName,
                                            avatarPath = conversation.avatarPath,
                                            address = conversation.address,
                                            isGroup = conversation.isGroup,
                                            participantNames = conversation.participantNames,
                                            hasContact = conversation.hasContact,
                                            hasInferredName = conversation.hasInferredName
                                        )
                                    }
                                )
                            }
                        }
                    } // End of itemsIndexed

                    // Loading indicator when fetching more conversations
                    if (uiState.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                } // End of LazyColumn
                } // End of Column

                // Drag overlay - renders dragged pin on top of everything
                if (isPinDragging && draggedPinConversation != null) {
                    val conversation = draggedPinConversation!!
                    val unpinProgress = (draggedPinOffset.y / unpinThresholdPx).coerceIn(0f, 1f)
                    val scale = 1.08f - (unpinProgress * 0.15f)
                    val overlayAlpha = 1f - (unpinProgress * 0.5f)

                    // Calculate position relative to this container (not root)
                    val relativeX = draggedPinStartPosition.x - containerRootPosition.x
                    val relativeY = draggedPinStartPosition.y - containerRootPosition.y

                    Box(
                        modifier = Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    (relativeX + draggedPinOffset.x).toInt(),
                                    (relativeY + draggedPinOffset.y.coerceAtLeast(0f)).toInt()
                                )
                            }
                            .zIndex(100f)
                            .scale(scale)
                            .alpha(overlayAlpha)
                            .shadow(8.dp, RoundedCornerShape(12.dp))
                    ) {
                        // Render the pinned item visually
                        Column(
                            modifier = Modifier
                                .width(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            if (conversation.isGroup) {
                                GroupAvatar(
                                    names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                                    avatarPaths = conversation.participantAvatarPaths,
                                    size = 56.dp
                                )
                            } else {
                                Avatar(
                                    name = conversation.displayName,
                                    avatarPath = conversation.avatarPath,
                                    size = 56.dp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = conversation.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
                } // End of Box wrapper
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
        if (uiState.isSyncCorrupted) {
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
            // BlueBubbles sync progress bar (shows during initial/incremental sync)
            AnimatedVisibility(
                visible = uiState.isSyncing && !isSearchActive,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                SyncProgressBar(
                    progress = uiState.syncProgress ?: 0f,
                    stage = uiState.syncStage ?: "Syncing...",
                    totalChats = uiState.syncTotalChats,
                    processedChats = uiState.syncProcessedChats,
                    syncedMessages = uiState.syncedMessages,
                    isInitialSync = uiState.isInitialSync,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // SMS import progress bar
            AnimatedVisibility(
                visible = uiState.isImportingSms,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                SmsImportProgressBar(
                    progress = uiState.smsImportProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // SMS import error bar (shown when import fails)
            AnimatedVisibility(
                visible = uiState.smsImportError != null && !uiState.isImportingSms,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                uiState.smsImportError?.let { error ->
                    SmsImportErrorBar(
                        errorMessage = error,
                        onRetry = { viewModel.startSmsImport() },
                        onDismiss = { viewModel.dismissSmsImportError() },
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
