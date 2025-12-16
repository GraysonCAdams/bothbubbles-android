package com.bothbubbles.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.chat.components.ChatBackground
import com.bothbubbles.ui.components.attachment.LocalExoPlayerPool
import com.bothbubbles.ui.components.message.AnimatedThreadOverlay
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.ui.effects.screen.ScreenEffectOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatGuid: String,
    onBackClick: () -> Unit,
    onDetailsClick: () -> Unit,
    onMediaClick: (String) -> Unit,
    onCameraClick: () -> Unit = {},
    onEditAttachmentClick: (Uri) -> Unit = {},
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
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Stage 3: Access delegate states directly (Container Pattern)
    val sendState by viewModel.send.state.collectAsStateWithLifecycle()
    val searchState by viewModel.search.state.collectAsStateWithLifecycle()
    val operationsState by viewModel.operations.state.collectAsStateWithLifecycle()
    val syncState by viewModel.sync.state.collectAsStateWithLifecycle()
    val effectsState by viewModel.effects.state.collectAsStateWithLifecycle()
    val threadState by viewModel.thread.state.collectAsStateWithLifecycle()
    val messages by viewModel.messageList.messagesState.collectAsStateWithLifecycle()
    val draftText by viewModel.composer.draftText.collectAsStateWithLifecycle()
    val smartReplySuggestions by viewModel.composer.smartReplySuggestions.collectAsStateWithLifecycle()
    val chatInfoState by viewModel.chatInfo.state.collectAsStateWithLifecycle()
    val connectionState by viewModel.connection.state.collectAsStateWithLifecycle()
    val etaSharingState by viewModel.etaSharing.etaSharingState.collectAsStateWithLifecycle()

    // Recomposition debugging (see ChatScreenDebug.kt - can be disabled via ENABLE_RECOMPOSITION_DEBUG)
    ChatScreenRecompositionDebug(
        messages = messages,
        draftText = draftText,
        isSending = sendState.isSending,
        smartReplyCount = smartReplySuggestions.size,
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
    val hapticFeedback = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Consolidated scroll effects: keyboard hiding, load more, scroll position tracking
    // See ChatScrollHelper.kt for implementation details
    ChatScrollEffects(
        listState = state.listState,
        keyboardController = keyboardController,
        canLoadMore = uiState.canLoadMore,
        isLoadingMore = uiState.isLoadingMore,
        onLoadMore = { viewModel.messageList.loadMoreMessages() },
        onScrollPositionChanged = { index, offset, visibleCount ->
            viewModel.updateScrollPosition(index, offset, visibleCount)
            viewModel.onScrollPositionChanged(index, index + visibleCount - 1)
        }
    )

    // Stage 3: ChatScreenEffects handles navigation, deep-links, and external inputs
    ChatScreenEffects(
        viewModel = viewModel,
        state = state,
        chatGuid = chatGuid,
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
        onClearError = viewModel::clearError,
        chatDeleted = operationsState.chatDeleted
    )

    // Effect settings from delegate state
    val autoPlayEffects = effectsState.autoPlayEffects
    val replayEffectsOnScroll = effectsState.replayOnScroll
    val reduceMotion = effectsState.reduceMotion
    val activeScreenEffectState = effectsState.activeScreenEffect

    // Animation control: only animate new messages after initial load completes
    val initialLoadComplete by viewModel.messageList.initialLoadComplete.collectAsStateWithLifecycle()

    // Track when fetching older messages from server (for loading indicator at top)
    val isLoadingFromServer by viewModel.messageList.isLoadingFromServer.collectAsStateWithLifecycle()

    // Attachment download settings and progress
    val autoDownloadEnabled by viewModel.attachment.autoDownloadEnabled.collectAsStateWithLifecycle()
    val downloadingAttachments by viewModel.attachment.downloadProgress.collectAsStateWithLifecycle()

    // Track processed screen effects this session to avoid re-triggering
    val processedEffectMessages = remember { mutableSetOf<String>() }

    // Track revealed invisible ink messages (resets when leaving chat)
    var revealedInvisibleInkMessages by remember { mutableStateOf(setOf<String>()) }

    // Track messages that have been animated (prevents re-animation on recompose)
    // Messages present during initial load are added immediately
    val animatedMessageGuids = remember { mutableSetOf<String>() }

    // Handle back press to dismiss tapback menu (uses state from ChatScreenState)
    BackHandler(enabled = state.selectedMessageForTapback != null) {
        state.clearTapbackSelection()
    }

    // Thread overlay state from delegate
    val threadOverlayState = threadState.threadOverlay

    // Forward message state is now in ChatScreenState; just need forwardableChats flow
    val forwardableChats by viewModel.getForwardableChats().collectAsStateWithLifecycle(initialValue = emptyList())

    // Track pending attachments locally for UI
    val pendingAttachments by viewModel.composer.pendingAttachments.collectAsStateWithLifecycle()

    // Voice memo recording and playback state (encapsulated in ChatAudioHelper)
    val audioState = rememberChatAudioState()

    // Check WhatsApp availability
    val isWhatsAppAvailable = remember { viewModel.operations.isWhatsAppAvailable(context) }

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

    // Load featured GIFs when GIF panel opens
    // Use dedicated activePanel flow instead of full composerState to avoid recomposition on text changes
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
    var topBarHeightPx by remember { mutableStateOf(0) }
    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topBarHeightDp = with(density) { topBarHeightPx.toDp() }
    val bottomBarHeightDp = with(density) { bottomBarHeightPx.toDp() }

    ChatBackground {
        // PERF FIX: Use Box with overlapping layout instead of Scaffold
        // Scaffold's SubcomposeLayout has O(N) overhead when comparing lambda closures
        // that capture the messages list. This Box approach avoids SubcomposeLayout entirely.

        // TopBar overlay at top
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { topBarHeightPx = it.height }
                .zIndex(1f)
        ) {
            // Stage 2B: Use decomposed state objects for ChatTopBar
            ChatTopBar(
                infoState = chatInfoState,
                operationsState = operationsState,
                onBackClick = {
                    // Clear saved state when user explicitly navigates back
                    viewModel.onNavigateBack()
                    onBackClick()
                },
                onDetailsClick = onDetailsClick,
                onVideoCallClick = { state.showVideoCallDialog = true },
                onMenuAction = { action ->
                    when (action) {
                        ChatMenuAction.ADD_PEOPLE -> {
                            context.startActivity(viewModel.operations.getAddToContactsIntent(
                                chatInfoState.participantPhone,
                                chatInfoState.inferredSenderName
                            ))
                        }
                        ChatMenuAction.DETAILS -> onDetailsClick()
                        ChatMenuAction.STARRED -> viewModel.operations.toggleStarred()
                        ChatMenuAction.SEARCH -> viewModel.search.activateSearch()
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

        // BottomBar overlay at bottom
        val composerState by viewModel.composer.state.collectAsStateWithLifecycle()
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomBarHeightPx = it.height }
                .zIndex(1f)
        ) {
            ChatInputUI(
                composerState = composerState,
                audioState = audioState,
                sendState = sendState,
                smartReplySuggestions = smartReplySuggestions,
                messages = messages,
                draftText = draftText,
                isLocalSmsChat = chatInfoState.isLocalSmsChat,
                showAttachmentPicker = state.showAttachmentPicker,
                showEmojiPicker = state.showEmojiPicker,
                gifPickerState = viewModel.composer.gifPickerState.collectAsState().value,
                gifSearchQuery = viewModel.composer.gifSearchQuery.collectAsState().value,
                onDismissAttachmentPicker = { state.showAttachmentPicker = false },
                onDismissEmojiPicker = { state.showEmojiPicker = false },
                onAttachmentSelected = { uri -> viewModel.composer.addAttachment(uri) },
                onLocationSelected = { lat, lng ->
                    val locationText = "📍 https://maps.google.com/?q=$lat,$lng"
                    viewModel.updateDraft(locationText)
                },
                onContactSelected = { contactUri ->
                    val contactData = viewModel.composer.getContactData(contactUri)
                    if (contactData != null) {
                        state.pendingContactData = contactData
                        state.showVCardOptionsDialog = true
                    } else {
                        Toast.makeText(context, "Failed to read contact", Toast.LENGTH_SHORT).show()
                    }
                },
                onScheduleClick = { state.showScheduleDialog = true },
                onCameraClick = onCameraClick,
                onEmojiSelected = { emoji ->
                    viewModel.updateDraft(draftText + emoji)
                    state.showEmojiPicker = false
                },
                onSmartReplyClick = { suggestion ->
                    viewModel.updateDraft(suggestion.text)
                    suggestion.templateId?.let { viewModel.composer.recordTemplateUsage(it) }
                },
                onCancelReply = { viewModel.send.clearReply() },
                onComposerEvent = { event -> viewModel.onComposerEvent(event) },
                onMediaSelected = { uris -> viewModel.composer.addAttachments(uris) },
                onFileClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                onLocationClick = { /* TODO: Launch location picker */ },
                onContactClick = { contactPickerLauncher.launch(null) },
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
                onSizeChanged = { height -> state.composerHeightPx = height.toFloat() }
            )
        }

        // Main content area - uses calculated padding for top/bottom bars
        // This avoids SubcomposeLayout by using pre-measured heights
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarHeightDp, bottom = bottomBarHeightDp)
        ) {
        // Detect new messages with screen effects and trigger playback
        LaunchedEffect(messages.firstOrNull()?.guid) {
            val newest = messages.firstOrNull() ?: return@LaunchedEffect
            // Skip if already processed this session
            if (newest.guid in processedEffectMessages) return@LaunchedEffect
            // Skip if effects disabled or reduce motion enabled
            if (!autoPlayEffects || reduceMotion) return@LaunchedEffect
            // Skip if already played (and not replaying on scroll)
            if (newest.effectPlayed && !replayEffectsOnScroll) return@LaunchedEffect

            val effect = MessageEffect.fromStyleId(newest.expressiveSendStyleId)
            if (effect is MessageEffect.Screen) {
                processedEffectMessages.add(newest.guid)
                viewModel.effects.triggerScreenEffect(newest)
            }
        }

        // Stage 2: Extracted ChatMessageList component
        ChatMessageList(
            modifier = Modifier.fillMaxSize(),
            listState = state.listState,
            messages = messages,

            // State objects
            chatInfoState = chatInfoState,
            sendState = sendState,
            syncState = syncState,
            searchState = searchState,
            operationsState = operationsState,
            effectsState = effectsState,
            etaSharingState = etaSharingState,

            // UI state
            highlightedMessageGuid = uiState.highlightedMessageGuid,
            isLoadingMore = uiState.isLoadingMore,
            isLoadingFromServer = isLoadingFromServer,
            isSyncingMessages = uiState.isSyncingMessages,
            initialLoadComplete = initialLoadComplete,
            autoDownloadEnabled = autoDownloadEnabled,
            downloadingAttachments = downloadingAttachments,

            // Socket new message flow
            socketNewMessageFlow = viewModel.messageList.socketNewMessage,

            // Callbacks
            callbacks = MessageListCallbacks(
                onMediaClick = onMediaClick,
                onToggleReaction = { messageGuid, tapback ->
                    viewModel.toggleReaction(messageGuid, tapback)
                },
                onSetReplyTo = { guid -> viewModel.send.setReplyTo(guid) },
                onClearReply = { viewModel.send.clearReply() },
                onLoadThread = { originGuid -> viewModel.thread.loadThread(originGuid) },
                onRetryMessage = { guid -> viewModel.send.retryMessage(guid) },
                onCanRetryAsSms = { guid -> viewModel.send.canRetryAsSms(guid) },
                onForwardRequest = { message ->
                    state.messageToForward = message
                    state.showForwardDialog = true
                },
                onBubbleEffectCompleted = { guid -> viewModel.effects.onBubbleEffectCompleted(guid) },
                onHighlightMessage = { guid -> viewModel.highlightMessage(guid) },
                onClearHighlight = { viewModel.clearHighlight() },
                onDownloadAttachment = if (!autoDownloadEnabled) {
                    { attachmentGuid -> viewModel.attachment.downloadAttachment(attachmentGuid) }
                } else null,
                onAddContact = {
                    addContactLauncher.launch(viewModel.operations.getAddToContactsIntent(
                        chatInfoState.participantPhone,
                        chatInfoState.inferredSenderName
                    ))
                },
                onReportSpam = {
                    viewModel.operations.reportAsSpam()
                    if (chatInfoState.isLocalSmsChat) {
                        viewModel.operations.blockContact(context, chatInfoState.participantPhone)
                    }
                    viewModel.chatInfo.dismissSaveContactBanner()
                },
                onDismissSaveContactBanner = viewModel.chatInfo::dismissSaveContactBanner,
                onMarkAsSafe = { viewModel.operations.markAsSafe() },
                onStartSharingEta = { viewModel.etaSharing.startSharingEta(chatGuid, chatInfoState.chatTitle) },
                onStopSharingEta = { viewModel.etaSharing.stopSharingEta() },
                onDismissEtaBanner = { viewModel.etaSharing.dismissBanner() },
                onExitSmsFallback = viewModel::exitSmsFallback,
                onSearchQueryChange = { query -> viewModel.search.updateSearchQuery(query, messages) },
                onCloseSearch = viewModel.search::closeSearch,
                onNavigateSearchUp = viewModel.search::navigateSearchUp,
                onNavigateSearchDown = viewModel.search::navigateSearchDown,
                onViewAllSearchResults = viewModel.search::showResultsSheet
            ),

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
            composerHeightPx = state.composerHeightPx,

            // Server connection
            isServerConnected = syncState.isServerConnected
        )

    } // End of content Box


    // SnackbarHost overlay
    SnackbarHost(
        hostState = state.snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )

    // Screen effect overlay (above all other content) - connected to ViewModel
    ScreenEffectOverlay(
        effect = activeScreenEffectState?.effect,
        messageText = activeScreenEffectState?.messageText,
        onEffectComplete = {
            viewModel.effects.onScreenEffectCompleted()
        }
    )

    // Thread overlay - shows when user taps a reply indicator
    AnimatedThreadOverlay(
        threadChain = threadOverlayState,
        onMessageClick = { guid -> viewModel.thread.scrollToMessage(guid) },
        onDismiss = { viewModel.thread.dismissThreadOverlay() }
    )
    } // End of outer Box
    } // End of CompositionLocalProvider

    // All dialogs and bottom sheets are now hosted in ChatDialogsHost
    ChatDialogsHost(
        viewModel = viewModel,
        context = context,
        chatInfoState = chatInfoState,
        connectionState = connectionState,
        operationsState = operationsState,
        sendState = sendState,
        searchState = searchState,
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
        pendingContactData = state.pendingContactData,
        forwardableChats = forwardableChats,
        draftText = draftText,
        pendingAttachments = pendingAttachments,
        attachmentQuality = uiState.attachmentQuality,
        isWhatsAppAvailable = isWhatsAppAvailable,
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
                state.showAttachmentPicker = false
            }
        },
        onShowDiscordSetup = { state.showDiscordSetupDialog = true },
        onShowDiscordHelp = { state.showDiscordHelpOverlay = true },
        onClearPendingContactData = { state.pendingContactData = null },
        onClearMessageToForward = { state.messageToForward = null }
    )
}


