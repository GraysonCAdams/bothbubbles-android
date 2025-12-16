package com.bothbubbles.ui.chat

import android.net.Uri
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
import androidx.compose.runtime.rememberUpdatedState
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

        // TopBar overlay at top
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { state.topBarHeightPx = it.height.toFloat() }
                .zIndex(1f)
        ) {
            // Wave 2: ChatTopBar uses delegates for internal state collection
            ChatTopBar(
                operationsDelegate = viewModel.operations,
                chatInfoDelegate = viewModel.chatInfo,
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
        // Wave 2: replyingToMessage calculation moved to ChatInputUI (collected internally)

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { newSize ->
                    // PERF FIX: Only track the BASE height (minimum) to avoid
                    // recomposition during keyboard/panel open animation.
                    // When keyboard/panels close, height decreases back to base.
                    val newHeight = newSize.height.toFloat()
                    if (state.bottomBarBaseHeightPx == 0f || newHeight < state.bottomBarBaseHeightPx) {
                        state.bottomBarBaseHeightPx = newHeight
                    }
                }
                .zIndex(1f)
        ) {
            ChatInputUI(
                // Wave 2: Delegates for internal state collection
                sendDelegate = viewModel.send,
                messageListDelegate = viewModel.messageList,
                composerDelegate = viewModel.composer,
                audioState = audioState,
                isLocalSmsChat = chatInfoState.isLocalSmsChat,
                // Wave 2: Deprecated params removed (sendState, smartReplySuggestions,
                // replyingToMessage, gifPickerState, gifSearchQuery) - now collected internally
                onCameraClick = onCameraClick,
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
                onLoadThread = viewModel.thread::loadThread,
                onRetryMessage = viewModel.send::retryMessage,
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
                    viewModel.etaSharing.startSharingEta(currentChatGuid, currentChatInfoState.chatTitle)
                },
                onStopSharingEta = viewModel.etaSharing::stopSharingEta,
                onDismissEtaBanner = viewModel.etaSharing::dismissBanner,
                onExitSmsFallback = viewModel::exitSmsFallback,
                // Phase 4: ViewModel coordinates search with messages from messageList
                onSearchQueryChange = viewModel::updateSearchQuery,
                onCloseSearch = viewModel.search::closeSearch,
                onNavigateSearchUp = viewModel.search::navigateSearchUp,
                onNavigateSearchDown = viewModel.search::navigateSearchDown,
                onViewAllSearchResults = viewModel.search::showResultsSheet
            )
        }

        // Stage 2: Extracted ChatMessageList component
        ChatMessageList(
            modifier = Modifier.fillMaxSize(),
            chatScreenState = state,
            messages = messages,

            // Wave 2: All delegates for internal state collection
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
            isLoadingMore = uiState.isLoadingMore,
            isSyncingMessages = uiState.isSyncingMessages,
            // Wave 2: isLoadingFromServer, initialLoadComplete, autoDownloadEnabled
            // now collected internally from delegates

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
            isServerConnected = viewModel.sync.state.collectAsState().value.isServerConnected
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

    // Wave 2: Thread overlay uses delegate for internal state collection
    AnimatedThreadOverlay(
        threadDelegate = viewModel.thread,
        onMessageClick = { guid -> viewModel.thread.scrollToMessage(guid) },
        onDismiss = { viewModel.thread.dismissThreadOverlay() }
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
        onClearMessageToForward = { state.messageToForward = null }
    )
}


