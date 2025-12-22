package com.bothbubbles.ui.chat

import timber.log.Timber
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.media.ExoPlayerPool
import com.bothbubbles.services.notifications.Notifier
import com.bothbubbles.services.messaging.ChatFallbackTracker
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.chat.composer.MentionSuggestion
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
import com.bothbubbles.ui.chat.delegates.ChatComposerDelegate
import com.bothbubbles.ui.chat.delegates.ChatConnectionDelegate
import com.bothbubbles.ui.chat.delegates.ChatEffectsDelegate
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatInfoDelegate
import com.bothbubbles.ui.chat.delegates.ChatReelsDelegate
import com.bothbubbles.ui.chat.delegates.CursorChatMessageListDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatScheduledMessageDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendModeManager
import com.bothbubbles.ui.chat.delegates.ChatSyncDelegate
import com.bothbubbles.ui.chat.delegates.ChatThreadDelegate
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.util.error.AppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Chat screen that orchestrates messaging functionality.
 *
 * ## Architecture
 *
 * This ViewModel follows the **Delegate Pattern** for decomposition. Complex functionality
 * is delegated to focused handlers:
 *
 * - **ChatSendDelegate**: Message sending, retry, and forwarding (see [sendDelegate])
 * - **ChatComposerDelegate**: Draft text, attachments, and composer UI state (see [composerDelegate])
 * - **ChatMessageListDelegate**: Message loading, paging, and socket observation (see [messageListDelegate])
 * - **ChatSearchDelegate**: In-chat message search (see [searchDelegate])
 * - **ChatOperationsDelegate**: Archive, star, delete, spam, block (see [operationsDelegate])
 * - **ChatEffectsDelegate**: Message effect playback (see [effectsDelegate])
 * - **ChatSyncDelegate**: Adaptive polling and resume sync (see [syncDelegate])
 * - **ChatThreadDelegate**: Thread overlay navigation (see [threadDelegate])
 * - **ChatSendModeManager**: iMessage/SMS mode switching (see [sendModeManager])
 * - **ChatScheduledMessageDelegate**: Scheduled message management (see [scheduledMessageDelegate])
 * - **ChatAttachmentDelegate**: Attachment download and progress (see [attachmentDelegate])
 * - **ChatEtaSharingDelegate**: ETA sharing during navigation (see [etaSharingDelegate])
 *
 * ## State Isolation
 *
 * Each delegate exposes its own StateFlow (e.g., [sendState], [searchState]) to reduce
 * cascade recompositions. ChatScreen should collect from these flows directly rather than
 * through a single monolithic UI state.
 *
 * @see ChatSendDelegate
 * @see ChatComposerDelegate
 * @see ChatMessageListDelegate
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val socketConnection: SocketConnection,
    private val settingsDataStore: SettingsDataStore,
    private val chatFallbackTracker: ChatFallbackTracker,
    private val activeConversationManager: ActiveConversationManager,
    private val notifier: Notifier,
    // Delegate factories for AssistedInject pattern
    private val sendFactory: ChatSendDelegate.Factory,
    private val attachmentFactory: ChatAttachmentDelegate.Factory,
    private val etaSharingFactory: ChatEtaSharingDelegate.Factory,
    private val searchFactory: ChatSearchDelegate.Factory,
    private val operationsFactory: ChatOperationsDelegate.Factory,
    private val syncFactory: ChatSyncDelegate.Factory,
    private val effectsFactory: ChatEffectsDelegate.Factory,
    private val threadFactory: ChatThreadDelegate.Factory,
    private val composerFactory: ChatComposerDelegate.Factory,
    private val messageListFactory: CursorChatMessageListDelegate.Factory,
    private val sendModeFactory: ChatSendModeManager.Factory,
    private val scheduledMessagesFactory: ChatScheduledMessageDelegate.Factory,
    private val chatInfoFactory: ChatInfoDelegate.Factory,
    private val connectionFactory: ChatConnectionDelegate.Factory,
    // Media playback
    val exoPlayerPool: ExoPlayerPool,
    // Reels delegate - initialized with chatGuid in init block
    private val reelsDelegate: ChatReelsDelegate
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val chatGuid: String = checkNotNull(savedStateHandle["chatGuid"])

    // For merged conversations (iMessage + SMS), contains all chat GUIDs
    // If mergedGuids is null or has single entry, this is a regular (non-merged) chat
    private val mergedChatGuids: List<String> = run {
        val mergedGuidsStr: String? = savedStateHandle["mergedGuids"]
        if (mergedGuidsStr.isNullOrBlank()) {
            listOf(chatGuid)
        } else {
            mergedGuidsStr.split(",").filter { it.isNotBlank() }
        }
    }

    // True if this is a merged conversation with multiple underlying chats
    private val isMergedChat: Boolean = mergedChatGuids.size > 1

    // True if running inside a bubble - don't cancel notification (bubble IS the notification)
    private val isBubbleMode: Boolean = savedStateHandle["isBubbleMode"] ?: false

    // Determine initial send mode synchronously from GUID to avoid SMS flash on iMessage chats
    private val initialSendMode: ChatSendMode = if (chatGuid.startsWith("iMessage;", ignoreCase = true)) {
        ChatSendMode.IMESSAGE
    } else {
        ChatSendMode.SMS
    }

    private val _uiState = MutableStateFlow(ChatUiState(currentSendMode = initialSendMode))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Create delegates using factories - these are "born ready"
    val effects: ChatEffectsDelegate = effectsFactory.create(viewModelScope)
    val etaSharing: ChatEtaSharingDelegate = etaSharingFactory.create(viewModelScope)
    val scheduledMessages: ChatScheduledMessageDelegate = scheduledMessagesFactory.create(chatGuid, viewModelScope)
    val thread: ChatThreadDelegate = threadFactory.create(viewModelScope, mergedChatGuids)
    val search: ChatSearchDelegate = searchFactory.create(viewModelScope, mergedChatGuids)
    val attachment: ChatAttachmentDelegate = attachmentFactory.create(chatGuid, viewModelScope, mergedChatGuids)
    val chatInfo: ChatInfoDelegate = chatInfoFactory.create(chatGuid, viewModelScope, mergedChatGuids)
    val operations: ChatOperationsDelegate = operationsFactory.create(chatGuid, viewModelScope)
    val sync: ChatSyncDelegate = syncFactory.create(chatGuid, viewModelScope, mergedChatGuids)
    val send: ChatSendDelegate = sendFactory.create(chatGuid, viewModelScope)

    // Message list delegate - cursor-based pagination with Room as single source of truth
    val messageList: CursorChatMessageListDelegate = messageListFactory.create(
        chatGuid = chatGuid,
        mergedChatGuids = mergedChatGuids,
        scope = viewModelScope,
        savedStateHandle = savedStateHandle,
        uiStateFlow = _uiState
    ) { transform -> _uiState.update { it.transform() } }

    // sendMode needs isGroup and participantPhone - created lazily after chatInfo loads
    // For now, create with null participantPhone and update later
    val sendMode: ChatSendModeManager = sendModeFactory.create(
        chatGuid = chatGuid,
        scope = viewModelScope,
        initialSendMode = initialSendMode,
        isGroup = false, // Updated after chatInfo loads
        participantPhone = null // Updated after chatInfo loads
    )

    val connection: ChatConnectionDelegate = connectionFactory.create(
        chatGuid, viewModelScope, sendMode, mergedChatGuids
    )

    // Reels delegate - public accessor
    val reels: ChatReelsDelegate = reelsDelegate.also {
        it.initialize(chatGuid, viewModelScope)
    }

    val composer: ChatComposerDelegate = composerFactory.create(
        chatGuid = chatGuid,
        scope = viewModelScope,
        uiStateFlow = _uiState,
        syncStateFlow = sync.state,
        sendStateFlow = send.state,
        messagesStateFlow = messageList.messagesState
    ) { transform -> _uiState.update { it.transform() } }

    // Stage 3: Passthrough properties removed - access delegates directly:
    // - State: viewModel.send.state, viewModel.search.state, viewModel.operations.state, etc.
    // - Composer: viewModel.composer.draftText, viewModel.composer.pendingAttachments, etc.
    // - Messages: viewModel.messageList.messagesState, viewModel.messageList.sparseMessages, etc.
    // - Attachments: viewModel.attachment.downloadProgress, viewModel.attachment.autoDownloadEnabled

    // ============================================================================
    // COMPOSER EVENT HANDLING
    // ============================================================================

    fun onComposerEvent(event: ComposerEvent) {
        // Log Send events for debugging duplicate message issues
        if (event is ComposerEvent.Send || event is ComposerEvent.SendLongPress) {
            Timber.i("[UI_EVENT] ComposerEvent.${event::class.simpleName} received at ${System.currentTimeMillis()}")
        }

        composer.onComposerEvent(
            event = event,
            onSend = { sendMessage() },
            onToggleSendMode = { newMode -> _uiState.update { it.copy(currentSendMode = newMode) } },
            onDismissReply = { clearReply() }
        )
    }

    // Error state for UI display (consolidated into uiState.appError)
    private val _appError = MutableStateFlow<AppError?>(null)
    val appError: StateFlow<AppError?> = _appError.asStateFlow()

    fun clearAppError() {
        _appError.value = null
        _uiState.update { it.copy(appError = null) }
    }

    // Retained passthrough method - still needed by some callers
    fun hasMessageBeenSeen(guid: String): Boolean = messageList.hasMessageBeenSeen(guid)

    init {
        // Phase 3: Delegates are now "born ready" via AssistedInject
        // No initialize() calls needed - delegates self-initialize in their init blocks

        // Phase 4: setDelegates() calls removed - ChatViewModel now coordinates all cross-delegate interactions
        // - sendMessage() coordinates: get input → queue message → insert optimistic → clear input
        // - toggleReaction() coordinates: get message → optimistic update → API call → refresh/rollback
        // - search uses updateSearchQuery(query, messages) with messages from messageList

        // Observe effect settings and update delegate
        viewModelScope.launch {
            combine(
                settingsDataStore.autoPlayEffects,
                settingsDataStore.replayEffectsOnScroll,
                settingsDataStore.reduceMotion
            ) { autoPlay, replayOnScroll, reduceMotion -> Triple(autoPlay, replayOnScroll, reduceMotion) }
                .collect { (autoPlay, replayOnScroll, reduceMotion) ->
                    effects.setAutoPlayEffects(autoPlay)
                    effects.setReplayOnScroll(replayOnScroll)
                    effects.setReduceMotion(reduceMotion)
                }
        }

        // Derive replyToMessage from send.state.replyingToGuid (lookup in messages)
        viewModelScope.launch {
            send.state
                .map { it.replyingToGuid }
                .distinctUntilChanged()
                .collect { guid ->
                    if (guid != null) {
                        val message = messageList.messagesState.value.find { it.guid == guid }
                        _uiState.update { it.copy(replyToMessage = message) }
                    } else {
                        _uiState.update { it.copy(replyToMessage = null) }
                    }
                }
        }

        // Forward attachment refresh trigger to message list delegate
        viewModelScope.launch {
            attachment.refreshTrigger.collect { trigger ->
                if (trigger > 0) {
                    messageList.triggerAttachmentRefresh()
                }
            }
        }

        // Mark as read when new messages arrive via socket (user is viewing the chat)
        viewModelScope.launch {
            messageList.socketNewMessage.collect {
                messageList.markAsRead()
            }
        }
        // Forward ETA sharing delegate's state to UI state
        viewModelScope.launch {
            etaSharing.etaSharingState.collect { etaState ->
                _uiState.update {
                    it.copy(
                        isEtaSharingEnabled = etaState.isEnabled,
                        isNavigationActive = etaState.isNavigationActive,
                        isEtaSharing = etaState.isCurrentlySharing,
                        currentEtaMinutes = etaState.currentEtaMinutes,
                        isEtaBannerDismissed = etaState.isBannerDismissed
                    )
                }
            }
        }

        // Forward ChatInfoDelegate's state to UI state for backward compatibility
        viewModelScope.launch {
            chatInfo.state.collect { infoState ->
                _uiState.update {
                    it.copy(
                        chatTitle = infoState.chatTitle,
                        isGroup = infoState.isGroup,
                        avatarPath = infoState.avatarPath,
                        participantNames = infoState.participantNames,
                        participantAvatarPaths = infoState.participantAvatarPaths,
                        participantPhone = infoState.participantPhone,
                        isLocalSmsChat = infoState.isLocalSmsChat,
                        // smsInputBlocked is combined from both info and connection states below
                        isIMessageChat = infoState.isIMessageChat,
                        showSaveContactBanner = infoState.showSaveContactBanner,
                        unsavedSenderAddress = infoState.unsavedSenderAddress,
                        inferredSenderName = infoState.inferredSenderName,
                        isSnoozed = infoState.isSnoozed,
                        snoozeUntil = infoState.snoozeUntil
                    )
                }

                // Pass participant info to composer for mention suggestions (group chats only)
                if (infoState.isGroup && infoState.participantAddresses.isNotEmpty()) {
                    val suggestions = infoState.participantAddresses.mapIndexedNotNull { index, address ->
                        val fullName = infoState.participantNames.getOrNull(index) ?: return@mapIndexedNotNull null
                        MentionSuggestion(
                            address = address,
                            fullName = fullName,
                            firstName = infoState.participantFirstNames.getOrNull(index),
                            avatarPath = infoState.participantAvatarPaths.getOrNull(index)
                        )
                    }
                    composer.setParticipants(suggestions, isGroup = true)
                } else {
                    composer.setParticipants(emptyList(), isGroup = false)
                }
            }
        }

        // Combine smsInputBlocked from both sources:
        // - ChatInfoDelegate: blocked due to SMS permissions (not default SMS app)
        // - ChatConnectionDelegate: blocked due to server fallback unavailable
        viewModelScope.launch {
            combine(
                chatInfo.state,
                connection.state
            ) { infoState, connState ->
                infoState.smsInputBlocked || connState.serverFallbackBlocked
            }.collect { blocked ->
                _uiState.update { it.copy(smsInputBlocked = blocked) }
            }
        }

        // Forward ChatConnectionDelegate's state to UI state for backward compatibility
        viewModelScope.launch {
            connection.state.collect { connState ->
                _uiState.update {
                    it.copy(
                        currentSendMode = connState.currentSendMode,
                        contactIMessageAvailable = connState.contactIMessageAvailable,
                        isCheckingIMessageAvailability = connState.isCheckingIMessageAvailability,
                        canToggleSendMode = connState.canToggleSendMode,
                        showSendModeRevealAnimation = connState.showSendModeRevealAnimation,
                        sendModeManuallySet = connState.sendModeManuallySet,
                        tutorialState = connState.tutorialState,
                        counterpartSynced = connState.counterpartSynced
                    )
                }
            }
        }

        // Track this conversation as active to suppress notifications while viewing
        activeConversationManager.setActiveConversation(chatGuid, mergedChatGuids.toSet())

        // Cancel any existing notifications/bubbles for this chat (and merged chats)
        // Skip in bubble mode - the bubble IS the notification, cancelling it would dismiss the bubble
        if (!isBubbleMode) {
            mergedChatGuids.forEach { guid ->
                notifier.cancelNotification(guid)
            }
        }

        // Notify server which chat is open (helps server optimize notification delivery)
        socketConnection.sendOpenChat(chatGuid)

        // Chat metadata loading, determineChatType, and observeParticipantsForSaveContactBanner
        // are now handled by ChatInfoDelegate (self-initializes via AssistedInject).
        // Draft loading is handled by ChatComposerDelegate.
        // Operations state (archive/star/spam) is handled by ChatOperationsDelegate.
        loadPersistedSendMode()

        // Note: Message loading, socket observation, and sync are now handled by messageList.
        // Typing indicators are now handled by ChatSyncDelegate.
        messageList.syncMessages()
        messageList.markAsRead()
        // Forward connection.fallbackState to sync delegate
        viewModelScope.launch {
            connection.fallbackState.collect { fallback ->
                sync.setSmsFallbackMode(
                    isInFallback = fallback.isInFallback,
                    reason = fallback.reason
                )
            }
        }
        // Stage 2A Logic Migration: The following are now handled by delegates:
        // - observeFallbackMode → ChatConnectionDelegate
        // - observeSendModeManagerState → ChatConnectionDelegate.state
        // - observeAutoDownloadSetting → ChatAttachmentDelegate
        // - observeUploadProgress → ChatSendDelegate
        // - observeQueuedMessages → ChatSendDelegate
        // - checkAndRepairCounterpart → ChatConnectionDelegate

        saveCurrentChatState()
        connection.checkAndRepairCounterpart() // Lazy repair: check for missing iMessage/SMS counterpart

        // Check iMessage availability after chat data is loaded
        // Delay slightly to ensure chatInfo has populated participantPhone and isGroup
        viewModelScope.launch {
            delay(500) // Wait for chatInfo to load
            val currentState = _uiState.value

            // Check if iMessage is available again (for chats in SMS fallback mode)
            sendMode.checkAndMaybeExitFallback(currentState.participantPhone)

            // Check iMessage availability for the contact (for send mode switching)
            sendMode.checkIMessageAvailability(
                isGroup = currentState.isGroup,
                isIMessageChat = currentState.isIMessageChat,
                isLocalSmsChat = currentState.isLocalSmsChat,
                participantPhone = currentState.participantPhone
            ) { available, isChecking, mode, canToggle, showReveal ->
                _uiState.update { state ->
                    state.copy(
                        contactIMessageAvailable = available,
                        isCheckingIMessageAvailability = isChecking,
                        currentSendMode = mode,
                        canToggleSendMode = canToggle,
                        showSendModeRevealAnimation = showReveal
                        // Note: smsInputBlocked is now solely controlled by ChatInfoDelegate (permissions)
                        // and combined with ChatConnectionDelegate.serverFallbackBlocked
                    )
                }
                // Initialize tutorial if toggle just became available
                if (canToggle) {
                    initTutorialIfNeeded()
                }
            }
        }
    }

    /**
     * Save current chat state immediately for state restoration.
     * Called in init so state is persisted as soon as chat opens.
     */
    private fun saveCurrentChatState() {
        Timber.tag("StateRestore").e("saveCurrentChatState CALLED: chatGuid=$chatGuid")
        viewModelScope.launch {
            val mergedGuidsStr = if (isMergedChat) mergedChatGuids.joinToString(",") else null
            Timber.tag("StateRestore").e("saveCurrentChatState SAVING: chatGuid=$chatGuid")
            settingsDataStore.setLastOpenChat(chatGuid, mergedGuidsStr)
        }
    }

    // setAttachmentQuality - use viewModel.composer.setAttachmentQuality() directly
    // observeUploadProgress - now handled by ChatSendDelegate

    // downloadPendingAttachments, downloadAttachment, isDownloading, getDownloadProgress - use viewModel.attachment.* directly
    // observeQueuedMessages - now handled by ChatSendDelegate

    // Stage 3: Passthrough methods removed - access delegates directly:
    // - viewModel.send.retryQueuedMessage(), cancelQueuedMessage()
    // - viewModel.sendMode.setSendMode(), tryToggleSendMode(), canToggleSendModeNow()
    // - viewModel.sendMode.markRevealAnimationShown(), updateTutorialState(), onTutorialToggleSuccess()

    /**
     * Initialize tutorial state based on whether user has completed it before.
     * Note: This is still needed here for the init block callback.
     */
    private fun initTutorialIfNeeded() {
        // Tutorial state is now managed by sendModeManager
        // This method is called from the init callback when toggle becomes available
        if (!_uiState.value.canToggleSendMode) return

        viewModelScope.launch {
            settingsDataStore.hasCompletedSendModeTutorial.first().let { completed ->
                if (!completed) {
                    sendMode.updateTutorialState(TutorialState.STEP_1_SWIPE_UP)
                }
            }
        }
    }

    // syncMessages, syncSmsMessages - now handled by ChatMessageListDelegate.syncMessages()

    // checkAndRepairCounterpart - now handled by ChatConnectionDelegate.checkAndRepairCounterpart()

    // determineChatType() and observeParticipantsForSaveContactBanner() are now handled
    // by ChatInfoDelegate.initialize() - see chatInfo.state for the results.
    // Access chatInfo.dismissSaveContactBanner() and chatInfo.refreshContactInfo() directly.

    /**
     * Load persisted send mode preference from database.
     * Chat type determination is handled by ChatInfoDelegate.
     */
    private fun loadPersistedSendMode() {
        viewModelScope.launch {
            try {
                val chat = chatRepository.getChat(chatGuid)
                val preferredMode = chat?.preferredSendMode
                if (chat != null && chat.sendModeManuallySet && preferredMode != null) {
                    val persistedMode = when (preferredMode.lowercase()) {
                        "imessage" -> ChatSendMode.IMESSAGE
                        "sms" -> ChatSendMode.SMS
                        else -> null
                    }
                    if (persistedMode != null) {
                        Timber.d("Loaded persisted send mode: $persistedMode for chat $chatGuid")
                        _uiState.update {
                            it.copy(
                                currentSendMode = persistedMode,
                                sendModeManuallySet = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load persisted send mode")
            }
        }
    }

    fun exitSmsFallback() {
        chatFallbackTracker.exitFallbackMode(chatGuid)
    }


    /**
     * Jump to a specific message, loading its position from the database if needed.
     * The cursor delegate handles positioning internally.
     *
     * @param guid The message GUID to jump to
     * @return True if jump succeeded, false otherwise
     */
    suspend fun jumpToMessage(guid: String): Boolean {
        return messageList.jumpToMessage(guid)
    }

    /**
     * Highlight a message with an iOS-like blink animation.
     * Called after scrolling to a message from notification deep-link.
     */
    fun highlightMessage(guid: String) {
        _uiState.update { it.copy(highlightedMessageGuid = guid) }
    }

    /**
     * Clear the highlighted message after animation completes.
     */
    fun clearHighlight() {
        _uiState.update { it.copy(highlightedMessageGuid = null) }
    }

    // loadDraftAndOperationsState - now handled by:
    // - Draft loading: ChatComposerDelegate.loadDraftFromChat()
    // - Operations state: ChatOperationsDelegate.observeChatState()

    /**
     * Called by ChatScreen when scroll position changes.
     * Note: Cursor-based pagination uses growing query limit, so this is a no-op.
     * Retained for API compatibility.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onScrollPositionChanged(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        // Cursor delegate uses growing query limit - no position-based loading needed
    }

    /**
     * Update draft text with typing indicator and persistence.
     * Delegates to ChatComposerDelegate.
     */
    fun updateDraft(text: String) {
        composer.updateDraft(text)
    }

    /**
     * Called when leaving the chat to ensure we send stopped-typing and save draft
     */
    fun onChatLeave() {
        // Clear active conversation tracking to resume notifications
        activeConversationManager.clearActiveConversation()

        // Notify server we're leaving this chat
        socketConnection.sendCloseChat(chatGuid)

        // Send stopped-typing and save draft via composer delegate
        composer.sendStoppedTyping()
        composer.saveDraftImmediately()

        // Delegate state saving to message list
        viewModelScope.launch {
            messageList.onChatLeave()
        }
    }

    /**
     * Update scroll position for state restoration.
     * Delegated to messageList delegate.
     */
    fun updateScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int, visibleItemCount: Int = 10) {
        messageList.updateScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset, visibleItemCount)
    }

    /**
     * Mark that the user has navigated away from chat (back to conversation list).
     * This clears the saved chat state so the app opens to conversation list next time.
     */
    fun onNavigateBack() {
        Timber.tag("StateRestore").d("onNavigateBack: clearing saved chat state")
        viewModelScope.launch {
            settingsDataStore.clearLastOpenChat()
        }
    }

    override fun onCleared() {
        super.onCleared()
        onChatLeave()
        // Dispose message list delegate (cancels observers, clears cache)
        messageList.dispose()
    }


    // ============================================================================
    // SEND MESSAGE
    // Phase 4: ViewModel coordinates the entire send flow (ADR 0001)
    // ============================================================================

    /**
     * Send the current message with optional effect.
     * Phase 4: ViewModel coordinates the entire send flow:
     * 1. Get input from composer
     * 2. Get send mode from connection
     * 3. Cancel typing indicator
     * 4. Clear composer input immediately for responsive feel
     * 5. Queue message (returns info for optimistic UI)
     * 6. Insert optimistic message into message list
     * 7. Clear draft from database
     */
    fun sendMessage(effectId: String? = null) {
        // Step 1: Get input from composer
        val text = composer.draftText.value.trim()
        val attachments = composer.pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) {
            return
        }

        // Step 2: Get send mode and chat info
        val currentSendMode = connection.state.value.currentSendMode
        val isLocalSmsChat = chatInfo.state.value.isLocalSmsChat

        // Step 2.5: Get attributedBodyJson BEFORE clearing (for mentions)
        val attributedBodyJson = composer.buildAttributedBodyJson()

        // Step 3: Cancel typing indicator immediately
        send.cancelTypingIndicator()

        // Step 4: Clear composer input immediately for responsive feel
        composer.clearInput()
        composer.clearMentions()

        viewModelScope.launch {
            // Step 5: Queue message and get info for optimistic UI
            val result = send.queueMessageForSending(
                text = text,
                attachments = attachments,
                currentSendMode = currentSendMode,
                isLocalSmsChat = isLocalSmsChat,
                effectId = effectId,
                attributedBodyJson = attributedBodyJson
            )

            result.onSuccess { queuedInfo ->
                // Step 6: Insert optimistic message using QueuedMessageInfo
                messageList.insertOptimisticMessage(queuedInfo)

                // Step 7: Clear draft from database
                composer.clearDraftFromDatabase()
            }.onFailure { error ->
                Timber.e(error, "Failed to queue message")
                // Error state is updated by ChatSendDelegate
                // Note: No need to remove optimistic message here because it was never inserted
                // (queueMessageForSending returns a Result, so we only insert on success)
            }
        }
    }

    /**
     * Set the message to reply to (for swipe-to-reply)
     */
    fun setReplyTo(messageGuid: String) {
        send.setReplyTo(messageGuid)
    }

    /**
     * Clear the reply state
     */
    fun clearReply() {
        send.clearReply()
    }

    /**
     * Toggle a reaction on a message.
     * Phase 4: ViewModel coordinates the entire reaction flow:
     * 1. Find message and check if server-origin
     * 2. Apply optimistic update to message list
     * 3. Call API via operations delegate
     * 4. Room flow will automatically update UI when database changes
     */
    fun toggleReaction(messageGuid: String, tapback: Tapback) {
        // Step 1: Find message and validate
        val messages = messageList.messagesState.value
        val message = messages.find { it.guid == messageGuid } ?: return

        // Guard: Only allow on server-origin messages (IMESSAGE or SERVER_SMS)
        if (!message.isServerOrigin) return

        val isRemoving = tapback in message.myReactions
        val messageText = message.text ?: ""

        viewModelScope.launch {
            // Step 2: Apply optimistic update to message list
            messageList.applyReactionOptimistically(messageGuid, tapback, isRemoving)

            // Step 3: Call API via operations delegate
            val result = operations.sendReactionToggle(
                messageGuid = messageGuid,
                tapback = tapback,
                isRemoving = isRemoving,
                messageText = messageText
            )

            // Step 4: Room flow automatically updates UI when database changes
            // Success: Server data flows through Room → UI
            // Failure: Optimistic update gets overwritten by next Room emission
            result.onSuccess {
                Timber.d("toggleReaction: API success for $messageGuid")
            }.onFailure { error ->
                Timber.e(error, "toggleReaction: API failed for $messageGuid")
            }
        }
    }

    // ============================================================================
    // SEARCH
    // Phase 4: ViewModel coordinates search by providing messages from messageList
    // ============================================================================

    /**
     * Update search query.
     * Phase 4: ViewModel coordinates by providing messages from messageList.
     */
    fun updateSearchQuery(query: String) {
        search.updateSearchQuery(query, messageList.messagesState.value)
    }

    // Stage 3: Passthrough methods removed - access delegates directly:
    // - viewModel.send.retryMessage(), retryMessageAsSms(), forwardMessage(), clearForwardSuccess(), canRetryAsSms()
    // - viewModel.messageList.loadMoreMessages()
    // - viewModel.operations.archiveChat(), unarchiveChat(), toggleStarred(), deleteChat(), toggleSubjectField()
    // - viewModel.operations.getAddToContactsIntent(), getGoogleMeetIntent(), getWhatsAppCallIntent(), getHelpIntent()
    // - viewModel.operations.blockContact(), isWhatsAppAvailable(), markAsSafe(), reportAsSpam(), reportToCarrier()
    // - viewModel.search.activateSearch(), closeSearch(), navigateSearchUp/Down(), showResultsSheet()
    // - viewModel.scheduledMessages.scheduleMessage(), cancelScheduledMessage(), updateScheduledTime(), deleteScheduledMessage()
    // - viewModel.etaSharing.startSharingEta(), stopSharingEta(), dismissBanner()

    /**
     * Get all available chats for forwarding (excluding current chat).
     * Retained because it uses chatGuid which is private.
     */
    fun getForwardableChats(): Flow<List<com.bothbubbles.data.local.db.entity.ChatEntity>> {
        return chatRepository.observeActiveChats()
            .map { chats -> chats.filter { it.guid != chatGuid } }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, appError = null) }
    }

    /**
     * Scroll to a specific message by GUID and highlight it.
     * Uses paging-aware loading and runs in viewModelScope.
     * Retained because it uses viewModelScope and multiple delegates together.
     */
    fun scrollToAndHighlightMessage(messageGuid: String) {
        viewModelScope.launch {
            val success = messageList.jumpToMessage(messageGuid)
            if (success) {
                // Emit scroll event for the UI to handle
                thread.emitScrollEvent(messageGuid)
                // Highlight after scroll
                delay(100)
                highlightMessage(messageGuid)
            }
        }
    }
}

