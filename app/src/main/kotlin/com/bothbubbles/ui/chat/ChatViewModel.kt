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
    private val soundManager: SoundManager,
    private val attachmentRepository: AttachmentRepository,
    private val attachmentDownloadQueue: AttachmentDownloadQueue,
    private val androidContactsService: AndroidContactsService,
    private val vCardService: VCardService,
    private val smartReplyService: SmartReplyService,
    private val quickReplyTemplateRepository: QuickReplyTemplateRepository,
    private val handleRepository: HandleRepository,
    private val activeConversationManager: ActiveConversationManager,
    private val api: BothBubblesApi,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val attachmentPreloader: AttachmentPreloader,
    private val pendingMessageRepository: PendingMessageRepository,
    private val iMessageAvailabilityService: IMessageAvailabilityService,
    private val attachmentLimitsProvider: AttachmentLimitsProvider,
    private val chatStateCache: ChatStateCache,
    // Delegates for decomposition
    private val sendDelegate: ChatSendDelegate,
    private val attachmentDelegate: ChatAttachmentDelegate,
    private val etaSharingDelegate: ChatEtaSharingDelegate,
    private val searchDelegate: ChatSearchDelegate,
    private val operationsDelegate: ChatOperationsDelegate,
    private val syncDelegate: ChatSyncDelegate,
    private val effectsDelegate: ChatEffectsDelegate,
    private val threadDelegate: ChatThreadDelegate,
    private val composerDelegate: ChatComposerDelegate,
    private val messageListDelegate: ChatMessageListDelegate,
    private val sendModeManager: ChatSendModeManager,
    private val scheduledMessageDelegate: ChatScheduledMessageDelegate,
    private val messageSendingService: MessageSendingService,
    // Sync integrity services
    private val counterpartSyncService: CounterpartSyncService,
    private val gifRepository: GifRepository,
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

    // Send state - owned by ChatSendDelegate, exposed directly to ChatScreen
    // This reduces cascade recompositions by isolating send-related state updates
    val sendState: StateFlow<SendState> get() = sendDelegate.state

    // Search state - owned by ChatSearchDelegate, exposed directly to ChatScreen
    // This reduces cascade recompositions by isolating search-related state updates
    val searchState: StateFlow<SearchState> get() = searchDelegate.state

    // Operations state - owned by ChatOperationsDelegate, exposed directly to ChatScreen
    val operationsState: StateFlow<OperationsState> get() = operationsDelegate.state

    // Sync state - owned by ChatSyncDelegate, exposed directly to ChatScreen
    val syncState: StateFlow<SyncState> get() = syncDelegate.state

    // Effects state - owned by ChatEffectsDelegate, exposed directly to ChatScreen
    val effectsState: StateFlow<EffectsState> get() = effectsDelegate.state

    // Thread state - owned by ChatThreadDelegate, exposed directly to ChatScreen
    val threadState: StateFlow<ThreadState> get() = threadDelegate.state

    // Scheduled messages state - owned by ChatScheduledMessageDelegate, exposed directly to ChatScreen
    val scheduledMessagesState: StateFlow<ScheduledMessagesState> get() = scheduledMessageDelegate.state

    // Separate flow for messages - delegated to messageListDelegate
    val messagesState: StateFlow<StableList<MessageUiModel>> get() = messageListDelegate.messagesState

    // ============================================================================
    // COMPOSER STATE - Delegated to ChatComposerDelegate
    // The delegate owns draft text, pending attachments, quality, and panel state.
    // ============================================================================

    // Draft text - delegated to composerDelegate
    val draftText: StateFlow<String> get() = composerDelegate.draftText

    // Pending attachments - delegated to composerDelegate
    val pendingAttachments: StateFlow<List<PendingAttachmentInput>> get() = composerDelegate.pendingAttachments

    // Active panel - delegated to composerDelegate
    val activePanel: StateFlow<ComposerPanel> get() = composerDelegate.activePanel

    // Composer state - delegated to composerDelegate
    val composerState: StateFlow<ComposerState> get() = composerDelegate.state

    fun onComposerEvent(event: ComposerEvent) {
        composerDelegate.onComposerEvent(
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

    // Sparse message list - delegated to messageListDelegate
    val sparseMessages: StateFlow<SparseMessageList> get() = messageListDelegate.sparseMessages

    // Total message count for scroll indicator - delegated to messageListDelegate
    val totalMessageCount: StateFlow<Int> get() = messageListDelegate.totalMessageCount

    // Track initial load completion for animation control - delegated to messageListDelegate
    val initialLoadComplete: StateFlow<Boolean> get() = messageListDelegate.initialLoadComplete

    // Track when we're fetching older messages from the BlueBubbles server - delegated to messageListDelegate
    val isLoadingFromServer: StateFlow<Boolean> get() = messageListDelegate.isLoadingFromServer

    // Emit socket-pushed message GUIDs for "new messages" indicator - delegated to messageListDelegate
    val socketNewMessage: SharedFlow<String> get() = messageListDelegate.socketNewMessage

    /**
     * Check if a message has been loaded this session.
     * Used to skip entrance animations for previously-viewed messages when scrolling back.
     */
    fun hasMessageBeenSeen(guid: String): Boolean = messageListDelegate.hasMessageBeenSeen(guid)

    // Initial scroll position from LRU cache (for instant restore when re-opening chat)
    // ChatScreen should read this once to initialize LazyListState
    private val _cachedScrollPosition = MutableStateFlow<Pair<Int, Int>?>(null)
    val cachedScrollPosition: StateFlow<Pair<Int, Int>?> = _cachedScrollPosition.asStateFlow()

    // Attachment download progress tracking
    // Maps attachment GUID to download progress (0.0 to 1.0, or null if not downloading)
    private val _attachmentDownloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val attachmentDownloadProgress: StateFlow<Map<String, Float>> = _attachmentDownloadProgress.asStateFlow()

    // Whether to use auto-download mode (true) or manual download mode (false)
    private val _autoDownloadEnabled = MutableStateFlow(true)
    val autoDownloadEnabled: StateFlow<Boolean> = _autoDownloadEnabled.asStateFlow()

    // Smart reply suggestions (ML Kit + user templates, max 3)
    private val _mlSuggestions = MutableStateFlow<List<String>>(emptyList())

    val smartReplySuggestions: StateFlow<List<SuggestionItem>> = combine(
        _mlSuggestions,
        quickReplyTemplateRepository.observeMostUsedTemplates(limit = 3)
    ) { mlSuggestions, templates ->
        getCombinedSuggestions(mlSuggestions, templates, maxTotal = 3)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Typing indicator state
    private var typingDebounceJob: Job? = null
    private var isCurrentlyTyping = false
    private val typingDebounceMs = 3000L // 3 seconds after last keystroke to send stopped-typing
    private var lastStartedTypingTime = 0L
    private val typingCooldownMs = 500L // Min time between started-typing emissions

    // Cached settings for typing indicators (avoids suspend calls on every keystroke)
    @Volatile private var cachedPrivateApiEnabled = false
    @Volatile private var cachedTypingIndicatorsEnabled = false

    // Draft persistence
    private var draftSaveJob: Job? = null
    private val draftSaveDebounceMs = 500L // Debounce draft saves to avoid excessive DB writes

    // iMessage availability/send mode state is now managed by ChatSendModeManager

    // PERF: Search debounce - cancels previous search when new query arrives
    private var searchJob: Job? = null
    private val searchDebounceMs = 150L // Debounce search to avoid running on every keystroke

    // Scroll position tracking for state restoration
    private var lastScrollPosition: Int = 0
    private var lastScrollOffset: Int = 0
    private var lastScrollSaveTime: Long = 0L
    private val scrollSaveDebounceMs = 1000L // Debounce scroll saves

    // Throttle preloader to avoid calling on every scroll frame
    private var lastPreloadIndex: Int = -1
    private var lastPreloadTime: Long = 0L
    private val preloadThrottleMs = 150L // Only preload every 150ms at most

    // Cached attachment list for preloading - avoids flatMap on every scroll frame
    private var cachedAttachments: List<AttachmentUiModel> = emptyList()
    private var cachedAttachmentsMessageCount: Int = 0

    // Thread scroll event - exposed from delegate for ChatScreen to collect
    val scrollToGuid: SharedFlow<String> get() = threadDelegate.scrollToGuid

    init {
        // Initialize delegates
        sendDelegate.initialize(chatGuid, viewModelScope)
        attachmentDelegate.initialize(chatGuid, viewModelScope, mergedChatGuids)
        etaSharingDelegate.initialize(viewModelScope)
        searchDelegate.initialize(viewModelScope)
        operationsDelegate.initialize(chatGuid, viewModelScope)
        syncDelegate.initialize(chatGuid, viewModelScope)
        effectsDelegate.initialize(viewModelScope)
        threadDelegate.initialize(viewModelScope, mergedChatGuids)
        scheduledMessageDelegate.initialize(chatGuid, viewModelScope)
        composerDelegate.initialize(
            scope = viewModelScope,
            uiState = _uiState,
            syncState = syncDelegate.state,
            sendState = sendDelegate.state,
            onUiStateUpdate = { transform -> _uiState.update { it.transform() } }
        )
        messageListDelegate.initialize(
            chatGuid = chatGuid,
            mergedChatGuids = mergedChatGuids,
            scope = viewModelScope,
            uiState = _uiState,
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
                    effectsDelegate.setAutoPlayEffects(autoPlay)
                    effectsDelegate.setReplayOnScroll(replayOnScroll)
                    effectsDelegate.setReduceMotion(reduceMotion)
                }
        }

        // Send delegate state is exposed directly via sendState - no forwarding needed
        // ChatScreen should collect from sendState for send-related UI updates

        // Derive replyToMessage from sendState.replyingToGuid (lookup in messages)
        viewModelScope.launch {
            sendDelegate.state
                .map { it.replyingToGuid }
                .distinctUntilChanged()
                .collect { guid ->
                    if (guid != null) {
                        val message = messageListDelegate.messagesState.value.find { it.guid == guid }
                        _uiState.update { it.copy(replyToMessage = message) }
                    } else {
                        _uiState.update { it.copy(replyToMessage = null) }
                    }
                }
        }

        // Forward attachment delegate's state to ViewModel state
        viewModelScope.launch {
            combine(
                attachmentDelegate.downloadProgress,
                attachmentDelegate.refreshTrigger
            ) { progress, trigger -> Pair(progress, trigger) }
                .collect { (progress, trigger) ->
                    _attachmentDownloadProgress.value = progress
                    // Forward refresh trigger to message list delegate
                    if (trigger > 0) {
                        messageListDelegate.triggerAttachmentRefresh()
                    }
                }
        }

        // Mark as read when new messages arrive via socket (user is viewing the chat)
        viewModelScope.launch {
            messageListDelegate.socketNewMessage.collect {
                markAsRead()
            }
        }
        // Forward ETA sharing delegate's state to UI state
        viewModelScope.launch {
            etaSharingDelegate.etaSharingState.collect { etaState ->
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

        // Check LRU cache for previously viewed chat state (instant restore)
        val cachedState = chatStateCache.get(chatGuid)
        if (cachedState != null) {
            Log.d(TAG, "Restoring chat from cache: scrollPos=${cachedState.scrollPosition}, messages=${cachedState.messages.size}")
            lastScrollPosition = cachedState.scrollPosition
            lastScrollOffset = cachedState.scrollOffset
            // Expose to ChatScreen for LazyListState initialization
            _cachedScrollPosition.value = Pair(cachedState.scrollPosition, cachedState.scrollOffset)
        }

        // Track this conversation as active to suppress notifications while viewing
        activeConversationManager.setActiveConversation(chatGuid, mergedChatGuids.toSet())

        // Notify server which chat is open (helps server optimize notification delivery)
        socketService.sendOpenChat(chatGuid)

        // Set this chat as the active chat for download queue prioritization
        attachmentDownloadQueue.setActiveChat(chatGuid)

        loadChat()
        // Note: Message loading, socket observation, and sync are now handled by messageListDelegate
        syncMessages() // Always sync to check for new messages (runs in background)
        observeTypingIndicators()
        observeTypingIndicatorSettings()
        markAsRead()
        determineChatType()
        observeParticipantsForSaveContactBanner()
        observeFallbackMode()
        observeSendModeManagerState() // Observe delegate's state flows
        observeSmartReplies()
        observeAutoDownloadSetting()
        // Note: Quality settings are now observed by ChatComposerDelegate
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
            sendModeManager.initialize(
                chatGuid = chatGuid,
                scope = viewModelScope,
                initialSendMode = currentState.currentSendMode,
                isGroup = currentState.isGroup,
                participantPhone = currentState.participantPhone
            )

            // Check if iMessage is available again (for chats in SMS fallback mode)
            sendModeManager.checkAndMaybeExitFallback(currentState.participantPhone)

            // Check iMessage availability for the contact (for send mode switching)
            sendModeManager.checkIMessageAvailability(
                isGroup = currentState.isGroup,
                isIMessageChat = currentState.isIMessageChat,
                isLocalSmsChat = currentState.isLocalSmsChat,
                participantPhone = currentState.participantPhone
            ) { available, isChecking, sendMode, canToggle, showReveal, smsBlocked ->
                _uiState.update { state ->
                    state.copy(
                        contactIMessageAvailable = available,
                        isCheckingIMessageAvailability = isChecking,
                        currentSendMode = sendMode,
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
     * Observe typing indicator settings and cache them for fast access.
     * This avoids suspend calls on every keystroke in handleTypingIndicator.
     */
    private fun observeTypingIndicatorSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.enablePrivateApi,
                settingsDataStore.sendTypingIndicators
            ) { privateApi, typingIndicators -> Pair(privateApi, typingIndicators) }
                .collect { (privateApi, typingIndicators) ->
                    cachedPrivateApiEnabled = privateApi
                    cachedTypingIndicatorsEnabled = typingIndicators
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

    /**
     * Update the attachment quality for the current session.
     * Delegated to ChatComposerDelegate.
     */
    fun setAttachmentQuality(quality: AttachmentQuality) = composerDelegate.setAttachmentQuality(quality)

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

                    // Update pending message progress via sendDelegate
                    val currentState = sendDelegate.state.value
                    val pendingList = currentState.pendingMessages.toList()
                    val attachmentIndex = pendingList.indexOfFirst { it.hasAttachments }
                    if (attachmentIndex >= 0) {
                        sendDelegate.updatePendingMessageProgress(
                            pendingList[attachmentIndex].tempGuid,
                            messageProgress
                        )
                    }
                    // Update aggregate progress
                    sendDelegate.setSendProgress(sendDelegate.calculateAggregateProgress())
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
        attachmentDelegate.downloadPendingAttachments()
    }

    /**
     * Manually download a specific attachment.
     * Called when user taps on an attachment placeholder in manual download mode.
     * Uses IMMEDIATE priority to jump ahead of background downloads.
     */
    fun downloadAttachment(attachmentGuid: String) {
        attachmentDelegate.downloadAttachment(attachmentGuid)
    }

    /**
     * Check if an attachment is currently downloading.
     */
    fun isDownloading(attachmentGuid: String): Boolean {
        return attachmentDelegate.isDownloading(attachmentGuid)
    }

    /**
     * Get download progress for an attachment (0.0 to 1.0).
     */
    fun getDownloadProgress(attachmentGuid: String): Float {
        return attachmentDelegate.getDownloadProgress(attachmentGuid)
    }

    /**
     * Observe messages and generate ML Kit smart reply suggestions.
     * Debounced to avoid excessive processing while scrolling.
     */
    private fun observeSmartReplies() {
        viewModelScope.launch {
            messagesState
                // StateFlow already guarantees distinct values, no need for distinctUntilChanged()
                .debounce(500)  // Wait for conversation to settle
                .collect { messages ->
                    val suggestions = smartReplyService.getSuggestions(messages, maxSuggestions = 3)
                    _mlSuggestions.value = suggestions
                }
        }
    }

    /**
     * Observe queued messages from the database for offline-first UI.
     * These are messages that have been queued for sending but not yet delivered.
     * Queued messages are now managed by ChatSendDelegate's SendState.
     */
    private fun observeQueuedMessages() {
        viewModelScope.launch {
            pendingMessageRepository.observePendingForChat(chatGuid)
                .collect { pending ->
                    sendDelegate.updateQueuedMessages(pending.map { it.toQueuedUiModel() })
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

    /**
     * Combine ML suggestions and user templates into max N suggestions.
     * ML suggestions appear first (more contextual), user templates fill remaining slots.
     */
    private fun getCombinedSuggestions(
        mlSuggestions: List<String>,
        userTemplates: List<QuickReplyTemplateEntity>,
        maxTotal: Int
    ): List<SuggestionItem> {
        val combined = mutableListOf<SuggestionItem>()

        // Add ML suggestions first (most contextual)
        mlSuggestions.take(maxTotal).forEach { text ->
            combined.add(SuggestionItem(text = text, isSmartSuggestion = true))
        }

        // Fill remaining slots with user templates
        val remaining = maxTotal - combined.size
        userTemplates.take(remaining).forEach { template ->
            combined.add(SuggestionItem(
                text = template.title,
                isSmartSuggestion = false,
                templateId = template.id
            ))
        }

        return combined
    }

    /**
     * Record usage of a user template (for "most used" sorting).
     */
    fun recordTemplateUsage(templateId: Long) {
        viewModelScope.launch {
            quickReplyTemplateRepository.recordUsage(templateId)
        }
    }

    private fun observeFallbackMode() {
        viewModelScope.launch {
            chatFallbackTracker.fallbackStates.collect { fallbackStates ->
                val entry = fallbackStates[chatGuid]
                syncDelegate.setSmsFallbackMode(
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
            sendModeManager.currentSendMode.collect { mode ->
                _uiState.update { it.copy(currentSendMode = mode) }
            }
        }

        // Observe contact iMessage availability
        viewModelScope.launch {
            sendModeManager.contactIMessageAvailable.collect { available ->
                _uiState.update { it.copy(contactIMessageAvailable = available) }
            }
        }

        // Observe checking state
        viewModelScope.launch {
            sendModeManager.isCheckingIMessageAvailability.collect { isChecking ->
                _uiState.update { it.copy(isCheckingIMessageAvailability = isChecking) }
            }
        }

        // Observe toggle availability
        viewModelScope.launch {
            sendModeManager.canToggleSendMode.collect { canToggle ->
                _uiState.update { it.copy(canToggleSendMode = canToggle) }
            }
        }

        // Observe reveal animation state
        viewModelScope.launch {
            sendModeManager.showSendModeRevealAnimation.collect { showReveal ->
                _uiState.update { it.copy(showSendModeRevealAnimation = showReveal) }
            }
        }

        // Observe manually set state
        viewModelScope.launch {
            sendModeManager.sendModeManuallySet.collect { manuallySet ->
                _uiState.update { it.copy(sendModeManuallySet = manuallySet) }
            }
        }

        // Observe SMS input blocked state
        viewModelScope.launch {
            sendModeManager.smsInputBlocked.collect { blocked ->
                _uiState.update { it.copy(smsInputBlocked = blocked) }
            }
        }

        // Observe tutorial state
        viewModelScope.launch {
            sendModeManager.tutorialState.collect { tutorial ->
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
        return sendModeManager.setSendMode(mode, persist)
    }

    /**
     * Try to toggle send mode (for swipe gesture).
     * Returns true if the toggle was successful.
     */
    fun tryToggleSendMode(): Boolean {
        return sendModeManager.tryToggleSendMode()
    }

    /**
     * Check if send mode toggle is currently available.
     */
    fun canToggleSendMode(): Boolean {
        return sendModeManager.canToggleSendModeNow()
    }

    /**
     * Mark the send mode reveal animation as shown.
     */
    fun markRevealAnimationShown() {
        sendModeManager.markRevealAnimationShown()
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
                    sendModeManager.updateTutorialState(TutorialState.STEP_1_SWIPE_UP)
                }
            }
        }
    }

    /**
     * Update tutorial state after user completes a step.
     * Delegates to ChatSendModeManager.
     */
    fun updateTutorialState(newState: TutorialState) {
        sendModeManager.updateTutorialState(newState)
    }

    /**
     * Handle successful toggle during tutorial.
     * Progresses the tutorial state appropriately.
     * Delegates to ChatSendModeManager.
     */
    fun onTutorialToggleSuccess() {
        sendModeManager.onTutorialToggleSuccess()
    }

    private fun syncMessages() {
        _uiState.update { it.copy(isSyncingMessages = true) }
        if (messageRepository.isLocalSmsChat(chatGuid)) {
            syncSmsMessages()
        } else {
            messageListDelegate.syncMessagesFromServer()
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

    fun loadThread(originGuid: String) = threadDelegate.loadThread(originGuid)
    fun dismissThreadOverlay() = threadDelegate.dismissThreadOverlay()
    fun scrollToMessage(guid: String) = threadDelegate.scrollToMessage(guid)

    /**
     * Jump to a specific message, loading its position from the database if needed.
     * This is paging-aware and works with sparse loading.
     *
     * @param guid The message GUID to jump to
     * @return The position of the message, or null if not found
     */
    suspend fun jumpToMessage(guid: String): Int? {
        return messageListDelegate.jumpToMessage(guid)
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
                        composerDelegate.restoreDraftText(it.textFieldText)
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
                    operationsDelegate.updateState(
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
        messageListDelegate.onScrollPositionChanged(firstVisibleIndex, lastVisibleIndex)
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
                    syncDelegate.setTyping(event.isTyping)
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
        composerDelegate.setDraftText(text)
        handleTypingIndicator(text)
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
     * Handle typing indicator logic with debouncing and rate limiting.
     *
     * Optimizations:
     * 1. Uses cached settings (no suspend calls on every keystroke)
     * 2. Only sends started-typing once until stopped-typing is sent
     * 3. Rate limits started-typing to avoid rapid on/off transitions
     * 4. Debounces stopped-typing (3 seconds after last keystroke)
     */
    private fun handleTypingIndicator(text: String) {
        // Only send typing indicators for iMessage chats (not local SMS)
        if (_uiState.value.isLocalSmsChat) return

        // Use cached settings (no suspend required)
        if (!cachedPrivateApiEnabled || !cachedTypingIndicatorsEnabled) return

        // Cancel any pending stopped-typing
        typingDebounceJob?.cancel()

        if (text.isNotEmpty()) {
            // User is typing - send started-typing if not already sent
            // Also apply cooldown to avoid rapid started/stopped/started transitions
            val now = System.currentTimeMillis()
            if (!isCurrentlyTyping && (now - lastStartedTypingTime > typingCooldownMs)) {
                isCurrentlyTyping = true
                lastStartedTypingTime = now
                socketService.sendStartedTyping(chatGuid)
            }

            // Set up debounce to send stopped-typing after inactivity
            typingDebounceJob = viewModelScope.launch {
                delay(typingDebounceMs)
                if (isCurrentlyTyping) {
                    isCurrentlyTyping = false
                    socketService.sendStoppedTyping(chatGuid)
                }
            }
        } else {
            // Text cleared - immediately send stopped-typing
            if (isCurrentlyTyping) {
                isCurrentlyTyping = false
                socketService.sendStoppedTyping(chatGuid)
            }
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

        // Clear active chat for attachment download queue
        attachmentDelegate.onChatLeave()

        typingDebounceJob?.cancel()
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            socketService.sendStoppedTyping(chatGuid)
        }
        // Save draft immediately when leaving (cancel debounce and save now)
        draftSaveJob?.cancel()
        viewModelScope.launch {
            chatRepository.updateDraftText(chatGuid, composerDelegate.draftText.value)
            // Save scroll position for state restoration
            val mergedGuidsStr = if (isMergedChat) mergedChatGuids.joinToString(",") else null
            android.util.Log.d("StateRestore", "onChatLeave: saving chatGuid=$chatGuid, scroll=($lastScrollPosition, $lastScrollOffset)")
            settingsDataStore.setLastOpenChat(chatGuid, mergedGuidsStr)
            settingsDataStore.setLastScrollPosition(lastScrollPosition, lastScrollOffset)

            // Save state to LRU cache for instant restore when re-opening this chat
            chatStateCache.put(
                ChatStateCache.CachedChatState(
                    chatGuid = chatGuid,
                    mergedGuids = mergedChatGuids,
                    messages = sparseMessages.value,
                    totalCount = totalMessageCount.value,
                    scrollPosition = lastScrollPosition,
                    scrollOffset = lastScrollOffset
                )
            )
        }
    }

    /**
     * Update scroll position for state restoration.
     * Called from ChatScreen when scroll position changes.
     * IMPORTANT: This runs on every scroll frame - must be extremely lightweight!
     */
    fun updateScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int, visibleItemCount: Int = 10) {
        lastScrollPosition = firstVisibleItemIndex
        lastScrollOffset = firstVisibleItemScrollOffset

        // Throttle preloader - only call if BOTH index changed AND enough time has passed
        // Using AND (not OR) to minimize work during scroll
        val now = System.currentTimeMillis()
        val indexChanged = firstVisibleItemIndex != lastPreloadIndex
        val timeElapsed = now - lastPreloadTime >= preloadThrottleMs

        if (indexChanged && timeElapsed) {
            lastPreloadIndex = firstVisibleItemIndex
            lastPreloadTime = now

            // Defer preload work to avoid any main thread blocking
            val messages = messagesState.value
            if (messages.isNotEmpty()) {
                // Update cached attachments only if message count changed
                if (messages.size != cachedAttachmentsMessageCount) {
                    cachedAttachments = messages.flatMap { it.attachments }
                    cachedAttachmentsMessageCount = messages.size
                }

                if (cachedAttachments.isNotEmpty()) {
                    val visibleEnd = (firstVisibleItemIndex + visibleItemCount).coerceAtMost(messages.size - 1)
                    // Preload images for ~3 screens in each direction to prevent image pop-in
                    attachmentPreloader.preloadNearby(
                        attachments = cachedAttachments,
                        visibleRange = firstVisibleItemIndex..visibleEnd,
                        preloadCount = 15
                    )

                    // Boost download priority for attachments in extended visible range
                    // This ensures attachments needing download from server get prioritized
                    val extendedStart = (firstVisibleItemIndex - 15).coerceAtLeast(0)
                    val extendedEnd = (visibleEnd + 15).coerceAtMost(messages.size - 1)
                    for (i in extendedStart..extendedEnd) {
                        messages.getOrNull(i)?.attachments?.forEach { attachment ->
                            if (attachment.needsDownload) {
                                attachmentDownloadQueue.enqueue(
                                    attachmentGuid = attachment.guid,
                                    chatGuid = chatGuid,
                                    priority = AttachmentDownloadQueue.Priority.VISIBLE
                                )
                            }
                        }
                    }
                }
            }
        }

        // Throttled save to DataStore - uses timestamp instead of Job cancel/recreate
        // This avoids object allocation and scheduling overhead on every scroll frame
        val saveTimeElapsed = now - lastScrollSaveTime >= scrollSaveDebounceMs
        if (saveTimeElapsed) {
            lastScrollSaveTime = now
            viewModelScope.launch {
                settingsDataStore.setLastScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
            }
        }
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
        attachmentPreloader.clearTracking()
        // Clear active chat from download queue so priority is reset
        attachmentDownloadQueue.setActiveChat(null)
        // Dispose message list delegate (cancels observers, clears cache)
        messageListDelegate.dispose()
    }

    // ============================================================================
    // ATTACHMENT METHODS - Delegated to ChatComposerDelegate
    // ============================================================================

    fun addAttachment(uri: Uri) = composerDelegate.addAttachment(uri)

    fun addAttachments(uris: List<Uri>) = composerDelegate.addAttachments(uris)

    fun getContactData(contactUri: Uri): ContactData? = composerDelegate.getContactData(contactUri)

    fun addContactFromPicker(contactUri: Uri) = composerDelegate.addContactFromPicker(contactUri)

    fun addContactAsVCard(contactData: ContactData, options: FieldOptions): Boolean =
        composerDelegate.addContactAsVCard(contactData, options)

    // GIF Picker state - delegated to composerDelegate
    val gifPickerState get() = composerDelegate.gifPickerState
    val gifSearchQuery get() = composerDelegate.gifSearchQuery

    fun updateGifSearchQuery(query: String) = composerDelegate.updateGifSearchQuery(query)

    fun searchGifs(query: String) = composerDelegate.searchGifs(query)

    fun loadFeaturedGifs() = composerDelegate.loadFeaturedGifs()

    fun selectGif(gif: com.bothbubbles.ui.chat.composer.panels.GifItem) = composerDelegate.selectGif(gif)

    fun removeAttachment(uri: Uri) = composerDelegate.removeAttachment(uri)

    fun reorderAttachments(reorderedList: List<PendingAttachmentInput>) = composerDelegate.reorderAttachments(reorderedList)

    fun onAttachmentEdited(originalUri: Uri, editedUri: Uri, caption: String? = null) =
        composerDelegate.onAttachmentEdited(originalUri, editedUri, caption)

    fun clearAttachments() = composerDelegate.clearAttachments()

    fun dismissAttachmentWarning() = composerDelegate.dismissAttachmentWarning()

    // ============================================================================
    // SEND MESSAGE
    // ============================================================================

    fun sendMessage(effectId: String? = null) {
        val sendStartTime = System.currentTimeMillis()
        Log.d(TAG, "⏱️ [SEND] sendMessage() CALLED on thread: ${Thread.currentThread().name}")

        val text = composerDelegate.draftText.value.trim()
        val attachments = composerDelegate.pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        // Capture current state for optimistic UI insert
        val currentSendMode = _uiState.value.currentSendMode
        val isLocalSmsChat = _uiState.value.isLocalSmsChat

        // Delegate to ChatSendDelegate for the actual send operation
        sendDelegate.sendMessage(
            text = text,
            attachments = attachments,
            effectId = effectId,
            currentSendMode = currentSendMode,
            isLocalSmsChat = isLocalSmsChat,
            onClearInput = {
                Log.d(TAG, "⏱️ [SEND] onClearInput: +${System.currentTimeMillis() - sendStartTime}ms")
                // Clear UI state immediately for responsive feel via delegate
                Log.d("CascadeDebug", "[EMIT] composerDelegate.clearInput()")
                composerDelegate.clearInput()
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
                messageListDelegate.insertMessageOptimistically(optimisticModel)
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
        val text = composerDelegate.draftText.value.trim()
        val attachments = composerDelegate.pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        // Delegate to ChatSendDelegate
        sendDelegate.sendMessageVia(
            text = text,
            attachments = attachments,
            deliveryMode = deliveryMode,
            onClearInput = {
                // Clear UI state immediately via delegate
                composerDelegate.clearInput()
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
        sendDelegate.setReplyTo(messageGuid)
    }

    /**
     * Clear the reply state
     */
    fun clearReply() {
        sendDelegate.clearReply()
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
            val optimisticUpdateApplied = messageListDelegate.updateMessageLocally(messageGuid) { currentMessage ->
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
                messageListDelegate.updateMessage(messageGuid)
            }.onFailure { error ->
                Log.e(TAG, "toggleReaction: API failed for $messageGuid, rolling back optimistic update", error)
                // ROLLBACK: Revert to database state (which doesn't have the reaction)
                messageListDelegate.updateMessage(messageGuid)
            }
        }
    }

    fun retryMessage(messageGuid: String) {
        sendDelegate.retryMessage(messageGuid)
    }

    /**
     * Retry a failed iMessage as SMS/MMS
     */
    fun retryMessageAsSms(messageGuid: String) {
        sendDelegate.retryMessageAsSms(messageGuid)
    }

    /**
     * Forward a message to another conversation.
     */
    fun forwardMessage(messageGuid: String, targetChatGuid: String) {
        sendDelegate.forwardMessage(messageGuid, targetChatGuid)
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
        sendDelegate.clearForwardSuccess()
    }

    /**
     * Check if a failed message can be retried as SMS
     */
    suspend fun canRetryAsSms(messageGuid: String): Boolean {
        return sendDelegate.canRetryAsSms(messageGuid)
    }

    fun loadMoreMessages() {
        messageListDelegate.loadMoreMessages()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, appError = null) }
    }

    // ===== Menu Actions =====
    // Delegated to ChatOperationsDelegate for state isolation

    fun archiveChat() = operationsDelegate.archiveChat()
    fun unarchiveChat() = operationsDelegate.unarchiveChat()
    fun toggleStarred() = operationsDelegate.toggleStarred()
    fun deleteChat() = operationsDelegate.deleteChat()
    fun toggleSubjectField() = operationsDelegate.toggleSubjectField()

    // ===== Inline Search (delegated to ChatSearchDelegate) =====

    fun activateSearch() = searchDelegate.activateSearch()
    fun closeSearch() = searchDelegate.closeSearch()
    fun updateSearchQuery(query: String) = searchDelegate.updateSearchQuery(query, messagesState.value, mergedChatGuids)
    fun navigateSearchUp() = searchDelegate.navigateSearchUp()
    fun navigateSearchDown() = searchDelegate.navigateSearchDown()
    fun showSearchResultsSheet() = searchDelegate.showResultsSheet()
    fun hideSearchResultsSheet() = searchDelegate.hideResultsSheet()

    /**
     * Scroll to a specific message by GUID and highlight it.
     * Uses paging-aware loading and runs in viewModelScope.
     * Meant for UI callbacks that need fire-and-forget behavior.
     */
    fun scrollToAndHighlightMessage(messageGuid: String) {
        viewModelScope.launch {
            val position = messageListDelegate.jumpToMessage(messageGuid)
            if (position != null) {
                // Emit scroll event for the UI to handle
                threadDelegate.emitScrollEvent(messageGuid)
                // Highlight after scroll
                delay(100)
                highlightMessage(messageGuid)
            }
        }
    }

    // ===== Intent Creation & Contact Actions (delegated to ChatOperationsDelegate) =====

    fun getAddToContactsIntent(): Intent = operationsDelegate.getAddToContactsIntent(
        _uiState.value.participantPhone,
        _uiState.value.inferredSenderName
    )
    fun getGoogleMeetIntent(): Intent = operationsDelegate.getGoogleMeetIntent()
    fun getWhatsAppCallIntent(): Intent? = operationsDelegate.getWhatsAppCallIntent(_uiState.value.participantPhone)
    fun getHelpIntent(): Intent = operationsDelegate.getHelpIntent()
    fun blockContact(context: Context): Boolean {
        if (!_uiState.value.isLocalSmsChat) return false
        return operationsDelegate.blockContact(context, _uiState.value.participantPhone)
    }
    fun isWhatsAppAvailable(context: Context): Boolean = operationsDelegate.isWhatsAppAvailable(context)

    // ===== Spam Operations (delegated to ChatOperationsDelegate) =====

    fun markAsSafe() = operationsDelegate.markAsSafe()
    fun reportAsSpam() = operationsDelegate.reportAsSpam()
    fun reportToCarrier(): Boolean {
        if (!uiState.value.isLocalSmsChat) return false
        return operationsDelegate.reportToCarrier()
    }
    fun checkReportedToCarrier() = operationsDelegate.checkReportedToCarrier()

    // ===== Effect Playback (delegated to ChatEffectsDelegate) =====

    fun onBubbleEffectCompleted(messageGuid: String) = effectsDelegate.onBubbleEffectCompleted(messageGuid)
    fun triggerScreenEffect(message: MessageUiModel) = effectsDelegate.triggerScreenEffect(message)
    fun onScreenEffectCompleted() = effectsDelegate.onScreenEffectCompleted()

    // ===== Scheduled Messages (delegated to ChatScheduledMessageDelegate) =====

    /**
     * Schedule a message to be sent at a later time.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun scheduleMessage(text: String, attachments: List<PendingAttachmentInput>, sendAt: Long) =
        scheduledMessageDelegate.scheduleMessage(text, attachments, sendAt)

    /**
     * Cancel a scheduled message.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun cancelScheduledMessage(id: Long) = scheduledMessageDelegate.cancelScheduledMessage(id)

    /**
     * Update the scheduled time for a message.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun updateScheduledTime(id: Long, newSendAt: Long) = scheduledMessageDelegate.updateScheduledTime(id, newSendAt)

    /**
     * Delete a scheduled message from the database.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun deleteScheduledMessage(id: Long) = scheduledMessageDelegate.deleteScheduledMessage(id)

    /**
     * Retry a failed scheduled message.
     * Delegated to ChatScheduledMessageDelegate.
     */
    fun retryScheduledMessage(id: Long, newSendAt: Long = System.currentTimeMillis()) =
        scheduledMessageDelegate.retryScheduledMessage(id, newSendAt)

    // ===== ETA Sharing =====

    /**
     * Start sharing ETA with the current chat recipient.
     * This reads navigation notifications and sends periodic updates.
     */
    fun startEtaSharing() {
        val chatTitle = _uiState.value.chatTitle
        etaSharingDelegate.startSharingEta(chatGuid, chatTitle)
    }

    /**
     * Stop sharing ETA with the current chat recipient.
     */
    fun stopEtaSharing() {
        etaSharingDelegate.stopSharingEta()
    }

    /**
     * Dismiss the ETA sharing banner for this navigation session.
     * Banner will reappear when navigation stops and starts again.
     */
    fun dismissEtaBanner() {
        etaSharingDelegate.dismissBanner()
    }
}

private data class ParticipantMaps(
    val messages: List<MessageEntity>,
    val handleIdToName: Map<Long, String>,
    val addressToName: Map<String, String>,
    val addressToAvatarPath: Map<String, String?>
)


