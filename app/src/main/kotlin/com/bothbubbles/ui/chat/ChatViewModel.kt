package com.bothbubbles.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.BlockedNumberContract
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ScheduledMessageEntity
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
import com.bothbubbles.data.repository.ScheduledMessageRepository
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.PendingMessageRepository
import com.bothbubbles.data.repository.QuickReplyTemplateRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.data.repository.GifRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.AppLifecycleTracker
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
import com.bothbubbles.services.scheduled.ScheduledMessageWorker
import com.bothbubbles.services.spam.SpamReportingService
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.services.sync.CounterpartSyncService
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.components.message.EmojiAnalysis
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.ReactionUiModel
import com.bothbubbles.ui.components.message.ReplyPreviewData
import com.bothbubbles.ui.components.input.SuggestionItem
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.components.message.ThreadChain
import com.bothbubbles.util.EmojiUtils.analyzeEmojis
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.delegates.QueuedMessageInfo
import com.bothbubbles.ui.components.message.formatMessageTime
import com.bothbubbles.ui.chat.paging.MessagePagingController
import com.bothbubbles.ui.chat.paging.PagingConfig
import com.bothbubbles.ui.chat.paging.RoomMessageDataSource
import com.bothbubbles.ui.chat.paging.SparseMessageList
import com.bothbubbles.ui.chat.paging.SyncTrigger
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
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
    private val spamRepository: SpamRepository,
    private val spamReportingService: SpamReportingService,
    private val attachmentRepository: AttachmentRepository,
    private val attachmentDownloadQueue: AttachmentDownloadQueue,
    private val androidContactsService: AndroidContactsService,
    private val vCardService: VCardService,
    private val smartReplyService: SmartReplyService,
    private val quickReplyTemplateRepository: QuickReplyTemplateRepository,
    private val scheduledMessageRepository: ScheduledMessageRepository,
    private val workManager: WorkManager,
    private val handleRepository: HandleRepository,
    private val activeConversationManager: ActiveConversationManager,
    private val api: BothBubblesApi,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val attachmentPreloader: AttachmentPreloader,
    private val pendingMessageRepository: PendingMessageRepository,
    private val iMessageAvailabilityService: IMessageAvailabilityService,
    private val attachmentLimitsProvider: AttachmentLimitsProvider,
    private val chatStateCache: ChatStateCache,
    private val appLifecycleTracker: AppLifecycleTracker,
    // Delegates for decomposition
    private val sendDelegate: ChatSendDelegate,
    private val attachmentDelegate: ChatAttachmentDelegate,
    private val etaSharingDelegate: ChatEtaSharingDelegate,
    private val messageSendingService: MessageSendingService,
    // Sync integrity services
    private val counterpartSyncService: CounterpartSyncService,
    private val gifRepository: GifRepository,
    // Media playback
    val exoPlayerPool: ExoPlayerPool
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val AVAILABILITY_CHECK_COOLDOWN = 5 * 60 * 1000L // 5 minutes
        private const val SERVER_STABILITY_PERIOD_MS = 30_000L // 30 seconds stable before allowing iMessage
        private const val FLIP_FLOP_WINDOW_MS = 60_000L // 1 minute window for flip/flop detection
        private const val FLIP_FLOP_THRESHOLD = 3 // 3+ state changes = unstable server

        // Adaptive polling: catches missed messages when push is unreliable
        private const val POLL_INTERVAL_MS = 2000L // Poll every 2 seconds when socket is quiet
        private const val SOCKET_QUIET_THRESHOLD_MS = 5000L // Start polling after 5s of socket silence
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

    // Separate flow for messages to prevent full UI recomposition on message updates
    private val _messagesState = MutableStateFlow<StableList<MessageUiModel>>(StableList(emptyList()))
    val messagesState: StateFlow<StableList<MessageUiModel>> = _messagesState.asStateFlow()

    // Draft text flow (declared before composerState which depends on it)
    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    // Pending attachments flow (declared before composerState which depends on it)
    private val _pendingAttachments = MutableStateFlow<List<PendingAttachmentInput>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachmentInput>> = _pendingAttachments.asStateFlow()

    // Attachment quality flow (declared before composerState which depends on it)
    private val _attachmentQuality = MutableStateFlow(AttachmentQuality.STANDARD)

    private val _activePanel = MutableStateFlow(ComposerPanel.None)
    val activePanel: StateFlow<ComposerPanel> = _activePanel.asStateFlow()

    // ============================================================================
    // COMPOSER STATE OPTIMIZATION
    // Decouples composer from full _uiState (80+ fields) to prevent cascade recomposition.
    // Only the ~8 fields actually needed by the composer are extracted and gated by distinctUntilChanged.
    // ============================================================================

    /**
     * Lightweight projection of ChatUiState containing only fields relevant to the composer.
     * This isolates the composer from unrelated UI state changes (messages, search, etc.).
     */
    private data class ComposerRelevantState(
        val replyToMessage: MessageUiModel? = null,
        val currentSendMode: ChatSendMode = ChatSendMode.SMS,
        val isSending: Boolean = false,
        val smsInputBlocked: Boolean = false,
        val isLocalSmsChat: Boolean = false,
        val isInSmsFallbackMode: Boolean = false,
        val attachmentWarning: AttachmentWarning? = null
    )

    /**
     * Derived flow that extracts only composer-relevant fields from _uiState.
     * distinctUntilChanged prevents downstream emissions unless these specific fields change.
     */
    private val composerRelevantState: Flow<ComposerRelevantState> = _uiState
        .map { ui ->
            ComposerRelevantState(
                replyToMessage = ui.replyToMessage,
                currentSendMode = ui.currentSendMode,
                isSending = ui.isSending,
                smsInputBlocked = ui.smsInputBlocked,
                isLocalSmsChat = ui.isLocalSmsChat,
                isInSmsFallbackMode = ui.isInSmsFallbackMode,
                attachmentWarning = ui.attachmentWarning
            )
        }
        .distinctUntilChanged()

    // Memoization caches for expensive transformations inside combine
    @Volatile private var _cachedAttachmentItems: List<AttachmentItem> = emptyList()
    @Volatile private var _lastAttachmentInputs: List<PendingAttachmentInput>? = null
    @Volatile private var _lastAttachmentQuality: AttachmentQuality? = null
    @Volatile private var _cachedReplyPreview: MessagePreview? = null
    @Volatile private var _lastReplyMessageGuid: String? = null

    // Composer State
    val composerState: StateFlow<ComposerState> = combine(
        composerRelevantState,  // Gated by distinctUntilChanged - only emits when composer fields change
        _draftText,
        _pendingAttachments,
        _attachmentQuality,
        _activePanel
    ) { relevant, text, attachments, quality, panel ->
        // Memoized attachment transformation - only rebuild if inputs changed (referential equality)
        val attachmentItems = if (attachments === _lastAttachmentInputs && quality == _lastAttachmentQuality) {
            _cachedAttachmentItems
        } else {
            attachments.map {
                AttachmentItem(
                    id = it.uri.toString(),
                    uri = it.uri,
                    mimeType = it.mimeType,
                    displayName = it.name,
                    sizeBytes = it.size,
                    quality = quality,
                    caption = it.caption
                )
            }.also {
                _cachedAttachmentItems = it
                _lastAttachmentInputs = attachments
                _lastAttachmentQuality = quality
            }
        }

        // Memoized MessagePreview transformation - only rebuild if reply target changed
        val replyPreview = relevant.replyToMessage?.let { msg ->
            if (msg.guid == _lastReplyMessageGuid && _cachedReplyPreview != null) {
                _cachedReplyPreview
            } else {
                MessagePreview.fromMessageUiModel(msg).also {
                    _cachedReplyPreview = it
                    _lastReplyMessageGuid = msg.guid
                }
            }
        } ?: run {
            _cachedReplyPreview = null
            _lastReplyMessageGuid = null
            null
        }

        ComposerState(
            text = text,
            attachments = attachmentItems,
            attachmentWarning = relevant.attachmentWarning?.let { warning ->
                ComposerAttachmentWarning(
                    message = warning.message,
                    isError = warning.isError,
                    suggestCompression = warning.suggestCompression,
                    affectedUri = warning.affectedUri
                )
            },
            replyToMessage = replyPreview,
            sendMode = relevant.currentSendMode,
            isSending = relevant.isSending,
            smsInputBlocked = relevant.smsInputBlocked,
            isLocalSmsChat = relevant.isLocalSmsChat || relevant.isInSmsFallbackMode,
            currentImageQuality = quality,
            activePanel = panel
        )
    }
        .distinctUntilChanged()  // Final gate: skip UI emissions when ComposerState structurally unchanged
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ComposerState())

    fun onComposerEvent(event: ComposerEvent) {
        when (event) {
            is ComposerEvent.TextChanged -> {
                _draftText.value = event.text
                // TODO: Call sendDelegate.startTyping() when typing indicators are wired up
            }
            is ComposerEvent.AddAttachments -> {
                addAttachments(event.uris)
            }
            is ComposerEvent.RemoveAttachment -> {
                removeAttachment(event.attachment.uri)
            }
            is ComposerEvent.ClearAllAttachments -> {
                clearAttachments()
            }
            is ComposerEvent.Send -> {
                sendMessage()
            }
            is ComposerEvent.ToggleSendMode -> {
                _uiState.update { it.copy(currentSendMode = event.newMode) }
            }
            is ComposerEvent.DismissReply -> {
                clearReply()
            }
            is ComposerEvent.ToggleMediaPicker -> {
                _activePanel.update { if (it == ComposerPanel.MediaPicker) ComposerPanel.None else ComposerPanel.MediaPicker }
            }
            is ComposerEvent.ToggleEmojiPicker -> {
                _activePanel.update { if (it == ComposerPanel.EmojiKeyboard) ComposerPanel.None else ComposerPanel.EmojiKeyboard }
            }
            is ComposerEvent.ToggleGifPicker -> {
                _activePanel.update { if (it == ComposerPanel.GifPicker) ComposerPanel.None else ComposerPanel.GifPicker }
            }
            is ComposerEvent.OpenGallery -> {
                _activePanel.update { ComposerPanel.MediaPicker }
            }
            is ComposerEvent.DismissPanel -> {
                _activePanel.update { ComposerPanel.None }
            }
            is ComposerEvent.ReorderAttachments -> {
                // Convert AttachmentItem back to PendingAttachmentInput order
                val reorderedUris = event.attachments.map { it.uri }
                _pendingAttachments.update { currentList ->
                    reorderedUris.mapNotNull { uri ->
                        currentList.find { it.uri == uri }
                    }
                }
            }
            else -> {
                // Handle other events or ignore
            }
        }
    }


    // Error state for UI display (consolidated into uiState.appError, this is for backward compatibility)
    private val _appError = MutableStateFlow<AppError?>(null)
    val appError: StateFlow<AppError?> = _appError.asStateFlow()

    fun clearAppError() {
        _appError.value = null
        _uiState.update { it.copy(appError = null) }
    }

    // Track when we're fetching older messages from the BlueBubbles server
    // Used to show "Loading more messages..." indicator at top of message list
    private val _isLoadingFromServer = MutableStateFlow(false)
    val isLoadingFromServer: StateFlow<Boolean> = _isLoadingFromServer.asStateFlow()

    // PERF: Message cache for incremental updates - preserves object identity for unchanged messages
    // This allows Compose to skip recomposition for items that haven't changed
    private val messageCache = MessageCache()

    // SyncTrigger implementation for fetching from server when gaps are detected
    private val syncTriggerImpl = object : SyncTrigger {
        override suspend fun requestSyncForRange(chatGuids: List<String>, startPosition: Int, count: Int) {
            if (_isLoadingFromServer.value) return // Already loading
            // Skip if chat doesn't exist yet (foreign key constraint would fail)
            if (chatRepository.getChat(chatGuid) == null) return

            Log.d(TAG, "SyncTrigger: requesting sync for range $startPosition-${startPosition + count}")
            _isLoadingFromServer.value = true

            try {
                // Calculate the timestamp to fetch before (older messages)
                // For position-based pagination, we need to find the oldest message we have
                // and request messages before that
                messageRepository.syncMessagesForChat(
                    chatGuid = chatGuid,
                    limit = count.coerceAtLeast(100), // Use larger chunks for server fetch
                    offset = startPosition
                )
            } catch (e: Exception) {
                Log.e(TAG, "SyncTrigger: sync failed", e)
            } finally {
                _isLoadingFromServer.value = false
            }
        }

        override suspend fun requestSyncForMessage(chatGuids: List<String>, messageGuid: String) {
            Log.d(TAG, "SyncTrigger: requesting sync for message $messageGuid")
            // Skip if chat doesn't exist yet (foreign key constraint would fail)
            if (chatRepository.getChat(chatGuid) == null) return
            // For individual message sync, don't show loading indicator
            try {
                messageRepository.syncMessagesForChat(chatGuid = chatGuid, limit = 10)
            } catch (e: Exception) {
                Log.e(TAG, "SyncTrigger: message sync failed", e)
            }
        }
    }

    // Signal-style BitSet pagination controller
    private val pagingController: MessagePagingController by lazy {
        val dataSource = RoomMessageDataSource(
            chatGuids = mergedChatGuids,
            messageRepository = messageRepository,
            attachmentRepository = attachmentRepository,
            handleRepository = handleRepository,
            chatRepository = chatRepository,
            messageCache = messageCache,
            syncTrigger = syncTriggerImpl
        )
        MessagePagingController(
            dataSource = dataSource,
            config = PagingConfig.Default,
            scope = viewModelScope
        )
    }

    // Sparse message list from paging controller - supports holes for unloaded positions
    val sparseMessages: StateFlow<SparseMessageList> = pagingController.messages

    // Total message count for scroll indicator
    val totalMessageCount: StateFlow<Int> = pagingController.totalCount

    // Track initial load completion for animation control
    // When true, new messages should animate; when false, show instantly (initial load)
    val initialLoadComplete: StateFlow<Boolean> = pagingController.initialLoadComplete

    /**
     * Check if a message has been loaded this session.
     * Used to skip entrance animations for previously-viewed messages when scrolling back.
     */
    fun hasMessageBeenSeen(guid: String): Boolean = pagingController.hasBeenSeen(guid)

    // Initial scroll position from LRU cache (for instant restore when re-opening chat)
    // ChatScreen should read this once to initialize LazyListState
    private val _cachedScrollPosition = MutableStateFlow<Pair<Int, Int>?>(null)
    val cachedScrollPosition: StateFlow<Pair<Int, Int>?> = _cachedScrollPosition.asStateFlow()

    // Trigger to refresh messages (incremented when attachments are downloaded)
    private val _attachmentRefreshTrigger = MutableStateFlow(0)

    // NOTE: _draftText, val draftText, _pendingAttachments, val pendingAttachments
    // are now declared earlier in the file (before composerState)

    // Attachment download progress tracking
    // Maps attachment GUID to download progress (0.0 to 1.0, or null if not downloading)
    private val _attachmentDownloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val attachmentDownloadProgress: StateFlow<Map<String, Float>> = _attachmentDownloadProgress.asStateFlow()

    // Whether to use auto-download mode (true) or manual download mode (false)
    private val _autoDownloadEnabled = MutableStateFlow(true)
    val autoDownloadEnabled: StateFlow<Boolean> = _autoDownloadEnabled.asStateFlow()

    // Emit socket-pushed message GUIDs for "new messages" indicator
    // This ONLY fires for truly new messages from socket, NOT for synced/historical messages
    private val _socketNewMessage = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val socketNewMessage: SharedFlow<String> = _socketNewMessage.asSharedFlow()

    // Attachment Quality Settings
    // NOTE: _attachmentQuality is now declared earlier in the file (before composerState)
    private val _rememberQuality = MutableStateFlow(false)

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

    // iMessage availability checking state
    private var serverConnectedSince: Long? = null // Timestamp when server became connected
    private var iMessageAvailabilityCheckJob: Job? = null
    private var smsFallbackJob: Job? = null // Delayed SMS fallback on disconnect (debounced)
    private val smsFallbackDelayMs = 3000L // Wait 3 seconds before falling back to SMS
    // Track connection state changes for flip/flop detection
    // Only counts transitions between CONNECTED and DISCONNECTED/RECONNECTING (not initial connection)
    private val connectionStateChanges = mutableListOf<Long>()
    private var previousConnectionState: ConnectionState? = null
    private var hasEverConnected = false

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

    // Effect settings flows
    val autoPlayEffects = settingsDataStore.autoPlayEffects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val replayEffectsOnScroll = settingsDataStore.replayEffectsOnScroll
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val reduceMotion = settingsDataStore.reduceMotion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Screen effect state and queue
    data class ScreenEffectState(
        val effect: MessageEffect.Screen,
        val messageGuid: String,
        val messageText: String?
    )

    private val _activeScreenEffect = MutableStateFlow<ScreenEffectState?>(null)
    val activeScreenEffect: StateFlow<ScreenEffectState?> = _activeScreenEffect.asStateFlow()

    private val screenEffectQueue = mutableListOf<ScreenEffectState>()
    private var isPlayingScreenEffect = false

    // Thread overlay state - shows the thread chain when user taps a reply indicator
    private val _threadOverlayState = MutableStateFlow<ThreadChain?>(null)
    val threadOverlayState: StateFlow<ThreadChain?> = _threadOverlayState.asStateFlow()

    // Scroll-to-message event - emitted when user taps a message in thread overlay
    private val _scrollToGuid = MutableSharedFlow<String>()
    val scrollToGuid: SharedFlow<String> = _scrollToGuid.asSharedFlow()

    // iMessage availability check cooldown (per-session, resets on ViewModel creation)
    private var lastAvailabilityCheck: Long = 0

    // Adaptive polling: tracks when we last received a socket message for this chat
    // Used to detect when push may have failed and trigger fallback polling
    @Volatile
    private var lastSocketMessageTime: Long = System.currentTimeMillis()

    init {
        // Initialize delegates
        sendDelegate.initialize(chatGuid, viewModelScope)
        attachmentDelegate.initialize(chatGuid, viewModelScope, mergedChatGuids)
        etaSharingDelegate.initialize(viewModelScope)

        // Observe delegate state flows and forward to UI state
        viewModelScope.launch {
            sendDelegate.replyingToGuid.collect { guid ->
                _uiState.update { it.copy(replyingToGuid = guid) }
            }
        }
        viewModelScope.launch {
            sendDelegate.isForwarding.collect { forwarding ->
                _uiState.update { it.copy(isForwarding = forwarding) }
            }
        }
        viewModelScope.launch {
            sendDelegate.forwardSuccess.collect { success ->
                if (success) {
                    _uiState.update { it.copy(forwardSuccess = true) }
                }
            }
        }
        viewModelScope.launch {
            sendDelegate.sendError.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(error = error) }
                }
            }
        }
        // Forward attachment delegate's download progress to ViewModel state
        viewModelScope.launch {
            attachmentDelegate.downloadProgress.collect { progress ->
                _attachmentDownloadProgress.value = progress
            }
        }
        viewModelScope.launch {
            attachmentDelegate.refreshTrigger.collect { trigger ->
                _attachmentRefreshTrigger.value = trigger
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
        loadMessages()
        syncMessages() // Always sync to check for new messages (runs in background)
        observeTypingIndicators()
        observeTypingIndicatorSettings()
        observeNewMessages()
        observeMessageUpdates()
        markAsRead()
        determineChatType()
        observeParticipantsForSaveContactBanner()
        observeFallbackMode()
        observeConnectionState()
        observeSmartReplies()
        observeAutoDownloadSetting()
        observeQualitySettings()
        observeUploadProgress()
        saveCurrentChatState()
        observeQueuedMessages()
        startAdaptivePolling() // Catches missed messages when push is unreliable
        observeForegroundResume() // Sync when app returns from background
        checkAndRepairCounterpart() // Lazy repair: check for missing iMessage/SMS counterpart

        // Check if iMessage is available again (for chats in SMS fallback mode)
        // Delay slightly to ensure chat data is loaded first
        viewModelScope.launch {
            delay(500) // Wait for loadChat() to populate participantPhone
            checkAndMaybeExitFallback()
            // Check iMessage availability for the contact (for send mode switching)
            checkIMessageAvailability()
        }

        // Initialize server stability tracking if already connected
        if (socketService.connectionState.value == ConnectionState.CONNECTED) {
            serverConnectedSince = System.currentTimeMillis()
        }
    }

    /**
     * Observe typing indicator settings and cache them for fast access.
     * This avoids suspend calls on every keystroke in handleTypingIndicator.
     */
    private fun observeTypingIndicatorSettings() {
        viewModelScope.launch {
            settingsDataStore.enablePrivateApi.collect { enabled ->
                cachedPrivateApiEnabled = enabled
            }
        }
        viewModelScope.launch {
            settingsDataStore.sendTypingIndicators.collect { enabled ->
                cachedTypingIndicatorsEnabled = enabled
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
     * Observe attachment quality settings.
     */
    private fun observeQualitySettings() {
        viewModelScope.launch {
            settingsDataStore.defaultImageQuality.collect { qualityName ->
                val quality = try {
                    AttachmentQuality.valueOf(qualityName)
                } catch (e: IllegalArgumentException) {
                    AttachmentQuality.STANDARD
                }
                _attachmentQuality.value = quality
                _uiState.update { it.copy(attachmentQuality = quality) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.rememberLastQuality.collect { remember ->
                _rememberQuality.value = remember
                _uiState.update { it.copy(rememberQuality = remember) }
            }
        }
    }

    /**
     * Update the attachment quality for the current session.
     * If "remember last quality" is enabled, this also updates the global default.
     */
    fun setAttachmentQuality(quality: AttachmentQuality) {
        _attachmentQuality.value = quality
        _uiState.update { it.copy(attachmentQuality = quality) }

        if (_rememberQuality.value) {
            viewModelScope.launch {
                settingsDataStore.setDefaultImageQuality(quality.name)
            }
        }
    }

    /**
     * Observe upload progress from MessageSendingService for determinate progress bar.
     * Updates the first pending message with attachments and recalculates aggregate progress.
     */
    private fun observeUploadProgress() {
        viewModelScope.launch {
            messageSendingService.uploadProgress.collect { progress ->
                if (progress != null) {
                    // Calculate individual message progress (0.0 to 1.0)
                    val attachmentBase = progress.attachmentIndex.toFloat() / progress.totalAttachments
                    val currentProgress = progress.progress / progress.totalAttachments
                    val messageProgress = attachmentBase + currentProgress

                    // Update the first pending message with attachments
                    _uiState.update { state ->
                        val pendingList = state.pendingMessages.toMutableList()
                        val attachmentIndex = pendingList.indexOfFirst { it.hasAttachments }
                        if (attachmentIndex >= 0) {
                            pendingList[attachmentIndex] = pendingList[attachmentIndex].copy(progress = messageProgress)
                        }
                        state.copy(
                            pendingMessages = pendingList.toStable(),
                            sendProgress = calculateAggregateProgress(pendingList)
                        )
                    }
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
            _messagesState
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
     */
    private fun observeQueuedMessages() {
        viewModelScope.launch {
            pendingMessageRepository.observePendingForChat(chatGuid)
                .collect { pending ->
                    _uiState.update { state ->
                        state.copy(
                            queuedMessages = pending.map { it.toQueuedUiModel() }.toStable()
                        )
                    }
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
                _uiState.update {
                    it.copy(
                        isInSmsFallbackMode = entry != null,
                        fallbackReason = entry?.reason
                    )
                }
            }
        }
    }

    /**
     * Check if iMessage is available for this chat and auto-exit fallback mode if so.
     * Only checks if:
     * 1. Chat is in SMS fallback due to IMESSAGE_FAILED reason
     * 2. Cooldown has passed (5 minutes)
     * 3. Server is connected
     */
    private fun checkAndMaybeExitFallback() {
        val fallbackReason = chatFallbackTracker.getFallbackReason(chatGuid)

        // Only check for IMESSAGE_FAILED fallback, not server disconnected or user requested
        if (fallbackReason != FallbackReason.IMESSAGE_FAILED) return

        val now = System.currentTimeMillis()
        if (now - lastAvailabilityCheck < AVAILABILITY_CHECK_COOLDOWN) {
            Log.d(TAG, "Skipping availability check - cooldown not passed")
            return
        }
        lastAvailabilityCheck = now

        viewModelScope.launch {
            // Get the primary address from chat identifier or first participant
            val address = _uiState.value.participantPhone
            if (address.isNullOrBlank()) {
                Log.d(TAG, "No address found for availability check")
                return@launch
            }

            // Only check if server is connected
            if (socketService.connectionState.value != ConnectionState.CONNECTED) {
                Log.d(TAG, "Server not connected, skipping availability check")
                return@launch
            }

            try {
                Log.d(TAG, "Checking iMessage availability for $address")
                val response = api.checkIMessageAvailability(address)
                if (response.isSuccessful && response.body()?.data?.available == true) {
                    Log.d(TAG, "iMessage now available for $address, exiting fallback mode")
                    chatFallbackTracker.exitFallbackMode(chatGuid)
                    _uiState.update { it.copy(isInSmsFallbackMode = false, fallbackReason = null) }
                } else {
                    Log.d(TAG, "iMessage still unavailable for $address")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check iMessage availability", e)
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            socketService.connectionState.collect { state ->
                val isConnected = state == ConnectionState.CONNECTED
                _uiState.update { it.copy(isServerConnected = isConnected) }

                // Track flip/flops: only count transitions AFTER the first successful connection
                // This avoids counting initial DISCONNECTED → CONNECTING → CONNECTED as flip/flops
                val now = System.currentTimeMillis()
                val wasConnected = previousConnectionState == ConnectionState.CONNECTED
                val stateActuallyChanged = previousConnectionState != null && previousConnectionState != state

                if (hasEverConnected && stateActuallyChanged && (wasConnected || isConnected)) {
                    // Only count transitions between CONNECTED and non-CONNECTED states
                    connectionStateChanges.add(now)
                }

                // Prune old entries outside the 1-minute window
                connectionStateChanges.removeAll { it < now - FLIP_FLOP_WINDOW_MS }
                Log.d(TAG, "DEBUG: Connection state changed to $state (prev=$previousConnectionState), flip/flop count in last minute: ${connectionStateChanges.size}")

                // Update tracking state
                if (isConnected) {
                    hasEverConnected = true
                }
                previousConnectionState = state

                if (isConnected) {
                    // Cancel any pending SMS fallback since we're connected again
                    smsFallbackJob?.cancel()
                    smsFallbackJob = null

                    // Track when server became connected for stability check
                    if (serverConnectedSince == null) {
                        serverConnectedSince = System.currentTimeMillis()
                    }

                    // Restore iMessage mode for iMessage chats that were auto-switched to SMS on disconnect
                    val currentState = _uiState.value
                    val shouldBeIMessage = currentState.isIMessageChat && !currentState.isLocalSmsChat
                    val wasAutoSwitchedToSms = currentState.currentSendMode == ChatSendMode.SMS && !currentState.sendModeManuallySet

                    if (shouldBeIMessage && wasAutoSwitchedToSms) {
                        if (isServerStable()) {
                            // Server is stable, restore iMessage mode immediately
                            Log.d(TAG, "DEBUG: Server reconnected (stable), restoring iMessage mode")
                            _uiState.update { it.copy(currentSendMode = ChatSendMode.IMESSAGE) }
                        } else {
                            // Server is unstable, schedule check after stability period
                            scheduleIMessageModeCheck()
                        }
                    }
                } else {
                    // Server disconnected - reset stability tracking
                    serverConnectedSince = null
                    iMessageAvailabilityCheckJob?.cancel()

                    // Only switch to SMS for non-iMessage-only chats
                    // iMessage group chats (iMessage;+;...) can ONLY be iMessage - no SMS fallback
                    // Email addresses can ONLY be iMessage - no SMS fallback
                    val isIMessageGroup = chatGuid.startsWith("iMessage;+;", ignoreCase = true)
                    val isEmailChat = _uiState.value.participantPhone?.contains("@") == true
                    val isIMessageOnly = isIMessageGroup || isEmailChat

                    if (!isIMessageOnly) {
                        // Schedule SMS fallback after delay (debounced to avoid flicker on brief disconnects)
                        smsFallbackJob?.cancel()
                        smsFallbackJob = viewModelScope.launch {
                            Log.d(TAG, "DEBUG: Server disconnected, scheduling SMS fallback in ${smsFallbackDelayMs}ms")
                            delay(smsFallbackDelayMs)
                            Log.d(TAG, "DEBUG: SMS fallback delay elapsed, switching to SMS")
                            _uiState.update { currentState ->
                                currentState.copy(currentSendMode = ChatSendMode.SMS)
                            }
                        }
                    } else {
                        Log.d(TAG, "DEBUG: Server disconnected, keeping iMessage mode (iMessage-only chat: group=$isIMessageGroup, email=$isEmailChat)")
                    }
                }
            }
        }
    }

    /**
     * Schedule a check to switch to iMessage mode after server stability period.
     */
    private fun scheduleIMessageModeCheck() {
        iMessageAvailabilityCheckJob?.cancel()
        iMessageAvailabilityCheckJob = viewModelScope.launch {
            // Wait for server to be stable for required period
            delay(SERVER_STABILITY_PERIOD_MS)

            // Verify server is still connected
            if (socketService.connectionState.value != ConnectionState.CONNECTED) {
                return@launch
            }

            // Check if contact supports iMessage
            val contactAvailable = _uiState.value.contactIMessageAvailable
            if (contactAvailable == true) {
                _uiState.update { it.copy(currentSendMode = ChatSendMode.IMESSAGE) }
                Log.d(TAG, "Switched to iMessage mode after ${SERVER_STABILITY_PERIOD_MS}ms stability")
            }
        }
    }

    /**
     * Check iMessage availability for the current chat's contact(s).
     * Called when chat opens - re-checks if cache is from previous session.
     */
    private fun checkIMessageAvailability() {
        // Skip for ALL group chats - availability check only makes sense for 1-on-1 chats
        // Group chat type (iMessage vs SMS) is determined by GUID prefix, not individual availability
        val currentState = _uiState.value
        Log.d(TAG, "DEBUG checkIMessageAvailability: chatGuid=$chatGuid, isLocalSmsChat=${currentState.isLocalSmsChat}, isGroup=${currentState.isGroup}, isIMessageChat=${currentState.isIMessageChat}, participantPhone=${currentState.participantPhone}, currentSendMode=${currentState.currentSendMode}")
        if (currentState.isGroup) {
            Log.d(TAG, "Skipping iMessage check: group chat (isIMessage=${currentState.isIMessageChat})")
            return
        }

        val participantPhone = currentState.participantPhone ?: run {
            Log.w(TAG, "DEBUG: participantPhone is null, cannot check availability")
            return
        }

        // For existing iMessage 1-on-1 chats with phone numbers:
        // iMessage is already working (chat exists), and SMS is possible (has phone number).
        // Enable toggle immediately - no need to wait for availability check.
        val isEmailAddress = participantPhone.contains("@")
        if (currentState.isIMessageChat && !currentState.isGroup && !isEmailAddress) {
            Log.d(TAG, "iMessage chat with phone number - enabling SMS toggle immediately")
            _uiState.update { state ->
                state.copy(
                    contactIMessageAvailable = true,
                    canToggleSendMode = true,
                    showSendModeRevealAnimation = !state.sendModeManuallySet
                )
            }
            initTutorialIfNeeded()
            return // No need for availability check - we know both options work
        }

        Log.d(TAG, "Starting iMessage availability check for: $participantPhone")

        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingIMessageAvailability = true) }

            try {
                // Check if cache is from previous session (needs re-check on app restart)
                val needsRecheck = iMessageAvailabilityService.isCacheFromPreviousSession(participantPhone)
                Log.d(TAG, "DEBUG: needsRecheck=$needsRecheck for $participantPhone")

                val result = iMessageAvailabilityService.checkAvailability(
                    address = participantPhone,
                    forceRecheck = needsRecheck
                )

                val serverStable = isServerStable()
                Log.d(TAG, "DEBUG: serverStable=$serverStable, serverConnectedSince=$serverConnectedSince")

                // Email addresses can ONLY be iMessage - no stability check needed, no SMS fallback
                val isEmailAddress = participantPhone.contains("@")

                result.fold(
                    onSuccess = { available ->
                        // Determine send mode based on availability and stability
                        val newMode = when {
                            // Email addresses can ONLY be iMessage
                            isEmailAddress -> ChatSendMode.IMESSAGE
                            // iMessage available and server stable - use iMessage
                            available && serverStable -> ChatSendMode.IMESSAGE
                            // iMessage available but server not stable - keep current mode (wait for stability)
                            available -> _uiState.value.currentSendMode
                            // iMessage NOT available - use SMS
                            else -> ChatSendMode.SMS
                        }
                        Log.d(TAG, "DEBUG: iMessage availability result for $participantPhone: available=$available, serverStable=$serverStable, isEmail=$isEmailAddress, newMode=$newMode")

                        // Check if toggle is available (both modes supported, not email-only)
                        val canToggle = available && !isEmailAddress && !_uiState.value.isGroup
                        Log.d(TAG, "DEBUG: canToggleSendMode=$canToggle")

                        // Check if SMS should be blocked when falling back to SMS mode
                        // Block if iMessage not available AND SMS not properly activated
                        val isDefaultSmsApp = smsPermissionHelper.isDefaultSmsApp()
                        val smsEnabled = settingsDataStore.smsEnabled.first()
                        val shouldBlockSms = !available && !isEmailAddress && (!isDefaultSmsApp || !smsEnabled)
                        Log.d(TAG, "DEBUG: shouldBlockSms=$shouldBlockSms (isDefaultSmsApp=$isDefaultSmsApp, smsEnabled=$smsEnabled)")

                        _uiState.update { state ->
                            state.copy(
                                contactIMessageAvailable = available,
                                isCheckingIMessageAvailability = false,
                                currentSendMode = newMode,
                                canToggleSendMode = canToggle,
                                // Show reveal animation if toggle is available and not already shown
                                showSendModeRevealAnimation = canToggle && !state.sendModeManuallySet,
                                // Block SMS input if iMessage unavailable and SMS not activated
                                smsInputBlocked = shouldBlockSms
                            )
                        }
                        // Initialize tutorial if toggle just became available
                        if (canToggle) {
                            initTutorialIfNeeded()
                        }
                        // If available but server not yet stable, the scheduled check will handle it
                    },
                    onFailure = { e ->
                        Log.w(TAG, "DEBUG: iMessage availability check FAILED for $participantPhone: ${e.message}, isEmail=$isEmailAddress", e)
                        // For email addresses, default to iMessage even on failure (no SMS fallback)
                        val newMode = if (isEmailAddress) ChatSendMode.IMESSAGE else _uiState.value.currentSendMode
                        Log.d(TAG, "DEBUG: Setting mode to $newMode after failure (isEmail=$isEmailAddress)")
                        _uiState.update { state ->
                            state.copy(
                                contactIMessageAvailable = if (isEmailAddress) true else null,
                                isCheckingIMessageAvailability = false,
                                currentSendMode = newMode
                            )
                        }
                        Log.d(TAG, "DEBUG: After failure update, currentSendMode=${_uiState.value.currentSendMode}")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "DEBUG: Unexpected error checking iMessage availability: ${e.message}", e)
                _uiState.update { it.copy(isCheckingIMessageAvailability = false) }
            }
        }
    }

    /**
     * Check if server connection is stable.
     * Server is considered stable if there are fewer than 3 connection state changes in the last minute.
     * If unstable (3+ flip/flops), we also require 30 seconds of continuous connection.
     */
    private fun isServerStable(): Boolean {
        val now = System.currentTimeMillis()
        // Prune old entries outside the 1-minute window
        connectionStateChanges.removeAll { it < now - FLIP_FLOP_WINDOW_MS }

        // If fewer than threshold flip/flops, server is stable
        if (connectionStateChanges.size < FLIP_FLOP_THRESHOLD) {
            Log.d(TAG, "DEBUG isServerStable: true (flip/flop count ${connectionStateChanges.size} < $FLIP_FLOP_THRESHOLD)")
            return true
        }

        // Server has been flapping - require 30 seconds of continuous connection
        val connectedSince = serverConnectedSince ?: return false
        val stableFor = now - connectedSince
        val isStable = stableFor >= SERVER_STABILITY_PERIOD_MS
        Log.d(TAG, "DEBUG isServerStable: $isStable (flapping detected, connected for ${stableFor}ms, need ${SERVER_STABILITY_PERIOD_MS}ms)")
        return isStable
    }

    /**
     * Manually switch send mode (for UI toggle).
     * Only allows switching to iMessage if contact supports it and server is stable.
     *
     * @param mode The target send mode
     * @param persist Whether to persist the choice to the database (default true)
     * @return true if the switch was successful, false otherwise
     */
    fun setSendMode(mode: ChatSendMode, persist: Boolean = true): Boolean {
        if (mode == ChatSendMode.IMESSAGE) {
            // Validate before switching to iMessage
            if (_uiState.value.contactIMessageAvailable != true) {
                Log.w(TAG, "Cannot switch to iMessage: contact doesn't support it")
                return false
            }
            if (!isServerStable()) {
                Log.w(TAG, "Cannot switch to iMessage: server not stable yet")
                return false
            }
        }

        _uiState.update {
            it.copy(
                currentSendMode = mode,
                sendModeManuallySet = persist
            )
        }
        Log.d(TAG, "Send mode changed to: $mode (persist=$persist)")

        // Persist to database if requested
        if (persist) {
            viewModelScope.launch {
                try {
                    chatRepository.updatePreferredSendMode(
                        chatGuid = chatGuid,
                        mode = mode.name.lowercase(),
                        manuallySet = true
                    )
                    Log.d(TAG, "Persisted send mode preference: $mode for chat $chatGuid")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist send mode preference", e)
                }
            }
        }

        return true
    }

    /**
     * Try to toggle send mode (for swipe gesture).
     * Returns true if the toggle was successful.
     */
    fun tryToggleSendMode(): Boolean {
        val currentMode = _uiState.value.currentSendMode
        val newMode = if (currentMode == ChatSendMode.SMS) ChatSendMode.IMESSAGE else ChatSendMode.SMS
        return setSendMode(newMode, persist = true)
    }

    /**
     * Check if send mode toggle is currently available.
     */
    fun canToggleSendMode(): Boolean {
        val state = _uiState.value
        return state.canToggleSendMode &&
                state.contactIMessageAvailable == true &&
                !state.isGroup &&
                isServerStable()
    }

    /**
     * Mark the send mode reveal animation as shown.
     */
    fun markRevealAnimationShown() {
        _uiState.update { it.copy(showSendModeRevealAnimation = false) }
    }

    /**
     * Initialize tutorial state based on whether user has completed it before.
     */
    fun initTutorialIfNeeded() {
        if (!_uiState.value.canToggleSendMode) return

        viewModelScope.launch {
            settingsDataStore.hasCompletedSendModeTutorial.first().let { completed ->
                if (!completed) {
                    _uiState.update { it.copy(tutorialState = TutorialState.STEP_1_SWIPE_UP) }
                }
            }
        }
    }

    /**
     * Update tutorial state after user completes a step.
     */
    fun updateTutorialState(newState: TutorialState) {
        _uiState.update { it.copy(tutorialState = newState) }

        // If completed, persist to settings
        if (newState == TutorialState.COMPLETED) {
            viewModelScope.launch {
                settingsDataStore.setHasCompletedSendModeTutorial(true)
            }
        }
    }

    /**
     * Handle successful toggle during tutorial.
     * Progresses the tutorial state appropriately.
     */
    fun onTutorialToggleSuccess() {
        when (_uiState.value.tutorialState) {
            TutorialState.STEP_1_SWIPE_UP -> {
                _uiState.update { it.copy(tutorialState = TutorialState.STEP_2_SWIPE_BACK) }
            }
            TutorialState.STEP_2_SWIPE_BACK -> {
                _uiState.update { it.copy(tutorialState = TutorialState.COMPLETED) }
                viewModelScope.launch {
                    settingsDataStore.setHasCompletedSendModeTutorial(true)
                }
            }
            else -> { /* No action needed */ }
        }
    }

    private fun syncMessages() {
        _uiState.update { it.copy(isSyncingMessages = true) }
        if (messageRepository.isLocalSmsChat(chatGuid)) {
            syncSmsMessages()
        } else {
            syncMessagesFromServer()
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
                    if (_messagesState.value.isEmpty()) {
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

    // ===== Thread Overlay Functions =====

    /**
     * Load a thread chain for display in the thread overlay.
     * Called when user taps a reply indicator.
     */
    fun loadThread(originGuid: String) {
        viewModelScope.launch {
            val threadMessages = messageRepository.getThreadMessages(originGuid)
            val origin = threadMessages.find { it.guid == originGuid }
            val replies = threadMessages.filter { it.threadOriginatorGuid == originGuid }

            // Get participants for sender name and avatar resolution
            val participants = chatRepository.getParticipantsForChats(mergedChatGuids)
            val handleIdToName = participants.associate { it.id to it.displayName }
            val addressToName = participants.associate { normalizeAddress(it.address) to it.displayName }
            val addressToAvatarPath = participants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }

            // Batch load attachments for all thread messages
            val allAttachments = attachmentRepository.getAttachmentsForMessages(
                threadMessages.map { it.guid }
            ).groupBy { it.messageGuid }

            // Filter out placed stickers from thread overlay - they're visual overlays, not actual replies
            val filteredReplies = replies.filter { msg ->
                val msgAttachments = allAttachments[msg.guid].orEmpty()
                val isPlacedSticker = msg.associatedMessageGuid != null &&
                    msgAttachments.any { it.mimeType?.contains("sticker") == true }
                !isPlacedSticker
            }

            _threadOverlayState.value = ThreadChain(
                originMessage = origin?.toUiModel(
                    attachments = allAttachments[origin.guid].orEmpty(),
                    handleIdToName = handleIdToName,
                    addressToName = addressToName,
                    addressToAvatarPath = addressToAvatarPath
                ),
                replies = filteredReplies.map { msg ->
                    msg.toUiModel(
                        attachments = allAttachments[msg.guid].orEmpty(),
                        handleIdToName = handleIdToName,
                        addressToName = addressToName,
                        addressToAvatarPath = addressToAvatarPath
                    )
                }.toStable()
            )
        }
    }

    /**
     * Dismiss the thread overlay.
     */
    fun dismissThreadOverlay() {
        _threadOverlayState.value = null
    }

    /**
     * Scroll to a specific message in the main chat.
     * Called when user taps a message in the thread overlay.
     */
    fun scrollToMessage(guid: String) {
        viewModelScope.launch {
            dismissThreadOverlay()
            _scrollToGuid.emit(guid)
        }
    }

    /**
     * Jump to a specific message, loading its position from the database if needed.
     * This is paging-aware and works with sparse loading.
     *
     * @param guid The message GUID to jump to
     * @return The position of the message, or null if not found
     */
    suspend fun jumpToMessage(guid: String): Int? {
        return pagingController.jumpToMessage(guid)
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
                        _draftText.value = it.textFieldText ?: ""
                    }
                    _uiState.update { state ->
                        state.copy(
                            chatTitle = chatTitle,
                            isGroup = it.isGroup,
                            avatarPath = participants.firstOrNull()?.cachedAvatarPath,
                            participantNames = participants.map { p -> p.displayName }.toStable(),
                            participantAvatarPaths = participants.map { p -> p.cachedAvatarPath }.toStable(),
                            isArchived = it.isArchived,
                            isStarred = it.isStarred,
                            participantPhone = it.chatIdentifier,
                            isSpam = it.isSpam,
                            isReportedToCarrier = it.spamReportedToCarrier,
                            isSnoozed = it.isSnoozed,
                            snoozeUntil = it.snoozeUntil,
                            isLocalSmsChat = it.isLocalSms,  // Only local SMS, not server forwarding
                            isIMessageChat = it.isIMessage,
                            smsInputBlocked = it.isSmsChat && !smsPermissionHelper.isDefaultSmsApp()
                        )
                    }
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

    private fun loadMessages() {
        // Initialize Signal-style BitSet pagination controller
        pagingController.initialize()

        // Bridge sparse messages to uiState.messages for backwards compatibility with ChatScreen
        // TODO: Phase 4 will update ChatScreen to use sparseMessages directly
        //
        // PHASE 2 OPTIMIZATION: Run list transformation on background thread
        // The toList() operation sorts keys and iterates the map - O(N log N) + O(N).
        // At 5000+ messages, this can take >16ms and cause frame drops.
        // flowOn(Dispatchers.Default) moves this work off the main thread.
        viewModelScope.launch {
            pagingController.messages
                .map { sparseList -> sparseList.toList() }
                // .flowOn(Dispatchers.Default)  // REMOVED: Causing ~300ms latency on optimistic inserts. Sorting <5000 items is fast enough on main.
                .conflate()
                .collect { messageModels ->
                    val collectStart = System.currentTimeMillis()
                    Log.d(TAG, "⏱️ [UI] collecting messages: ${messageModels.size}, thread: ${Thread.currentThread().name}")

                    val collectId = PerformanceProfiler.start("Chat.messagesCollected", "${messageModels.size} messages")
                    
                    // Update separate messages flow FIRST (triggers list recomposition only)
                    val stableMessages = messageModels.toStable()
                    _messagesState.value = stableMessages

                    // Update main UI state (triggers rest of screen)
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            // messages = stableMessages, // REMOVED: Decoupled to prevent massive recomposition
                            canLoadMore = messageModels.size < pagingController.totalCount.value
                        )
                    }
                    PerformanceProfiler.end(collectId)
                    Log.d(TAG, "⏱️ [UI] messages updated: +${System.currentTimeMillis() - collectStart}ms")
                }
        }

        // Handle attachment refresh by refreshing the paging controller
        viewModelScope.launch {
            _attachmentRefreshTrigger.collect { trigger ->
                if (trigger > 0) {
                    Log.d(TAG, "Attachment refresh triggered, refreshing paging controller")
                    pagingController.refresh()
                }
            }
        }
    }

    /**
     * Called by ChatScreen when scroll position changes.
     * Notifies the paging controller to load data around the visible range.
     */
    fun onScrollPositionChanged(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        pagingController.onDataNeededAroundIndex(firstVisibleIndex, lastVisibleIndex)
    }

    private fun syncMessagesFromServer() {
        viewModelScope.launch {
            // Ensure chat exists in local DB before syncing messages
            // Messages have a foreign key constraint on chat_guid -> chats.guid
            // If chat doesn't exist, message inserts will fail with SQLiteConstraintException
            val chatExists = chatRepository.getChat(chatGuid) != null
            if (!chatExists) {
                Log.d(TAG, "Chat $chatGuid not in local DB, fetching from server first")
                chatRepository.fetchChat(chatGuid).onFailure { e ->
                    Log.e(TAG, "Failed to fetch chat $chatGuid from server", e)
                    if (_messagesState.value.isEmpty()) {
                        _uiState.update { it.copy(error = "Failed to load chat: ${e.message}") }
                    }
                    _uiState.update { it.copy(isSyncingMessages = false) }
                    return@launch
                }
            }

            messageRepository.syncMessagesForChat(
                chatGuid = chatGuid,
                limit = 50
            ).onFailure { e ->
                // Only show error if we have no local messages
                if (_messagesState.value.isEmpty()) {
                    _uiState.update { it.copy(error = e.message) }
                }
            }
            _uiState.update { it.copy(isSyncingMessages = false) }
        }
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
                    _uiState.update { it.copy(isTyping = event.isTyping) }
                }
        }
    }

    /**
     * Observe new messages from socket for this chat.
     * When a new message arrives, mark as read since user is viewing the chat.
     * The actual message display is handled by Room Flow (single source of truth).
     */
    private fun observeNewMessages() {
        viewModelScope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.NewMessage>()
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
                    android.util.Log.d("ChatViewModel", "Socket: New message received for ${event.chatGuid}, guid=${event.message.guid}")

                    // Update socket activity timestamp (for adaptive polling)
                    lastSocketMessageTime = System.currentTimeMillis()

                    // Notify paging controller about the new message
                    // Room Flow will handle the actual message display (single source of truth)
                    pagingController.onNewMessageInserted(event.message.guid)

                    // Emit for "new messages" indicator in ChatScreen
                    // Only socket-pushed messages should increment the counter
                    _socketNewMessage.tryEmit(event.message.guid)

                    // Mark as read since user is viewing the chat
                    markAsRead()
                }
        }
    }

    /**
     * Adaptive polling to catch messages missed by push notifications.
     *
     * BlueBubbles server occasionally fails to push messages via Socket/FCM.
     * This polling mechanism activates when the socket has been "quiet" for
     * longer than SOCKET_QUIET_THRESHOLD_MS, fetching any messages newer than
     * what we have locally.
     *
     * The polling is lightweight (~200 bytes request, 0-2KB response) and runs
     * against the local BlueBubbles server (< 5ms query time).
     */
    private fun startAdaptivePolling() {
        // Skip polling for local SMS chats (no server to poll)
        if (messageRepository.isLocalSmsChat(chatGuid)) return

        viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)

                // Only poll if socket has been quiet for a while
                val timeSinceLastSocketMessage = System.currentTimeMillis() - lastSocketMessageTime
                if (timeSinceLastSocketMessage < SOCKET_QUIET_THRESHOLD_MS) {
                    continue // Socket is active, trust push
                }

                // Only poll if socket is connected (server is reachable)
                if (!socketService.isConnected()) {
                    continue
                }

                // Get the timestamp of the newest message we have locally
                val newestMessage = _messagesState.value.firstOrNull()
                val afterTimestamp = newestMessage?.dateCreated

                try {
                    // Skip if chat doesn't exist yet (wait for initial sync to create it)
                    if (chatRepository.getChat(chatGuid) == null) {
                        continue
                    }

                    val result = messageRepository.syncMessagesForChat(
                        chatGuid = chatGuid,
                        limit = 10,
                        after = afterTimestamp
                    )
                    result.onSuccess { messages ->
                        if (messages.isNotEmpty()) {
                            Log.d(TAG, "Adaptive polling found ${messages.size} missed message(s)")
                        }
                    }
                } catch (e: Exception) {
                    // Silently ignore polling errors - this is a fallback mechanism
                    Log.d(TAG, "Adaptive polling error: ${e.message}")
                }
            }
        }
    }

    /**
     * Sync messages when app returns to foreground.
     *
     * This catches any messages that may have been missed while the app
     * was in the background (e.g., if FCM/socket failed to deliver).
     */
    private fun observeForegroundResume() {
        // Skip for local SMS chats (no server to sync from)
        if (messageRepository.isLocalSmsChat(chatGuid)) return

        viewModelScope.launch {
            appLifecycleTracker.foregroundState
                .collect { isInForeground ->
                    if (isInForeground) {
                        Log.d(TAG, "App resumed - syncing for missed messages")
                        // Reset socket activity timer so adaptive polling doesn't immediately fire
                        lastSocketMessageTime = System.currentTimeMillis()

                        // Skip if chat doesn't exist yet (wait for initial sync to create it)
                        if (chatRepository.getChat(chatGuid) == null) {
                            return@collect
                        }

                        // Sync recent messages to catch any missed while backgrounded
                        val newestMessage = _messagesState.value.firstOrNull()
                        val afterTimestamp = newestMessage?.dateCreated

                        try {
                            val result = messageRepository.syncMessagesForChat(
                                chatGuid = chatGuid,
                                limit = 25,
                                after = afterTimestamp
                            )
                            result.onSuccess { messages ->
                                if (messages.isNotEmpty()) {
                                    Log.d(TAG, "Foreground sync found ${messages.size} missed message(s)")
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Foreground sync error: ${e.message}")
                        }
                    }
                }
        }
    }

    /**
     * Observe message updates from socket.
     * Triggers paging controller to refresh the message from Room (single source of truth).
     */
    private fun observeMessageUpdates() {
        viewModelScope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.MessageUpdated>()
                .filter { event ->
                    val normalizedEventGuid = normalizeGuid(event.chatGuid)
                    mergedChatGuids.any { normalizeGuid(it) == normalizedEventGuid } ||
                        normalizeGuid(chatGuid) == normalizedEventGuid ||
                        extractAddress(event.chatGuid)?.let { eventAddress ->
                            mergedChatGuids.any { extractAddress(it) == eventAddress } ||
                                extractAddress(chatGuid) == eventAddress
                        } == true
                }
                .collect { event ->
                    android.util.Log.d("ChatViewModel", "Socket: Message updated for ${event.chatGuid}, guid=${event.message.guid}")

                    // Trigger paging controller to reload the message from Room
                    // Room Flow will handle the actual UI update (single source of truth)
                    pagingController.updateMessage(event.message.guid)
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

    /**
     * Force a one-time refresh of messages from Room.
     * This is used when we know new messages have arrived but Room's Flow hasn't emitted.
     */
    private suspend fun forceRefreshMessages() {
        val participantsFlow = chatRepository.observeParticipantsForChats(mergedChatGuids)
        val participants = participantsFlow.first()
        val handleIdToName = participants.associate { it.id to it.displayName }
        val addressToName = participants.associate { normalizeAddress(it.address) to it.displayName }
        val addressToAvatarPath = participants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }

        val messagesFlow = if (isMergedChat) {
            messageRepository.observeMessagesForChats(mergedChatGuids, limit = 50, offset = 0)
        } else {
            messageRepository.observeMessagesForChat(chatGuid, limit = 50, offset = 0)
        }

        val messages = messagesFlow.first()

        // Separate reactions from regular messages
        val iMessageReactions = messages.filter { it.isReaction }
        val regularMessages = messages.filter { !it.isReaction }

        // Group reactions by associated message GUID
        // Note: associatedMessageGuid may have "p:X/" prefix (e.g., "p:0/MESSAGE_GUID")
        // Strip the prefix to match against plain message GUIDs
        val reactionsByMessage = iMessageReactions.groupBy { reaction ->
            reaction.associatedMessageGuid?.let { guid ->
                if (guid.contains("/")) guid.substringAfter("/") else guid
            }
        }

        // Batch load all attachments in a single query
        val allAttachments = attachmentRepository.getAttachmentsForMessages(
            regularMessages.map { it.guid }
        ).groupBy { it.messageGuid }

        val messageModels = regularMessages.map { message ->
            val messageReactions = reactionsByMessage[message.guid].orEmpty()
            val attachments = allAttachments[message.guid].orEmpty()
            message.toUiModel(
                reactions = messageReactions,
                attachments = attachments,
                handleIdToName = handleIdToName,
                addressToName = addressToName,
                addressToAvatarPath = addressToAvatarPath
            )
        }

        _uiState.update { state ->
            state.copy(
                isLoading = false,
                messages = messageModels.toStable()
            )
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
        _draftText.value = text
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
            chatRepository.updateDraftText(chatGuid, _draftText.value)
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
            val messages = _messagesState.value
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
        // Dispose paging controller to cancel observers
        pagingController.dispose()
        // Clear message cache to free memory
        messageCache.clear()
    }

    fun addAttachment(uri: Uri) {
        addAttachments(listOf(uri))
    }

    fun addAttachments(uris: List<Uri>) {
        viewModelScope.launch {
            val currentAttachments = _pendingAttachments.value
            val currentSendMode = _uiState.value.currentSendMode
            val isLocalSms = _uiState.value.isLocalSmsChat

            // Determine delivery mode for validation
            val deliveryMode = when {
                isLocalSms -> MessageDeliveryMode.LOCAL_MMS
                currentSendMode == ChatSendMode.SMS -> MessageDeliveryMode.LOCAL_MMS
                currentSendMode == ChatSendMode.IMESSAGE -> MessageDeliveryMode.IMESSAGE
                else -> MessageDeliveryMode.AUTO
            }

            // Calculate existing total size
            val existingTotalSize = currentAttachments.sumOf { existingUri ->
                existingUri.size ?: 0L
            }

            val newAttachments = mutableListOf<PendingAttachmentInput>()
            var currentTotalSize = existingTotalSize
            var currentCount = currentAttachments.size
            var lastWarning: AttachmentWarning? = null

            for (uri in uris) {
                // Get file size for new attachment
                val newFileSize = try {
                    attachmentRepository.getAttachmentSize(uri) ?: 0L
                } catch (e: Exception) {
                    Log.w(TAG, "Could not determine attachment size", e)
                    0L
                }
                
                // Get mime type and name
                val mimeType = try {
                    attachmentRepository.getMimeType(uri)
                } catch (e: Exception) {
                    null
                }
                
                val name = try {
                    attachmentRepository.getFileName(uri)
                } catch (e: Exception) {
                    null
                }

                // Validate
                val validation = attachmentLimitsProvider.validateAttachment(
                    sizeBytes = newFileSize,
                    deliveryMode = deliveryMode,
                    existingTotalSize = currentTotalSize,
                    existingCount = currentCount
                )

                val warning = when {
                    !validation.isValid -> AttachmentWarning(
                        message = validation.message ?: "Attachment too large",
                        isError = true,
                        suggestCompression = validation.suggestCompression,
                        affectedUri = uri
                    )
                    validation.warning != null -> AttachmentWarning(
                        message = validation.warning,
                        isError = false,
                        suggestCompression = false,
                        affectedUri = uri
                    )
                    else -> null
                }

                if (warning != null && warning.isError) {
                    lastWarning = warning
                    continue // Skip adding this one if it's an error
                } else if (warning != null) {
                    lastWarning = warning
                }

                newAttachments.add(PendingAttachmentInput(uri, mimeType = mimeType, name = name, size = newFileSize))
                currentTotalSize += newFileSize
                currentCount++
            }

            if (newAttachments.isNotEmpty()) {
                _pendingAttachments.update { it + newAttachments }
            }
            
            _uiState.update {
                it.copy(
                    attachmentCount = _pendingAttachments.value.size,
                    attachmentWarning = lastWarning
                )
            }
        }
    }

    /**
     * Gets contact data from a contact URI for preview in options dialog.
     * Returns null if the contact cannot be read.
     */
    fun getContactData(contactUri: Uri): ContactData? {
        return vCardService.getContactData(contactUri)
    }

    /**
     * Adds a contact as a vCard attachment directly from a contact picker URI.
     * Uses default options (includes all fields).
     */
    fun addContactFromPicker(contactUri: Uri) {
        val contactData = vCardService.getContactData(contactUri) ?: return
        val defaultOptions = FieldOptions()
        addContactAsVCard(contactData, defaultOptions)
    }

    /**
     * Creates a vCard file from contact data with field options and adds it as an attachment.
     * Returns true if successful, false otherwise.
     */
    fun addContactAsVCard(contactData: ContactData, options: FieldOptions): Boolean {
        val vcardUri = vCardService.createVCardFromContactData(contactData, options)
        return if (vcardUri != null) {
            addAttachment(vcardUri)
            true
        } else {
            false
        }
    }

    // GIF Picker state - delegate to repository
    val gifPickerState = gifRepository.state
    val gifSearchQuery = gifRepository.searchQuery

    fun updateGifSearchQuery(query: String) {
        gifRepository.updateSearchQuery(query)
    }

    fun searchGifs(query: String) {
        viewModelScope.launch {
            gifRepository.search(query)
        }
    }

    fun loadFeaturedGifs() {
        viewModelScope.launch {
            gifRepository.loadFeatured()
        }
    }

    fun selectGif(gif: com.bothbubbles.ui.chat.composer.panels.GifItem) {
        viewModelScope.launch {
            val fileUri = gifRepository.downloadGif(gif)
            fileUri?.let { addAttachment(it) }
        }
    }

    fun removeAttachment(uri: Uri) {
        _pendingAttachments.update { list -> list.filter { it.uri != uri } }
        // Clear warning if removing the attachment that caused the issue
        val currentWarning = _uiState.value.attachmentWarning
        val clearWarning = currentWarning?.affectedUri == uri
        _uiState.update {
            it.copy(
                attachmentCount = _pendingAttachments.value.size,
                attachmentWarning = if (clearWarning) null else currentWarning
            )
        }
    }

    /**
     * Reorder attachments based on drag-and-drop result.
     */
    fun reorderAttachments(reorderedList: List<PendingAttachmentInput>) {
        _pendingAttachments.update { reorderedList }
    }

    fun onAttachmentEdited(originalUri: Uri, editedUri: Uri, caption: String? = null) {
        _pendingAttachments.update { list ->
            list.map { input ->
                if (input.uri == originalUri) {
                    input.copy(uri = editedUri, caption = caption)
                } else {
                    input
                }
            }
        }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
        _uiState.update { it.copy(attachmentCount = 0, attachmentWarning = null) }
    }

    /**
     * Dismiss the attachment warning without removing the attachment.
     * Use for non-error warnings the user acknowledges.
     */
    fun dismissAttachmentWarning() {
        _uiState.update { it.copy(attachmentWarning = null) }
    }

    fun sendMessage(effectId: String? = null) {
        val sendStartTime = System.currentTimeMillis()
        Log.d(TAG, "⏱️ [SEND] sendMessage() CALLED on thread: ${Thread.currentThread().name}")

        val text = _draftText.value.trim()
        val attachments = _pendingAttachments.value

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
                // Clear UI state immediately for responsive feel
                _draftText.value = ""
                _pendingAttachments.value = emptyList()
                _uiState.update { state ->
                    state.copy(attachmentCount = 0)
                }
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
                pagingController.insertMessageOptimistically(optimisticModel)
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
        val text = _draftText.value.trim()
        val attachments = _pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        // Delegate to ChatSendDelegate
        sendDelegate.sendMessageVia(
            text = text,
            attachments = attachments,
            deliveryMode = deliveryMode,
            onClearInput = {
                // Clear UI state immediately
                _draftText.value = ""
                _pendingAttachments.value = emptyList()
                _uiState.update { it.copy(attachmentCount = 0) }
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
        val message = _messagesState.value.find { it.guid == messageGuid } ?: return

        // Guard: Only allow on server-origin messages (IMESSAGE or SERVER_SMS)
        // Local SMS/MMS cannot have tapbacks
        if (!message.isServerOrigin) {
            return
        }

        val isRemoving = tapback in message.myReactions
        Log.d(TAG, "toggleReaction: messageGuid=$messageGuid, tapback=${tapback.apiName}, isRemoving=$isRemoving")

        viewModelScope.launch {
            // OPTIMISTIC UPDATE: Immediately show the reaction in UI
            val optimisticUpdateApplied = pagingController.updateMessageLocally(messageGuid) { currentMessage ->
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
                pagingController.updateMessage(messageGuid)
            }.onFailure { error ->
                Log.e(TAG, "toggleReaction: API failed for $messageGuid, rolling back optimistic update", error)
                // ROLLBACK: Revert to database state (which doesn't have the reaction)
                pagingController.updateMessage(messageGuid)
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
     */
    fun clearForwardSuccess() {
        sendDelegate.clearForwardSuccess()
        _uiState.update { it.copy(forwardSuccess = false) }
    }

    /**
     * Check if a failed message can be retried as SMS
     */
    suspend fun canRetryAsSms(messageGuid: String): Boolean {
        return sendDelegate.canRetryAsSms(messageGuid)
    }

    fun loadMoreMessages() {
        Log.d("ChatScroll", "[VM] loadMoreMessages called: isLoadingMore=${_uiState.value.isLoadingMore}, canLoadMore=${_uiState.value.canLoadMore}")
        if (_uiState.value.isLoadingMore || !_uiState.value.canLoadMore) {
            Log.d("ChatScroll", "[VM] loadMoreMessages SKIPPED - already loading or no more to load")
            return
        }

        val oldestMessage = _messagesState.value.lastOrNull()
        if (oldestMessage == null) {
            Log.d("ChatScroll", "[VM] loadMoreMessages SKIPPED - no messages in list")
            return
        }

        Log.d("ChatScroll", "[VM] >>> STARTING loadMoreMessages: oldestDate=${oldestMessage.dateCreated}, currentMsgCount=${_messagesState.value.size}")

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            val startTime = System.currentTimeMillis()
            // Sync older messages from server to fill gaps in local Room database
            messageRepository.syncMessagesForChat(
                chatGuid = chatGuid,
                before = oldestMessage.dateCreated,
                limit = 50
            ).handle(
                onSuccess = { messages ->
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d("ChatScroll", "[VM] loadMoreMessages SUCCESS: synced ${messages.size} messages in ${elapsed}ms")

                    // Refresh paging controller to pick up newly synced messages
                    // The paging controller will automatically load them based on scroll position
                    pagingController.refresh()

                    _uiState.update { state ->
                        state.copy(
                            isLoadingMore = false,
                            canLoadMore = messages.size == 50
                        )
                    }
                    Log.d("ChatScroll", "[VM] loadMoreMessages COMPLETE: canLoadMore=${messages.size == 50}")
                },
                onError = { appError ->
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.e("ChatScroll", "[VM] loadMoreMessages FAILED after ${elapsed}ms: ${appError.technicalMessage}")
                    _uiState.update { it.copy(isLoadingMore = false, appError = appError) }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, appError = null) }
    }

    // ===== Menu Actions =====

    fun archiveChat() {
        viewModelScope.launch {
            chatRepository.setArchived(chatGuid, true).handle(
                onSuccess = {
                    _uiState.update { it.copy(isArchived = true) }
                },
                onError = { appError ->
                    _uiState.update { it.copy(appError = appError) }
                }
            )
        }
    }

    fun unarchiveChat() {
        viewModelScope.launch {
            chatRepository.setArchived(chatGuid, false).handle(
                onSuccess = {
                    _uiState.update { it.copy(isArchived = false) }
                },
                onError = { appError ->
                    _uiState.update { it.copy(appError = appError) }
                }
            )
        }
    }

    fun toggleStarred() {
        val currentStarred = _uiState.value.isStarred
        viewModelScope.launch {
            chatRepository.setStarred(chatGuid, !currentStarred).handle(
                onSuccess = {
                    _uiState.update { it.copy(isStarred = !currentStarred) }
                },
                onError = { appError ->
                    _uiState.update { it.copy(appError = appError) }
                }
            )
        }
    }

    fun deleteChat() {
        viewModelScope.launch {
            chatRepository.deleteChat(chatGuid).handle(
                onSuccess = {
                    _uiState.update { it.copy(chatDeleted = true) }
                },
                onError = { appError ->
                    _uiState.update { it.copy(appError = appError) }
                }
            )
        }
    }

    fun toggleSubjectField() {
        _uiState.update { it.copy(showSubjectField = !it.showSubjectField) }
    }

    // ===== Inline Search =====

    fun activateSearch() {
        _uiState.update {
            it.copy(
                isSearchActive = true,
                searchQuery = "",
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = -1,
                isSearchingDatabase = false,
                databaseSearchResultCount = 0,
                showSearchResultsSheet = false
            )
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = -1,
                isSearchingDatabase = false,
                databaseSearchResultCount = 0,
                showSearchResultsSheet = false
            )
        }
    }

    fun updateSearchQuery(query: String) {
        // PERF: Cancel previous search job - only latest query matters
        searchJob?.cancel()

        // Update query immediately for responsive UI
        _uiState.update { it.copy(searchQuery = query) }

        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    searchMatchIndices = emptyList(),
                    currentSearchMatchIndex = -1,
                    isSearchingDatabase = false,
                    databaseSearchResultCount = 0
                )
            }
            return
        }

        // Debounce the actual search to avoid running on every keystroke
        searchJob = viewModelScope.launch {
            delay(searchDebounceMs)

            val messages = _messagesState.value
            val normalizedQuery = TextNormalization.normalizeForSearch(query)

            // Search with expanded scope: text, subject, attachment filenames
            // Uses diacritic-insensitive matching (e.g., "cafe" matches "café")
            val matchIndices = messages.mapIndexedNotNull { index, message ->
                if (matchesSearchQuery(message, normalizedQuery)) index else null
            }

            val currentIndex = if (matchIndices.isNotEmpty()) 0 else -1
            _uiState.update {
                it.copy(
                    searchMatchIndices = matchIndices,
                    currentSearchMatchIndex = currentIndex
                )
            }

            // TODO: Add async database search for full conversation history
            // This would search mergedChatGuids via MessageRepository and update
            // isSearchingDatabase and databaseSearchResultCount
        }
    }

    /**
     * Check if a message matches the normalized search query.
     * Searches text, subject, and attachment filenames with diacritic-insensitive matching.
     */
    private fun matchesSearchQuery(message: MessageUiModel, normalizedQuery: String): Boolean {
        // Check message text
        if (TextNormalization.containsNormalized(message.text, normalizedQuery)) {
            return true
        }

        // Check subject
        if (TextNormalization.containsNormalized(message.subject, normalizedQuery)) {
            return true
        }

        // Check attachment filenames
        return message.attachments.any { attachment ->
            TextNormalization.containsNormalized(attachment.transferName, normalizedQuery)
        }
    }

    fun navigateSearchUp() {
        val state = _uiState.value
        if (state.searchMatchIndices.isEmpty()) return
        val newIndex = if (state.currentSearchMatchIndex <= 0) {
            state.searchMatchIndices.size - 1
        } else {
            state.currentSearchMatchIndex - 1
        }
        _uiState.update { it.copy(currentSearchMatchIndex = newIndex) }
    }

    fun navigateSearchDown() {
        val state = _uiState.value
        if (state.searchMatchIndices.isEmpty()) return
        val newIndex = if (state.currentSearchMatchIndex >= state.searchMatchIndices.size - 1) {
            0
        } else {
            state.currentSearchMatchIndex + 1
        }
        _uiState.update { it.copy(currentSearchMatchIndex = newIndex) }
    }

    /**
     * Show the search results bottom sheet.
     */
    fun showSearchResultsSheet() {
        _uiState.update { it.copy(showSearchResultsSheet = true) }
    }

    /**
     * Hide the search results bottom sheet.
     */
    fun hideSearchResultsSheet() {
        _uiState.update { it.copy(showSearchResultsSheet = false) }
    }

    /**
     * Scroll to a specific message by GUID and highlight it.
     * Uses paging-aware loading and runs in viewModelScope.
     * Meant for UI callbacks that need fire-and-forget behavior.
     */
    fun scrollToAndHighlightMessage(messageGuid: String) {
        viewModelScope.launch {
            val position = pagingController.jumpToMessage(messageGuid)
            if (position != null) {
                // Emit scroll event for the UI to handle
                _scrollToGuid.emit(messageGuid)
                // Highlight after scroll
                delay(100)
                highlightMessage(messageGuid)
            }
        }
    }

    /**
     * Create intent to add contact to Android Contacts app.
     * Pre-fills the name if we have an inferred name from a self-introduction message.
     */
    fun getAddToContactsIntent(): Intent {
        val phone = _uiState.value.participantPhone ?: ""
        val inferredName = _uiState.value.inferredSenderName
        return Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, phone)
            // Pre-fill the contact name if we inferred it from a self-introduction message
            if (inferredName != null) {
                putExtra(ContactsContract.Intents.Insert.NAME, inferredName)
            }
        }
    }

    /**
     * Create intent to start Google Meet call
     */
    fun getGoogleMeetIntent(): Intent {
        // Open Google Meet to create a new meeting
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com/new"))
    }

    /**
     * Create intent to start WhatsApp video call
     */
    fun getWhatsAppCallIntent(): Intent? {
        val phone = _uiState.value.participantPhone?.replace(Regex("[^0-9+]"), "") ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone"))
    }

    /**
     * Create intent to open help page
     */
    fun getHelpIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BlueBubblesApp/bluebubbles-app/issues"))
    }

    /**
     * Block a phone number (SMS only)
     */
    fun blockContact(context: Context): Boolean {
        if (!_uiState.value.isLocalSmsChat) return false

        val phone = _uiState.value.participantPhone ?: return false

        return try {
            val values = android.content.ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, phone)
            }
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values
            )
            true
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Failed to block contact: ${e.message}") }
            false
        }
    }

    /**
     * Check if WhatsApp is installed
     */
    fun isWhatsAppAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.whatsapp", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun MessageEntity.toUiModel(
        reactions: List<MessageEntity> = emptyList(),
        attachments: List<AttachmentEntity> = emptyList(),
        handleIdToName: Map<Long, String> = emptyMap(),
        addressToName: Map<String, String> = emptyMap(),
        addressToAvatarPath: Map<String, String?> = emptyMap(),
        replyPreview: ReplyPreviewData? = null
    ): MessageUiModel {
        // Filter reactions using old BlueBubbles Flutter logic:
        // 1. Remove duplicate GUIDs
        // 2. Sort by date (newest first)
        // 3. Keep only the latest reaction per sender
        // 4. Filter out removals (types starting with "-" or 3xxx codes)
        val uniqueReactions = reactions
            .distinctBy { it.guid }
            .sortedByDescending { it.dateCreated }
            .let { sorted ->
                val seenSenders = mutableSetOf<Long>()
                sorted.filter { reaction ->
                    val senderId = if (reaction.isFromMe) 0L else (reaction.handleId ?: 0L)
                    seenSenders.add(senderId) // Returns true if not already present
                }
            }
            .filter { !isReactionRemoval(it.associatedMessageType) }

        // Parse filtered reactions into UI models
        val allReactions = uniqueReactions.mapNotNull { reaction ->
            val tapbackType = parseReactionType(reaction.associatedMessageType)
            tapbackType?.let {
                ReactionUiModel(
                    tapback = it,
                    isFromMe = reaction.isFromMe,
                    senderName = reaction.handleId?.let { id -> handleIdToName[id] }
                )
            }
        }

        // Get my reactions (for highlighting in the menu)
        val myReactions = allReactions
            .filter { it.isFromMe }
            .map { it.tapback }
            .toSet()

        // Map attachments to UI models
        val attachmentUiModels = attachments
            .filter { !it.hideAttachment }
            .map { attachment ->
                AttachmentUiModel(
                    guid = attachment.guid,
                    mimeType = attachment.mimeType,
                    localPath = attachment.localPath,
                    webUrl = attachment.webUrl,
                    width = attachment.width,
                    height = attachment.height,
                    transferName = attachment.transferName,
                    totalBytes = attachment.totalBytes,
                    isSticker = attachment.isSticker,
                    blurhash = attachment.blurhash,
                    thumbnailPath = attachment.thumbnailPath,
                    transferState = attachment.transferState,
                    transferProgress = attachment.transferProgress,
                    isOutgoing = attachment.isOutgoing
                )
            }

        return MessageUiModel(
            guid = guid,
            text = text,
            subject = subject,
            dateCreated = dateCreated,
            formattedTime = formatTime(dateCreated),
            isFromMe = isFromMe,
            isSent = !guid.startsWith("temp-") && error == 0,
            isDelivered = dateDelivered != null,
            isRead = dateRead != null,
            hasError = error != 0,
            isReaction = associatedMessageType?.contains("reaction") == true,
            attachments = attachmentUiModels.toStable(),
            // Resolve sender name: try senderAddress first (most accurate), then fall back to handleId lookup
            senderName = resolveSenderName(senderAddress, handleId, addressToName, handleIdToName),
            senderAvatarPath = resolveSenderAvatarPath(senderAddress, addressToAvatarPath),
            messageSource = messageSource,
            reactions = allReactions.toStable(),
            myReactions = myReactions,
            expressiveSendStyleId = expressiveSendStyleId,
            effectPlayed = datePlayed != null,
            associatedMessageGuid = associatedMessageGuid,
            // Reply indicator fields
            threadOriginatorGuid = threadOriginatorGuid,
            replyPreview = replyPreview,
            // Pre-compute emoji analysis to avoid recalculating on every composition
            emojiAnalysis = analyzeEmojis(text)
        )
    }

    /**
     * Parse the reaction type from the associatedMessageType field.
     * BlueBubbles format examples: "2000" (love), "2001" (like), etc.
     * Or text format: "love", "like", "dislike", "laugh", "emphasize", "question"
     */
    private fun parseReactionType(associatedMessageType: String?): Tapback? {
        if (associatedMessageType == null) return null

        // Try parsing as API name first (text format)
        // Note: "-love" indicates removal, so check for that first
        if (associatedMessageType.startsWith("-")) {
            return null // This is a removal, handled separately
        }
        Tapback.fromApiName(associatedMessageType)?.let { return it }

        // Parse numeric codes (iMessage internal format)
        // 2000 = love, 2001 = like, 2002 = dislike, 2003 = laugh, 2004 = emphasize, 2005 = question
        // 3000-3005 = removal of reactions (should not be counted as active reactions)
        return when {
            associatedMessageType.contains("3000") -> null // love removal
            associatedMessageType.contains("3001") -> null // like removal
            associatedMessageType.contains("3002") -> null // dislike removal
            associatedMessageType.contains("3003") -> null // laugh removal
            associatedMessageType.contains("3004") -> null // emphasize removal
            associatedMessageType.contains("3005") -> null // question removal
            associatedMessageType.contains("2000") -> Tapback.LOVE
            associatedMessageType.contains("2001") -> Tapback.LIKE
            associatedMessageType.contains("2002") -> Tapback.DISLIKE
            associatedMessageType.contains("2003") -> Tapback.LAUGH
            associatedMessageType.contains("2004") -> Tapback.EMPHASIZE
            associatedMessageType.contains("2005") -> Tapback.QUESTION
            associatedMessageType.contains("love") -> Tapback.LOVE
            associatedMessageType.contains("like") -> Tapback.LIKE
            associatedMessageType.contains("dislike") -> Tapback.DISLIKE
            associatedMessageType.contains("laugh") -> Tapback.LAUGH
            associatedMessageType.contains("emphasize") -> Tapback.EMPHASIZE
            associatedMessageType.contains("question") -> Tapback.QUESTION
            else -> null
        }
    }

    /**
     * Check if the reaction code indicates a removal (3000-3005 range).
     */
    private fun isReactionRemoval(associatedMessageType: String?): Boolean {
        if (associatedMessageType == null) return false
        if (associatedMessageType.startsWith("-")) return true
        return associatedMessageType.contains("3000") ||
                associatedMessageType.contains("3001") ||
                associatedMessageType.contains("3002") ||
                associatedMessageType.contains("3003") ||
                associatedMessageType.contains("3004") ||
                associatedMessageType.contains("3005")
    }

    /**
     * Parse the reaction type from a removal code (3xxx range).
     */
    private fun parseRemovalType(associatedMessageType: String?): Tapback? {
        if (associatedMessageType == null) return null
        if (associatedMessageType.startsWith("-")) {
            return Tapback.fromApiName(associatedMessageType.removePrefix("-"))
        }
        return when {
            associatedMessageType.contains("3000") -> Tapback.LOVE
            associatedMessageType.contains("3001") -> Tapback.LIKE
            associatedMessageType.contains("3002") -> Tapback.DISLIKE
            associatedMessageType.contains("3003") -> Tapback.LAUGH
            associatedMessageType.contains("3004") -> Tapback.EMPHASIZE
            associatedMessageType.contains("3005") -> Tapback.QUESTION
            else -> null
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
    }

    /**
     * Normalize an address for comparison/lookup.
     * Strips non-essential characters from phone numbers, lowercases emails.
     */
    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            address.lowercase()
        } else {
            address.replace(Regex("[^0-9+]"), "")
        }
    }

    /**
     * Resolve sender name from available data sources.
     * Priority: senderAddress lookup > handleId lookup > formatted address
     */
    private fun resolveSenderName(
        senderAddress: String?,
        handleId: Long?,
        addressToName: Map<String, String>,
        handleIdToName: Map<Long, String>
    ): String? {
        // 1. Try looking up by senderAddress (most accurate for group chats)
        senderAddress?.let { address ->
            val normalized = normalizeAddress(address)
            addressToName[normalized]?.let { return it }
            // No contact match - return formatted phone number
            return PhoneNumberFormatter.format(address)
        }

        // 2. Fall back to handleId lookup
        return handleId?.let { handleIdToName[it] }
    }

    /**
     * Resolve sender avatar path from address.
     */
    private fun resolveSenderAvatarPath(
        senderAddress: String?,
        addressToAvatarPath: Map<String, String?>
    ): String? {
        senderAddress?.let { address ->
            val normalized = normalizeAddress(address)
            return addressToAvatarPath[normalized]
        }
        return null
    }

    /**
     * Mark the current chat as safe (not spam).
     * This clears the spam flag and whitelists all participants.
     */
    fun markAsSafe() {
        viewModelScope.launch {
            spamRepository.markAsSafe(chatGuid)
        }
    }

    /**
     * Report the current chat as spam.
     * This marks the chat as spam and increments the spam count for all participants.
     */
    fun reportAsSpam() {
        viewModelScope.launch {
            spamRepository.reportAsSpam(chatGuid)
        }
    }

    /**
     * Report the spam to carrier via 7726.
     * Only works for SMS chats.
     */
    fun reportToCarrier(): Boolean {
        if (!uiState.value.isLocalSmsChat) return false

        viewModelScope.launch {
            val result = spamReportingService.reportToCarrier(chatGuid)
            if (result is SpamReportingService.ReportResult.Success) {
                _uiState.update { it.copy(isReportedToCarrier = true) }
            }
        }
        return true
    }

    /**
     * Check if the chat has already been reported to carrier.
     */
    fun checkReportedToCarrier() {
        viewModelScope.launch {
            val isReported = spamReportingService.isReportedToCarrier(chatGuid)
            _uiState.update { it.copy(isReportedToCarrier = isReported) }
        }
    }

    // ===== Effect Playback =====

    /**
     * Called when a bubble effect animation completes.
     */
    fun onBubbleEffectCompleted(messageGuid: String) {
        viewModelScope.launch {
            messageRepository.markEffectPlayed(messageGuid)
        }
    }

    /**
     * Trigger a screen effect for a message.
     * Effects are queued to prevent overlapping animations.
     */
    fun triggerScreenEffect(message: MessageUiModel) {
        val effect = MessageEffect.fromStyleId(message.expressiveSendStyleId) as? MessageEffect.Screen ?: return
        val state = ScreenEffectState(effect, message.guid, message.text)
        screenEffectQueue.add(state)
        if (!isPlayingScreenEffect) playNextScreenEffect()
    }

    private fun playNextScreenEffect() {
        val next = screenEffectQueue.removeFirstOrNull()
        if (next != null) {
            isPlayingScreenEffect = true
            _activeScreenEffect.value = next
        } else {
            isPlayingScreenEffect = false
        }
    }

    /**
     * Called when a screen effect animation completes.
     */
    fun onScreenEffectCompleted() {
        val state = _activeScreenEffect.value
        if (state != null) {
            viewModelScope.launch {
                messageRepository.markEffectPlayed(state.messageGuid)
            }
        }
        _activeScreenEffect.value = null
        isPlayingScreenEffect = false
        playNextScreenEffect()
    }

    // ===== Scheduled Messages =====

    /**
     * Schedule a message to be sent at a later time.
     *
     * Note: This uses client-side scheduling with WorkManager.
     * The phone must be on and have network connectivity for the message to send.
     */
    fun scheduleMessage(text: String, attachments: List<PendingAttachmentInput>, sendAt: Long) {
        viewModelScope.launch {
            // Convert attachments to JSON array string (extract URIs from PendingAttachmentInput)
            val attachmentUrisJson = if (attachments.isNotEmpty()) {
                attachments.joinToString(",", "[", "]") { "\"${it.uri}\"" }
            } else {
                null
            }

            // Create scheduled message entity
            val scheduledMessage = ScheduledMessageEntity(
                chatGuid = chatGuid,
                text = text.ifBlank { null },
                attachmentUris = attachmentUrisJson,
                scheduledAt = sendAt
            )

            // Insert into database
            val id = scheduledMessageRepository.insert(scheduledMessage)

            // Calculate delay
            val delay = sendAt - System.currentTimeMillis()

            // Schedule WorkManager job
            val workRequest = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
                .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(ScheduledMessageWorker.KEY_SCHEDULED_MESSAGE_ID to id)
                )
                .build()

            workManager.enqueue(workRequest)

            // Save the work request ID for potential cancellation
            scheduledMessageRepository.updateWorkRequestId(id, workRequest.id.toString())
        }
    }

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


