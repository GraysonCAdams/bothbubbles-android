package com.bothbubbles.ui.chat.delegates

import androidx.lifecycle.SavedStateHandle
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.AppLifecycleTracker
import com.bothbubbles.services.media.AttachmentDownloadQueue
import com.bothbubbles.services.media.AttachmentPreloader
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.ui.chat.ChatStateCache
import com.bothbubbles.ui.chat.ChatUiState
import com.bothbubbles.ui.chat.MessageCache
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.components.message.ChatListItem
import com.bothbubbles.ui.components.message.ChatViewMode
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.ReactionUiModel
import com.bothbubbles.ui.components.message.ReplyPreviewData
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.components.message.formatMessageTime
import com.bothbubbles.ui.components.message.normalizeAddress
import com.bothbubbles.ui.components.message.toUiModel
import com.bothbubbles.ui.util.StableList
import com.bothbubbles.ui.util.toStable
import com.bothbubbles.util.error.AppError
import com.bothbubbles.util.error.handle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cursor-based pagination delegate for chat messages.
 *
 * This delegate implements the "growing query limit" pattern where Room is the
 * single source of truth. Key benefits over the old BitSet pagination:
 * - O(1) new message handling (Room insert + Flow emit)
 * - No race conditions (Room is transactional SSOT)
 * - Standard Android patterns (no custom pagination logic)
 * - LazyColumn handles diffing via stable keys
 *
 * The delegate manages:
 * - Dynamic query limit that grows as user scrolls up
 * - Recent vs Archive viewing modes
 * - Optimistic message insertion for instant sent display
 * - Date separator generation
 * - Server sync when local data is insufficient
 *
 * @see ChatViewMode for Recent vs Archive modes
 * @see ChatListItem for list item types (Message, DateSeparator, TypingIndicator)
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CursorChatMessageListDelegate @AssistedInject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val attachmentRepository: AttachmentRepository,
    private val handleRepository: HandleRepository,
    private val smsRepository: SmsRepository,
    private val socketConnection: SocketConnection,
    private val appLifecycleTracker: AppLifecycleTracker,
    private val attachmentPreloader: AttachmentPreloader,
    private val attachmentDownloadQueue: AttachmentDownloadQueue,
    private val settingsDataStore: SettingsDataStore,
    private val chatStateCache: ChatStateCache,
    @Assisted private val chatGuid: String,
    @Assisted private val mergedChatGuids: List<String>,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val savedStateHandle: SavedStateHandle,
    @Assisted private val uiStateFlow: MutableStateFlow<ChatUiState>,
    @Assisted private val onUiStateUpdate: (ChatUiState.() -> ChatUiState) -> Unit
) {

    @AssistedFactory
    interface Factory {
        fun create(
            chatGuid: String,
            mergedChatGuids: List<String>,
            scope: CoroutineScope,
            savedStateHandle: SavedStateHandle,
            uiStateFlow: MutableStateFlow<ChatUiState>,
            onUiStateUpdate: (ChatUiState.() -> ChatUiState) -> Unit
        ): CursorChatMessageListDelegate
    }

    // ============================================================================
    // CONSTANTS
    // ============================================================================

    companion object {
        private const val TAG = "CursorPagination"
        private const val DEFAULT_LIMIT = 50
        private const val PAGE_SIZE = 50
        private const val POLL_INTERVAL_MS = 2000L
        private const val SOCKET_QUIET_THRESHOLD_MS = 5000L
        private const val OPTIMISTIC_STALE_THRESHOLD_MS = 30_000L
    }

    // ============================================================================
    // STATE - QUERY LIMIT
    // ============================================================================

    /**
     * The current query limit. Persisted to SavedStateHandle for config changes
     * and to ChatStateCache for navigation.
     */
    private val _queryLimit = MutableStateFlow(
        savedStateHandle.get<Int>("queryLimit")
            ?: chatStateCache.get(chatGuid)?.queryLimit
            ?: DEFAULT_LIMIT
    )
    val queryLimit: StateFlow<Int> = _queryLimit.asStateFlow()

    /**
     * Current viewing mode (Recent or Archive).
     */
    private val _viewMode = MutableStateFlow<ChatViewMode>(ChatViewMode.Recent)
    val viewMode: StateFlow<ChatViewMode> = _viewMode.asStateFlow()

    // ============================================================================
    // STATE - LOADING
    // ============================================================================

    private val isLoadingMore = AtomicBoolean(false)

    private val _hasMoreMessages = MutableStateFlow(true)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private val _loadError = MutableStateFlow<AppError?>(null)
    val loadError: StateFlow<AppError?> = _loadError.asStateFlow()

    private val _isLoadingFromServer = MutableStateFlow(false)
    val isLoadingFromServer: StateFlow<Boolean> = _isLoadingFromServer.asStateFlow()

    /** Tracks whether the initial Room query has completed */
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete.asStateFlow()

    // ============================================================================
    // STATE - OPTIMISTIC MESSAGES
    // ============================================================================

    /**
     * Optimistic messages not yet persisted to Room.
     * Displayed immediately for instant send feedback.
     */
    private data class OptimisticMessage(
        val model: MessageUiModel,
        val insertedAt: Long = System.currentTimeMillis()
    )

    private val _optimisticMessages = MutableStateFlow<List<OptimisticMessage>>(emptyList())

    // ============================================================================
    // STATE - SOCKET & ARCHIVE MODE
    // ============================================================================

    /** Emit socket-pushed message GUIDs for "new messages" indicator */
    private val _socketNewMessage = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val socketNewMessage: SharedFlow<String> = _socketNewMessage.asSharedFlow()

    /**
     * Trigger to force message list refresh when attachments are updated.
     * Incremented when downloads complete to cause the combine to re-emit,
     * which re-fetches attachments from the database with updated localPath.
     */
    private val _attachmentRefreshTrigger = MutableStateFlow(0)

    /** Count of new messages since entering Archive mode */
    private val _newMessagesSinceArchive = MutableStateFlow(0)
    val newMessagesSinceArchive: StateFlow<Int> = _newMessagesSinceArchive.asStateFlow()

    @Volatile
    private var lastSocketMessageTime: Long = System.currentTimeMillis()

    // ============================================================================
    // STATE - MESSAGES
    // ============================================================================

    private val messageCache = MessageCache()

    // Cached participant data for sender name resolution
    private var cachedParticipants: List<HandleEntity> = emptyList()
    private var handleIdToName: Map<Long, String> = emptyMap()
    private var addressToName: Map<String, String> = emptyMap()
    private var addressToAvatarPath: Map<String, String?> = emptyMap()

    /**
     * The main messages flow. Combines:
     * - Room database messages (based on viewMode and queryLimit)
     * - Optimistic messages (not yet in Room)
     * - Date separators
     */
    private val _chatListItems = MutableStateFlow<ImmutableList<ChatListItem>>(persistentListOf())
    val chatListItems: StateFlow<ImmutableList<ChatListItem>> = _chatListItems.asStateFlow()

    /** Messages without date separators (for internal use) */
    private val _messagesState = MutableStateFlow<StableList<MessageUiModel>>(StableList(emptyList()))
    val messagesState: StateFlow<StableList<MessageUiModel>> = _messagesState.asStateFlow()

    // ============================================================================
    // SCROLL POSITION
    // ============================================================================

    private val _cachedScrollPosition = MutableStateFlow<Pair<Int, Int>?>(null)
    val cachedScrollPosition: StateFlow<Pair<Int, Int>?> = _cachedScrollPosition.asStateFlow()

    private var lastScrollPosition: Int = 0
    private var lastScrollOffset: Int = 0
    private var lastScrollSaveTime: Long = 0L
    private val scrollSaveDebounceMs = 1000L

    // Attachment preloading
    private var lastPreloadIndex: Int = -1
    private var lastPreloadTime: Long = 0L
    private val preloadThrottleMs = 150L
    private var cachedAttachments: List<AttachmentUiModel> = emptyList()
    private var cachedAttachmentsMessageCount: Int = 0

    private val isMergedChat: Boolean = mergedChatGuids.size > 1

    // ============================================================================
    // INITIALIZATION
    // ============================================================================

    init {
        // Persist queryLimit to SavedStateHandle on changes
        scope.launch {
            _queryLimit.collect { limit ->
                savedStateHandle["queryLimit"] = limit
            }
        }

        // Restore cached scroll position
        val cachedState = chatStateCache.get(chatGuid)
        if (cachedState != null) {
            Timber.tag(TAG).d("Restoring from cache: scrollPos=${cachedState.scrollPosition}")
            lastScrollPosition = cachedState.scrollPosition
            lastScrollOffset = cachedState.scrollOffset
            _cachedScrollPosition.value = Pair(cachedState.scrollPosition, cachedState.scrollOffset)

            // Ensure queryLimit is large enough for scroll restoration
            if (cachedState.scrollPosition > _queryLimit.value - 10) {
                _queryLimit.value = cachedState.scrollPosition + 20
            }
        }

        attachmentDownloadQueue.setActiveChat(chatGuid)

        // Start message collection
        startMessageCollection()
        observeNewMessages()
        observeMessageUpdates()
        observeDownloadCompletions()
        startAdaptivePolling()
        observeForegroundResume()
        observeNewMessagesInArchiveMode()

        logPaginationState("init")
    }

    // ============================================================================
    // MESSAGE COLLECTION
    // ============================================================================

    private fun startMessageCollection() {
        scope.launch {
            // Combine viewMode, queryLimit, and refresh trigger to determine the Room query
            // The refresh trigger forces re-transformation when attachments are updated
            combine(_viewMode, _queryLimit, _attachmentRefreshTrigger) { mode, limit, _ ->
                mode to limit
            }.flatMapLatest { (mode, limit) ->
                when (mode) {
                    is ChatViewMode.Recent -> {
                        messageRepository.observeRecentMessages(mergedChatGuids, limit)
                    }
                    is ChatViewMode.Archive -> {
                        messageRepository.observeMessagesInWindow(
                            chatGuids = mergedChatGuids,
                            windowStart = mode.targetTimestamp - mode.windowMs,
                            windowEnd = mode.targetTimestamp + mode.windowMs
                        )
                    }
                }
            }
            .map { entities -> transformToUiModels(entities) }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .combine(_optimisticMessages) { roomMsgs, optimistic ->
                mergeOptimisticMessages(roomMsgs, optimistic)
            }
            .map { messages -> insertDateSeparators(messages) }
            .flowOn(Dispatchers.Default)
            .collect { items ->
                _chatListItems.value = items
                _messagesState.value = items
                    .filterIsInstance<ChatListItem.Message>()
                    .map { it.message }
                    .toStable()

                // Mark initial load complete on first emission
                if (!_initialLoadComplete.value) {
                    _initialLoadComplete.value = true
                }

                val canLoadMore = _messagesState.value.size >= _queryLimit.value
                onUiStateUpdate { copy(isLoading = false, canLoadMore = canLoadMore) }
            }
        }
    }

    /**
     * Merge optimistic messages with Room messages.
     * Deduplicates by GUID and cleans up stale optimistic entries.
     */
    private fun mergeOptimisticMessages(
        roomMsgs: List<MessageUiModel>,
        optimistic: List<OptimisticMessage>
    ): List<MessageUiModel> {
        if (optimistic.isEmpty()) return roomMsgs

        val roomGuids = roomMsgs.map { it.guid }.toSet()
        val cutoff = System.currentTimeMillis() - OPTIMISTIC_STALE_THRESHOLD_MS

        // Filter out messages that are now in Room or are stale
        val pending = optimistic.filter { opt ->
            opt.model.guid !in roomGuids && opt.insertedAt > cutoff
        }

        // Update the flow if we cleaned up any entries
        if (pending.size < optimistic.size) {
            _optimisticMessages.value = pending
        }

        // Prepend pending optimistic messages (they appear at bottom in reverseLayout)
        return pending.map { it.model } + roomMsgs
    }

    /**
     * Insert date separators between messages on different days.
     * Uses ISO date format for stable keys.
     */
    private fun insertDateSeparators(messages: List<MessageUiModel>): ImmutableList<ChatListItem> {
        if (messages.isEmpty()) return persistentListOf()

        val result = mutableListOf<ChatListItem>()
        var lastDateKey: String? = null
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        for (message in messages) {
            val dateKey = dateFormat.format(Date(message.dateCreated))

            if (dateKey != lastDateKey) {
                result.add(
                    ChatListItem.DateSeparator(
                        dateKey = dateKey,
                        displayText = formatDisplayDate(message.dateCreated)
                    )
                )
                lastDateKey = dateKey
            }
            result.add(ChatListItem.Message(message))
        }

        return result.toImmutableList()
    }

    /**
     * Format a timestamp for display ("Today", "Yesterday", or date).
     */
    private fun formatDisplayDate(timestamp: Long): String {
        val messageDate = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        return when {
            isSameDay(messageDate, today) -> "Today"
            isSameDay(messageDate, yesterday) -> "Yesterday"
            messageDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> {
                SimpleDateFormat("MMMM d", Locale.getDefault()).format(Date(timestamp))
            }
            else -> {
                SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    // ============================================================================
    // TRANSFORMATION
    // ============================================================================

    private suspend fun transformToUiModels(entities: List<MessageEntity>): List<MessageUiModel> {
        if (entities.isEmpty()) return emptyList()

        // Ensure participants are loaded
        if (cachedParticipants.isEmpty()) {
            loadParticipants()
        }

        // Dedupe entities (defensive)
        val dedupedEntities = entities.distinctBy { it.guid }

        // Fetch reactions
        val messageGuids = dedupedEntities.map { it.guid }
        val reactions = if (messageGuids.isNotEmpty()) {
            messageRepository.getReactionsForMessages(messageGuids)
        } else {
            emptyList()
        }

        // Group reactions by associated message GUID
        val reactionsByMessage = reactions.groupBy { reaction ->
            reaction.associatedMessageGuid?.let { guid ->
                if (guid.contains("/")) guid.substringAfter("/") else guid
            }
        }

        // Batch load attachments
        val allAttachments = attachmentRepository.getAttachmentsForMessages(messageGuids)
            .groupBy { it.messageGuid }

        // Build reply preview map
        val replyPreviewMap = buildReplyPreviewMap(dedupedEntities)

        // Fetch missing handles
        val mutableHandleIdToName = handleIdToName.toMutableMap()
        val missingHandleIds = dedupedEntities
            .filter { !it.isFromMe && it.handleId != null && it.handleId !in mutableHandleIdToName }
            .mapNotNull { it.handleId }
            .distinct()

        if (missingHandleIds.isNotEmpty()) {
            val handles = handleRepository.getHandlesByIds(missingHandleIds)
            handles.forEach { handle ->
                val normalizedAddress = normalizeAddress(handle.address)
                val matchingName = addressToName[normalizedAddress]
                if (matchingName != null) {
                    mutableHandleIdToName[handle.id] = matchingName
                } else {
                    mutableHandleIdToName[handle.id] = handle.displayName
                }
            }
        }

        // Use MessageCache for incremental updates
        val (messageModels, _) = messageCache.updateAndBuild(
            entities = dedupedEntities,
            reactions = reactionsByMessage,
            attachments = allAttachments
        ) { entity, entityReactions, entityAttachments ->
            val replyPreview = entity.threadOriginatorGuid?.let { replyPreviewMap[it] }
            entity.toUiModel(
                reactions = entityReactions,
                attachments = entityAttachments,
                handleIdToName = mutableHandleIdToName,
                addressToName = addressToName,
                addressToAvatarPath = addressToAvatarPath,
                replyPreview = replyPreview
            )
        }

        return messageModels
    }

    private suspend fun loadParticipants() {
        cachedParticipants = chatRepository.observeParticipantsForChats(mergedChatGuids).first()
        handleIdToName = cachedParticipants.associate { it.id to it.displayName }.toMutableMap()
        addressToName = cachedParticipants.associate { normalizeAddress(it.address) to it.displayName }
        addressToAvatarPath = cachedParticipants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }
    }

    private suspend fun buildReplyPreviewMap(messages: List<MessageEntity>): Map<String, ReplyPreviewData> {
        val replyGuids = messages.mapNotNull { it.threadOriginatorGuid }.distinct()
        if (replyGuids.isEmpty()) return emptyMap()

        val loadedMessagesMap = messages.associateBy { it.guid }
        val missingGuids = replyGuids.filter { it !in loadedMessagesMap }
        val fetchedOriginals = if (missingGuids.isNotEmpty()) {
            messageRepository.getMessagesByGuids(missingGuids).associateBy { it.guid }
        } else {
            emptyMap()
        }

        val allMessagesMap = loadedMessagesMap + fetchedOriginals

        return replyGuids.mapNotNull { originGuid ->
            val originalMessage = allMessagesMap[originGuid]
            if (originalMessage != null) {
                originGuid to ReplyPreviewData(
                    originalGuid = originGuid,
                    previewText = originalMessage.text?.take(50),
                    senderName = resolveSenderName(originalMessage.senderAddress, originalMessage.handleId),
                    isFromMe = originalMessage.isFromMe,
                    hasAttachment = originalMessage.hasAttachments,
                    isNotLoaded = false
                )
            } else {
                originGuid to ReplyPreviewData(
                    originalGuid = originGuid,
                    previewText = null,
                    senderName = null,
                    isFromMe = false,
                    hasAttachment = false,
                    isNotLoaded = true
                )
            }
        }.toMap()
    }

    private fun resolveSenderName(senderAddress: String?, handleId: Long?): String? {
        senderAddress?.let { addr ->
            val normalized = normalizeAddress(addr)
            addressToName[normalized]?.let { return it }
        }
        handleId?.let { id ->
            handleIdToName[id]?.let { return it }
        }
        return null
    }

    // ============================================================================
    // LOAD MORE
    // ============================================================================

    /**
     * Load more (older) messages.
     * Increases queryLimit and fetches from server (or imports SMS) if needed.
     */
    fun loadMore() {
        if (!isLoadingMore.compareAndSet(false, true)) {
            Timber.tag(TAG).d("loadMore skipped - already loading")
            return
        }

        logPaginationState("loadMore.start")

        scope.launch {
            try {
                val totalInDb = messageRepository.countMessagesForCursor(mergedChatGuids)
                val newLimit = _queryLimit.value + PAGE_SIZE
                _queryLimit.value = newLimit

                // If we have enough locally, just update the flag
                if (newLimit <= totalInDb) {
                    _hasMoreMessages.value = true
                    logPaginationState("loadMore.localSufficient")
                    return@launch
                }

                // For local SMS chats, import more from device SMS database
                if (messageRepository.isLocalSmsChat(chatGuid)) {
                    val result = smsRepository.importMessagesForChat(chatGuid, limit = PAGE_SIZE)
                    result.fold(
                        onSuccess = { count ->
                            _hasMoreMessages.value = count >= PAGE_SIZE
                            _loadError.value = null
                            Timber.tag(TAG).d("Imported $count SMS messages")
                        },
                        onFailure = { e ->
                            // Don't show error for permission denial - just mark no more messages
                            val isPermissionError = e.message?.contains("Permission Denial", ignoreCase = true) == true
                            if (!isPermissionError) {
                                Timber.tag(TAG).w("SMS import failed: ${e.message}")
                            }
                            _hasMoreMessages.value = false
                        }
                    )
                    return@launch
                }

                // Need to fetch from server for iMessage chats
                val oldestMessage = _messagesState.value.lastOrNull()
                if (oldestMessage == null) {
                    _hasMoreMessages.value = false
                    return@launch
                }

                _isLoadingFromServer.value = true

                // Skip if chat doesn't exist yet
                if (chatRepository.getChat(chatGuid) == null) {
                    _hasMoreMessages.value = false
                    return@launch
                }

                messageRepository.syncMessagesForChat(
                    chatGuid = chatGuid,
                    before = oldestMessage.dateCreated,
                    limit = PAGE_SIZE
                ).handle(
                    onSuccess = { fetchedMessages ->
                        _hasMoreMessages.value = fetchedMessages.size >= PAGE_SIZE
                        _loadError.value = null
                        logPaginationState("loadMore.serverSuccess fetched=${fetchedMessages.size}")
                    },
                    onError = { error ->
                        _loadError.value = error
                        Timber.tag(TAG).e("loadMore failed: ${error.technicalMessage}")
                    }
                )
            } finally {
                _isLoadingFromServer.value = false
                isLoadingMore.set(false)
            }
        }
    }

    /**
     * Retry loading after an error.
     */
    fun retryLoad() {
        _loadError.value = null
        loadMore()
    }

    /**
     * Clear the current load error.
     */
    fun clearLoadError() {
        _loadError.value = null
    }

    // ============================================================================
    // OPTIMISTIC MESSAGES
    // ============================================================================

    /**
     * Insert a message optimistically for instant display.
     */
    fun insertOptimisticMessage(queuedInfo: QueuedMessageInfo) {
        val optimisticModel = MessageUiModel(
            guid = queuedInfo.guid,
            text = queuedInfo.text,
            subject = null,
            dateCreated = queuedInfo.dateCreated,
            formattedTime = formatMessageTime(queuedInfo.dateCreated),
            isFromMe = true,
            isSent = false,
            isDelivered = false,
            isRead = false,
            hasError = false,
            isReaction = false,
            attachments = emptyList<AttachmentUiModel>().toStable(),
            senderName = null,
            senderAvatarPath = null,
            messageSource = queuedInfo.messageSource,
            expressiveSendStyleId = queuedInfo.effectId,
            threadOriginatorGuid = queuedInfo.replyToGuid
        )

        _optimisticMessages.update { current ->
            current + OptimisticMessage(optimisticModel)
        }

        Timber.tag(TAG).d("Inserted optimistic message: ${queuedInfo.guid}")
    }

    /**
     * Insert an incoming message optimistically for instant display.
     * Called immediately when socket event arrives to bypass Room Flow latency.
     * The message will be deduplicated when Room Flow catches up.
     */
    fun insertOptimisticIncomingMessage(message: MessageDto, chatGuid: String) {
        val guid = message.guid

        // Skip if already in Room messages
        if (_messagesState.value.any { it.guid == guid }) {
            Timber.tag(TAG).d("Skipping optimistic incoming: $guid already in Room")
            return
        }

        // Skip if already in optimistic list
        if (_optimisticMessages.value.any { it.model.guid == guid }) {
            Timber.tag(TAG).d("Skipping optimistic incoming: $guid already optimistic")
            return
        }

        // Skip reactions - they're handled differently
        if (message.associatedMessageGuid != null && message.associatedMessageType != null) {
            return
        }

        val dateCreated = message.dateCreated ?: System.currentTimeMillis()
        val senderAddress = message.handle?.address
        val senderName = resolveSenderName(senderAddress, message.handleId)
        val senderAvatarPath = senderAddress?.let { addr ->
            val normalized = normalizeAddress(addr)
            addressToAvatarPath[normalized]
        }

        // Create placeholder attachments with PENDING state for loading indicator
        val placeholderAttachments = message.attachments?.map { attachmentDto ->
            AttachmentUiModel(
                guid = attachmentDto.guid,
                mimeType = attachmentDto.mimeType,
                localPath = null, // Not downloaded yet
                webUrl = null, // Will be set when Room syncs
                width = attachmentDto.width,
                height = attachmentDto.height,
                transferName = attachmentDto.transferName,
                totalBytes = attachmentDto.totalBytes,
                isSticker = attachmentDto.isSticker,
                transferState = TransferState.PENDING.name,
                transferProgress = 0f,
                // Use message.isFromMe for UI purposes - if I sent this message, treat attachment as "mine"
                isOutgoing = message.isFromMe
            )
        }?.toStable() ?: emptyList<AttachmentUiModel>().toStable()

        // Determine message source
        val messageSource = when {
            message.handle?.service?.equals("SMS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            else -> MessageSource.IMESSAGE.name
        }

        val optimisticModel = MessageUiModel(
            guid = guid,
            text = message.text,
            subject = message.subject,
            dateCreated = dateCreated,
            formattedTime = formatMessageTime(dateCreated),
            isFromMe = false,
            isSent = true, // Incoming messages are already "sent"
            isDelivered = true,
            isRead = false,
            hasError = false,
            isReaction = false,
            attachments = placeholderAttachments,
            senderName = senderName,
            senderAvatarPath = senderAvatarPath,
            messageSource = messageSource,
            reactions = emptyList<ReactionUiModel>().toStable(),
            expressiveSendStyleId = message.expressiveSendStyleId,
            threadOriginatorGuid = message.threadOriginatorGuid
        )

        _optimisticMessages.update { current ->
            current + OptimisticMessage(optimisticModel)
        }

        Timber.tag(TAG).d("Inserted optimistic INCOMING message: $guid")
    }

    /**
     * Remove an optimistic message (e.g., if send failed and user cancelled).
     */
    fun removeOptimisticMessage(guid: String) {
        _optimisticMessages.update { current ->
            current.filter { it.model.guid != guid }
        }
    }

    /**
     * Apply a reaction optimistically to a message.
     */
    suspend fun applyReactionOptimistically(messageGuid: String, tapback: Tapback, isRemoving: Boolean) {
        // Update the message in the cache
        messageCache.updateModel(messageGuid) { currentMessage ->
            val newMyReactions = if (isRemoving) {
                currentMessage.myReactions - tapback
            } else {
                currentMessage.myReactions + tapback
            }

            val newReactions = if (isRemoving) {
                currentMessage.reactions.filter { !(it.tapback == tapback && it.isFromMe) }.toStable()
            } else {
                (currentMessage.reactions + ReactionUiModel(
                    tapback = tapback,
                    isFromMe = true,
                    senderName = null
                )).toStable()
            }

            currentMessage.copy(
                myReactions = newMyReactions,
                reactions = newReactions
            )
        }
    }

    // ============================================================================
    // ARCHIVE MODE / JUMP TO MESSAGE
    // ============================================================================

    /**
     * Jump to a specific message by GUID.
     * Switches to Archive mode centered on the target message.
     *
     * @return true if successful, false if message not found
     */
    suspend fun jumpToMessage(targetGuid: String): Boolean {
        val message = messageRepository.getMessageByGuid(targetGuid)
        if (message == null) {
            Timber.tag(TAG).w("jumpToMessage failed: message $targetGuid not found")
            return false
        }

        _viewMode.value = ChatViewMode.Archive(
            targetGuid = targetGuid,
            targetTimestamp = message.dateCreated,
            windowMs = 12 * 60 * 60 * 1000L // ±12 hours
        )

        _newMessagesSinceArchive.value = 0
        logPaginationState("jumpToMessage targetGuid=$targetGuid")
        return true
    }

    /**
     * Return to Recent mode from Archive mode.
     */
    fun returnToRecentMode() {
        _viewMode.value = ChatViewMode.Recent
        _newMessagesSinceArchive.value = 0
        logPaginationState("returnToRecentMode")
    }

    /**
     * Observe new messages while in Archive mode.
     */
    private fun observeNewMessagesInArchiveMode() {
        scope.launch {
            _socketNewMessage.collect { _ ->
                if (_viewMode.value is ChatViewMode.Archive) {
                    _newMessagesSinceArchive.update { it + 1 }
                }
            }
        }
    }

    // ============================================================================
    // SOCKET OBSERVERS
    // ============================================================================

    private fun observeNewMessages() {
        scope.launch {
            socketConnection.events
                .filterIsInstance<SocketEvent.NewMessage>()
                .filter { event -> isEventForThisChat(event.chatGuid) }
                .collect { event ->
                    Timber.tag(TAG).d("Socket: New message ${event.message.guid}")
                    lastSocketMessageTime = System.currentTimeMillis()

                    // Insert optimistically for instant display (bypasses Room Flow latency)
                    if (!event.message.isFromMe) {
                        insertOptimisticIncomingMessage(event.message, event.chatGuid)
                    }

                    _socketNewMessage.tryEmit(event.message.guid)
                }
        }
    }

    private fun observeMessageUpdates() {
        scope.launch {
            socketConnection.events
                .filterIsInstance<SocketEvent.MessageUpdated>()
                .filter { event -> isEventForThisChat(event.chatGuid) }
                .collect { event ->
                    Timber.tag(TAG).d("Socket: Message updated ${event.message.guid}")
                    // Room Flow will automatically emit the update
                }
        }
    }

    /**
     * Observe download completions and trigger message list refresh.
     * When an attachment download completes, the localPath in the attachments table is updated,
     * but the messages table doesn't change. We increment the refresh trigger to force
     * the message list to re-transform with the updated attachment data.
     */
    private fun observeDownloadCompletions() {
        scope.launch {
            attachmentDownloadQueue.downloadCompletions.collect { attachmentGuid ->
                Timber.tag(TAG).d("Download completed: $attachmentGuid, triggering refresh")
                // Invalidate cache entry for messages that have this attachment
                // so the next transform will re-fetch and show the updated localPath
                _attachmentRefreshTrigger.update { it + 1 }
            }
        }
    }

    private fun isEventForThisChat(eventChatGuid: String): Boolean {
        val normalizedEventGuid = normalizeGuid(eventChatGuid)
        return mergedChatGuids.any { normalizeGuid(it) == normalizedEventGuid } ||
            normalizeGuid(chatGuid) == normalizedEventGuid ||
            extractAddress(eventChatGuid)?.let { eventAddress ->
                mergedChatGuids.any { extractAddress(it) == eventAddress } ||
                    extractAddress(chatGuid) == eventAddress
            } == true
    }

    private fun normalizeGuid(guid: String): String {
        val parts = guid.split(";-;")
        if (parts.size != 2) return guid.lowercase()
        val prefix = parts[0].lowercase()
        val address = if (parts[1].contains("@")) {
            parts[1].lowercase()
        } else {
            parts[1].replace(Regex("[^0-9+]"), "")
        }
        return "$prefix;-;$address"
    }

    private fun extractAddress(guid: String): String? {
        val parts = guid.split(";-;")
        if (parts.size != 2) return null
        return if (parts[1].contains("@")) {
            parts[1].lowercase()
        } else {
            parts[1].replace(Regex("[^0-9+]"), "")
        }
    }

    // ============================================================================
    // SYNC MECHANISMS
    // ============================================================================

    private fun startAdaptivePolling() {
        if (messageRepository.isLocalSmsChat(chatGuid)) return

        scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)

                val timeSinceLastSocketMessage = System.currentTimeMillis() - lastSocketMessageTime
                if (timeSinceLastSocketMessage < SOCKET_QUIET_THRESHOLD_MS) continue
                if (!socketConnection.isConnected()) continue
                if (chatRepository.getChat(chatGuid) == null) continue

                val newestMessage = _messagesState.value.firstOrNull()
                val afterTimestamp = newestMessage?.dateCreated

                try {
                    val result = messageRepository.syncMessagesForChat(
                        chatGuid = chatGuid,
                        limit = 10,
                        after = afterTimestamp
                    )
                    result.onSuccess { messages ->
                        if (messages.isNotEmpty()) {
                            Timber.tag(TAG).d("Adaptive polling found ${messages.size} missed messages")
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).d("Adaptive polling error: ${e.message}")
                }
            }
        }
    }

    private fun observeForegroundResume() {
        if (messageRepository.isLocalSmsChat(chatGuid)) return

        scope.launch {
            appLifecycleTracker.foregroundState.collect { isInForeground ->
                if (isInForeground) {
                    lastSocketMessageTime = System.currentTimeMillis()

                    if (chatRepository.getChat(chatGuid) == null) return@collect

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
                                Timber.tag(TAG).d("Foreground sync found ${messages.size} messages")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).d("Foreground sync error: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Sync messages - dispatches to SMS or server sync based on chat type.
     */
    fun syncMessages() {
        onUiStateUpdate { copy(isSyncingMessages = true) }
        if (messageRepository.isLocalSmsChat(chatGuid)) {
            syncSmsMessages()
        } else {
            syncMessagesFromServer()
        }
    }

    fun syncMessagesFromServer() {
        scope.launch {
            val chatExists = chatRepository.getChat(chatGuid) != null
            if (!chatExists) {
                chatRepository.fetchChat(chatGuid).onFailure { e ->
                    Timber.tag(TAG).e(e, "Failed to fetch chat")
                    if (_messagesState.value.isEmpty()) {
                        onUiStateUpdate { copy(error = "Failed to load chat: ${e.message}") }
                    }
                    onUiStateUpdate { copy(isSyncingMessages = false) }
                    return@launch
                }
            }

            messageRepository.syncMessagesForChat(chatGuid = chatGuid, limit = 50)
                .onFailure { e ->
                    if (_messagesState.value.isEmpty()) {
                        onUiStateUpdate { copy(error = e.message) }
                    }
                }
            onUiStateUpdate { copy(isSyncingMessages = false) }
        }
    }

    private fun syncSmsMessages() {
        scope.launch {
            smsRepository.importMessagesForChat(chatGuid, limit = 100).fold(
                onSuccess = { count ->
                    Timber.tag(TAG).d("Imported $count SMS messages")
                },
                onFailure = { e ->
                    val isPermissionError = e.message?.contains("Permission Denial", ignoreCase = true) == true
                    if (_messagesState.value.isEmpty() && !isPermissionError) {
                        onUiStateUpdate { copy(error = "Failed to load SMS: ${e.message}") }
                    }
                }
            )
            onUiStateUpdate { copy(isSyncingMessages = false) }
        }
    }

    // ============================================================================
    // SCROLL POSITION
    // ============================================================================

    fun updateScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int, visibleItemCount: Int = 10) {
        lastScrollPosition = firstVisibleItemIndex
        lastScrollOffset = firstVisibleItemScrollOffset

        val now = System.currentTimeMillis()
        val indexChanged = firstVisibleItemIndex != lastPreloadIndex
        val timeElapsed = now - lastPreloadTime >= preloadThrottleMs

        if (indexChanged && timeElapsed) {
            lastPreloadIndex = firstVisibleItemIndex
            lastPreloadTime = now

            val messages = _messagesState.value
            if (messages.isNotEmpty()) {
                if (messages.size != cachedAttachmentsMessageCount) {
                    cachedAttachments = messages.flatMap { it.attachments }
                    cachedAttachmentsMessageCount = messages.size
                }

                if (cachedAttachments.isNotEmpty()) {
                    val visibleEnd = (firstVisibleItemIndex + visibleItemCount).coerceAtMost(messages.size - 1)
                    attachmentPreloader.preloadNearby(
                        attachments = cachedAttachments,
                        visibleRange = firstVisibleItemIndex..visibleEnd,
                        preloadCount = 15
                    )

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

        val saveTimeElapsed = now - lastScrollSaveTime >= scrollSaveDebounceMs
        if (saveTimeElapsed) {
            lastScrollSaveTime = now
            scope.launch {
                settingsDataStore.setLastScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
            }
        }
    }

    fun getScrollPosition(): Pair<Int, Int> = Pair(lastScrollPosition, lastScrollOffset)

    fun saveStateToCacheSync(): ChatStateCache.CachedChatState {
        return ChatStateCache.CachedChatState(
            chatGuid = chatGuid,
            mergedGuids = mergedChatGuids,
            scrollPosition = lastScrollPosition,
            scrollOffset = lastScrollOffset,
            queryLimit = _queryLimit.value
        )
    }

    fun markAsRead() {
        scope.launch {
            for (guid in mergedChatGuids) {
                try {
                    val chat = chatRepository.getChat(guid)
                    if (chat?.isLocalSms == true) {
                        smsRepository.markThreadAsRead(guid)
                    } else {
                        chatRepository.markChatAsRead(guid)
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to mark $guid as read")
                }
            }
        }
    }

    /**
     * Check if a message has been seen (is in the current messages list).
     * Used by ChatScreen to track which messages have been animated.
     */
    fun hasMessageBeenSeen(guid: String): Boolean {
        return _messagesState.value.any { it.guid == guid }
    }

    /**
     * Trigger a refresh when attachment state changes (download complete, etc.).
     * This forces the message list to re-transform with updated attachment data,
     * since the Room messages flow doesn't emit when attachments table changes.
     */
    fun triggerAttachmentRefresh() {
        Timber.tag(TAG).d("triggerAttachmentRefresh called")
        _attachmentRefreshTrigger.update { it + 1 }
    }

    fun clearActiveChat() {
        attachmentDownloadQueue.setActiveChat(null)
        attachmentPreloader.clearTracking()
    }

    suspend fun onChatLeave() {
        clearActiveChat()
        val mergedGuidsStr = if (isMergedChat) mergedChatGuids.joinToString(",") else null
        settingsDataStore.setLastOpenChat(chatGuid, mergedGuidsStr)
        settingsDataStore.setLastScrollPosition(lastScrollPosition, lastScrollOffset)
        chatStateCache.put(saveStateToCacheSync())
    }

    fun dispose() {
        messageCache.clear()
    }

    // ============================================================================
    // DEBUG LOGGING
    // ============================================================================

    private fun logPaginationState(event: String) {
        if (timber.log.Timber.forest().isEmpty()) return
        Timber.tag(TAG).d(
            "$event | limit=${_queryLimit.value} | hasMore=${_hasMoreMessages.value} | " +
                "msgCount=${_messagesState.value.size} | mode=${_viewMode.value::class.simpleName}"
        )
    }
}
