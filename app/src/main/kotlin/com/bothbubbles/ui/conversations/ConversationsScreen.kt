package com.bothbubbles.ui.conversations

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.R
import com.bothbubbles.services.categorization.MessageCategory
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.ui.components.Avatar
import com.bothbubbles.ui.components.ConnectionBannerState
import com.bothbubbles.ui.components.ConnectionStatusBanner
import com.bothbubbles.ui.components.SmsBannerState
import com.bothbubbles.ui.components.SmsStatusBanner
import com.bothbubbles.ui.components.ContactInfo
import com.bothbubbles.ui.components.ContactQuickActionsPopup
import com.bothbubbles.ui.components.GroupAvatar
import com.bothbubbles.ui.components.SnoozeDurationDialog
import com.bothbubbles.ui.components.SwipeActionType
import com.bothbubbles.ui.components.SwipeConfig
import com.bothbubbles.ui.components.SwipeableConversationTile
import com.bothbubbles.ui.components.ConversationListSkeleton
import com.bothbubbles.ui.components.staggeredEntrance
import com.bothbubbles.ui.components.UrlParsingUtils
import com.bothbubbles.ui.settings.SettingsContent
import com.bothbubbles.ui.settings.SettingsViewModel
import com.bothbubbles.ui.theme.KumbhSansFamily

// iMessage and SMS colors for split profile ring
private val iMessageBlue = Color(0xFF007AFF)
private val smsGreen = Color(0xFF34C759)

// Screen state for animated transitions
private enum class ConversationScreenState {
    LOADING, EMPTY, CONTENT
}

// Conversation list filter options (shown in top bar dropdown)
enum class ConversationFilter(val label: String, val icon: ImageVector) {
    ALL("All", Icons.Outlined.Inbox),
    UNREAD("Unread", Icons.Outlined.MarkChatUnread),
    SPAM("Spam", Icons.Outlined.Report),
    UNKNOWN_SENDERS("Unknown Senders", Icons.Outlined.PersonSearch),
    KNOWN_SENDERS("Known Senders", Icons.Outlined.Person)
}

// Search filter options
enum class SearchFilter(val label: String, val icon: ImageVector) {
    UNREAD("Unread", Icons.Outlined.MarkChatUnread),
    KNOWN("Known", Icons.Outlined.Person),
    UNKNOWN("Unknown", Icons.Outlined.Block),
    STARRED("Starred", Icons.Outlined.Star),
    IMAGES("Images", Icons.Outlined.Image),
    VIDEOS("Videos", Icons.Outlined.VideoLibrary),
    PLACES("Places", Icons.Outlined.Place),
    LINKS("Links", Icons.Outlined.Link)
}

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

    // Slightly darker background that shows behind the header and card
    val darkerBackground = MaterialTheme.colorScheme.surfaceContainerLow

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

/**
 * SMS import progress bar that shows at the bottom of the conversation list.
 * Displays a linear progress indicator with percentage text.
 */
@Composable
private fun SmsImportProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Sms,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Importing SMS messages...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

/**
 * BlueBubbles sync progress bar that shows during initial or incremental sync.
 * Displays current sync stage, progress percentage, and detailed counts for initial sync.
 */
@Composable
private fun SyncProgressBar(
    progress: Float,
    stage: String,
    totalChats: Int,
    processedChats: Int,
    syncedMessages: Int,
    isInitialSync: Boolean,
    modifier: Modifier = Modifier
) {
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = if (isInitialSync) "Importing BlueBubbles messages..." else stage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Show detailed counts for initial sync
                        if (isInitialSync && (totalChats > 0 || syncedMessages > 0)) {
                            Text(
                                text = buildString {
                                    if (totalChats > 0) {
                                        append("$processedChats/$totalChats chats")
                                    }
                                    if (syncedMessages > 0) {
                                        if (totalChats > 0) append(" • ")
                                        append("$syncedMessages messages")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

/**
 * SMS import error bar that shows when import fails.
 * Displays error message with retry and dismiss options.
 */
@Composable
private fun SmsImportErrorBar(
    errorMessage: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SMS import failed",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedFilter: SearchFilter?,
    onFilterSelected: (SearchFilter) -> Unit,
    onFilterCleared: () -> Unit,
    onClose: () -> Unit,
    conversations: List<ConversationUiModel>,
    messageSearchResults: List<MessageSearchResult>,
    onConversationClick: (chatGuid: String, mergedGuids: List<String>) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Search bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Filter tag (shown when a filter is selected)
                    if (selectedFilter != null) {
                        SearchFilterTag(
                            filter = selectedFilter,
                            onRemove = onFilterCleared
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box {
                                if (query.isEmpty() && selectedFilter == null) {
                                    Text(
                                        text = "Search messages",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 16.sp
                                    )
                                } else if (query.isEmpty() && selectedFilter != null) {
                                    Text(
                                        text = "Search in ${selectedFilter.label.lowercase()}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 16.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (query.isNotEmpty() || selectedFilter != null) {
                        IconButton(onClick = {
                            onQueryChange("")
                            onFilterCleared()
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Filter chips grid (shown when no query and no filter selected)
            if (query.isEmpty() && selectedFilter == null) {
                SearchFilterGrid(
                    selectedFilter = selectedFilter,
                    onFilterSelected = onFilterSelected,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                // Search results (filtered by both query and filter type)
                val filteredConversations = conversations.filter { conv ->
                    val matchesQuery = query.isEmpty() ||
                        conv.displayName.contains(query, ignoreCase = true) ||
                        conv.address.contains(query, ignoreCase = true) ||
                        // Also search link preview titles for conversations with links
                        (conv.lastMessageLinkTitle?.contains(query, ignoreCase = true) == true)

                    val matchesFilter = when (selectedFilter) {
                        SearchFilter.UNREAD -> conv.unreadCount > 0
                        SearchFilter.KNOWN -> !conv.displayName.contains("@") &&
                            !conv.displayName.matches(Regex("^[+\\d\\s()-]+$"))
                        SearchFilter.UNKNOWN -> conv.displayName.contains("@") ||
                            conv.displayName.matches(Regex("^[+\\d\\s()-]+$"))
                        SearchFilter.STARRED -> conv.isPinned // Using pinned as proxy for starred
                        SearchFilter.IMAGES -> conv.lastMessageType == MessageType.IMAGE
                        SearchFilter.VIDEOS -> conv.lastMessageType == MessageType.VIDEO
                        SearchFilter.PLACES -> conv.lastMessageLinkDomain?.let { domain ->
                            // Check if the domain matches any known places/maps service
                            val placesPatterns = listOf(
                                "maps.google", "google.com/maps", "maps.app.goo.gl",
                                "goo.gl/maps", "maps.apple.com", "findmy.apple.com",
                                "find-my.apple.com"
                            )
                            placesPatterns.any { domain.lowercase().contains(it) }
                        } == true
                        SearchFilter.LINKS -> conv.lastMessageType == MessageType.LINK
                        null -> true
                    }

                    matchesQuery && matchesFilter
                }

                // Filter message results to exclude conversations already shown
                val conversationGuids = filteredConversations.map { it.guid }.toSet()
                val filteredMessageResults = if (selectedFilter == null) {
                    messageSearchResults.filter { it.chatGuid !in conversationGuids }
                } else {
                    emptyList() // Don't show message results when a filter is selected
                }

                val hasResults = filteredConversations.isNotEmpty() || filteredMessageResults.isNotEmpty()

                if (!hasResults) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = selectedFilter?.icon ?: Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = if (selectedFilter != null) {
                                    "No ${selectedFilter.label.lowercase()} found"
                                } else {
                                    "No results found"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn {
                        // Conversation matches (name/number matches)
                        if (filteredConversations.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Conversations",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(
                                items = filteredConversations,
                                key = { it.guid }
                            ) { conversation ->
                                GoogleStyleConversationTile(
                                    conversation = conversation,
                                    onClick = { onConversationClick(conversation.guid, conversation.mergedChatGuids) },
                                    onLongClick = { }
                                )
                            }
                        }

                        // Message content matches - reuse GoogleStyleConversationTile
                        if (filteredMessageResults.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Messages",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(
                                items = filteredMessageResults,
                                key = { it.messageGuid }
                            ) { result ->
                                // Look up conversation to get merged guids
                                val conversation = conversations.find { it.guid == result.chatGuid }
                                val mergedGuids = conversation?.mergedChatGuids ?: listOf(result.chatGuid)
                                GoogleStyleConversationTile(
                                    conversation = result.toConversationUiModel(),
                                    onClick = { onConversationClick(result.chatGuid, mergedGuids) },
                                    onLongClick = { } // No long-press actions for search results
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilterTag(
    filter: SearchFilter,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                filter.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = filter.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove filter",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun SearchFilterGrid(
    selectedFilter: SearchFilter?,
    onFilterSelected: (SearchFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = SearchFilter.entries

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Create 2-column grid
        filters.chunked(2).forEach { rowFilters ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowFilters.forEach { filter ->
                    SearchFilterChip(
                        filter = filter,
                        isSelected = selectedFilter == filter,
                        onClick = { onFilterSelected(filter) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill empty space if odd number
                if (rowFilters.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SearchFilterChip(
    filter: SearchFilter,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.height(52.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                filter.icon,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = filter.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun ProfileAvatarWithRing(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    userAvatarPath: String? = null,
    userName: String? = null
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iMessageBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            // Always show placeholder first
            if (userName != null) {
                val initials = userName.split(" ")
                    .filter { it.isNotBlank() }
                    .take(2)
                    .joinToString("") { it.first().uppercase() }
                Text(
                    text = initials.ifEmpty { "?" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = "Profile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Overlay with profile photo if available
            if (userAvatarPath != null) {
                val avatarUri = remember(userAvatarPath) { android.net.Uri.parse(userAvatarPath) }
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(avatarUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Profile",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }
    }
}

@Composable
private fun SelectionModeHeader(
    selectedCount: Int,
    totalSelectableCount: Int,
    majorityUnread: Boolean, // true if majority of selected conversations are unread
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPin: () -> Unit,
    onSnooze: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onBlock: () -> Unit,
    onAddContact: (() -> Unit)? = null, // null means "Add contact" option should be hidden
    isPinEnabled: Boolean = true, // false when none of the selected conversations can be pinned
    modifier: Modifier = Modifier
) {
    val allSelected = selectedCount >= totalSelectableCount && totalSelectableCount > 0
    var showMoreMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Close button
        IconButton(onClick = onClose) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Cancel selection",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // Selection count
        Text(
            text = selectedCount.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        IconButton(
            onClick = onPin,
            enabled = isPinEnabled
        ) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = "Pin",
                tint = if (isPinEnabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }

        IconButton(onClick = onSnooze) {
            Icon(
                Icons.Default.Snooze,
                contentDescription = "Snooze",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onArchive) {
            Icon(
                Icons.Outlined.Archive,
                contentDescription = "Archive",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // More options with dropdown
        Box {
            IconButton(onClick = { showMoreMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false }
            ) {
                // Select all / Deselect all option
                DropdownMenuItem(
                    text = { Text(if (allSelected) "Deselect all" else "Select all") },
                    onClick = {
                        showMoreMenu = false
                        onSelectAll()
                    }
                )

                // Dynamic mark as read/unread based on majority state
                DropdownMenuItem(
                    text = { Text(if (majorityUnread) "Mark as read" else "Mark as unread") },
                    onClick = {
                        showMoreMenu = false
                        if (majorityUnread) onMarkAsRead() else onMarkAsUnread()
                    }
                )

                // Show "Add contact" only for single selection without existing contact
                if (onAddContact != null) {
                    DropdownMenuItem(
                        text = { Text("Add contact") },
                        onClick = {
                            showMoreMenu = false
                            onAddContact()
                        }
                    )
                }

                DropdownMenuItem(
                    text = { Text("Block") },
                    onClick = {
                        showMoreMenu = false
                        onBlock()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GoogleStyleConversationTile(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    hasRoundedCorners: Boolean = false,
    onAvatarClick: (() -> Unit)? = null
) {
    val shape = when {
        isSelectionMode -> RoundedCornerShape(50)
        hasRoundedCorners -> RoundedCornerShape(16.dp)
        else -> RoundedCornerShape(0.dp)
    }
    val needsPadding = isSelectionMode || hasRoundedCorners

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (needsPadding) Modifier.padding(vertical = 4.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        color = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with selection checkmark or regular avatar
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .then(
                        if (onAvatarClick != null && !isSelected) {
                            Modifier.combinedClickable(
                                onClick = onAvatarClick,
                                onLongClick = onAvatarClick
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (isSelected) {
                    // Show checkmark when selected - use muted color instead of saturated primary
                    Surface(
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                } else {
                    if (conversation.isGroup) {
                        GroupAvatar(
                            names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                            avatarPaths = conversation.participantAvatarPaths,
                            size = 56.dp
                        )
                    } else {
                        Avatar(
                            name = conversation.rawDisplayName,
                            avatarPath = conversation.avatarPath,
                            size = 56.dp
                        )
                    }

                    // Typing indicator badge
                    if (conversation.isTyping) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(20.dp)
                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            ) {
                                AnimatedTypingDots()
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Name row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatDisplayName(conversation.displayName),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (conversation.unreadCount > 0) FontWeight.ExtraBold else FontWeight.Normal
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (conversation.isMuted) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Muted",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (conversation.isSnoozed) {
                        Icon(
                            Icons.Default.Snooze,
                            contentDescription = "Snoozed",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // "is typing..." indicator text under the name
                if (conversation.isTyping) {
                    Text(
                        text = "is typing...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = FontStyle.Italic
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Message preview with inline status indicator
                val textColor = when {
                    conversation.hasDraft -> MaterialTheme.colorScheme.error
                    conversation.unreadCount > 0 -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                }

                val showInlineStatus = conversation.isFromMe &&
                    conversation.lastMessageStatus != MessageStatus.NONE &&
                    conversation.unreadCount == 0 &&
                    !conversation.isTyping

                val previewText = formatMessagePreview(conversation)
                val annotatedText = buildAnnotatedString {
                    append(previewText)
                    if (showInlineStatus) {
                        append(" ")
                        appendInlineContent("status", "[status]")
                    }
                }

                val inlineContent = if (showInlineStatus) {
                    mapOf(
                        "status" to InlineTextContent(
                            placeholder = Placeholder(
                                width = 14.sp,
                                height = 14.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                            )
                        ) {
                            InlineMessageStatusIcon(
                                status = conversation.lastMessageStatus,
                                tint = textColor
                            )
                        }
                    )
                } else {
                    emptyMap()
                }

                Text(
                    text = annotatedText,
                    inlineContent = inlineContent,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                        lineHeight = 18.sp
                    ),
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Trailing content - timestamp and unread badge
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Draft or timestamp
                if (conversation.hasDraft) {
                    Text(
                        text = "Draft",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = conversation.lastMessageTime,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Unread badge only - status indicator is now inline with text
                if (conversation.unreadCount > 0) {
                    UnreadBadge(count = conversation.unreadCount)
                }
            }
        }
    }
}

@Composable
private fun AnimatedTypingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "typingDots")

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(2.dp)
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1200
                        val delay = index * 150
                        0.3f at delay using LinearEasing
                        1f at (delay + 200) using FastOutSlowInEasing
                        0.3f at (delay + 400) using FastOutSlowInEasing
                        0.3f at 1200 using LinearEasing
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "dotFade$index"
            )

            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

/**
 * Inline message status icon for use in text with InlineTextContent.
 * Uses clock icon for sending, checkmarks for sent/delivered/read.
 */
@Composable
private fun InlineMessageStatusIcon(
    status: MessageStatus,
    tint: Color,
    modifier: Modifier = Modifier
) {
    when (status) {
        MessageStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Sending",
                tint = tint,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = tint,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered",
                tint = tint,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.fillMaxSize()
            )
        }
        MessageStatus.NONE -> {
            // No icon for NONE status
        }
    }
}

@Composable
private fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = CircleShape,
        modifier = modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }
}

@Composable
private fun PinnedConversationsRow(
    conversations: List<ConversationUiModel>,
    onConversationClick: (ConversationUiModel) -> Unit,
    onConversationLongClick: (String) -> Unit = {}, // Kept for compatibility but unused - drag gesture handles long press
    onUnpin: (String) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
    onAvatarClick: (ConversationUiModel) -> Unit = {},
    selectedConversations: Set<String> = emptySet(),
    isSelectionMode: Boolean = false,
    onDragOverlayStart: (ConversationUiModel, Offset) -> Unit = { _, _ -> },
    onDragOverlayMove: (Offset) -> Unit = {},
    onDragOverlayEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Item width including spacing (100dp item + 12dp spacing)
    val itemWidth = 112.dp
    val density = LocalDensity.current
    val itemWidthPx = with(density) { itemWidth.toPx() }

    // Threshold for drag-to-unpin (drag downward past this to unpin)
    val unpinThresholdPx = with(density) { 60.dp.toPx() }

    // Drag state
    var draggedItemIndex by remember { mutableIntStateOf(-1) }
    var draggedItemGuid by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Track item positions for overlay
    val itemPositions = remember { mutableStateMapOf<String, Offset>() }

    // Mutable list for reordering during drag
    var currentOrder by remember(conversations) { mutableStateOf(conversations.map { it.guid }) }

    // Reset order when conversations change
    LaunchedEffect(conversations) {
        currentOrder = conversations.map { it.guid }
    }

    // Map guid to conversation for lookup
    val conversationMap = remember(conversations) { conversations.associateBy { it.guid } }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        userScrollEnabled = !isDragging
    ) {
        itemsIndexed(
            items = currentOrder,
            key = { _, guid -> guid }
        ) { index, guid ->
            val conversation = conversationMap[guid] ?: return@itemsIndexed

            val isBeingDragged = index == draggedItemIndex && isDragging

            // Calculate visual offset for dragged item
            val offsetX = if (isBeingDragged) dragOffsetX else 0f
            val offsetY = if (isBeingDragged) dragOffsetY.coerceAtLeast(0f) else 0f

            // Calculate progress toward unpin threshold (0 to 1)
            val unpinProgress = if (isBeingDragged) (dragOffsetY / unpinThresholdPx).coerceIn(0f, 1f) else 0f

            // Scale up dragged item for visual feedback, reduce when approaching unpin
            val scale by animateFloatAsState(
                targetValue = if (isBeingDragged) 1.08f - (unpinProgress * 0.15f) else 1f,
                animationSpec = tween(150),
                label = "dragScale"
            )

            // Fade out when approaching unpin threshold
            val alpha by animateFloatAsState(
                targetValue = if (isBeingDragged) 1f - (unpinProgress * 0.5f) else 1f,
                animationSpec = tween(100),
                label = "dragAlpha"
            )

            // Elevation for shadow effect during drag
            val elevation by animateDpAsState(
                targetValue = if (isBeingDragged) 8.dp else 0.dp,
                animationSpec = tween(150),
                label = "dragElevation"
            )

            Box(
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        itemPositions[guid] = coordinates.positionInRoot()
                    }
                    .zIndex(if (isBeingDragged) 1f else 0f)
                    .offset { androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()) }
                    .scale(scale)
                    // Make invisible when being dragged (overlay handles rendering)
                    .alpha(if (isBeingDragged) 0f else alpha)
                    .shadow(elevation, RoundedCornerShape(12.dp))
            ) {
                PinnedConversationItem(
                    conversation = conversation,
                    onClick = { if (!isDragging) onConversationClick(conversation) },
                    onAvatarClick = { onAvatarClick(conversation) },
                    isSelected = conversation.guid in selectedConversations,
                    isSelectionMode = isSelectionMode,
                    isDragging = isBeingDragged,
                    onDragStart = {
                        if (!isSelectionMode) {
                            draggedItemIndex = index
                            draggedItemGuid = guid
                            isDragging = true
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            // Notify overlay with initial position
                            val position = itemPositions[guid] ?: Offset.Zero
                            onDragOverlayStart(conversation, position)
                        }
                    },
                    onDrag = { dragAmountX, dragAmountY ->
                        if (isDragging && draggedItemIndex >= 0) {
                            dragOffsetX += dragAmountX
                            dragOffsetY += dragAmountY

                            // Update overlay position
                            onDragOverlayMove(Offset(dragOffsetX, dragOffsetY))

                            // Calculate if we should swap with neighbor (horizontal reordering)
                            val draggedPosition = draggedItemIndex
                            val offsetInItems = (dragOffsetX / itemWidthPx).toInt()
                            val newPosition = (draggedPosition + offsetInItems).coerceIn(0, currentOrder.size - 1)

                            if (newPosition != draggedPosition) {
                                // Swap items in current order
                                val mutableList = currentOrder.toMutableList()
                                val item = mutableList.removeAt(draggedPosition)
                                mutableList.add(newPosition, item)
                                currentOrder = mutableList

                                // Update dragged index and reset offset for smooth movement
                                draggedItemIndex = newPosition
                                dragOffsetX -= offsetInItems * itemWidthPx
                                // Update overlay offset after swap
                                onDragOverlayMove(Offset(dragOffsetX, dragOffsetY))
                            }
                        }
                    },
                    onDragEnd = {
                        if (isDragging && draggedItemIndex >= 0) {
                            // Check if dragged past unpin threshold (downward)
                            if (dragOffsetY >= unpinThresholdPx) {
                                draggedItemGuid?.let { onUnpin(it) }
                            } else {
                                // Only call onReorder if the order actually changed
                                val originalOrder = conversations.map { it.guid }
                                if (currentOrder != originalOrder) {
                                    onReorder(currentOrder)
                                }
                            }
                        }
                        onDragOverlayEnd()
                        isDragging = false
                        draggedItemIndex = -1
                        draggedItemGuid = null
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    },
                    onDragCancel = {
                        // Reset to original order on cancel
                        currentOrder = conversations.map { it.guid }
                        onDragOverlayEnd()
                        isDragging = false
                        draggedItemIndex = -1
                        draggedItemGuid = null
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedConversationItem(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit = {},
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    isDragging: Boolean = false,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(100.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(isSelectionMode) {
                    if (!isSelectionMode) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragCancel() }
                        )
                    }
                }
                .clickable(enabled = !isDragging) { onClick() }
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        // Avatar with selection checkmark
        // Outer Box sized for avatar + badge overflow (72dp + 4dp)
        Box(modifier = Modifier.size(76.dp)) {
            // Avatar content
            Box(
                modifier = Modifier
                    .size(76.dp) // Size includes badge overflow
                    .clickable(enabled = !isDragging) { onClick() }
            ) {
                if (isSelected) {
                    // Show checkmark when selected - use muted color
                    Surface(
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                } else {
                    if (conversation.isGroup) {
                        GroupAvatar(
                            names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                            avatarPaths = conversation.participantAvatarPaths,
                            size = 72.dp
                        )
                    } else {
                        Avatar(
                            name = conversation.displayName,
                            avatarPath = conversation.avatarPath,
                            size = 72.dp
                        )
                    }
                }
            }

            // Typing indicator
            if (conversation.isTyping && !isSelected) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-2).dp, y = 2.dp)
                        .size(20.dp)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AnimatedTypingDots()
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(6.dp))

            // Name row with unread badge to the left
            val formattedName = formatDisplayName(conversation.displayName)
            val hasUnread = conversation.unreadCount > 0 && !isSelected

            Row(
                modifier = Modifier.widthIn(max = 92.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Unread badge to the left of the name
                if (hasUnread) {
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Text(
                    text = formattedName,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun EmptyConversationsState(
    modifier: Modifier = Modifier,
    isSearching: Boolean = false
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isSearching) Icons.Default.SearchOff else Icons.AutoMirrored.Filled.Message,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = if (isSearching) "No conversations found" else stringResource(R.string.no_conversations),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isSearching) {
                Text(
                    text = "Start a conversation to get chatting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun EmptyFilterState(
    filter: ConversationFilter,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = filter.icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = when (filter) {
                    ConversationFilter.ALL -> "No conversations"
                    ConversationFilter.UNREAD -> "No unread messages"
                    ConversationFilter.SPAM -> "No spam messages"
                    ConversationFilter.UNKNOWN_SENDERS -> "No unknown senders"
                    ConversationFilter.KNOWN_SENDERS -> "No known senders"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onClearFilter) {
                Text("Show all conversations")
            }
        }
    }
}

@Composable
private fun EmptyCategoryState(
    category: MessageCategory,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = "No ${category.displayName.lowercase()}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = category.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            TextButton(onClick = onClearFilter) {
                Text("Show all conversations")
            }
        }
    }
}

private fun formatMessagePreview(conversation: ConversationUiModel): String {
    // Show typing indicator if someone is typing
    if (conversation.isTyping) {
        return "typing..."
    }

    // Check invisible ink first - hide actual content
    if (conversation.isInvisibleInk) {
        val content = if (conversation.lastMessageType in listOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.LIVE_PHOTO))
            "Image sent with Invisible Ink"
        else
            "Message sent with Invisible Ink"
        return formatWithSenderPrefix(conversation, content)
    }

    val content = when (conversation.lastMessageType) {
        MessageType.DELETED -> "Message deleted"
        MessageType.REACTION -> formatReactionPreview(conversation.reactionPreviewData)
        MessageType.GROUP_EVENT -> conversation.groupEventText ?: "Group updated"
        MessageType.STICKER -> "Sticker"
        MessageType.CONTACT -> "Contact"
        MessageType.LIVE_PHOTO -> "Live Photo"
        MessageType.VOICE_MESSAGE -> "Voice message"
        MessageType.DOCUMENT -> conversation.documentType ?: "Document"
        MessageType.LOCATION -> "Location"
        MessageType.APP_MESSAGE -> "App message"
        MessageType.IMAGE -> formatAttachmentCount(conversation.attachmentCount, "Photo")
        MessageType.VIDEO -> formatAttachmentCount(conversation.attachmentCount, "Video")
        MessageType.AUDIO -> "Audio"
        MessageType.LINK -> formatLinkPreview(conversation)
        MessageType.ATTACHMENT -> "Attachment"
        MessageType.TEXT -> conversation.lastMessageText
    }

    return formatWithSenderPrefix(conversation, content)
}

/**
 * Add sender prefix ("You:" or "Name:") to preview content
 */
private fun formatWithSenderPrefix(conversation: ConversationUiModel, content: String): String {
    return when {
        conversation.isFromMe -> "You: $content"
        // For group chats, show sender's first name
        conversation.isGroup && conversation.lastMessageSenderName != null ->
            "${conversation.lastMessageSenderName}: $content"
        else -> content
    }
}

/**
 * Format reaction preview text: "Liked 'original message...'"
 */
private fun formatReactionPreview(data: ReactionPreviewData?): String {
    if (data == null) return "Reacted to a message"
    val target = when {
        data.originalText != null -> {
            val truncated = data.originalText
            val ellipsis = if (truncated.length >= 30) "..." else ""
            "\"$truncated$ellipsis\""
        }
        data.hasAttachment -> "an attachment"
        else -> "a message"
    }
    return "${data.tapbackVerb} $target"
}

/**
 * Format attachment count: "Photo" for 1, "2 Photos" for multiple
 */
private fun formatAttachmentCount(count: Int, singular: String): String {
    return when {
        count <= 1 -> singular
        else -> "$count ${singular}s"
    }
}

/**
 * Formats a link preview for display in the conversation list.
 * Includes any text surrounding the link, with the link portion replaced by
 * the preview title/domain when available.
 */
private fun formatLinkPreview(conversation: ConversationUiModel): String {
    val title = conversation.lastMessageLinkTitle
    val domain = conversation.lastMessageLinkDomain
    val messageText = conversation.lastMessageText

    // Get the link representation (title, domain, or raw URL)
    val linkDisplay = when {
        title != null && domain != null -> "$title ($domain)"
        title != null -> title
        domain != null -> domain
        else -> null
    }

    // If we have no link display info, just return the message text
    if (linkDisplay == null) {
        return messageText.take(100)
    }

    // Find the URL position in the original text
    val detectedUrl = UrlParsingUtils.getFirstUrl(messageText)
    if (detectedUrl == null) {
        return linkDisplay
    }

    // Extract text before and after the URL
    val textBefore = messageText.substring(0, detectedUrl.startIndex).trim()
    val textAfter = messageText.substring(detectedUrl.endIndex).trim()

    // Build the preview with surrounding text
    return buildString {
        if (textBefore.isNotEmpty()) {
            append(textBefore)
            append(" ")
        }
        append(linkDisplay)
        if (textAfter.isNotEmpty()) {
            append(" ")
            append(textAfter)
        }
    }
}

/**
 * Formats a phone number for pretty display.
 * Handles various formats and converts to (XXX) XXX-XXXX for US numbers.
 */
private fun formatPhoneNumber(input: String): String {
    // Remove all non-digit characters
    val digits = input.replace(Regex("[^0-9]"), "")

    return when {
        // US number with country code: +1XXXXXXXXXX or 1XXXXXXXXXX
        digits.length == 11 && digits.startsWith("1") -> {
            val areaCode = digits.substring(1, 4)
            val prefix = digits.substring(4, 7)
            val lineNumber = digits.substring(7, 11)
            "($areaCode) $prefix-$lineNumber"
        }
        // US number without country code: XXXXXXXXXX
        digits.length == 10 -> {
            val areaCode = digits.substring(0, 3)
            val prefix = digits.substring(3, 6)
            val lineNumber = digits.substring(6, 10)
            "($areaCode) $prefix-$lineNumber"
        }
        // 7-digit local number: XXX-XXXX
        digits.length == 7 -> {
            val prefix = digits.substring(0, 3)
            val lineNumber = digits.substring(3, 7)
            "$prefix-$lineNumber"
        }
        // Short codes (5-6 digits): leave as-is
        digits.length in 5..6 -> digits
        // Other formats: return original if it looks like a number, otherwise return as-is
        else -> input
    }
}

/**
 * Formats a display name, applying phone number formatting if it looks like a phone number.
 */
private fun formatDisplayName(name: String): String {
    // Check if the name looks like a phone number (mostly digits with optional +, -, (), spaces)
    val stripped = name.replace(Regex("[+\\-()\\s]"), "")
    return if (stripped.all { it.isDigit() } && stripped.length >= 5) {
        formatPhoneNumber(name)
    } else {
        name
    }
}

@Composable
private fun PullToSearchIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Animated search icon that rotates/scales as you pull
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(progress * 360f),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = progress)
            )

            AnimatedVisibility(visible = progress > 0.3f) {
                Text(
                    text = if (progress >= 1f) "Release to search" else "Pull to search",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = progress)
                )
            }
        }
    }
}

/**
 * Scroll to top button that appears when scrolled down.
 * Uses MD3 SmallFloatingActionButton with reduced elevation and theme-aware colors.
 */
@Composable
private fun ScrollToTopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = if (isLightTheme) Color.White else Color(0xFF3C3C3C),
        contentColor = if (isLightTheme) Color(0xFF1C1C1C) else Color.White,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp,
            hoveredElevation = 3.dp,
            focusedElevation = 3.dp
        )
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = "Scroll to top"
        )
    }
}

/**
 * Settings panel that slides in from the right.
 * Contains all settings options with a close button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPanel(
    onClose: () -> Unit,
    onNavigate: (String, Boolean) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    actions = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close settings"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            val navigateFromDrawer: (String) -> Unit = { destination ->
                onClose()
                onNavigate(destination, true)
            }

            SettingsContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                uiState = uiState,
                onServerSettingsClick = { navigateFromDrawer("server") },
                onArchivedClick = { navigateFromDrawer("archived") },
                onBlockedClick = { navigateFromDrawer("blocked") },
                onSpamClick = { navigateFromDrawer("spam") },
                onCategorizationClick = { navigateFromDrawer("categorization") },
                onSyncSettingsClick = { navigateFromDrawer("sync") },
                onExportClick = { navigateFromDrawer("export") },
                onSmsSettingsClick = { navigateFromDrawer("sms") },
                onNotificationsClick = { navigateFromDrawer("notifications") },
                onSwipeSettingsClick = { navigateFromDrawer("swipe") },
                onEffectsSettingsClick = { navigateFromDrawer("effects") },
                onTemplatesClick = { navigateFromDrawer("templates") },
                onAboutClick = { navigateFromDrawer("about") },
                viewModel = viewModel
            )
        }
    }
}
