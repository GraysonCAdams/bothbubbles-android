package com.bothbubbles.ui.chat.delegates

import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.AppLifecycleTracker
import com.bothbubbles.services.media.AttachmentDownloadQueue
import com.bothbubbles.services.media.AttachmentPreloader
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.ui.chat.ChatStateCache
import com.bothbubbles.ui.chat.ChatUiState
import com.bothbubbles.ui.chat.MessageCache
import com.bothbubbles.ui.chat.paging.MessagePagingController
import com.bothbubbles.ui.chat.paging.PagingConfig
import com.bothbubbles.ui.chat.paging.RoomMessageDataSource
import com.bothbubbles.ui.chat.paging.SparseMessageList
import com.bothbubbles.ui.chat.paging.SyncTrigger
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.util.StableList
import com.bothbubbles.ui.util.toStable
import com.bothbubbles.util.PerformanceProfiler
import com.bothbubbles.util.error.handle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate that handles message list state and paging for ChatViewModel.
 *
 * Responsibilities:
 * - Message paging via MessagePagingController
 * - Message list state (_messagesState)
 * - Socket event handling for new/updated messages
 * - Adaptive polling for missed messages
 * - Foreground resume sync
 * - Server sync coordination
 *
 * This delegate owns the message list state and exposes it via StateFlows.
 * The ViewModel acts as a facade, delegating message list operations here.
 *
 * Usage in ChatViewModel:
 * ```kotlin
 * class ChatViewModel @Inject constructor(
 *     private val messageListDelegate: ChatMessageListDelegate,
 *     ...
 * ) : ViewModel() {
 *     init {
 *         messageListDelegate.initialize(
 *             chatGuid = chatGuid,
 *             mergedChatGuids = mergedChatGuids,
 *             scope = viewModelScope,
 *             uiState = _uiState
 *         )
 *     }
 *
 *     val messagesState = messageListDelegate.messagesState
 * }
 * ```
 */
class ChatMessageListDelegate @Inject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val attachmentRepository: AttachmentRepository,
    private val handleRepository: HandleRepository,
    private val smsRepository: SmsRepository,
    private val socketService: SocketService,
    private val appLifecycleTracker: AppLifecycleTracker,
    private val attachmentPreloader: AttachmentPreloader,
    private val attachmentDownloadQueue: AttachmentDownloadQueue,
    private val settingsDataStore: SettingsDataStore,
    private val chatStateCache: ChatStateCache
) {
    companion object {
        private const val TAG = "ChatMessageListDelegate"

        // Adaptive polling: catches missed messages when push is unreliable
        private const val POLL_INTERVAL_MS = 2000L // Poll every 2 seconds when socket is quiet
        private const val SOCKET_QUIET_THRESHOLD_MS = 5000L // Start polling after 5s of socket silence
    }

    // State - set during initialize()
    private lateinit var chatGuid: String
    private lateinit var mergedChatGuids: List<String>
    private lateinit var scope: CoroutineScope
    private lateinit var uiStateFlow: MutableStateFlow<ChatUiState>
    private lateinit var onUiStateUpdate: (ChatUiState.() -> ChatUiState) -> Unit

    // PERF: Message cache for incremental updates - preserves object identity for unchanged messages
    private val messageCache = MessageCache()

    // Track when we're fetching older messages from the BlueBubbles server
    private val _isLoadingFromServer = MutableStateFlow(false)
    val isLoadingFromServer: StateFlow<Boolean> = _isLoadingFromServer.asStateFlow()

    // Separate flow for messages to prevent full UI recomposition on message updates
    private val _messagesState = MutableStateFlow<StableList<MessageUiModel>>(StableList(emptyList()))
    val messagesState: StateFlow<StableList<MessageUiModel>> = _messagesState.asStateFlow()

    // Emit socket-pushed message GUIDs for "new messages" indicator
    private val _socketNewMessage = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val socketNewMessage: SharedFlow<String> = _socketNewMessage.asSharedFlow()

    // Trigger to refresh messages (incremented when attachments are downloaded)
    private val _attachmentRefreshTrigger = MutableStateFlow(0)

    // Adaptive polling: tracks when we last received a socket message for this chat
    @Volatile
    private var lastSocketMessageTime: Long = System.currentTimeMillis()

    // ============================================================================
    // SCROLL POSITION CACHING
    // ============================================================================

    // Initial scroll position from LRU cache (for instant restore when re-opening chat)
    private val _cachedScrollPosition = MutableStateFlow<Pair<Int, Int>?>(null)
    val cachedScrollPosition: StateFlow<Pair<Int, Int>?> = _cachedScrollPosition.asStateFlow()

    // Scroll position tracking for state restoration
    private var lastScrollPosition: Int = 0
    private var lastScrollOffset: Int = 0
    private var lastScrollSaveTime: Long = 0L
    private val scrollSaveDebounceMs = 1000L // Debounce scroll saves

    // ============================================================================
    // ATTACHMENT PRELOADING
    // ============================================================================

    // Throttle preloader to avoid calling on every scroll frame
    private var lastPreloadIndex: Int = -1
    private var lastPreloadTime: Long = 0L
    private val preloadThrottleMs = 150L // Only preload every 150ms at most

    // Cached attachment list for preloading - avoids flatMap on every scroll frame
    private var cachedAttachments: List<AttachmentUiModel> = emptyList()
    private var cachedAttachmentsMessageCount: Int = 0

    // Whether this is a merged conversation
    private var isMergedChat: Boolean = false

    // SyncTrigger implementation for fetching from server when gaps are detected
    private val syncTriggerImpl: SyncTrigger by lazy {
        object : SyncTrigger {
            override suspend fun requestSyncForRange(chatGuids: List<String>, startPosition: Int, count: Int) {
                if (_isLoadingFromServer.value) return // Already loading
                // Skip if chat doesn't exist yet (foreign key constraint would fail)
                if (chatRepository.getChat(chatGuid) == null) return

                Log.d(TAG, "SyncTrigger: requesting sync for range $startPosition-${startPosition + count}")
                _isLoadingFromServer.value = true

                try {
                    messageRepository.syncMessagesForChat(
                        chatGuid = chatGuid,
                        limit = count.coerceAtLeast(100),
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
                if (chatRepository.getChat(chatGuid) == null) return
                try {
                    messageRepository.syncMessagesForChat(chatGuid = chatGuid, limit = 10)
                } catch (e: Exception) {
                    Log.e(TAG, "SyncTrigger: message sync failed", e)
                }
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
            scope = scope
        )
    }

    // ============================================================================
    // EXPOSED STATE FROM PAGING CONTROLLER
    // ============================================================================

    /** Sparse message list from paging controller - supports holes for unloaded positions */
    val sparseMessages: StateFlow<SparseMessageList> get() = pagingController.messages

    /** Total message count for scroll indicator */
    val totalMessageCount: StateFlow<Int> get() = pagingController.totalCount

    /** Track initial load completion for animation control */
    val initialLoadComplete: StateFlow<Boolean> get() = pagingController.initialLoadComplete

    /**
     * Check if a message has been loaded this session.
     * Used to skip entrance animations for previously-viewed messages when scrolling back.
     */
    fun hasMessageBeenSeen(guid: String): Boolean = pagingController.hasBeenSeen(guid)

    // ============================================================================
    // INITIALIZATION
    // ============================================================================

    /**
     * Initialize the delegate with chat context.
     * Must be called before any operations.
     */
    fun initialize(
        chatGuid: String,
        mergedChatGuids: List<String>,
        scope: CoroutineScope,
        uiState: MutableStateFlow<ChatUiState>,
        onUiStateUpdate: (ChatUiState.() -> ChatUiState) -> Unit
    ) {
        this.chatGuid = chatGuid
        this.mergedChatGuids = mergedChatGuids
        this.scope = scope
        this.uiStateFlow = uiState
        this.onUiStateUpdate = onUiStateUpdate
        this.isMergedChat = mergedChatGuids.size > 1

        // Restore cached scroll position from LRU cache
        val cachedState = chatStateCache.get(chatGuid)
        if (cachedState != null) {
            Log.d(TAG, "Restoring chat from cache: scrollPos=${cachedState.scrollPosition}, messages=${cachedState.messages.size}")
            lastScrollPosition = cachedState.scrollPosition
            lastScrollOffset = cachedState.scrollOffset
            _cachedScrollPosition.value = Pair(cachedState.scrollPosition, cachedState.scrollOffset)
        }

        // Set this chat as active for download queue prioritization
        attachmentDownloadQueue.setActiveChat(chatGuid)

        // Start message loading and socket observers
        loadMessages()
        observeNewMessages()
        observeMessageUpdates()
        startAdaptivePolling()
        observeForegroundResume()
    }

    // ============================================================================
    // PUBLIC API
    // ============================================================================

    /**
     * Called by ChatScreen when scroll position changes.
     * Notifies the paging controller to load data around the visible range.
     */
    fun onScrollPositionChanged(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        pagingController.onDataNeededAroundIndex(firstVisibleIndex, lastVisibleIndex)
    }

    /**
     * Jump to a specific message by GUID.
     * Returns the position if found, null otherwise.
     */
    suspend fun jumpToMessage(guid: String): Int? {
        return pagingController.jumpToMessage(guid)
    }

    /**
     * Optimistically insert a new message at position 0.
     * Used for instant display of sent messages without waiting for Room Flow.
     */
    fun insertMessageOptimistically(model: MessageUiModel) {
        val start = System.currentTimeMillis()
        Log.i(TAG, "[SEND_TRACE] ── ChatMessageListDelegate.insertMessageOptimistically START ──")
        Log.i(TAG, "[SEND_TRACE] guid=${model.guid}")
        pagingController.insertMessageOptimistically(model)
        Log.i(TAG, "[SEND_TRACE] ── ChatMessageListDelegate.insertMessageOptimistically END: ${System.currentTimeMillis() - start}ms ──")
    }

    /**
     * Update a single message by GUID.
     * Used for real-time updates (reactions, delivery status, etc.)
     */
    fun updateMessage(guid: String) {
        pagingController.updateMessage(guid)
    }

    /**
     * Update a message locally without database round-trip.
     * Used for optimistic UI updates (e.g., reactions).
     */
    suspend fun updateMessageLocally(guid: String, transform: (MessageUiModel) -> MessageUiModel): Boolean {
        return pagingController.updateMessageLocally(guid, transform)
    }

    /**
     * Handle a new message being inserted.
     * Called when a message arrives via socket or is sent locally.
     */
    fun onNewMessageInserted(guid: String) {
        pagingController.onNewMessageInserted(guid)
    }

    /**
     * Force refresh all loaded data.
     */
    fun refresh() {
        pagingController.refresh()
    }

    /**
     * Trigger refresh when attachments are downloaded.
     */
    fun triggerAttachmentRefresh() {
        _attachmentRefreshTrigger.value++
    }

    /**
     * Load more (older) messages from the server.
     */
    fun loadMoreMessages() {
        Log.d("ChatScroll", "[Delegate] loadMoreMessages called: isLoadingMore=${uiStateFlow.value.isLoadingMore}, canLoadMore=${uiStateFlow.value.canLoadMore}")
        if (uiStateFlow.value.isLoadingMore || !uiStateFlow.value.canLoadMore) {
            Log.d("ChatScroll", "[Delegate] loadMoreMessages SKIPPED - already loading or no more to load")
            return
        }

        val oldestMessage = _messagesState.value.lastOrNull()
        if (oldestMessage == null) {
            Log.d("ChatScroll", "[Delegate] loadMoreMessages SKIPPED - no messages in list")
            return
        }

        Log.d("ChatScroll", "[Delegate] >>> STARTING loadMoreMessages: oldestDate=${oldestMessage.dateCreated}, currentMsgCount=${_messagesState.value.size}")

        scope.launch {
            onUiStateUpdate { copy(isLoadingMore = true) }

            val startTime = System.currentTimeMillis()
            messageRepository.syncMessagesForChat(
                chatGuid = chatGuid,
                before = oldestMessage.dateCreated,
                limit = 50
            ).handle(
                onSuccess = { messages ->
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d("ChatScroll", "[Delegate] loadMoreMessages SUCCESS: synced ${messages.size} messages in ${elapsed}ms")

                    pagingController.refresh()

                    onUiStateUpdate {
                        copy(isLoadingMore = false, canLoadMore = messages.size == 50)
                    }
                    Log.d("ChatScroll", "[Delegate] loadMoreMessages COMPLETE: canLoadMore=${messages.size == 50}")
                },
                onError = { appError ->
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.e("ChatScroll", "[Delegate] loadMoreMessages FAILED after ${elapsed}ms: ${appError.technicalMessage}")
                    onUiStateUpdate { copy(isLoadingMore = false, appError = appError) }
                }
            )
        }
    }

    /**
     * Sync messages - dispatches to SMS or server sync based on chat type.
     * Sets isSyncingMessages = true before dispatching.
     */
    fun syncMessages() {
        onUiStateUpdate { copy(isSyncingMessages = true) }
        if (messageRepository.isLocalSmsChat(chatGuid)) {
            syncSmsMessages()
        } else {
            syncMessagesFromServer()
        }
    }

    /**
     * Sync messages from server. Called during initial load for iMessage/server-based chats.
     */
    fun syncMessagesFromServer() {
        scope.launch {
            // Ensure chat exists in local DB before syncing messages
            val chatExists = chatRepository.getChat(chatGuid) != null
            if (!chatExists) {
                Log.d(TAG, "Chat $chatGuid not in local DB, fetching from server first")
                chatRepository.fetchChat(chatGuid).onFailure { e ->
                    Log.e(TAG, "Failed to fetch chat $chatGuid from server", e)
                    if (_messagesState.value.isEmpty()) {
                        onUiStateUpdate { copy(error = "Failed to load chat: ${e.message}") }
                    }
                    onUiStateUpdate { copy(isSyncingMessages = false) }
                    return@launch
                }
            }

            messageRepository.syncMessagesForChat(
                chatGuid = chatGuid,
                limit = 50
            ).onFailure { e ->
                if (_messagesState.value.isEmpty()) {
                    onUiStateUpdate { copy(error = e.message) }
                }
            }
            onUiStateUpdate { copy(isSyncingMessages = false) }
        }
    }

    /**
     * Sync SMS messages from Android system. Called during initial load for local SMS chats.
     */
    private fun syncSmsMessages() {
        scope.launch {
            smsRepository.importMessagesForChat(chatGuid, limit = 100).fold(
                onSuccess = { count ->
                    // Messages are now in Room and will be picked up by the paging controller
                    Log.d(TAG, "Imported $count SMS messages for $chatGuid")
                },
                onFailure = { e ->
                    if (_messagesState.value.isEmpty()) {
                        onUiStateUpdate { copy(error = "Failed to load SMS messages: ${e.message}") }
                    }
                }
            )
            onUiStateUpdate { copy(isSyncingMessages = false) }
        }
    }

    /**
     * Clean up resources when ViewModel is cleared.
     */
    fun dispose() {
        pagingController.dispose()
        messageCache.clear()
    }

    // ============================================================================
    // PRIVATE - MESSAGE LOADING
    // ============================================================================

    private fun loadMessages() {
        // Initialize Signal-style BitSet pagination controller
        pagingController.initialize()

        // Bridge sparse messages to _messagesState for backwards compatibility
        scope.launch {
            pagingController.messages
                .map { sparseList -> sparseList.toList() }
                .conflate()
                .collect { messageModels ->
                    val collectStart = System.currentTimeMillis()
                    val newestGuid = messageModels.firstOrNull()?.guid?.takeLast(12) ?: "none"
                    Log.i(TAG, "[SEND_TRACE] ── ChatMessageListDelegate COLLECTING ${messageModels.size} messages ──")
                    Log.i(TAG, "[SEND_TRACE] Newest message guid=...$newestGuid")

                    val collectId = PerformanceProfiler.start("Chat.messagesCollected", "${messageModels.size} messages")

                    // Update separate messages flow FIRST (triggers list recomposition only)
                    val stableMessages = messageModels.toStable()
                    Log.i(TAG, "[SEND_TRACE] Setting _messagesState with ${stableMessages.size} messages")
                    _messagesState.value = stableMessages

                    // Update main UI state
                    val canLoad = messageModels.size < pagingController.totalCount.value
                    onUiStateUpdate { copy(isLoading = false, canLoadMore = canLoad) }
                    PerformanceProfiler.end(collectId)
                    Log.i(TAG, "[SEND_TRACE] ── ChatMessageListDelegate COLLECT DONE: ${System.currentTimeMillis() - collectStart}ms ──")
                }
        }

        // Handle attachment refresh by refreshing the paging controller
        scope.launch {
            _attachmentRefreshTrigger.collect { trigger ->
                if (trigger > 0) {
                    Log.d(TAG, "Attachment refresh triggered, refreshing paging controller")
                    pagingController.refresh()
                }
            }
        }
    }

    // ============================================================================
    // PRIVATE - SOCKET EVENT HANDLING
    // ============================================================================

    /**
     * Observe new messages from socket for this chat.
     */
    private fun observeNewMessages() {
        scope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.NewMessage>()
                .filter { event -> isEventForThisChat(event.chatGuid) }
                .collect { event ->
                    Log.d(TAG, "Socket: New message received for ${event.chatGuid}, guid=${event.message.guid}")

                    // Update socket activity timestamp (for adaptive polling)
                    lastSocketMessageTime = System.currentTimeMillis()

                    // Notify paging controller about the new message
                    pagingController.onNewMessageInserted(event.message.guid)

                    // Emit for "new messages" indicator in ChatScreen
                    _socketNewMessage.tryEmit(event.message.guid)
                }
        }
    }

    /**
     * Observe message updates from socket.
     */
    private fun observeMessageUpdates() {
        scope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.MessageUpdated>()
                .filter { event -> isEventForThisChat(event.chatGuid) }
                .collect { event ->
                    Log.d(TAG, "Socket: Message updated for ${event.chatGuid}, guid=${event.message.guid}")
                    pagingController.updateMessage(event.message.guid)
                }
        }
    }

    /**
     * Check if a socket event is for this chat (handles merged conversations).
     */
    private fun isEventForThisChat(eventChatGuid: String): Boolean {
        val normalizedEventGuid = normalizeGuid(eventChatGuid)
        return mergedChatGuids.any { normalizeGuid(it) == normalizedEventGuid } ||
            normalizeGuid(chatGuid) == normalizedEventGuid ||
            extractAddress(eventChatGuid)?.let { eventAddress ->
                mergedChatGuids.any { extractAddress(it) == eventAddress } ||
                    extractAddress(chatGuid) == eventAddress
            } == true
    }

    /**
     * Normalize a chat GUID for comparison by stripping formatting from phone numbers.
     */
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

    /**
     * Extract just the address/phone portion from a chat GUID.
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

    // ============================================================================
    // PRIVATE - SYNC MECHANISMS
    // ============================================================================

    /**
     * Adaptive polling to catch messages missed by push notifications.
     */
    private fun startAdaptivePolling() {
        // Skip polling for local SMS chats (no server to poll)
        if (messageRepository.isLocalSmsChat(chatGuid)) return

        scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)

                // Only poll if socket has been quiet for a while
                val timeSinceLastSocketMessage = System.currentTimeMillis() - lastSocketMessageTime
                if (timeSinceLastSocketMessage < SOCKET_QUIET_THRESHOLD_MS) {
                    continue
                }

                // Only poll if socket is connected
                if (!socketService.isConnected()) {
                    continue
                }

                // Get the timestamp of the newest message we have locally
                val newestMessage = _messagesState.value.firstOrNull()
                val afterTimestamp = newestMessage?.dateCreated

                // Skip if chat doesn't exist yet
                if (chatRepository.getChat(chatGuid) == null) {
                    continue
                }

                try {
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
                    Log.d(TAG, "Adaptive polling error: ${e.message}")
                }
            }
        }
    }

    /**
     * Sync messages when app returns to foreground.
     */
    private fun observeForegroundResume() {
        // Skip for local SMS chats
        if (messageRepository.isLocalSmsChat(chatGuid)) return

        scope.launch {
            appLifecycleTracker.foregroundState
                .collect { isInForeground ->
                    if (isInForeground) {
                        Log.d(TAG, "App resumed - syncing for missed messages")
                        lastSocketMessageTime = System.currentTimeMillis()

                        if (chatRepository.getChat(chatGuid) == null) {
                            return@collect
                        }

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

    // ============================================================================
    // SCROLL POSITION & PRELOADING
    // ============================================================================

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
            scope.launch {
                settingsDataStore.setLastScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
            }
        }
    }

    /**
     * Get the current scroll position for state saving.
     */
    fun getScrollPosition(): Pair<Int, Int> = Pair(lastScrollPosition, lastScrollOffset)

    /**
     * Save current state to the LRU cache.
     * Called when leaving the chat.
     */
    fun saveStateToCacheSync(): ChatStateCache.CachedChatState {
        return ChatStateCache.CachedChatState(
            chatGuid = chatGuid,
            mergedGuids = mergedChatGuids,
            messages = sparseMessages.value,
            totalCount = totalMessageCount.value,
            scrollPosition = lastScrollPosition,
            scrollOffset = lastScrollOffset
        )
    }

    /**
     * Mark all chats in merged conversation as read.
     * Handles both local SMS chats and server-based (iMessage/server SMS) chats.
     */
    fun markAsRead() {
        scope.launch {
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
                    Log.w(TAG, "Failed to mark $guid as read", e)
                    // Continue with other chats
                }
            }
        }
    }

    /**
     * Clear the active chat from download queue.
     * Called when leaving the chat.
     */
    fun clearActiveChat() {
        attachmentDownloadQueue.setActiveChat(null)
        attachmentPreloader.clearTracking()
    }

    /**
     * Handle cleanup when leaving the chat.
     * Saves scroll position and state to cache for instant restoration.
     */
    suspend fun onChatLeave() {
        // Clear attachment queue prioritization
        clearActiveChat()

        // Save scroll position to DataStore
        val mergedGuidsStr = if (isMergedChat) mergedChatGuids.joinToString(",") else null
        Log.d(TAG, "onChatLeave: saving chatGuid=$chatGuid, scroll=($lastScrollPosition, $lastScrollOffset)")
        settingsDataStore.setLastOpenChat(chatGuid, mergedGuidsStr)
        settingsDataStore.setLastScrollPosition(lastScrollPosition, lastScrollOffset)

        // Save state to LRU cache for instant restore when re-opening this chat
        chatStateCache.put(saveStateToCacheSync())
    }
}
