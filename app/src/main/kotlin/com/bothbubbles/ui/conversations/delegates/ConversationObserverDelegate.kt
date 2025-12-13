package com.bothbubbles.ui.conversations.delegates

import android.util.Log
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.UnifiedChatGroupRepository
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.services.sync.SyncState
import com.bothbubbles.ui.conversations.normalizeGuid
import com.bothbubbles.ui.util.toStable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for observing database and socket changes.
 * Handles real-time updates from sync, socket events, and typing indicators.
 *
 * This delegate extracts the observer/reactive logic from ConversationsViewModel,
 * including socket event handling and sync state observation.
 */
class ConversationObserverDelegate @Inject constructor(
    private val unifiedChatGroupRepository: UnifiedChatGroupRepository,
    private val chatRepository: ChatRepository,
    private val socketService: SocketService,
    private val syncService: SyncService
) {
    companion object {
        private const val TAG = "ConversationObserverDelegate"
    }

    private lateinit var scope: CoroutineScope

    // Typing indicator state
    private val _typingChats = MutableStateFlow<Set<String>>(emptySet())
    val typingChats: StateFlow<Set<String>> = _typingChats.asStateFlow()

    // Connection tracking for banner logic
    private var wasEverConnected = false
    private val _startupGracePeriodPassed = MutableStateFlow(false)
    val startupGracePeriodPassed: StateFlow<Boolean> = _startupGracePeriodPassed.asStateFlow()

    // Connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Sync state
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow<Float?>(null)
    val syncProgress: StateFlow<Float?> = _syncProgress.asStateFlow()

    private val _syncStage = MutableStateFlow<String?>(null)
    val syncStage: StateFlow<String?> = _syncStage.asStateFlow()

    private val _syncTotalChats = MutableStateFlow(0)
    val syncTotalChats: StateFlow<Int> = _syncTotalChats.asStateFlow()

    private val _syncProcessedChats = MutableStateFlow(0)
    val syncProcessedChats: StateFlow<Int> = _syncProcessedChats.asStateFlow()

    private val _syncedMessages = MutableStateFlow(0)
    val syncedMessages: StateFlow<Int> = _syncedMessages.asStateFlow()

    private val _syncCurrentChatName = MutableStateFlow<String?>(null)
    val syncCurrentChatName: StateFlow<String?> = _syncCurrentChatName.asStateFlow()

    private val _isInitialSync = MutableStateFlow(false)
    val isInitialSync: StateFlow<Boolean> = _isInitialSync.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _isSyncCorrupted = MutableStateFlow(false)
    val isSyncCorrupted: StateFlow<Boolean> = _isSyncCorrupted.asStateFlow()

    // Callbacks for triggering refresh
    private var onDataChanged: (suspend () -> Unit)? = null
    private var onNewMessage: (suspend () -> Unit)? = null
    private var onMessageUpdated: (suspend () -> Unit)? = null
    private var onChatRead: ((String) -> Unit)? = null

    /**
     * Initialize the delegate with coroutine scope.
     */
    fun initialize(
        scope: CoroutineScope,
        onDataChanged: suspend () -> Unit,
        onNewMessage: suspend () -> Unit,
        onMessageUpdated: suspend () -> Unit,
        onChatRead: (String) -> Unit
    ) {
        this.scope = scope
        this.onDataChanged = onDataChanged
        this.onNewMessage = onNewMessage
        this.onMessageUpdated = onMessageUpdated
        this.onChatRead = onChatRead

        startGracePeriod()
        observeDataChanges()
        observeConnectionState()
        observeTypingIndicators()
        observeSyncState()
        observeNewMessagesFromSocket()
        observeMessageUpdatesFromSocket()
        observeChatReadFromSocket()
    }

    /**
     * Start grace period timer to avoid showing "not connected" banner immediately.
     */
    private fun startGracePeriod() {
        scope.launch {
            delay(2500) // 2.5 second grace period
            _startupGracePeriodPassed.value = true
        }
    }

    /**
     * Observe data changes to refresh loaded conversations reactively.
     * Triggers on any change to unified groups, chats, or typing indicators.
     */
    private fun observeDataChanges() {
        scope.launch {
            combine(
                unifiedChatGroupRepository.observeActiveGroupCount(),
                chatRepository.observeGroupChatCount(),
                chatRepository.observeNonGroupChatCount(),
                _typingChats
            ) { _, _, _, _ -> Unit }
                .debounce(100) // Reduced debounce for faster UI updates
                .collect {
                    onDataChanged?.invoke()
                }
        }
    }

    /**
     * Observe connection state changes.
     */
    private fun observeConnectionState() {
        scope.launch {
            socketService.connectionState.collect { state ->
                // Track if we ever connected (for banner logic)
                if (state == ConnectionState.CONNECTED) {
                    wasEverConnected = true
                }

                _isConnected.value = state == ConnectionState.CONNECTED
                _connectionState.value = state
            }
        }
    }

    /**
     * Observe typing indicators from socket.
     */
    private fun observeTypingIndicators() {
        scope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.TypingIndicator>()
                .collect { event ->
                    _typingChats.update { chats ->
                        if (event.isTyping) {
                            chats + event.chatGuid
                        } else {
                            chats - event.chatGuid
                        }
                    }
                }
        }
    }

    /**
     * Observe sync state changes.
     */
    private fun observeSyncState() {
        scope.launch {
            syncService.syncState.collect { state ->
                when (state) {
                    is SyncState.Syncing -> {
                        _isSyncing.value = true
                        _syncProgress.value = state.progress
                        _syncStage.value = state.stage
                        _syncTotalChats.value = state.totalChats
                        _syncProcessedChats.value = state.processedChats
                        _syncedMessages.value = state.syncedMessages
                        _syncCurrentChatName.value = state.currentChatName
                        _isInitialSync.value = state.isInitialSync
                        _syncError.value = null
                        _isSyncCorrupted.value = false
                    }
                    is SyncState.Completed -> {
                        _isSyncing.value = false
                        _syncProgress.value = null
                        _syncStage.value = null
                        _syncTotalChats.value = 0
                        _syncProcessedChats.value = 0
                        _syncedMessages.value = 0
                        _syncCurrentChatName.value = null
                        _isInitialSync.value = false
                        _syncError.value = null
                        _isSyncCorrupted.value = false
                    }
                    is SyncState.Error -> {
                        _isSyncing.value = false
                        _syncProgress.value = null
                        _syncStage.value = null
                        _syncError.value = state.message
                        _isSyncCorrupted.value = state.isCorrupted
                    }
                    SyncState.Idle -> {
                        _isSyncing.value = false
                        _syncProgress.value = null
                        _syncStage.value = null
                        _syncError.value = null
                        _isSyncCorrupted.value = false
                    }
                }
            }
        }
    }

    /**
     * Observe new messages from socket for immediate conversation list updates.
     * This supplements Room Flow observation to ensure the list updates instantly
     * when new messages arrive, rather than waiting for database invalidation.
     */
    private fun observeNewMessagesFromSocket() {
        scope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.NewMessage>()
                .collect { event ->
                    Log.d(TAG, "Socket: New message for ${event.chatGuid}")
                    // Immediately refresh to show new message in conversation list
                    onNewMessage?.invoke()
                }
        }
    }

    /**
     * Observe message updates from socket for immediate UI updates.
     * Handles read receipts, delivery status, edits, and reactions.
     */
    private fun observeMessageUpdatesFromSocket() {
        scope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.MessageUpdated>()
                .collect { event ->
                    Log.d(TAG, "Socket: Message updated for ${event.chatGuid}")
                    // Refresh to show updated status (read receipts, delivery, edits)
                    onMessageUpdated?.invoke()
                }
        }
    }

    /**
     * Observe chat read status changes from socket for immediate unread badge updates.
     * When a chat is marked as read (e.g., from another device), update the UI instantly.
     */
    private fun observeChatReadFromSocket() {
        scope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.ChatRead>()
                .collect { event ->
                    Log.d(TAG, "Socket: Chat read ${event.chatGuid}")
                    // Trigger optimistic update in ViewModel
                    onChatRead?.invoke(event.chatGuid)
                }
        }
    }

    /**
     * Get whether the connection was ever established.
     */
    fun wasEverConnected(): Boolean = wasEverConnected

    /**
     * Retry connection now.
     */
    fun retryConnection() {
        socketService.retryNow()
    }
}

// Extension to update MutableStateFlow
private fun <T> MutableStateFlow<Set<T>>.update(function: (Set<T>) -> Set<T>) {
    value = function(value)
}
