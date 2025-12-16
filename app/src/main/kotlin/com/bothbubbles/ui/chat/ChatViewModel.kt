package com.bothbubbles.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.media.ExoPlayerPool
import com.bothbubbles.services.messaging.ChatFallbackTracker
import android.util.Log
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
import com.bothbubbles.ui.chat.delegates.ChatComposerDelegate
import com.bothbubbles.ui.chat.delegates.ChatConnectionDelegate
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatEffectsDelegate
import com.bothbubbles.ui.chat.delegates.ChatInfoDelegate
import com.bothbubbles.ui.chat.delegates.ChatMessageListDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendModeManager
import com.bothbubbles.ui.chat.delegates.ChatSyncDelegate
import com.bothbubbles.ui.chat.delegates.ChatScheduledMessageDelegate
import com.bothbubbles.ui.chat.delegates.ChatThreadDelegate
import com.bothbubbles.util.error.AppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore,
    private val chatFallbackTracker: ChatFallbackTracker,
    private val activeConversationManager: ActiveConversationManager,
    // Delegates for decomposition - publicly exposed for thin controller pattern
    val send: ChatSendDelegate,
    val attachment: ChatAttachmentDelegate,
    val etaSharing: ChatEtaSharingDelegate,
    val search: ChatSearchDelegate,
    val operations: ChatOperationsDelegate,
    val sync: ChatSyncDelegate,
    val effects: ChatEffectsDelegate,
    val thread: ChatThreadDelegate,
    val composer: ChatComposerDelegate,
    val messageList: ChatMessageListDelegate,
    val sendMode: ChatSendModeManager,
    val scheduledMessages: ChatScheduledMessageDelegate,
    // New Stage 1 delegates for state decomposition
    val chatInfo: ChatInfoDelegate,
    val connection: ChatConnectionDelegate,
    // Media playback
    val exoPlayerPool: ExoPlayerPool
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
        // Send mode constants moved to ChatSendModeManager
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

    // Determine initial send mode synchronously from GUID to avoid SMS flash on iMessage chats
    private val initialSendMode: ChatSendMode = if (chatGuid.startsWith("iMessage;", ignoreCase = true)) {
        ChatSendMode.IMESSAGE
    } else {
        ChatSendMode.SMS
    }

    private val _uiState = MutableStateFlow(ChatUiState(currentSendMode = initialSendMode))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Stage 3: Passthrough properties removed - access delegates directly:
    // - State: viewModel.send.state, viewModel.search.state, viewModel.operations.state, etc.
    // - Composer: viewModel.composer.draftText, viewModel.composer.pendingAttachments, etc.
    // - Messages: viewModel.messageList.messagesState, viewModel.messageList.sparseMessages, etc.
    // - Attachments: viewModel.attachment.downloadProgress, viewModel.attachment.autoDownloadEnabled

    // ============================================================================
    // COMPOSER EVENT HANDLING
    // ============================================================================

    fun onComposerEvent(event: ComposerEvent) {
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
        // Initialize delegates
        send.initialize(chatGuid, viewModelScope)
        attachment.initialize(chatGuid, viewModelScope, mergedChatGuids)
        etaSharing.initialize(viewModelScope)
        search.initialize(viewModelScope)
        operations.initialize(chatGuid, viewModelScope)
        sync.initialize(chatGuid, viewModelScope, mergedChatGuids)
        effects.initialize(viewModelScope)
        thread.initialize(viewModelScope, mergedChatGuids)
        scheduledMessages.initialize(chatGuid, viewModelScope)
        // Initialize new Stage 1 delegates
        chatInfo.initialize(chatGuid, viewModelScope, mergedChatGuids)
        connection.initialize(chatGuid, viewModelScope, sendMode, mergedChatGuids)
        // Initialize messageList first so we can pass its messagesState to composer
        messageList.initialize(
            chatGuid = chatGuid,
            mergedChatGuids = mergedChatGuids,
            scope = viewModelScope,
            uiState = _uiState,
            onUiStateUpdate = { transform -> _uiState.update { it.transform() } }
        )
        composer.initialize(
            chatGuid = chatGuid,
            scope = viewModelScope,
            uiState = _uiState,
            syncState = sync.state,
            sendState = send.state,
            messagesState = messageList.messagesState,
            onUiStateUpdate = { transform -> _uiState.update { it.transform() } }
        )

        // Set delegate references for full send flow (enables optimistic UI in delegate)
        send.setDelegates(
            messageList = messageList,
            composer = composer,
            chatInfo = chatInfo,
            connection = connection,
            onDraftCleared = { composer.clearDraftFromDatabase() }
        )

        // Set delegate references for operations (enables reaction toggling in delegate)
        operations.setMessageListDelegate(messageList)

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
                        smsInputBlocked = infoState.smsInputBlocked,
                        isIMessageChat = infoState.isIMessageChat,
                        showSaveContactBanner = infoState.showSaveContactBanner,
                        unsavedSenderAddress = infoState.unsavedSenderAddress,
                        inferredSenderName = infoState.inferredSenderName,
                        isSnoozed = infoState.isSnoozed,
                        snoozeUntil = infoState.snoozeUntil
                    )
                }
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

        // Notify server which chat is open (helps server optimize notification delivery)
        socketService.sendOpenChat(chatGuid)

        // Chat metadata loading, determineChatType, and observeParticipantsForSaveContactBanner
        // are now handled by chatInfo.initialize() above.
        // Draft loading is handled by composer.initialize() above.
        // Operations state (archive/star/spam) is handled by operations.initialize() above.
        loadPersistedSendMode()

        // Note: Message loading, socket observation, and sync are now handled by messageList
        // Typing indicators are now handled by sync.initialize() above.
        messageList.syncMessages() // Always sync to check for new messages (runs in background)
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

        // Initialize send mode manager and check iMessage availability
        // Delay slightly to ensure chat data is loaded first
        viewModelScope.launch {
            delay(500) // Wait for loadChat() to populate participantPhone and isGroup
            val currentState = _uiState.value

            // Initialize send mode manager with chat data
            sendMode.initialize(
                chatGuid = chatGuid,
                scope = viewModelScope,
                initialSendMode = currentState.currentSendMode,
                isGroup = currentState.isGroup,
                participantPhone = currentState.participantPhone
            )

            // Check if iMessage is available again (for chats in SMS fallback mode)
            sendMode.checkAndMaybeExitFallback(currentState.participantPhone)

            // Check iMessage availability for the contact (for send mode switching)
            sendMode.checkIMessageAvailability(
                isGroup = currentState.isGroup,
                isIMessageChat = currentState.isIMessageChat,
                isLocalSmsChat = currentState.isLocalSmsChat,
                participantPhone = currentState.participantPhone
            ) { available, isChecking, mode, canToggle, showReveal, smsBlocked ->
                _uiState.update { state ->
                    state.copy(
                        contactIMessageAvailable = available,
                        isCheckingIMessageAvailability = isChecking,
                        currentSendMode = mode,
                        canToggleSendMode = canToggle,
                        showSendModeRevealAnimation = showReveal,
                        smsInputBlocked = smsBlocked
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
        android.util.Log.e("StateRestore", "saveCurrentChatState CALLED: chatGuid=$chatGuid")
        viewModelScope.launch {
            val mergedGuidsStr = if (isMergedChat) mergedChatGuids.joinToString(",") else null
            android.util.Log.e("StateRestore", "saveCurrentChatState SAVING: chatGuid=$chatGuid")
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
                if (chat != null && chat.sendModeManuallySet && chat.preferredSendMode != null) {
                    val persistedMode = when (chat.preferredSendMode.lowercase()) {
                        "imessage" -> ChatSendMode.IMESSAGE
                        "sms" -> ChatSendMode.SMS
                        else -> null
                    }
                    if (persistedMode != null) {
                        Log.d(TAG, "Loaded persisted send mode: $persistedMode for chat $chatGuid")
                        _uiState.update {
                            it.copy(
                                currentSendMode = persistedMode,
                                sendModeManuallySet = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load persisted send mode", e)
            }
        }
    }

    fun exitSmsFallback() {
        chatFallbackTracker.exitFallbackMode(chatGuid)
    }


    /**
     * Jump to a specific message, loading its position from the database if needed.
     * This is paging-aware and works with sparse loading.
     *
     * @param guid The message GUID to jump to
     * @return The position of the message, or null if not found
     */
    suspend fun jumpToMessage(guid: String): Int? {
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
     * Notifies the paging controller to load data around the visible range.
     */
    fun onScrollPositionChanged(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        messageList.onScrollPositionChanged(firstVisibleIndex, lastVisibleIndex)
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
        socketService.sendCloseChat(chatGuid)

        // Send stopped-typing and save draft via composer delegate
        composer.sendStoppedTyping()
        composer.saveDraftImmediately()

        // Delegate state saving to messageList
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
        android.util.Log.d("StateRestore", "onNavigateBack: clearing saved chat state")
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
    // ============================================================================

    /**
     * Send the current message with optional effect.
     * Fully delegated to ChatSendDelegate which handles optimistic UI internally.
     */
    fun sendMessage(effectId: String? = null) {
        send.sendCurrentMessage(effectId)
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
     * Delegated to ChatOperationsDelegate.
     */
    fun toggleReaction(messageGuid: String, tapback: Tapback) {
        operations.toggleReaction(messageGuid, tapback)
    }

    // Stage 3: Passthrough methods removed - access delegates directly:
    // - viewModel.send.retryMessage(), retryMessageAsSms(), forwardMessage(), clearForwardSuccess(), canRetryAsSms()
    // - viewModel.messageList.loadMoreMessages()
    // - viewModel.operations.archiveChat(), unarchiveChat(), toggleStarred(), deleteChat(), toggleSubjectField()
    // - viewModel.operations.getAddToContactsIntent(), getGoogleMeetIntent(), getWhatsAppCallIntent(), getHelpIntent()
    // - viewModel.operations.blockContact(), isWhatsAppAvailable(), markAsSafe(), reportAsSpam(), reportToCarrier()
    // - viewModel.search.activateSearch(), closeSearch(), updateSearchQuery(), navigateSearchUp/Down(), showResultsSheet()
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
            val position = messageList.jumpToMessage(messageGuid)
            if (position != null) {
                // Emit scroll event for the UI to handle
                thread.emitScrollEvent(messageGuid)
                // Highlight after scroll
                delay(100)
                highlightMessage(messageGuid)
            }
        }
    }
}

