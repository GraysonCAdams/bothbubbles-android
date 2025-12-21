package com.bothbubbles.ui.chat

import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.chat.components.CaptureTypeSheet
import com.bothbubbles.ui.chat.components.ChatBackground
import com.bothbubbles.ui.chat.components.EtaDestinationInputDialog
import com.bothbubbles.ui.chat.components.EtaDrivingWarningDialog
import com.bothbubbles.ui.chat.components.MessageSelectionHeader
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.components.common.copyToClipboard
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.attachment.LocalExoPlayerPool
import com.bothbubbles.ui.components.dialogs.ContactInfo
import com.bothbubbles.ui.components.dialogs.ContactQuickActionsPopup
import com.bothbubbles.ui.components.message.AnimatedThreadOverlay
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.ui.effects.screen.ScreenEffectOverlay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatGuid: String,
    onBackClick: () -> Unit,
    onDetailsClick: () -> Unit,
    onMediaClick: (String) -> Unit,
    onEditAttachmentClick: (Uri) -> Unit = {},
    onLife360MapClick: (participantAddress: String) -> Unit = {},
    capturedPhotoUri: Uri? = null,
    onCapturedPhotoHandled: () -> Unit = {},
    editedAttachmentUri: Uri? = null,
    editedAttachmentCaption: String? = null,
    originalAttachmentUri: Uri? = null,
    onEditedAttachmentHandled: () -> Unit = {},
    sharedText: String? = null,
    sharedUris: List<Uri> = emptyList(),
    onSharedContentHandled: () -> Unit = {},
    activateSearch: Boolean = false,
    onSearchActivated: () -> Unit = {},
    initialScrollPosition: Int = 0,
    initialScrollOffset: Int = 0,
    onScrollPositionRestored: () -> Unit = {},
    targetMessageGuid: String? = null,
    // Bubble mode - simplified UI for Android conversation bubbles
    isBubbleMode: Boolean = false,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Wave 2: State collections moved to child components for internal collection
    // Keeping only what's needed at ChatScreen level
    val messages by viewModel.messageList.messagesState.collectAsStateWithLifecycle()
    val chatInfoState by viewModel.chatInfo.state.collectAsStateWithLifecycle()
    // Note: sendState, searchState, operationsState, syncState, effectsState, threadState,
    // smartReplySuggestions, connectionState, etaSharingState now collected internally by children

    // Recomposition debugging (see ChatScreenDebug.kt - can be disabled via ENABLE_RECOMPOSITION_DEBUG)
    // Note: isSending and smartReplyCount now collected internally by children
    ChatScreenRecompositionDebug(
        viewModel = viewModel,
        messages = messages,
        isSending = false, // Collected internally by ChatInputUI
        smartReplyCount = 0, // Collected internally by ChatInputUI
        attachmentCount = uiState.attachmentCount,
        isLoading = uiState.isLoading,
        canLoadMore = uiState.canLoadMore,
        uiStateHash = System.identityHashCode(uiState)
    )

    // LRU cached scroll position (for instant restore when re-opening recently viewed chat)
    val cachedScrollPosition by viewModel.messageList.cachedScrollPosition.collectAsStateWithLifecycle()

    // Determine effective scroll position: navigation state takes priority, then LRU cache
    val effectiveScrollPosition = if (initialScrollPosition > 0 || initialScrollOffset > 0) {
        Pair(initialScrollPosition, initialScrollOffset)
    } else {
        cachedScrollPosition ?: Pair(0, 0)
    }

    // Stage 3: Hoisted state container for all local UI state
    val state = rememberChatScreenState(
        initialScrollPosition = effectiveScrollPosition.first,
        initialScrollOffset = effectiveScrollPosition.second,
        cachedScrollPosition = cachedScrollPosition
    )
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Consolidated scroll effects: keyboard hiding, load more, scroll position tracking
    // See ChatScrollHelper.kt for implementation details
    ChatScrollEffects(
        listState = state.listState,
        keyboardController = keyboardController,
        canLoadMore = uiState.canLoadMore,
        isLoadingMore = uiState.isLoadingMore,
        onLoadMore = { viewModel.messageList.loadMore() },
        onScrollPositionChanged = { index, offset, visibleCount ->
            viewModel.updateScrollPosition(index, offset, visibleCount)
            viewModel.onScrollPositionChanged(index, index + visibleCount - 1)
        }
    )

    // Wave 2: ChatScreenEffects handles navigation, deep-links, and external inputs
    // Now collects operationsState internally from delegate
    ChatScreenEffects(
        viewModel = viewModel,
        state = state,
        chatGuid = chatGuid,
        operationsDelegate = viewModel.operations,
        messages = messages,
        effectiveScrollPosition = effectiveScrollPosition,
        onScrollPositionRestored = onScrollPositionRestored,
        capturedPhotoUri = capturedPhotoUri,
        editedAttachmentUri = editedAttachmentUri,
        editedAttachmentCaption = editedAttachmentCaption,
        originalAttachmentUri = originalAttachmentUri,
        sharedText = sharedText,
        sharedUris = sharedUris,
        targetMessageGuid = targetMessageGuid,
        activateSearch = activateSearch,
        onBackClick = onBackClick,
        onCapturedPhotoHandled = onCapturedPhotoHandled,
        onEditedAttachmentHandled = onEditedAttachmentHandled,
        onSharedContentHandled = onSharedContentHandled,
        onSearchActivated = onSearchActivated,
        error = uiState.error,
        onClearError = viewModel::clearError
    )

    // Wave 2: Effect settings, initialLoadComplete, isLoadingFromServer, autoDownloadEnabled,
    // threadOverlayState, forwardableChats, isWhatsAppAvailable are now collected internally
    // by their respective child components

    // Handle back press to exit message selection mode (higher priority than tapback)
    BackHandler(enabled = state.isMessageSelectionMode) {
        state.clearMessageSelection()
    }

    // Handle back press to dismiss tapback menu (uses state from ChatScreenState)
    BackHandler(enabled = state.selectedMessageForTapback != null) {
        state.clearTapbackSelection()
    }

    // Track pending attachments locally for UI (still needed at ChatScreen level for dialogs)
    val pendingAttachments by viewModel.composer.pendingAttachments.collectAsStateWithLifecycle()

    // Voice memo recording and playback state (encapsulated in ChatAudioHelper)
    val audioState = rememberChatAudioState()

    // Recording duration timer with amplitude tracking + playback position tracker
    ChatAudioEffects(audioState)

    // Note: Scroll position tracking is now handled by ChatScrollEffects above

    // Notify ViewModel we're leaving the chat on dispose
    // Note: Audio cleanup is handled by rememberChatAudioState()
    DisposableEffect(Unit) {
        onDispose {
            viewModel.onChatLeave()
        }
    }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            viewModel.composer.addAttachment(uri)
        }
    }

    // Add contact launcher - refresh contact info when returning from contacts app
    val addContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Refresh contact info when returning from contacts app (regardless of result)
        viewModel.chatInfo.refreshContactInfo()
    }

    // File picker launcher for attaching documents
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            viewModel.composer.addAttachment(uri)
        }
    }

    // Contact picker launcher for sharing contacts as vCard
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        contactUri?.let { uri ->
            viewModel.composer.addContactFromPicker(uri)
        }
    }

    // Location permission launcher for sharing current location as native vLocation
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            // Show loading indicator while fetching GPS location
            viewModel.composer.setFetchingLocation(true)
            // Get current location and send as native Apple vLocation format
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        viewModel.composer.setFetchingLocation(false)
                        location?.let {
                            // Create vLocation attachment (Apple's native iMessage format)
                            val success = viewModel.composer.addLocationAsVLocation(
                                latitude = it.latitude,
                                longitude = it.longitude
                            )
                            if (success) {
                                // Just dismiss panel - don't auto-send, let user tap send
                                viewModel.composer.dismissPanel()
                            } else {
                                Toast.makeText(context, "Failed to create location", Toast.LENGTH_SHORT).show()
                            }
                        } ?: run {
                            Toast.makeText(context, "Unable to get location", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener {
                        viewModel.composer.setFetchingLocation(false)
                        Toast.makeText(context, "Failed to get location", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: SecurityException) {
                viewModel.composer.setFetchingLocation(false)
                Toast.makeText(context, "Location permission required", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Location permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // Stock camera - Photo capture
    var takePhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            takePhotoUri?.let { uri ->
                viewModel.composer.addAttachment(uri)
                // Collapse media picker and show keyboard
                viewModel.composer.dismissPanel()
                viewModel.composer.requestTextFieldFocus()
            }
        }
    }

    // Stock camera - Video capture
    var takeVideoUri by remember { mutableStateOf<Uri?>(null) }
    val takeVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success) {
            takeVideoUri?.let { uri ->
                viewModel.composer.addAttachment(uri)
                // Collapse media picker and show keyboard
                viewModel.composer.dismissPanel()
                viewModel.composer.requestTextFieldFocus()
            }
        }
    }

    // Collect ETA sharing state for media picker (needs to show ETA option when navigation active)
    val etaSharingState by viewModel.etaSharing.etaSharingState.collectAsStateWithLifecycle()
    // Collect destination fetch state for accessibility-based destination scraping dialogs
    val destinationFetchUiState by viewModel.etaSharing.destinationFetchUiState.collectAsStateWithLifecycle()

    // Lifecycle observer to detect return from navigation app (for accessibility destination scraping)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.etaSharing.onReturnedFromNavApp()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Load featured GIFs when GIF panel opens
    // Use dedicated activePanel flow instead of full composerState to avoid recomposition on text changes
    // Wave 2: gifPickerState and gifSearchQuery now collected internally by ChatInputUI
    val activePanelState by viewModel.composer.activePanel.collectAsStateWithLifecycle()
    LaunchedEffect(activePanelState) {
        if (activePanelState == com.bothbubbles.ui.chat.composer.ComposerPanel.GifPicker) {
            viewModel.composer.loadFeaturedGifs()
        }
    }

    // Note: LaunchedEffects for chat deletion, captured photos, edited attachments,
    // shared content, and search activation are now in ChatScreenEffects

    // Provide ExoPlayerPool to video composables for pooled player management
    // This limits active video players and auto-evicts oldest when scrolling
    CompositionLocalProvider(LocalExoPlayerPool provides viewModel.exoPlayerPool) {

    // PERF FIX: Track topBar/bottomBar heights for content padding
    // This avoids SubcomposeLayout which has O(N) overhead with message list
    // Height state now consolidated in ChatScreenState (topBarHeightPx, bottomBarBaseHeightPx)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topBarHeightDp = with(density) { state.topBarHeightPx.toDp() }
    val bottomBarHeightDp = with(density) { state.bottomBarBaseHeightPx.toDp() }

    // Wave 2: Collect effect settings (needed for screen effect detection and overlay)
    val effectsStateForOverlay by viewModel.effects.state.collectAsStateWithLifecycle()
    val activeScreenEffectState = effectsStateForOverlay.activeScreenEffect

    ChatBackground {
        // PERF FIX: Use Box with overlapping layout instead of Scaffold
        // Scaffold's SubcomposeLayout has O(N) overhead when comparing lambda closures
        // that capture the messages list. This Box approach avoids SubcomposeLayout entirely.

        // TopBar overlay at top - switches between normal and selection mode
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { state.topBarHeightPx = it.height.toFloat() }
                .zIndex(1f)
        ) {
            AnimatedContent(
                targetState = state.isMessageSelectionMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "TopBarModeSwitch"
            ) { isSelectionMode ->
                if (isSelectionMode) {
                    MessageSelectionHeader(
                        selectedCount = state.selectedMessageGuids.size,
                        onClose = { state.clearMessageSelection() },
                        onCopy = {
                            val selectedMessages = messages.filter { it.guid in state.selectedMessageGuids }
                            val hasAnyText = selectedMessages.any { !it.text.isNullOrBlank() }

                            if (hasAnyText) {
                                val formattedText = formatSelectedMessagesForCopy(
                                    selectedGuids = state.selectedMessageGuids,
                                    messages = messages
                                )
                                copyToClipboard(
                                    context = context,
                                    text = formattedText,
                                    toastMessage = "Copied ${state.selectedMessageGuids.size} message(s)"
                                )
                            } else {
                                // No text to copy - only attachments selected
                                android.widget.Toast.makeText(
                                    context,
                                    "Selected messages contain no text to copy",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            state.clearMessageSelection()
                        },
                        onShare = {
                            // Use Android system share sheet
                            val formattedText = formatSelectedMessagesForCopy(
                                selectedGuids = state.selectedMessageGuids,
                                messages = messages
                            )
                            if (formattedText.isNotBlank()) {
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, formattedText)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(shareIntent, "Share messages")
                                )
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "Selected messages contain no text to share",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            state.clearMessageSelection()
                        },
                        onForward = {
                            // Store selected messages sorted by date (oldest first) and show forward dialog
                            state.messagesToForward = messages
                                .filter { it.guid in state.selectedMessageGuids }
                                .sortedBy { it.dateCreated }
                                .map { it.guid }
                            state.showForwardDialog = true
                            // Don't clear selection yet - will be cleared after forward success
                        },
                        onDelete = {
                            // Show confirmation dialog before deleting
                            state.showDeleteMessagesDialog = true
                        },
                        onSelectAll = {
                            state.selectAllMessages(messages.map { it.guid })
                        }
                    )
                } else {
                    // Wave 2: ChatTopBar uses delegates for internal state collection
                    ChatTopBar(
                        operationsDelegate = viewModel.operations,
                        chatInfoDelegate = viewModel.chatInfo,
                        sendModeManager = viewModel.sendMode,
                        onBackClick = {
                            // Clear saved state when user explicitly navigates back
                            viewModel.onNavigateBack()
                            onBackClick()
                        },
                        onDetailsClick = onDetailsClick,
                        onVideoCallClick = { state.showVideoCallDialog = true },
                        onLife360MapClick = onLife360MapClick,
                        isBubbleMode = isBubbleMode,
                        onMenuAction = { action ->
                            when (action) {
                                ChatMenuAction.ADD_PEOPLE -> {
                                    context.startActivity(viewModel.operations.getAddToContactsIntent(
                                        chatInfoState.participantPhone,
                                        chatInfoState.inferredSenderName
                                    ))
                                }
                                ChatMenuAction.DETAILS -> onDetailsClick()
                                ChatMenuAction.SWITCH_SEND_MODE -> {
                                    // Toggle send mode between iMessage and SMS
                                    viewModel.sendMode.tryToggleSendMode()
                                }
                                ChatMenuAction.STARRED -> viewModel.operations.toggleStarred()
                                ChatMenuAction.SEARCH -> viewModel.search.activateSearch()
                                ChatMenuAction.SELECT_MESSAGES -> {
                                    // Enter selection mode with first message selected (if any)
                                    messages.firstOrNull()?.let { firstMessage ->
                                        state.enterMessageSelectionMode(firstMessage.guid)
                                    }
                                }
                                ChatMenuAction.ARCHIVE -> viewModel.operations.archiveChat()
                                ChatMenuAction.UNARCHIVE -> viewModel.operations.unarchiveChat()
                                ChatMenuAction.DELETE -> state.showDeleteDialog = true
                                ChatMenuAction.BLOCK_AND_REPORT -> state.showBlockDialog = true
                                ChatMenuAction.HELP_AND_FEEDBACK -> {
                                    context.startActivity(viewModel.operations.getHelpIntent())
                                }
                            }
                        }
                    )
                }
            }
        }

        // BottomBar overlay at bottom
        // Wave 2: replyingToMessage calculation moved to ChatInputUI (collected internally)

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { newSize ->
                    // Track full height of bottom bar (includes imePadding from ChatInputUI).
                    // Content Box does NOT have imePadding - it relies on this measurement
                    // to properly clear the composer area including keyboard.
                    state.bottomBarBaseHeightPx = newSize.height.toFloat()
                }
                .zIndex(1f)
        ) {
            ChatInputUI(
                composerDelegate = viewModel.composer,
                audioState = audioState,
                isLocalSmsChat = chatInfoState.isLocalSmsChat,
                // Wave 2: Deprecated params removed (sendState, smartReplySuggestions,
                // replyingToMessage, gifPickerState, gifSearchQuery) - now collected internally
                onCameraClick = { state.showCaptureTypeSheet = true },
                onSmartReplyClick = { suggestion ->
                    viewModel.updateDraft(suggestion.text)
                },
                onComposerEvent = { event -> viewModel.onComposerEvent(event) },
                onMediaSelected = { uris -> viewModel.composer.addAttachments(uris) },
                onFileClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                onLocationClick = {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onContactClick = { contactPickerLauncher.launch(null) },
                onEtaClick = {
                    viewModel.etaSharing.startSharingEta(chatGuid, chatInfoState.chatTitle)
                },
                // Show ETA option when navigation is active, enabled, and not currently sharing
                isEtaSharingAvailable = etaSharingState.isEnabled &&
                    etaSharingState.isNavigationActive &&
                    !etaSharingState.isCurrentlySharing,
                onGifSearchQueryChange = { viewModel.composer.updateGifSearchQuery(it) },
                onGifSearch = { viewModel.composer.searchGifs(it) },
                onGifSelected = { gif -> viewModel.composer.selectGif(gif) },
                onShowEffectPicker = { state.showEffectPicker = true },
                onShowQualitySheet = { state.showQualitySheet = true },
                onEditAttachmentClick = onEditAttachmentClick,
                onSendVoiceMemo = { uri ->
                    viewModel.composer.addAttachment(uri)
                    viewModel.sendMessage()
                },
                onSendButtonBoundsChanged = { bounds -> state.sendButtonBounds = bounds },
                onSizeChanged = { height -> state.composerHeightPx = height.toFloat() },
                isBubbleMode = isBubbleMode
            )
        }

        // Main content area - uses calculated padding for top/bottom bars
        // This avoids SubcomposeLayout by using pre-measured heights
        // Note: NO imePadding() here - ChatInputUI has imePadding() and its measured height
        // (bottomBarHeightDp) already includes keyboard clearance when keyboard is open.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarHeightDp, bottom = bottomBarHeightDp)
        ) {
        // Wave 2: Use effect settings from outer scope (collected at higher level)
        val autoPlayEffects = effectsStateForOverlay.autoPlayEffects
        val replayEffectsOnScroll = effectsStateForOverlay.replayOnScroll
        val reduceMotion = effectsStateForOverlay.reduceMotion

        // Detect new messages with screen effects and trigger playback
        LaunchedEffect(messages.firstOrNull()?.guid) {
            val newest = messages.firstOrNull() ?: return@LaunchedEffect
            // Skip if already processed this session
            if (state.isEffectProcessed(newest.guid)) return@LaunchedEffect
            // Skip if effects disabled or reduce motion enabled
            if (!autoPlayEffects || reduceMotion) return@LaunchedEffect
            // Skip if already played (and not replaying on scroll)
            if (newest.effectPlayed && !replayEffectsOnScroll) return@LaunchedEffect

            val effect = MessageEffect.fromStyleId(newest.expressiveSendStyleId)
            if (effect is MessageEffect.Screen) {
                state.markEffectProcessed(newest.guid)
                viewModel.effects.triggerScreenEffect(newest)
            }
        }

        // Wave 3G: rememberUpdatedState for callbacks that need access to state values
        // These provide stable callback references while allowing access to current state
        val currentState by rememberUpdatedState(state)
        val currentChatInfoState by rememberUpdatedState(chatInfoState)
        val currentContext by rememberUpdatedState(context)
        val currentAddContactLauncher by rememberUpdatedState(addContactLauncher)
        val currentChatGuid by rememberUpdatedState(chatGuid)
        val currentMessages by rememberUpdatedState(messages)

        // Wave 3G: Stable MessageListCallbacks wrapped in remember(viewModel)
        // Callbacks are stable because:
        // - Method references (viewModel::method) are inherently stable
        // - State-accessing callbacks use rememberUpdatedState to get current values
        val messageListCallbacks = remember(viewModel) {
            MessageListCallbacks(
                onMediaClick = onMediaClick,
                onToggleReaction = viewModel::toggleReaction,
                onSetReplyTo = viewModel.send::setReplyTo,
                onClearReply = viewModel.send::clearReply,
                onScrollToOriginal = { originGuid ->
                    // Find the message in the list and scroll to it with highlight
                    val index = currentMessages.indexOfFirst { it.guid == originGuid }
                    if (index >= 0) {
                        // Highlight the message and scroll to it
                        viewModel.highlightMessage(originGuid)
                        currentState.coroutineScope.launch {
                            val viewportHeight = currentState.listState.layoutInfo.viewportSize.height
                            val centerOffset = -(viewportHeight / 3)
                            currentState.listState.animateScrollToItem(index, scrollOffset = centerOffset)
                        }
                    } else {
                        // Message not in view - fall back to loading thread overlay
                        viewModel.thread.loadThread(originGuid)
                    }
                },
                onLoadThread = viewModel.thread::loadThread,
                onRetryMessage = viewModel.send::retryMessage,
                onRetryAsSms = viewModel.send::retryMessageAsSms,
                onDeleteMessage = viewModel.send::deleteFailedMessage,
                onCanRetryAsSms = viewModel.send::canRetryAsSms,
                onForwardRequest = { message ->
                    currentState.messageToForward = message
                    currentState.showForwardDialog = true
                },
                onBubbleEffectCompleted = viewModel.effects::onBubbleEffectCompleted,
                onHighlightMessage = viewModel::highlightMessage,
                onClearHighlight = viewModel::clearHighlight,
                onDownloadAttachment = viewModel.attachment::downloadAttachment,
                onAddContact = {
                    currentAddContactLauncher.launch(viewModel.operations.getAddToContactsIntent(
                        currentChatInfoState.participantPhone,
                        currentChatInfoState.inferredSenderName
                    ))
                },
                onReportSpam = {
                    viewModel.operations.reportAsSpam()
                    if (currentChatInfoState.isLocalSmsChat) {
                        viewModel.operations.blockContact(currentChatInfoState.participantPhone)
                    }
                    viewModel.chatInfo.dismissSaveContactBanner()
                },
                onDismissSaveContactBanner = viewModel.chatInfo::dismissSaveContactBanner,
                onMarkAsSafe = viewModel.operations::markAsSafe,
                onStartSharingEta = {
                    viewModel.etaSharing.startSharingWithAccessibilityScrape(
                        chatGuid = currentChatGuid,
                        displayName = currentChatInfoState.chatTitle,
                        openNavApp = { navApp ->
                            val intent = viewModel.etaSharing.createNavAppIntent(navApp)
                            if (intent != null) {
                                context.startActivity(intent)
                                true
                            } else {
                                false
                            }
                        }
                    )
                },
                onStopSharingEta = viewModel.etaSharing::stopSharingEta,
                onDismissEtaBanner = viewModel.etaSharing::dismissBanner,
                onExitSmsFallback = viewModel::exitSmsFallback,
                // Phase 4: ViewModel coordinates search with messages from messageList
                onSearchQueryChange = viewModel::updateSearchQuery,
                onCloseSearch = viewModel.search::closeSearch,
                onNavigateSearchUp = viewModel.search::navigateSearchUp,
                onNavigateSearchDown = viewModel.search::navigateSearchDown,
                onViewAllSearchResults = viewModel.search::showResultsSheet,
                // Avatar click in group chats opens contact details popup
                onAvatarClick = { message ->
                    currentState.selectedSenderMessage = message
                }
            )
        }

        // Stage 2: Extracted ChatMessageList component
        ChatMessageList(
            modifier = Modifier.fillMaxSize(),
            chatScreenState = state,
            messages = messages,

            // Message list delegate (cursor-based pagination)
            messageListDelegate = viewModel.messageList,
            sendDelegate = viewModel.send,
            searchDelegate = viewModel.search,
            syncDelegate = viewModel.sync,
            operationsDelegate = viewModel.operations,
            attachmentDelegate = viewModel.attachment,
            etaSharingDelegate = viewModel.etaSharing,
            effectsDelegate = viewModel.effects,

            // State objects (still needed at this level)
            chatInfoState = chatInfoState,

            // UI state
            highlightedMessageGuid = uiState.highlightedMessageGuid,

            // Socket new message flow
            socketNewMessageFlow = viewModel.messageList.socketNewMessage,

            // Wave 3G: Stable callbacks reference
            callbacks = messageListCallbacks,

            // Tapback overlay state
            selectedMessageForTapback = state.selectedMessageForTapback,
            selectedMessageBounds = state.selectedMessageBounds,
            onSelectMessageForTapback = { message ->
                state.selectedMessageForTapback = message
            },
            onSelectedBoundsChange = { bounds ->
                state.selectedMessageBounds = bounds
            },

            // Retry menu state
            selectedMessageForRetry = state.selectedMessageForRetry,
            canRetrySmsForMessage = state.canRetrySmsForMessage,
            onSelectMessageForRetry = { message ->
                state.selectedMessageForRetry = message
            },
            onCanRetrySmsUpdate = { canRetry ->
                state.canRetrySmsForMessage = canRetry
            },

            // Swipe state
            swipingMessageGuid = state.swipingMessageGuid,
            onSwipingMessageChange = { guid ->
                state.swipingMessageGuid = guid
            },

            // Composer height
            // PERF FIX: Pass lambda to avoid reading composerHeightPx during ChatScreen composition
            // (keyboard/panel animations would otherwise cause ChatMessageList recomposition every frame)
            composerHeightPxProvider = { state.composerHeightPx },

            // Wave 2: Server connection collected locally for tapback availability
            isServerConnected = viewModel.sync.state.collectAsStateWithLifecycle().value.isServerConnected,

            // Bubble mode
            isBubbleMode = isBubbleMode
        )

    } // End of content Box


    // SnackbarHost overlay - positioned above the composer
    SnackbarHost(
        hostState = state.snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomBarHeightDp)
    )

    // Screen effect overlay (above all other content) - connected to ViewModel
    ScreenEffectOverlay(
        effect = activeScreenEffectState?.effect,
        messageText = activeScreenEffectState?.messageText,
        onEffectComplete = {
            viewModel.effects.onScreenEffectCompleted()
        }
    )

    // Wave 2: Thread overlay uses delegate for internal state collection
    AnimatedThreadOverlay(
        threadDelegate = viewModel.thread,
        onMessageClick = { guid -> viewModel.thread.scrollToMessage(guid) },
        onDismiss = { viewModel.thread.dismissThreadOverlay() },
        bottomPadding = bottomBarHeightDp
    )
    } // End of outer Box
    } // End of CompositionLocalProvider

    // Wave 2: ChatDialogsHost uses delegates for internal state collection
    ChatDialogsHost(
        viewModel = viewModel,
        context = context,
        // Wave 2: Delegates for internal state collection
        connectionDelegate = viewModel.connection,
        sendDelegate = viewModel.send,
        operationsDelegate = viewModel.operations,
        searchDelegate = viewModel.search,
        // State objects (still needed at this level)
        chatInfoState = chatInfoState,
        // Wave 2: Deprecated params removed (connectionState, operationsState, sendState,
        // searchState, forwardableChats, isWhatsAppAvailable) - now collected internally
        showEffectPicker = state.showEffectPicker,
        showDeleteDialog = state.showDeleteDialog,
        showBlockDialog = state.showBlockDialog,
        showSmsBlockedDialog = state.showSmsBlockedDialog,
        showVideoCallDialog = state.showVideoCallDialog,
        showDiscordSetupDialog = state.showDiscordSetupDialog,
        showDiscordHelpOverlay = state.showDiscordHelpOverlay,
        showScheduleDialog = state.showScheduleDialog,
        showVCardOptionsDialog = state.showVCardOptionsDialog,
        showQualitySheet = state.showQualitySheet,
        showForwardDialog = state.showForwardDialog,
        selectedMessageForRetry = state.selectedMessageForRetry,
        canRetrySmsForMessage = state.canRetrySmsForMessage,
        messageToForward = state.messageToForward,
        messagesToForward = state.messagesToForward,
        pendingContactData = state.pendingContactData,
        pendingAttachments = pendingAttachments,
        attachmentQuality = uiState.attachmentQuality,
        sendButtonBounds = state.sendButtonBounds,
        onDismissEffectPicker = { state.showEffectPicker = false },
        onDismissDeleteDialog = { state.showDeleteDialog = false },
        onDismissBlockDialog = { state.showBlockDialog = false },
        onDismissSmsBlockedDialog = { state.showSmsBlockedDialog = false },
        onDismissVideoCallDialog = { state.showVideoCallDialog = false },
        onDismissDiscordSetupDialog = { state.showDiscordSetupDialog = false },
        onDismissDiscordHelpOverlay = { state.showDiscordHelpOverlay = false },
        onDismissScheduleDialog = { state.showScheduleDialog = false },
        onDismissVCardOptionsDialog = { state.showVCardOptionsDialog = false },
        onDismissQualitySheet = { state.showQualitySheet = false },
        onDismissForwardDialog = { state.showForwardDialog = false },
        onDismissRetrySheet = { state.selectedMessageForRetry = null },
        onEffectSelected = { effect ->
            state.showEffectPicker = false
            if (effect != null) {
                viewModel.sendMessage(effect.appleId)
                viewModel.composer.dismissPanel()
            }
        },
        onShowDiscordSetup = { state.showDiscordSetupDialog = true },
        onShowDiscordHelp = { state.showDiscordHelpOverlay = true },
        onClearPendingContactData = { state.pendingContactData = null },
        onClearMessageToForward = { state.messageToForward = null },
        onClearMessagesToForward = { state.messagesToForward = emptyList() },
        onClearMessageSelection = { state.clearMessageSelection() },
        isBubbleMode = isBubbleMode
    )

    // Contact quick actions popup for group chat avatar clicks
    state.selectedSenderMessage?.let { message ->
        message.senderAddress?.let { address ->
            ContactQuickActionsPopup(
                contactInfo = ContactInfo(
                    chatGuid = "",  // Not used for navigation from here
                    displayName = message.senderName ?: address,
                    rawDisplayName = message.senderName ?: address,
                    avatarPath = message.senderAvatarPath,
                    address = address,
                    isGroup = false,
                    hasContact = false,  // TODO: Could check if contact exists
                    hasInferredName = false
                ),
                onDismiss = { state.selectedSenderMessage = null },
                onMessageClick = { state.selectedSenderMessage = null }
            )
        }
    }

    // Delete messages confirmation dialog
    if (state.showDeleteMessagesDialog) {
        val count = state.selectedMessageGuids.size
        AlertDialog(
            onDismissRequest = { state.showDeleteMessagesDialog = false },
            title = { Text("Delete $count message${if (count != 1) "s" else ""}?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.operations.deleteMessages(state.selectedMessageGuids.toList())
                        state.showDeleteMessagesDialog = false
                        state.clearMessageSelection()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { state.showDeleteMessagesDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Capture type sheet (photo/video) for stock camera
    CaptureTypeSheet(
        visible = state.showCaptureTypeSheet,
        onTakePhoto = {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(context.cacheDir, "IMG_$timestamp.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            takePhotoUri = uri
            takePhotoLauncher.launch(uri)
            state.showCaptureTypeSheet = false
        },
        onRecordVideo = {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(context.cacheDir, "VID_$timestamp.mp4")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            takeVideoUri = uri
            takeVideoLauncher.launch(uri)
            state.showCaptureTypeSheet = false
        },
        onDismiss = { state.showCaptureTypeSheet = false }
    )

    // ETA destination fetch dialogs (accessibility-based destination scraping)
    when (val fetchState = destinationFetchUiState) {
        is ChatEtaSharingDelegate.DestinationFetchUiState.ShowingDrivingWarning -> {
            EtaDrivingWarningDialog(
                countdownSeconds = fetchState.countdownSeconds,
                onShareNow = viewModel.etaSharing::acceptShareWithoutDestination,
                onCancel = viewModel.etaSharing::cancelCountdownAndFetch
            )
        }
        is ChatEtaSharingDelegate.DestinationFetchUiState.ShowingDestinationInput -> {
            EtaDestinationInputDialog(
                onShare = viewModel.etaSharing::shareWithManualDestination,
                onCancel = viewModel.etaSharing::cancelCountdownAndFetch
            )
        }
        else -> { /* Idle or FetchingDestination - no dialog */ }
    }
}

/**
 * Formats selected messages for clipboard copy.
 *
 * Format rules:
 * - Single line break between each message (no blank lines)
 * - If all messages are from the same sender, no names are included
 * - If messages are from different senders, include "[sender]: " prefix
 *
 * Messages are sorted by timestamp (oldest first) for natural reading order.
 */
private fun formatSelectedMessagesForCopy(
    selectedGuids: Set<String>,
    messages: List<MessageUiModel>
): String {
    val selectedMessages = messages
        .filter { it.guid in selectedGuids }
        .sortedBy { it.dateCreated } // Oldest first for natural reading order

    if (selectedMessages.isEmpty()) return ""

    // Determine sender identities for each message
    val senderIdentities = selectedMessages.map { message ->
        if (message.isFromMe) "Me" else (message.senderName ?: "Unknown")
    }

    // Check if all messages are from the same sender
    val allFromSameSender = senderIdentities.distinct().size == 1

    return selectedMessages.mapIndexed { index, message ->
        val senderPrefix = if (!allFromSameSender) {
            "${senderIdentities[index]}: "
        } else {
            ""
        }
        senderPrefix + (message.text ?: "[Attachment]")
    }.joinToString("\n")
}
