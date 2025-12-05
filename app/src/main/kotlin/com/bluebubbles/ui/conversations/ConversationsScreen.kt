package com.bluebubbles.ui.conversations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluebubbles.R
import com.bluebubbles.services.socket.ConnectionState
import com.bluebubbles.ui.components.Avatar
import com.bluebubbles.ui.components.ConnectionBannerState
import com.bluebubbles.ui.components.ConnectionStatusBanner
import com.bluebubbles.ui.components.ContactInfo
import com.bluebubbles.ui.components.ContactQuickActionsPopup
import com.bluebubbles.ui.components.GroupAvatar
import com.bluebubbles.ui.components.SwipeActionType
import com.bluebubbles.ui.components.SwipeConfig
import com.bluebubbles.ui.components.SwipeableConversationTile
import com.bluebubbles.ui.components.MessageStatusIndicator
import com.bluebubbles.ui.settings.SettingsViewModel
import com.bluebubbles.ui.settings.components.ProfileHeader
import com.bluebubbles.ui.settings.components.SettingsCard
import com.bluebubbles.ui.settings.components.SettingsMenuItem
import com.bluebubbles.ui.settings.components.SettingsSectionTitle
import com.bluebubbles.ui.theme.KumbhSansFamily

// iMessage and SMS colors for split profile ring
private val iMessageBlue = Color(0xFF007AFF)
private val smsGreen = Color(0xFF34C759)

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
    onConversationClick: (String) -> Unit,
    onNewMessageClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSettingsNavigate: (String) -> Unit = {},
    onSetupServerClick: () -> Unit = {},
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Determine if connection banner should be visible (for padding calculation)
    val isBannerVisible = when (uiState.connectionBannerState) {
        is ConnectionBannerState.NotConfigured,
        is ConnectionBannerState.Reconnecting -> true
        else -> false
    }
    // Banner height for padding (approximate: 56dp content + 16dp padding + nav bar)
    val bannerPadding = if (isBannerVisible) 72.dp else 0.dp

    var isSearchActive by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf<SearchFilter?>(null) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Settings panel state
    var isSettingsOpen by remember { mutableStateOf(false) }

    // Pull-to-search state
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
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

    // Contact quick actions popup state
    var quickActionsContact by remember { mutableStateOf<ContactInfo?>(null) }

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

                            val context = LocalContext.current

                            // Selection mode header
                            SelectionModeHeader(
                                selectedCount = selectedConversations.size,
                                onClose = { selectedConversations = emptySet() },
                                onPin = {
                                    selectedConversations.forEach { viewModel.togglePin(it) }
                                    selectedConversations = emptySet()
                                },
                                onSnooze = { /* TODO: Implement snooze */ },
                                onArchive = {
                                    selectedConversations.forEach { viewModel.archiveChat(it) }
                                    selectedConversations = emptySet()
                                },
                                onDelete = {
                                    selectedConversations.forEach { viewModel.deleteChat(it) }
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
                            // Main header row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
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

                                // Profile avatar with split ring
                                ProfileAvatarWithRing(
                                    onClick = { isSettingsOpen = true },
                                    userAvatarPath = uiState.userProfileAvatarUri,
                                    userName = uiState.userProfileName
                                )
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                // MD3 Extended FAB with animated expansion
                // Uses standard MD3 shape (16dp corner radius) and elevation
                // The `expanded` parameter animates the text label with a slide transition
                ExtendedFloatingActionButton(
                    onClick = onNewMessageClick,
                    expanded = isFabExpanded,
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
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.conversations.isEmpty() -> {
                EmptyConversationsState(
                    modifier = Modifier
                        .fillMaxSize(),
                    isSearching = uiState.searchQuery.isNotBlank()
                )
            }
            else -> {
                val pinnedConversations = uiState.conversations.filter { it.isPinned }
                val regularConversations = uiState.conversations.filter { !it.isPinned }

                // Animated pull indicator offset
                val animatedPullOffset by animateFloatAsState(
                    targetValue = pullOffset,
                    label = "pullOffset"
                )

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
                                onConversationClick = { guid ->
                                    // In selection mode, pinned items just navigate (not selectable)
                                    onConversationClick(guid)
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
                                onAvatarClick = { conversation ->
                                    // Disable avatar popup in selection mode
                                    if (!isSelectionMode) {
                                        quickActionsContact = ContactInfo(
                                            chatGuid = conversation.guid,
                                            displayName = conversation.displayName,
                                            avatarPath = conversation.avatarPath,
                                            address = conversation.address,
                                            isGroup = conversation.isGroup,
                                            participantNames = conversation.participantNames,
                                            hasContact = conversation.hasContact
                                        )
                                    }
                                },
                                selectedConversations = emptySet(), // Never show selection state for pinned items
                                isSelectionMode = isSelectionMode,
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

                    // Regular conversations
                    items(
                        items = displayConversations,
                        key = { it.guid }
                    ) { conversation ->
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
                                modifier = Modifier.animateItemPlacement()
                            )
                        } else {
                            // Use swipeable tile when not in selection mode
                            // Modify swipe config to disable PIN for unsaved contacts that aren't already pinned
                            val conversationSwipeConfig = if (!conversation.hasContact && !conversation.isPinned) {
                                // Disable PIN action for unsaved contacts
                                uiState.swipeConfig.copy(
                                    leftAction = if (uiState.swipeConfig.leftAction == SwipeActionType.PIN) SwipeActionType.NONE else uiState.swipeConfig.leftAction,
                                    rightAction = if (uiState.swipeConfig.rightAction == SwipeActionType.PIN) SwipeActionType.NONE else uiState.swipeConfig.rightAction
                                )
                            } else {
                                uiState.swipeConfig
                            }
                            SwipeableConversationTile(
                                title = formatDisplayName(conversation.displayName),
                                subtitle = formatMessagePreview(conversation),
                                timestamp = conversation.lastMessageTime,
                                unreadCount = conversation.unreadCount,
                                isPinned = conversation.isPinned,
                                isMuted = conversation.isMuted,
                                isTyping = conversation.isTyping,
                                messageStatus = conversation.lastMessageStatus,
                                avatarContent = {
                                    if (conversation.isGroup) {
                                        GroupAvatar(
                                            names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                                            size = 56.dp
                                        )
                                    } else {
                                        Avatar(
                                            name = conversation.displayName,
                                            avatarPath = conversation.avatarPath,
                                            size = 56.dp
                                        )
                                    }
                                },
                                onClick = { onConversationClick(conversation.guid) },
                                onLongClick = {
                                    selectedConversations = selectedConversations + conversation.guid
                                },
                                onSwipeAction = { action ->
                                    viewModel.handleSwipeAction(conversation.guid, action)
                                },
                                onAvatarClick = {
                                    quickActionsContact = ContactInfo(
                                        chatGuid = conversation.guid,
                                        displayName = conversation.displayName,
                                        avatarPath = conversation.avatarPath,
                                        address = conversation.address,
                                        isGroup = conversation.isGroup,
                                        participantNames = conversation.participantNames,
                                        hasContact = conversation.hasContact
                                    )
                                },
                                swipeConfig = conversationSwipeConfig,
                                modifier = Modifier.animateItemPlacement()
                            )
                        }
                    }
                    }
                }
            }
        }
            }
        }

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
                onConversationClick = { guid ->
                    onConversationClick(guid)
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
                contactInfo = contact,
                onDismiss = { quickActionsContact = null },
                onMessageClick = {
                    onConversationClick(contact.chatGuid)
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

        // Connection status banner at the bottom
        ConnectionStatusBanner(
            state = uiState.connectionBannerState,
            onSetupClick = {
                // Navigate to server settings
                onSettingsNavigate("server")
            },
            onDismiss = {
                viewModel.dismissSetupBanner()
            },
            onRetryClick = {
                viewModel.retryConnection()
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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
    onConversationClick: (String) -> Unit,
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
                        conv.address.contains(query, ignoreCase = true)

                    val matchesFilter = when (selectedFilter) {
                        SearchFilter.UNREAD -> conv.unreadCount > 0
                        SearchFilter.KNOWN -> !conv.displayName.contains("@") &&
                            !conv.displayName.matches(Regex("^[+\\d\\s()-]+$"))
                        SearchFilter.UNKNOWN -> conv.displayName.contains("@") ||
                            conv.displayName.matches(Regex("^[+\\d\\s()-]+$"))
                        SearchFilter.STARRED -> conv.isPinned // Using pinned as proxy for starred
                        SearchFilter.IMAGES -> conv.lastMessageType == MessageType.IMAGE
                        SearchFilter.VIDEOS -> conv.lastMessageType == MessageType.VIDEO
                        SearchFilter.PLACES -> false // TODO: Implement places filter
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
                                    onClick = { onConversationClick(conversation.guid) },
                                    onLongClick = { }
                                )
                            }
                        }

                        // Message content matches
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
                                MessageSearchResultTile(
                                    result = result,
                                    onClick = { onConversationClick(result.chatGuid) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Message search result tile styled like a compact conversation tile.
 * Uses the same visual style as GoogleStyleConversationTile but with:
 * - Smaller avatar (40dp vs 56dp)
 * - No swipe/long-press actions
 * - Same phone number formatting
 * - Same date formatting as conversation list
 */
@Composable
private fun MessageSearchResultTile(
    result: MessageSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar (compact size)
            Avatar(
                name = result.chatDisplayName,
                avatarPath = null,
                size = 40.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Chat name with phone number formatting
                Text(
                    text = formatDisplayName(result.chatDisplayName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Message preview
                Text(
                    text = if (result.isFromMe) "You: ${result.messageText}" else result.messageText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Timestamp - same format as conversation list
            Text(
                text = formatRelativeTime(result.timestamp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Formats timestamp for search results using the same logic as conversation list.
 * - Today: Time in device format (12h/24h)
 * - Within 7 days: Day abbreviation (Mon, Tue, etc.)
 * - Same year: Month and day (Jan 15)
 * - Different year: Short date (1/15/24)
 */
@Composable
private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp == 0L) return ""

    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val messageDate = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = java.util.Calendar.getInstance()

    val isToday = messageDate.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            messageDate.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)

    val isSameYear = messageDate.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)

    // Check if within the last 7 days
    val daysDiff = (now - timestamp) / (1000 * 60 * 60 * 24)

    // Get system time format (12h or 24h)
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val timePattern = if (is24Hour) "HH:mm" else "h:mm a"

    return when {
        isToday -> java.text.SimpleDateFormat(timePattern, java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        daysDiff < 7 -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        isSameYear -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        else -> java.text.SimpleDateFormat("M/d/yy", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
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
            if (userAvatarPath != null) {
                coil.compose.AsyncImage(
                    model = userAvatarPath,
                    contentDescription = "Profile",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else if (userName != null) {
                // Show initials
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
        }
    }
}

@Composable
private fun SelectionModeHeader(
    selectedCount: Int,
    onClose: () -> Unit,
    onPin: () -> Unit,
    onSnooze: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onBlock: () -> Unit,
    onAddContact: (() -> Unit)? = null, // null means "Add contact" option should be hidden
    isPinEnabled: Boolean = true, // false when none of the selected conversations can be pinned
    modifier: Modifier = Modifier
) {
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
                DropdownMenuItem(
                    text = { Text("Mark as unread") },
                    onClick = {
                        showMoreMenu = false
                        onMarkAsUnread()
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
    isSelectionMode: Boolean = false
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isSelectionMode) Modifier.padding(vertical = 4.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = if (isSelectionMode) RoundedCornerShape(50) else RoundedCornerShape(0.dp),
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
            Box(modifier = Modifier.size(56.dp)) {
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
                            names = listOf(conversation.displayName), // TODO: Get participant names
                            size = 56.dp
                        )
                    } else {
                        Avatar(
                            name = conversation.displayName,
                            avatarPath = conversation.avatarPath,
                            size = 56.dp
                        )
                    }

                    // Chat badge indicator for recent messages
                    if (conversation.isTyping) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .size(20.dp)
                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                TypingDots()
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
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Message preview with status indicator
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (conversation.lastMessageStatus != MessageStatus.NONE) {
                        MessageStatusIndicator(status = conversation.lastMessageStatus)
                    }
                    Text(
                        text = formatMessagePreview(conversation),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                            lineHeight = 18.sp
                        ),
                        color = when {
                            conversation.hasDraft -> MaterialTheme.colorScheme.error
                            conversation.unreadCount > 0 -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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

                // Unread badge
                if (conversation.unreadCount > 0) {
                    UnreadBadge(count = conversation.unreadCount)
                }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(2.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer)
            )
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
    onConversationClick: (String) -> Unit,
    onConversationLongClick: (String) -> Unit = {},
    onUnpin: (String) -> Unit = {},
    onAvatarClick: (ConversationUiModel) -> Unit = {},
    selectedConversations: Set<String> = emptySet(),
    isSelectionMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = conversations,
            key = { it.guid }
        ) { conversation ->
            PinnedConversationItem(
                conversation = conversation,
                onClick = { onConversationClick(conversation.guid) },
                onLongClick = { onConversationLongClick(conversation.guid) },
                onUnpin = { onUnpin(conversation.guid) },
                onAvatarClick = { onAvatarClick(conversation) },
                isSelected = conversation.guid in selectedConversations,
                isSelectionMode = isSelectionMode
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedConversationItem(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onUnpin: () -> Unit = {},
    onAvatarClick: () -> Unit = {},
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showContextMenu = true }
                )
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        // Avatar with unread badge or selection checkmark
        // Outer Box without clip to allow badge overflow
        Box(modifier = Modifier.size(56.dp)) {
            // Avatar content - clip only applies to avatar, not badge
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onAvatarClick,
                        onLongClick = { showContextMenu = true }
                    )
            ) {
                if (isSelected) {
                    // Show checkmark when selected - use muted color
                    Surface(
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxSize()
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
                } else if (conversation.isGroup) {
                    GroupAvatar(
                        names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                        size = 56.dp
                    )
                } else {
                    Avatar(
                        name = conversation.displayName,
                        avatarPath = conversation.avatarPath,
                        size = 56.dp
                    )
                }
            }

            // Unread badge - outside clipped area so it can overflow
            if (conversation.unreadCount > 0 && !isSelected) {
                // Outer surface creates the "cut into avatar" border effect
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                ) {
                    // Inner badge with padding to create border thickness
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(3.dp)
                            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = if (conversation.unreadCount > 9) "9+" else conversation.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
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
                        TypingDots()
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(6.dp))

            // Name (truncated) - format phone numbers nicely
            val formattedName = formatDisplayName(conversation.displayName)
            Text(
                text = formattedName.split(" ").firstOrNull() ?: formattedName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Context menu dropdown
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Unpin") },
                onClick = {
                    showContextMenu = false
                    onUnpin()
                }
            )
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

private fun formatMessagePreview(conversation: ConversationUiModel): String {
    return when {
        conversation.hasDraft -> conversation.draftText ?: ""
        conversation.isFromMe -> {
            val prefix = "You: "
            val content = when (conversation.lastMessageType) {
                MessageType.IMAGE -> "Image"
                MessageType.VIDEO -> "Video"
                MessageType.AUDIO -> "Audio"
                MessageType.LINK -> conversation.lastMessageText.take(50)
                MessageType.ATTACHMENT -> "Attachment"
                else -> conversation.lastMessageText
            }
            prefix + content
        }
        else -> conversation.lastMessageText
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
    onNavigate: (String) -> Unit,
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Profile Header
                item {
                    ProfileHeader(
                        serverUrl = uiState.serverUrl,
                        connectionState = uiState.connectionState,
                        onManageServerClick = { onNavigate("server") }
                    )
                }

                // Quick Actions Card
                item {
                    SettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Mark all as read (most common action first)
                        SettingsMenuItem(
                            icon = Icons.Default.DoneAll,
                            title = "Mark all as read",
                            subtitle = if (uiState.unreadCount > 0) {
                                "${uiState.unreadCount} unread conversation${if (uiState.unreadCount > 1) "s" else ""}"
                            } else {
                                "All caught up!"
                            },
                            onClick = { viewModel.markAllAsRead() },
                            enabled = !uiState.isMarkingAllRead && uiState.unreadCount > 0,
                            trailingContent = if (uiState.isMarkingAllRead) {
                                {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            } else null
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        // Archived
                        SettingsMenuItem(
                            icon = Icons.Outlined.Archive,
                            title = "Archived",
                            onClick = { onNavigate("archived") },
                            trailingContent = if (uiState.archivedCount > 0) {
                                {
                                    Text(
                                        text = uiState.archivedCount.toString(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else null
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        // Blocked contacts
                        SettingsMenuItem(
                            icon = Icons.Default.Block,
                            title = "Blocked contacts",
                            onClick = { onNavigate("blocked") }
                        )
                    }
                }

                // Messaging Section
                item {
                    SettingsSectionTitle(title = "Messaging")
                }

                item {
                    SettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        // Notifications
                        SettingsMenuItem(
                            icon = Icons.Default.Notifications,
                            title = stringResource(R.string.settings_notifications),
                            subtitle = "Sound, vibration, and display",
                            onClick = { onNavigate("notifications") }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        // SMS/MMS settings
                        SettingsMenuItem(
                            icon = Icons.Default.Sms,
                            title = stringResource(R.string.settings_sms),
                            subtitle = "Local SMS messaging options",
                            onClick = { onNavigate("sms") }
                        )
                    }
                }

                // Appearance & Behavior Section
                item {
                    SettingsSectionTitle(title = "Appearance & behavior")
                }

                item {
                    SettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        // Simple app title toggle
                        SettingsMenuItem(
                            icon = Icons.Default.TextFields,
                            title = "Simple app title",
                            subtitle = if (uiState.useSimpleAppTitle) "Showing \"Messages\"" else "Showing \"BothBubbles\"",
                            onClick = { viewModel.setUseSimpleAppTitle(!uiState.useSimpleAppTitle) },
                            trailingContent = {
                                Switch(
                                    checked = uiState.useSimpleAppTitle,
                                    onCheckedChange = { viewModel.setUseSimpleAppTitle(it) }
                                )
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        // Swipe gestures
                        SettingsMenuItem(
                            icon = Icons.Default.SwipeRight,
                            title = "Swipe actions",
                            subtitle = "Customize conversation swipe gestures",
                            onClick = { onNavigate("swipe") }
                        )
                    }
                }

                // Connection & Data Section
                item {
                    SettingsSectionTitle(title = "Connection & data")
                }

                item {
                    SettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        // Server pairing
                        SettingsMenuItem(
                            icon = Icons.Default.QrCodeScanner,
                            title = "Server pairing",
                            subtitle = "Reconnect or pair new server",
                            onClick = { onNavigate("server") }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        // Sync settings
                        SettingsMenuItem(
                            icon = Icons.Default.Sync,
                            title = "Sync settings",
                            subtitle = "Last synced: ${uiState.lastSyncFormatted}",
                            onClick = { onNavigate("sync") }
                        )
                    }
                }

                // About (always at bottom)
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    SettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        SettingsMenuItem(
                            icon = Icons.Default.Info,
                            title = stringResource(R.string.settings_about),
                            subtitle = "Version, licenses, and help",
                            onClick = { onNavigate("about") }
                        )
                    }
                }
            }
        }
    }
}
