package com.bothbubbles.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.QuickReplyTemplateEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.local.db.entity.PendingMessageEntity
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.ui.chat.composer.ComposerState
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.chat.composer.AttachmentItem
import com.bothbubbles.ui.chat.composer.ComposerInputMode
import com.bothbubbles.ui.chat.composer.ComposerPanel
import com.bothbubbles.ui.chat.composer.ComposerTutorialState
import com.bothbubbles.ui.chat.composer.RecordingState
import com.bothbubbles.ui.chat.composer.MessagePreview
import com.bothbubbles.ui.chat.composer.ComposerAttachmentWarning
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.PendingMessageRepository
import com.bothbubbles.data.repository.QuickReplyTemplateRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.data.repository.GifRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.ContactData
import com.bothbubbles.services.contacts.FieldOptions
import com.bothbubbles.services.contacts.VCardService
import com.bothbubbles.services.media.AttachmentDownloadQueue
import com.bothbubbles.services.media.AttachmentLimitsProvider
import com.bothbubbles.services.media.AttachmentPreloader
import com.bothbubbles.services.media.ExoPlayerPool
import com.bothbubbles.services.media.ValidationError
import com.bothbubbles.services.messaging.ChatFallbackTracker
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.smartreply.SmartReplyService
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.MessageDto
import android.util.Log
import com.bothbubbles.services.imessage.IMessageAvailabilityService
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sound.SoundManager
import com.bothbubbles.services.sync.CounterpartSyncService
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.components.message.EmojiAnalysis
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.ReactionUiModel
import com.bothbubbles.ui.components.message.ReplyPreviewData
import com.bothbubbles.ui.components.input.SuggestionItem
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.util.EmojiUtils.analyzeEmojis
import com.bothbubbles.ui.chat.MessageTransformationUtils.normalizeAddress
import com.bothbubbles.ui.chat.MessageTransformationUtils.toUiModel
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
import com.bothbubbles.ui.chat.delegates.ChatComposerDelegate
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatEffectsDelegate
import com.bothbubbles.ui.chat.delegates.ChatMessageListDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendModeManager
import com.bothbubbles.ui.chat.delegates.ChatSyncDelegate
import com.bothbubbles.ui.chat.delegates.ChatScheduledMessageDelegate
import com.bothbubbles.ui.chat.delegates.ChatThreadDelegate
import com.bothbubbles.ui.chat.delegates.QueuedMessageInfo
import com.bothbubbles.ui.chat.state.EffectsState
import com.bothbubbles.ui.chat.state.ScheduledMessagesState
import com.bothbubbles.ui.chat.state.OperationsState
import com.bothbubbles.ui.chat.state.SearchState
import com.bothbubbles.ui.chat.state.SendState
import com.bothbubbles.ui.chat.state.SyncState
import com.bothbubbles.ui.chat.state.ThreadState
import com.bothbubbles.ui.components.message.formatMessageTime
import com.bothbubbles.ui.chat.paging.SparseMessageList
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.PerformanceProfiler
import com.bothbubbles.util.error.AppError
import com.bothbubbles.util.text.TextNormalization
import com.bothbubbles.util.error.handle
import com.bothbubbles.ui.util.StableList
import com.bothbubbles.ui.util.toStable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val messageRepository: MessageRepository,
    private val smsRepository: SmsRepository,
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore,
    private val chatFallbackTracker: ChatFallbackTracker,
    private val androidContactsService: AndroidContactsService,
    private val activeConversationManager: ActiveConversationManager,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val pendingMessageRepository: PendingMessageRepository,
    private val chatStateCache: ChatStateCache,
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
    private val messageSendingService: MessageSendingService,
    // Sync integrity services
    private val counterpartSyncService: CounterpartSyncService,
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

    // State flows exposed directly from delegates for thin controller pattern
    // ChatScreen can access these directly: viewModel.send.state, viewModel.search.state, etc.
    // Backward compatibility accessors:
    val sendState: StateFlow<SendState> get() = send.state
    val searchState: StateFlow<SearchState> get() = search.state
    val operationsState: StateFlow<OperationsState> get() = operations.state
    val syncState: StateFlow<SyncState> get() = sync.state
    val effectsState: StateFlow<EffectsState> get() = effects.state
    val threadState: StateFlow<ThreadState> get() = thread.state
    val scheduledMessagesState: StateFlow<ScheduledMessagesState> get() = scheduledMessages.state
    val composerState: StateFlow<ComposerState> get() = composer.state
    val draftText: StateFlow<String> get() = composer.draftText
    val pendingAttachments: StateFlow<List<PendingAttachmentInput>> get() = composer.pendingAttachments
    val activePanel: StateFlow<ComposerPanel> get() = composer.activePanel
    val smartReplySuggestions: StateFlow<List<SuggestionItem>> get() = composer.smartReplySuggestions

    // Separate flow for messages - delegated to messageList
    val messagesState: StateFlow<StableList<MessageUiModel>> get() = messageList.messagesState

    // ============================================================================
    // COMPOSER STATE - Delegated to composer
    // ChatScreen should access directly: viewModel.composer.draftText, etc.
    // ============================================================================

    fun onComposerEvent(event: ComposerEvent) {
        composer.onComposerEvent(
            event = event,
            onSend = { sendMessage() },
            onToggleSendMode = { newMode -> _uiState.update { it.copy(currentSendMode = newMode) } },
            onDismissReply = { clearReply() }
        )
    }


    // Error state for UI display (consolidated into uiState.appError, this is for backward compatibility)
    private val _appError = MutableStateFlow<AppError?>(null)
    val appError: StateFlow<AppError?> = _appError.asStateFlow()

    fun clearAppError() {
        _appError.value = null
        _uiState.update { it.copy(appError = null) }
    }

    // Message list state - delegated to messageList
    // ChatScreen should access directly: viewModel.messageList.sparseMessages, etc.
    // Backward compatibility accessors:
    val sparseMessages: StateFlow<SparseMessageList> get() = messageList.sparseMessages
    val totalMessageCount: StateFlow<Int> get() = messageList.totalMessageCount
    val initialLoadComplete: StateFlow<Boolean> get() = messageList.initialLoadComplete
    val isLoadingFromServer: StateFlow<Boolean> get() = messageList.isLoadingFromServer
    val socketNewMessage: SharedFlow<String> get() = messageList.socketNewMessage
    val cachedScrollPosition: StateFlow<Pair<Int, Int>?> get() = messageList.cachedScrollPosition
    fun hasMessageBeenSeen(guid: String): Boolean = messageList.hasMessageBeenSeen(guid)

    // Attachment download progress tracking
    // Maps attachment GUID to download progress (0.0 to 1.0, or null if not downloading)
    private val _attachmentDownloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val attachmentDownloadProgress: StateFlow<Map<String, Float>> = _attachmentDownloadProgress.asStateFlow()

    // Whether to use auto-download mode (true) or manual download mode (false)
    private val _autoDownloadEnabled = MutableStateFlow(true)
    val autoDownloadEnabled: StateFlow<Boolean> = _autoDownloadEnabled.asStateFlow()

    // Draft persistence
    private var draftSaveJob: Job? = null
    private val draftSaveDebounceMs = 500L // Debounce draft saves to avoid excessive DB writes

    // Thread scroll event - exposed from delegate for ChatScreen to collect
    val scrollToGuid: SharedFlow<String> get() = thread.scrollToGuid

    init {
        // Initialize delegates
        send.initialize(chatGuid, viewModelScope)
        attachment.initialize(chatGuid, viewModelScope, mergedChatGuids)
        etaSharing.initialize(viewModelScope)
        search.initialize(viewModelScope)
        operations.initialize(chatGuid, viewModelScope)
        sync.initialize(chatGuid, viewModelScope)
        effects.initialize(viewModelScope)
        thread.initialize(viewModelScope, mergedChatGuids)
        scheduledMessages.initialize(chatGuid, viewModelScope)
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

        // Forward attachment delegate's state to ViewModel state
        viewModelScope.launch {
            combine(
                attachment.downloadProgress,
                attachment.refreshTrigger
            ) { progress, trigger -> Pair(progress, trigger) }
                .collect { (progress, trigger) ->
                    _attachmentDownloadProgress.value = progress
                    // Forward refresh trigger to message list delegate
                    if (trigger > 0) {
                        messageList.triggerAttachmentRefresh()
                    }
                }
        }

        // Mark as read when new messages arrive via socket (user is viewing the chat)
        viewModelScope.launch {
            messageList.socketNewMessage.collect {
                markAsRead()
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

        // Track this conversation as active to suppress notifications while viewing
        activeConversationManager.setActiveConversation(chatGuid, mergedChatGuids.toSet())

        // Notify server which chat is open (helps server optimize notification delivery)
        socketService.sendOpenChat(chatGuid)

        loadChat()
        // Note: Message loading, socket observation, and sync are now handled by messageList
        syncMessages() // Always sync to check for new messages (runs in background)
        observeTypingIndicators()
        markAsRead()
        determineChatType()
        observeParticipantsForSaveContactBanner()
        observeFallbackMode()
        observeSendModeManagerState() // Observe delegate's state flows
        observeAutoDownloadSetting()
        // Note: Quality settings and smart replies are now observed by ChatComposerDelegate
        observeUploadProgress()
        saveCurrentChatState()
        observeQueuedMessages()
        checkAndRepairCounterpart() // Lazy repair: check for missing iMessage/SMS counterpart

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

    /**
     * Observe the auto-download setting and trigger downloads when chat is opened.
     */
    private fun observeAutoDownloadSetting() {
        viewModelScope.launch {
            settingsDataStore.autoDownloadAttachments.collect { autoDownload ->
                _autoDownloadEnabled.value = autoDownload
                if (autoDownload) {
                    // Auto-download pending attachments for this chat
                    downloadPendingAttachments()
                }
            }
        }
    }

    // setAttachmentQuality - use viewModel.composer.setAttachmentQuality() directly

    /**
     * Observe upload progress from MessageSendingService for determinate progress bar.
     * Updates the first pending message with attachments and recalculates aggregate progress.
     * Progress is now managed by ChatSendDelegate's SendState.
     */
    private fun observeUploadProgress() {
        viewModelScope.launch {
            messageSendingService.uploadProgress.collect { progress ->
                if (progress != null) {
                    // Calculate individual message progress (0.0 to 1.0)
                    val attachmentBase = progress.attachmentIndex.toFloat() / progress.totalAttachments
                    val currentProgress = progress.progress / progress.totalAttachments
                    val messageProgress = attachmentBase + currentProgress

                    // Update pending message progress via send delegate
                    val currentState = send.state.value
                    val pendingList = currentState.pendingMessages.toList()
                    val attachmentIndex = pendingList.indexOfFirst { it.hasAttachments }
                    if (attachmentIndex >= 0) {
                        send.updatePendingMessageProgress(
                            pendingList[attachmentIndex].tempGuid,
                            messageProgress
                        )
                    }
                    // Update aggregate progress
                    send.setSendProgress(send.calculateAggregateProgress())
                }
                // Don't reset progress to 0 when progress is null - let completion handlers manage that
            }
        }
    }

    /**
     * Download all pending attachments for this chat (or merged chats).
     * Called automatically when auto-download is enabled, or can be triggered manually.
     * Uses the download queue for prioritized, concurrent downloads.
     */
    private fun downloadPendingAttachments() {
        attachment.downloadPendingAttachments()
    }

    // downloadAttachment, isDownloading, getDownloadProgress - use viewModel.attachment.* directly

    /**
     * Observe queued messages from the database for offline-first UI.
     * These are messages that have been queued for sending but not yet delivered.
     * Queued messages are now managed by ChatSendDelegate's SendState.
     */
    private fun observeQueuedMessages() {
        viewModelScope.launch {
            pendingMessageRepository.observePendingForChat(chatGuid)
                .collect { pending ->
                    send.updateQueuedMessages(pending.map { it.toQueuedUiModel() })
                }
        }
    }

    /**
     * Convert PendingMessageEntity to UI model.
     */
    private fun PendingMessageEntity.toQueuedUiModel(): QueuedMessageUiModel {
        return QueuedMessageUiModel(
            localId = localId,
            text = text,
            hasAttachments = false, // TODO: Check attachments table
            syncStatus = try {
                PendingSyncStatus.valueOf(syncStatus)
            } catch (e: Exception) {
                PendingSyncStatus.PENDING
            },
            errorMessage = errorMessage,
            createdAt = createdAt
        )
    }

    /**
     * Retry a failed queued message.
     */
    fun retryQueuedMessage(localId: String) {
        viewModelScope.launch {
            pendingMessageRepository.retryMessage(localId)
                .onFailure { e ->
                    Log.e(TAG, "Failed to retry message: $localId", e)
                    _uiState.update { it.copy(error = "Failed to retry: ${e.message}") }
                }
        }
    }

    /**
     * Cancel a queued message.
     */
    fun cancelQueuedMessage(localId: String) {
        viewModelScope.launch {
            pendingMessageRepository.cancelMessage(localId)
                .onFailure { e ->
                    Log.e(TAG, "Failed to cancel message: $localId", e)
                    _uiState.update { it.copy(error = "Failed to cancel: ${e.message}") }
                }
        }
    }

    // getCombinedSuggestions, recordTemplateUsage - use viewModel.composer.* directly

    private fun observeFallbackMode() {
        viewModelScope.launch {
            chatFallbackTracker.fallbackStates.collect { fallbackStates ->
                val entry = fallbackStates[chatGuid]
                sync.setSmsFallbackMode(
                    isInFallback = entry != null,
                    reason = entry?.reason
                )
            }
        }
    }

    /**
     * Observe ChatSendModeManager's state flows and forward to _uiState.
     * This keeps send mode, availability, and toggle state in sync with the delegate.
     */
    private fun observeSendModeManagerState() {
        // Observe current send mode
        viewModelScope.launch {
            sendMode.currentSendMode.collect { mode ->
                _uiState.update { it.copy(currentSendMode = mode) }
            }
        }

        // Observe contact iMessage availability
        viewModelScope.launch {
            sendMode.contactIMessageAvailable.collect { available ->
                _uiState.update { it.copy(contactIMessageAvailable = available) }
            }
        }

        // Observe checking state
        viewModelScope.launch {
            sendMode.isCheckingIMessageAvailability.collect { isChecking ->
                _uiState.update { it.copy(isCheckingIMessageAvailability = isChecking) }
            }
        }

        // Observe toggle availability
        viewModelScope.launch {
            sendMode.canToggleSendMode.collect { canToggle ->
                _uiState.update { it.copy(canToggleSendMode = canToggle) }
            }
        }

        // Observe reveal animation state
        viewModelScope.launch {
            sendMode.showSendModeRevealAnimation.collect { showReveal ->
                _uiState.update { it.copy(showSendModeRevealAnimation = showReveal) }
            }
        }

        // Observe manually set state
        viewModelScope.launch {
            sendMode.sendModeManuallySet.collect { manuallySet ->
                _uiState.update { it.copy(sendModeManuallySet = manuallySet) }
            }
        }

        // Observe SMS input blocked state
        viewModelScope.launch {
            sendMode.smsInputBlocked.collect { blocked ->
                _uiState.update { it.copy(smsInputBlocked = blocked) }
            }
        }

        // Observe tutorial state
        viewModelScope.launch {
            sendMode.tutorialState.collect { tutorial ->
                _uiState.update { it.copy(tutorialState = tutorial) }
            }
        }
    }

    // Connection state observation and iMessage availability checking are now handled by ChatSendModeManager

    /**
     * Manually switch send mode (for UI toggle).
     * Delegates to ChatSendModeManager for validation and state management.
     *
     * @param mode The target send mode
     * @param persist Whether to persist the choice to the database (default true)
     * @return true if the switch was successful, false otherwise
     */
    fun setSendMode(mode: ChatSendMode, persist: Boolean = true): Boolean {
        return sendMode.setSendMode(mode, persist)
    }

    /**
     * Try to toggle send mode (for swipe gesture).
     * Returns true if the toggle was successful.
     */
    fun tryToggleSendMode(): Boolean {
        return sendMode.tryToggleSendMode()
    }

    /**
     * Check if send mode toggle is currently available.
     */
    fun canToggleSendMode(): Boolean {
        return sendMode.canToggleSendModeNow()
    }

    /**
     * Mark the send mode reveal animation as shown.
     */
    fun markRevealAnimationShown() {
        sendMode.markRevealAnimationShown()
    }

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

    /**
     * Update tutorial state after user completes a step.
     * Delegates to ChatSendModeManager.
     */
    fun updateTutorialState(newState: TutorialState) {
        sendMode.updateTutorialState(newState)
    }

    /**
     * Handle successful toggle during tutorial.
     * Progresses the tutorial state appropriately.
     * Delegates to ChatSendModeManager.
     */
    fun onTutorialToggleSuccess() {
        sendMode.onTutorialToggleSuccess()
    }

    private fun syncMessages() {
        _uiState.update { it.copy(isSyncingMessages = true) }
        if (messageRepository.isLocalSmsChat(chatGuid)) {
            syncSmsMessages()
        } else {
            messageList.syncMessagesFromServer()
        }
    }

    /**
     * Tier 2 Lazy Repair: Check for missing counterpart chat (iMessage/SMS) and sync if found.
     *
     * When a unified group has only one chat (e.g., SMS only), this checks the server
     * for a counterpart (e.g., iMessage). If found, it syncs the counterpart to local DB
     * and links it to the unified group.
     *
     * This runs in the background and doesn't block the UI. Results are cached to avoid
     * repeated checks for contacts without counterparts (e.g., Android users).
     */
    private fun checkAndRepairCounterpart() {
        // Only run for non-merged conversations (single chat in unified group)
        if (isMergedChat) {
            Log.d(TAG, "Skipping counterpart check - already merged conversation")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Find unified group for this chat
                val unifiedGroup = chatRepository.getUnifiedGroupForChat(chatGuid)
                if (unifiedGroup == null) {
                    Log.d(TAG, "No unified group for $chatGuid - skipping counterpart check")
                    return@launch
                }

                val result = counterpartSyncService.checkAndRepairCounterpart(unifiedGroup.id)
                when (result) {
                    is CounterpartSyncService.CheckResult.Found -> {
                        Log.i(TAG, "Found and synced counterpart: ${result.chatGuid}")
                        // Emit event to refresh conversation (will include new chat's messages on next open)
                        _uiState.update { it.copy(counterpartSynced = true) }
                    }
                    is CounterpartSyncService.CheckResult.NotFound -> {
                        Log.d(TAG, "No counterpart exists for this contact (likely Android user)")
                    }
                    is CounterpartSyncService.CheckResult.AlreadyVerified -> {
                        Log.d(TAG, "Already verified: hasCounterpart=${result.hasCounterpart}")
                    }
                    is CounterpartSyncService.CheckResult.Skipped -> {
                        Log.d(TAG, "Counterpart check skipped (group already complete)")
                    }
                    is CounterpartSyncService.CheckResult.Error -> {
                        Log.w(TAG, "Counterpart check failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for counterpart", e)
            }
        }
    }

    private fun syncSmsMessages() {
        viewModelScope.launch {
            smsRepository.importMessagesForChat(chatGuid, limit = 100).fold(
                onSuccess = { count ->
                    // Messages are now in Room and will be picked up by observeMessagesForChat
                },
                onFailure = { e ->
                    if (messagesState.value.isEmpty()) {
                        _uiState.update { it.copy(error = "Failed to load SMS messages: ${e.message}") }
                    }
                }
            )
            _uiState.update { it.copy(isSyncingMessages = false) }
        }
    }

    private fun determineChatType() {
        val isLocalSms = messageRepository.isLocalSmsChat(chatGuid)
        val isServerForward = chatGuid.startsWith("SMS;", ignoreCase = true)
        val isSmsChat = isLocalSms || isServerForward
        val isDefaultSmsApp = smsPermissionHelper.isDefaultSmsApp()

        // iMessage chats default to iMessage mode, SMS chats default to SMS mode
        // Availability check will verify/update the mode for 1-on-1 chats
        val isIMessageChat = chatGuid.startsWith("iMessage;", ignoreCase = true)
        val initialSendMode = if (isIMessageChat) {
            Log.d(TAG, "DEBUG determineChatType: iMessage chat detected, setting initial mode to IMESSAGE")
            ChatSendMode.IMESSAGE
        } else {
            Log.d(TAG, "DEBUG determineChatType: SMS chat detected, setting initial mode to SMS")
            ChatSendMode.SMS // Availability check may upgrade this to iMessage
        }

        _uiState.update {
            it.copy(
                isLocalSmsChat = isLocalSms,
                isIMessageChat = !isSmsChat,
                smsInputBlocked = isSmsChat && !isDefaultSmsApp,
                currentSendMode = initialSendMode
            )
        }

        // Load persisted send mode preference from database
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

    private fun observeParticipantsForSaveContactBanner() {
        viewModelScope.launch {
            // Combine chat info, participants, dismissed banners, and messages
            // This ensures we have the correct state before checking
            chatRepository.observeChat(chatGuid)
                .filterNotNull()
                .combine(chatRepository.observeParticipantsForChat(chatGuid)) { chat, participants ->
                    Triple(chat, participants, chat.isGroup)
                }
                .combine(settingsDataStore.dismissedSaveContactBanners) { (chat, participants, isGroup), dismissed ->
                    Triple(participants, isGroup, dismissed)
                }
                .combine(messageRepository.observeMessagesForChat(chatGuid, limit = 1, offset = 0)) { (participants, isGroup, dismissed), messages ->
                    // Check if there are any messages received from the other party (not from me)
                    val hasReceivedMessages = messages.any { !it.isFromMe }
                    object {
                        val participants = participants
                        val isGroup = isGroup
                        val dismissed = dismissed
                        val hasReceivedMessages = hasReceivedMessages
                    }
                }
                .collect { state ->
                    // Only show banner for 1-on-1 chats with unsaved contacts that have received messages
                    if (state.isGroup || !state.hasReceivedMessages) {
                        _uiState.update { it.copy(showSaveContactBanner = false, unsavedSenderAddress = null) }
                        return@collect
                    }

                    // For chats without participants in the cross-ref table,
                    // check if the chat title looks like a phone number (unsaved contact)
                    val chatTitle = _uiState.value.chatTitle
                    val participantPhone = _uiState.value.participantPhone

                    // Find the first unsaved participant (no cached display name)
                    val unsavedParticipant = state.participants.firstOrNull { participant ->
                        participant.cachedDisplayName == null &&
                            participant.address !in state.dismissed
                    }

                    // If we have an unsaved participant from the DB, use that
                    // Otherwise, check if the chat title looks like a phone/address (no contact name)
                    val unsavedAddress = when {
                        unsavedParticipant != null -> unsavedParticipant.address
                        state.participants.isEmpty() && participantPhone != null &&
                            participantPhone !in state.dismissed &&
                            looksLikePhoneOrAddress(chatTitle) -> participantPhone
                        else -> null
                    }

                    // Get inferred name from participant if available
                    val inferredName = unsavedParticipant?.inferredName

                    _uiState.update {
                        it.copy(
                            showSaveContactBanner = unsavedAddress != null,
                            unsavedSenderAddress = unsavedAddress,
                            inferredSenderName = inferredName
                        )
                    }
                }
        }
    }

    /**
     * Check if a string looks like a phone number or email address (not a contact name)
     */
    private fun looksLikePhoneOrAddress(text: String): Boolean {
        val trimmed = text.trim()
        // Check for phone number patterns (digits, spaces, dashes, parens, plus)
        val phonePattern = Regex("^[+]?[0-9\\s\\-().]+$")
        // Check for email pattern
        val emailPattern = Regex("^[^@]+@[^@]+\\.[^@]+$")
        return phonePattern.matches(trimmed) || emailPattern.matches(trimmed)
    }

    fun dismissSaveContactBanner() {
        val address = _uiState.value.unsavedSenderAddress ?: return
        viewModelScope.launch {
            settingsDataStore.dismissSaveContactBanner(address)
            _uiState.update { it.copy(showSaveContactBanner = false) }
        }
    }

    /**
     * Refresh contact info from device contacts.
     * Called when returning from the system contacts app after adding a contact.
     */
    fun refreshContactInfo() {
        val address = _uiState.value.unsavedSenderAddress
            ?: _uiState.value.participantPhone
            ?: return

        viewModelScope.launch {
            val displayName = androidContactsService.getContactDisplayName(address)
            val photoUri = androidContactsService.getContactPhotoUri(address)
            if (displayName != null || photoUri != null) {
                // Update the cached display name and photo in the database
                chatRepository.updateHandleCachedContactInfo(address, displayName, photoUri)
                // Hide the save contact banner since they saved the contact
                _uiState.update { it.copy(showSaveContactBanner = false) }
            }
        }
    }

    fun exitSmsFallback() {
        chatFallbackTracker.exitFallbackMode(chatGuid)
    }

    // ===== Thread Overlay Functions (delegated to ChatThreadDelegate) =====

    fun loadThread(originGuid: String) = thread.loadThread(originGuid)
    fun dismissThreadOverlay() = thread.dismissThreadOverlay()
    fun scrollToMessage(guid: String) = thread.scrollToMessage(guid)

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

    private fun loadChat() {
        viewModelScope.launch {
            var draftLoaded = false

            // Observe participants from all chats in merged conversation
            val participantsFlow = chatRepository.observeParticipantsForChats(mergedChatGuids)

            // Combine chat with participants to resolve display name properly
            combine(
                chatRepository.observeChat(chatGuid),
                participantsFlow
            ) { chat, participants -> chat to participants }
            .collect { (chat, participants) ->
                chat?.let {
                    val chatTitle = resolveChatTitle(it, participants)
                    // Load draft only on first observation to avoid overwriting user edits
                    if (!draftLoaded) {
                        composer.restoreDraftText(it.textFieldText)
                    }
                    _uiState.update { state ->
                        state.copy(
                            chatTitle = chatTitle,
                            isGroup = it.isGroup,
                            avatarPath = participants.firstOrNull()?.cachedAvatarPath,
                            participantNames = participants.map { p -> p.displayName }.toStable(),
                            participantAvatarPaths = participants.map { p -> p.cachedAvatarPath }.toStable(),
                            participantPhone = it.chatIdentifier,
                            isSnoozed = it.isSnoozed,
                            snoozeUntil = it.snoozeUntil,
                            isLocalSmsChat = it.isLocalSms,  // Only local SMS, not server forwarding
                            isIMessageChat = it.isIMessage,
                            smsInputBlocked = it.isSmsChat && !smsPermissionHelper.isDefaultSmsApp()
                        )
                    }
                    // Update operations delegate with archive/star/spam state
                    operations.updateState(
                        isArchived = it.isArchived,
                        isStarred = it.isStarred,
                        isSpam = it.isSpam,
                        isReportedToCarrier = it.spamReportedToCarrier
                    )
                    draftLoaded = true
                }
            }
        }
    }

    /**
     * Resolve the display name for a chat, using consistent logic with the conversation list.
     * For 1:1 chats: prefer participant's displayName (from contacts or inferred)
     * For group chats: use chat displayName or generate from participant names
     */
    private fun resolveChatTitle(chat: ChatEntity, participants: List<HandleEntity>): String {
        // For group chats: use explicit group name or generate from participants
        if (chat.isGroup) {
            return chat.displayName?.takeIf { it.isNotBlank() }
                ?: participants.take(3).joinToString(", ") { it.displayName }
                    .let { names -> if (participants.size > 3) "$names +${participants.size - 3}" else names }
                    .ifEmpty { PhoneNumberFormatter.format(chat.chatIdentifier ?: "") }
        }

        // For 1:1 chats: prefer participant's displayName (handles contact lookup, inferred names)
        val primaryParticipant = participants.firstOrNull()
        return primaryParticipant?.displayName
            ?: chat.displayName?.takeIf { it.isNotBlank() }
            ?: PhoneNumberFormatter.format(chat.chatIdentifier ?: primaryParticipant?.address ?: "")
    }

    /**
     * Called by ChatScreen when scroll position changes.
     * Notifies the paging controller to load data around the visible range.
     */
    fun onScrollPositionChanged(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        messageList.onScrollPositionChanged(firstVisibleIndex, lastVisibleIndex)
    }

    private fun observeTypingIndicators() {
        viewModelScope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.TypingIndicator>()
                .filter { event ->
                    // Use normalized GUID comparison to handle format differences
                    // Server may send "+1234567890" but local has "+1-234-567-890"
                    val normalizedEventGuid = normalizeGuid(event.chatGuid)
                    mergedChatGuids.any { normalizeGuid(it) == normalizedEventGuid } ||
                        normalizeGuid(chatGuid) == normalizedEventGuid ||
                        // Fallback: match by address/phone number only
                        extractAddress(event.chatGuid)?.let { eventAddress ->
                            mergedChatGuids.any { extractAddress(it) == eventAddress } ||
                                extractAddress(chatGuid) == eventAddress
                        } == true
                }
                .collect { event ->
                    sync.setTyping(event.isTyping)
                }
        }
    }

    /**
     * Normalize a chat GUID for comparison by stripping formatting from phone numbers.
     * Handles cases where server sends "+1234567890" but local has "+1-234-567-890".
     */
    private fun normalizeGuid(guid: String): String {
        val parts = guid.split(";-;")
        if (parts.size != 2) return guid.lowercase()
        val prefix = parts[0].lowercase()
        val address = if (parts[1].contains("@")) {
            // Email address - just lowercase
            parts[1].lowercase()
        } else {
            // Phone number - strip non-digits except leading +
            parts[1].replace(Regex("[^0-9+]"), "")
        }
        return "$prefix;-;$address"
    }

    /**
     * Extract just the address/phone portion from a chat GUID for fallback matching.
     * Returns null if the GUID format is invalid.
     */
    private fun extractAddress(guid: String): String? {
        val parts = guid.split(";-;")
        if (parts.size != 2) return null
        return if (parts[1].contains("@")) {
            parts[1].lowercase()
        } else {
            parts[1].replace(Regex("[^0-9+]"), "")
        }
    }

    private fun markAsRead() {
        viewModelScope.launch {
            // Mark all chats in merged conversation as read
            for (guid in mergedChatGuids) {
                try {
                    val chat = chatRepository.getChat(guid)

                    if (chat?.isLocalSms == true) {
                        // Local SMS/MMS chat - mark in Android system
                        smsRepository.markThreadAsRead(guid)
                    } else {
                        // iMessage or server SMS - mark via server API
                        chatRepository.markChatAsRead(guid)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ChatViewModel", "Failed to mark $guid as read", e)
                    // Continue with other chats
                }
            }
        }
    }

    fun updateDraft(text: String) {
        composer.setDraftText(text)
        composer.handleTypingIndicator(text)
        persistDraft(text)
    }

    /**
     * Persist draft to database with debouncing to avoid excessive writes.
     */
    private fun persistDraft(text: String) {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(draftSaveDebounceMs)
            chatRepository.updateDraftText(chatGuid, text)
        }
    }

    /**
     * Called when leaving the chat to ensure we send stopped-typing and save draft
     */
    fun onChatLeave() {
        // Clear active conversation tracking to resume notifications
        activeConversationManager.clearActiveConversation()

        // Notify server we're leaving this chat
        socketService.sendCloseChat(chatGuid)

        // Clear active chat for attachment download queue and preloader
        messageList.clearActiveChat()

        // Send stopped-typing via composer delegate
        composer.sendStoppedTyping()

        // Save draft immediately when leaving (cancel debounce and save now)
        draftSaveJob?.cancel()
        viewModelScope.launch {
            chatRepository.updateDraftText(chatGuid, composer.draftText.value)
            // Save scroll position for state restoration
            val mergedGuidsStr = if (isMergedChat) mergedChatGuids.joinToString(",") else null
            val (scrollPos, scrollOffset) = messageList.getScrollPosition()
            android.util.Log.d("StateRestore", "onChatLeave: saving chatGuid=$chatGuid, scroll=($scrollPos, $scrollOffset)")
            settingsDataStore.setLastOpenChat(chatGuid, mergedGuidsStr)
            settingsDataStore.setLastScrollPosition(scrollPos, scrollOffset)

            // Save state to LRU cache for instant restore when re-opening this chat
            chatStateCache.put(messageList.saveStateToCacheSync())
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
    // ATTACHMENT METHODS - Backward compatibility wrappers
    // ChatScreen will be updated to use viewModel.composer.* directly in future
    // ============================================================================

    fun addAttachment(uri: Uri) = composer.addAttachment(uri)
    fun addAttachments(uris: List<Uri>) = composer.addAttachments(uris)
    fun getContactData(contactUri: Uri): ContactData? = composer.getContactData(contactUri)
    fun addContactFromPicker(contactUri: Uri) = composer.addContactFromPicker(contactUri)
    fun addContactAsVCard(contactData: ContactData, options: FieldOptions): Boolean =
        composer.addContactAsVCard(contactData, options)
    fun removeAttachment(uri: Uri) = composer.removeAttachment(uri)
    fun reorderAttachments(reorderedList: List<PendingAttachmentInput>) = composer.reorderAttachments(reorderedList)
    fun onAttachmentEdited(originalUri: Uri, editedUri: Uri, caption: String? = null) =
        composer.onAttachmentEdited(originalUri, editedUri, caption)
    fun clearAttachments() = composer.clearAttachments()
    fun dismissAttachmentWarning() = composer.dismissAttachmentWarning()
    fun setAttachmentQuality(quality: AttachmentQuality) = composer.setAttachmentQuality(quality)

    // GIF Picker - delegated to composer
    val gifPickerState get() = composer.gifPickerState
    val gifSearchQuery get() = composer.gifSearchQuery
    fun updateGifSearchQuery(query: String) = composer.updateGifSearchQuery(query)
    fun searchGifs(query: String) = composer.searchGifs(query)
    fun loadFeaturedGifs() = composer.loadFeaturedGifs()
    fun selectGif(gif: com.bothbubbles.ui.chat.composer.panels.GifItem) = composer.selectGif(gif)

    // Smart Reply - delegated to composer
    fun recordTemplateUsage(templateId: Long) = composer.recordTemplateUsage(templateId)

    // Attachment Download - delegated to attachment delegate
    fun downloadAttachment(attachmentGuid: String) = attachment.downloadAttachment(attachmentGuid)
    fun isDownloading(attachmentGuid: String): Boolean = attachment.isDownloading(attachmentGuid)
    fun getDownloadProgress(attachmentGuid: String): Float = attachment.getDownloadProgress(attachmentGuid)

    // ============================================================================
    // SEND MESSAGE
    // ============================================================================

    fun sendMessage(effectId: String? = null) {
        val sendStartTime = System.currentTimeMillis()
        Log.d(TAG, "⏱️ [SEND] sendMessage() CALLED on thread: ${Thread.currentThread().name}")

        val text = composer.draftText.value.trim()
        val attachments = composer.pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        // Capture current state for optimistic UI insert
        val currentSendMode = _uiState.value.currentSendMode
        val isLocalSmsChat = _uiState.value.isLocalSmsChat

        // Delegate to ChatSendDelegate for the actual send operation
        send.sendMessage(
            text = text,
            attachments = attachments,
            effectId = effectId,
            currentSendMode = currentSendMode,
            isLocalSmsChat = isLocalSmsChat,
            onClearInput = {
                Log.d(TAG, "⏱️ [SEND] onClearInput: +${System.currentTimeMillis() - sendStartTime}ms")
                // Clear UI state immediately for responsive feel via delegate
                Log.d("CascadeDebug", "[EMIT] composer.clearInput()")
                composer.clearInput()
            },
            onDraftCleared = {
                Log.d(TAG, "⏱️ [SEND] onDraftCleared: +${System.currentTimeMillis() - sendStartTime}ms")
                // Clear draft from database
                draftSaveJob?.cancel()
                viewModelScope.launch {
                    chatRepository.updateDraftText(chatGuid, null)
                }
            },
            onQueued = { info ->
                Log.d(TAG, "⏱️ [SEND] onQueued callback START: +${System.currentTimeMillis() - sendStartTime}ms, guid=${info.guid}")
                // Optimistic UI insert - immediately show the message in the list
                // This bypasses the paging controller's DB-based shift/reload cycle
                val messageSource = when {
                    isLocalSmsChat -> MessageSource.LOCAL_SMS.name
                    currentSendMode == ChatSendMode.SMS -> MessageSource.LOCAL_SMS.name
                    else -> MessageSource.IMESSAGE.name
                }

                val optimisticModel = MessageUiModel(
                    guid = info.guid,
                    text = info.text,
                    subject = null,
                    dateCreated = info.dateCreated,
                    formattedTime = formatMessageTime(info.dateCreated),
                    isFromMe = true,
                    isSent = false, // Still sending (temp- guid)
                    isDelivered = false,
                    isRead = false,
                    hasError = false,
                    isReaction = false,
                    attachments = emptyList<AttachmentUiModel>().toStable(),
                    senderName = null,
                    senderAvatarPath = null,
                    messageSource = messageSource,
                    expressiveSendStyleId = info.effectId,
                    threadOriginatorGuid = info.replyToGuid
                )

                Log.d(TAG, "⏱️ [SEND] calling insertMessageOptimistically: +${System.currentTimeMillis() - sendStartTime}ms")
                messageList.insertMessageOptimistically(optimisticModel)
                Log.d(TAG, "⏱️ [SEND] insertMessageOptimistically returned: +${System.currentTimeMillis() - sendStartTime}ms")
            }
        )
        Log.d(TAG, "⏱️ [SEND] sendMessage() returning: +${System.currentTimeMillis() - sendStartTime}ms")
    }

    /**
     * Calculate aggregate progress across all pending messages.
     * Each message contributes: 10% base + 90% * its progress
     */
    private fun calculateAggregateProgress(pendingMessages: List<PendingMessage>): Float {
        if (pendingMessages.isEmpty()) return 0f
        val totalProgress = pendingMessages.sumOf { msg ->
            (0.1f + 0.9f * msg.progress).toDouble()
        }
        return (totalProgress / pendingMessages.size).toFloat()
    }

    /**
     * Send message with explicit delivery mode override
     */
    fun sendMessageVia(deliveryMode: MessageDeliveryMode) {
        val text = composer.draftText.value.trim()
        val attachments = composer.pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        // Delegate to ChatSendDelegate
        send.sendMessageVia(
            text = text,
            attachments = attachments,
            deliveryMode = deliveryMode,
            onClearInput = {
                // Clear UI state immediately via delegate
                composer.clearInput()
            },
            onDraftCleared = {
                // Clear draft from database
                draftSaveJob?.cancel()
                viewModelScope.launch {
                    chatRepository.updateDraftText(chatGuid, null)
                }
            }
        )
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
     * Only works on server-origin messages (IMESSAGE or SERVER_SMS).
     * Uses native tapback API via BlueBubbles server.
     */
    fun toggleReaction(messageGuid: String, tapback: Tapback) {
        val message = messagesState.value.find { it.guid == messageGuid } ?: return

        // Guard: Only allow on server-origin messages (IMESSAGE or SERVER_SMS)
        // Local SMS/MMS cannot have tapbacks
        if (!message.isServerOrigin) {
            return
        }

        val isRemoving = tapback in message.myReactions
        Log.d(TAG, "toggleReaction: messageGuid=$messageGuid, tapback=${tapback.apiName}, isRemoving=$isRemoving")

        viewModelScope.launch {
            // OPTIMISTIC UPDATE: Immediately show the reaction in UI
            val optimisticUpdateApplied = messageList.updateMessageLocally(messageGuid) { currentMessage ->
                val newMyReactions = if (isRemoving) {
                    currentMessage.myReactions - tapback
                } else {
                    currentMessage.myReactions + tapback
                }

                val newReactions = if (isRemoving) {
                    // Remove my reaction from the list
                    currentMessage.reactions.filter { !(it.tapback == tapback && it.isFromMe) }.toStable()
                } else {
                    // Add my reaction to the list
                    (currentMessage.reactions + ReactionUiModel(
                        tapback = tapback,
                        isFromMe = true,
                        senderName = null // Will be filled in on refresh from DB
                    )).toStable()
                }

                currentMessage.copy(
                    myReactions = newMyReactions,
                    reactions = newReactions
                )
            }

            if (optimisticUpdateApplied) {
                Log.d(TAG, "toggleReaction: optimistic update applied for $messageGuid")
            }

            // Call API in background (fire and forget with rollback on failure)
            // Pass selectedMessageText - required by BlueBubbles server for reaction matching
            val messageText = message.text ?: ""
            val result = if (isRemoving) {
                messageSendingService.removeReaction(
                    chatGuid = chatGuid,
                    messageGuid = messageGuid,
                    reaction = tapback.apiName,
                    selectedMessageText = messageText
                )
            } else {
                messageSendingService.sendReaction(
                    chatGuid = chatGuid,
                    messageGuid = messageGuid,
                    reaction = tapback.apiName,
                    selectedMessageText = messageText
                )
            }

            result.onSuccess {
                Log.d(TAG, "toggleReaction: API success for $messageGuid")
                // Refresh from database to get the canonical server state
                // This ensures our optimistic update is replaced with the real data
                messageList.updateMessage(messageGuid)
            }.onFailure { error ->
                Log.e(TAG, "toggleReaction: API failed for $messageGuid, rolling back optimistic update", error)
                // ROLLBACK: Revert to database state (which doesn't have the reaction)
                messageList.updateMessage(messageGuid)
            }
        }
    }

    fun retryMessage(messageGuid: String) {
        send.retryMessage(messageGuid)
    }

    /**
     * Retry a failed iMessage as SMS/MMS
     */
    fun retryMessageAsSms(messageGuid: String) {
        send.retryMessageAsSms(messageGuid)
    }

    /**
     * Forward a message to another conversation.
     */
    fun forwardMessage(messageGuid: String, targetChatGuid: String) {
        send.forwardMessage(messageGuid, targetChatGuid)
    }

    /**
     * Get all available chats for forwarding (excluding current chat).
     */
    fun getForwardableChats(): Flow<List<com.bothbubbles.data.local.db.entity.ChatEntity>> {
        return chatRepository.observeActiveChats()
            .map { chats -> chats.filter { it.guid != chatGuid } }
    }

    /**
     * Clear the forward success flag.
     * Forward success is now managed by ChatSendDelegate's SendState.
     */
    fun clearForwardSuccess() {
        send.clearForwardSuccess()
    }

    /**
     * Check if a failed message can be retried as SMS
     */
    suspend fun canRetryAsSms(messageGuid: String): Boolean {
        return send.canRetryAsSms(messageGuid)
    }

    fun loadMoreMessages() {
        messageList.loadMoreMessages()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, appError = null) }
    }

    // ===== Menu Actions =====
    // Delegated to ChatOperationsDelegate for state isolation

    fun archiveChat() = operations.archiveChat()
    fun unarchiveChat() = operations.unarchiveChat()
    fun toggleStarred() = operations.toggleStarred()
    fun deleteChat() = operations.deleteChat()
    fun toggleSubjectField() = operations.toggleSubjectField()

    // ===== Inline Search (delegated to ChatSearchDelegate) =====

    fun activateSearch() = search.activateSearch()
    fun closeSearch() = search.closeSearch()
    fun updateSearchQuery(query: String) = search.updateSearchQuery(query, messagesState.value, mergedChatGuids)
    fun navigateSearchUp() = search.navigateSearchUp()
    fun navigateSearchDown() = search.navigateSearchDown()
    fun showSearchResultsSheet() = search.showResultsSheet()
    fun hideSearchResultsSheet() = search.hideResultsSheet()

    /**
     * Scroll to a specific message by GUID and highlight it.
     * Uses paging-aware loading and runs in viewModelScope.
     * Meant for UI callbacks that need fire-and-forget behavior.
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

    // ===== Intent Creation & Contact Actions (delegated to ChatOperationsDelegate) =====

    fun getAddToContactsIntent(): Intent = operations.getAddToContactsIntent(
        _uiState.value.participantPhone,
        _uiState.value.inferredSenderName
    )
    fun getGoogleMeetIntent(): Intent = operations.getGoogleMeetIntent()
    fun getWhatsAppCallIntent(): Intent? = operations.getWhatsAppCallIntent(_uiState.value.participantPhone)
    fun getHelpIntent(): Intent = operations.getHelpIntent()
    fun blockContact(context: Context): Boolean {
        if (!_uiState.value.isLocalSmsChat) return false
        return operations.blockContact(context, _uiState.value.participantPhone)
    }
    fun isWhatsAppAvailable(context: Context): Boolean = operations.isWhatsAppAvailable(context)

    // ===== Spam Operations (delegated to ChatOperationsDelegate) =====

    fun markAsSafe() = operations.markAsSafe()
    fun reportAsSpam() = operations.reportAsSpam()
    fun reportToCarrier(): Boolean {
        if (!uiState.value.isLocalSmsChat) return false
        return operations.reportToCarrier()
    }
    fun checkReportedToCarrier() = operations.checkReportedToCarrier()

    // ===== Effect Playback (delegated to ChatEffectsDelegate) =====

    fun onBubbleEffectCompleted(messageGuid: String) = effects.onBubbleEffectCompleted(messageGuid)
    fun triggerScreenEffect(message: MessageUiModel) = effects.triggerScreenEffect(message)
    fun onScreenEffectCompleted() = effects.onScreenEffectCompleted()

    // ===== Scheduled Messages (delegated to ChatScheduledMessageDelegate) =====

    /**
     * Schedule a message to be sent at a later time.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun scheduleMessage(text: String, attachments: List<PendingAttachmentInput>, sendAt: Long) =
        scheduledMessages.scheduleMessage(text, attachments, sendAt)

    /**
     * Cancel a scheduled message.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun cancelScheduledMessage(id: Long) = scheduledMessages.cancelScheduledMessage(id)

    /**
     * Update the scheduled time for a message.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun updateScheduledTime(id: Long, newSendAt: Long) = scheduledMessages.updateScheduledTime(id, newSendAt)

    /**
     * Delete a scheduled message from the database.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun deleteScheduledMessage(id: Long) = scheduledMessages.deleteScheduledMessage(id)

    /**
     * Retry a failed scheduled message.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun retryScheduledMessage(id: Long, newSendAt: Long = System.currentTimeMillis()) =
        scheduledMessages.retryScheduledMessage(id, newSendAt)

    // ===== ETA Sharing =====

    /**
     * Start sharing ETA with the current chat recipient.
     * This reads navigation notifications and sends periodic updates.
     */
    fun startEtaSharing() {
        val chatTitle = _uiState.value.chatTitle
        etaSharing.startSharingEta(chatGuid, chatTitle)
    }

    /**
     * Stop sharing ETA with the current chat recipient.
     */
    fun stopEtaSharing() {
        etaSharing.stopSharingEta()
    }

    /**
     * Dismiss the ETA sharing banner for this navigation session.
     * Banner will reappear when navigation stops and starts again.
     */
    fun dismissEtaBanner() {
        etaSharing.dismissBanner()
    }
}

private data class ParticipantMaps(
    val messages: List<MessageEntity>,
    val handleIdToName: Map<Long, String>,
    val addressToName: Map<String, String>,
    val addressToAvatarPath: Map<String, String?>
)


