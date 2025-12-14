package com.bothbubbles.ui.conversations.delegates

import android.util.Log
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.UnifiedChatGroupRepository
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sync.StageProgress
import com.bothbubbles.services.sync.StageStatus
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.services.sync.SyncState
import com.bothbubbles.services.sync.SyncStageType
import com.bothbubbles.services.sync.UnifiedSyncProgress
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

    // Unified sync progress (combines iMessage sync, SMS import, and categorization)
    private val _unifiedSyncProgress = MutableStateFlow<UnifiedSyncProgress?>(null)
    val unifiedSyncProgress: StateFlow<UnifiedSyncProgress?> = _unifiedSyncProgress.asStateFlow()

    // Internal state for building unified progress
    private val _iMessageSyncState = MutableStateFlow<SyncState>(SyncState.Idle)
    private val _smsImportState = MutableStateFlow<SmsImportState>(SmsImportState.Idle)
    private val _categorizationState = MutableStateFlow<CategorizationState>(CategorizationState.Idle)
    private val _isExpanded = MutableStateFlow(false)

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
     * Observe sync state changes and build unified progress.
     */
    private fun observeSyncState() {
        // Observe iMessage sync state
        scope.launch {
            syncService.syncState.collect { state ->
                Log.d(TAG, "SyncState changed: $state")
                _iMessageSyncState.value = state
            }
        }

        // Combine all sync states into unified progress
        scope.launch {
            combine(
                _iMessageSyncState,
                _smsImportState,
                _categorizationState,
                _isExpanded
            ) { iMessageState, smsState, categState, expanded ->
                buildUnifiedProgress(iMessageState, smsState, categState, expanded)
            }.collect { unified ->
                if (unified != null) {
                    Log.d(TAG, "UnifiedProgress: stage=${unified.currentStage}, progress=${unified.overallProgress}, stages=${unified.stages.size}")
                }
                _unifiedSyncProgress.value = unified
            }
        }
    }

    /**
     * Build unified sync progress from individual states.
     */
    private fun buildUnifiedProgress(
        iMessageState: SyncState,
        smsState: SmsImportState,
        categState: CategorizationState,
        isExpanded: Boolean
    ): UnifiedSyncProgress? {
        val stages = mutableListOf<StageProgress>()
        var hasAnyActivity = false
        var hasError = false
        var currentStage = "Setting up..."

        // iMessage sync stage (weight: 60% - typically the longest)
        when (iMessageState) {
            is SyncState.Syncing -> {
                hasAnyActivity = true
                val detail = buildIMessageDetail(iMessageState)
                currentStage = if (iMessageState.isInitialSync) {
                    "Importing BlueBubbles messages..."
                } else {
                    iMessageState.stage
                }
                stages.add(
                    StageProgress(
                        type = SyncStageType.IMESSAGE,
                        name = "iMessage sync",
                        status = StageStatus.IN_PROGRESS,
                        progress = iMessageState.progress,
                        weight = 0.6f,
                        detail = detail
                    )
                )
            }
            is SyncState.Error -> {
                hasAnyActivity = true
                hasError = true
                currentStage = "iMessage sync failed"
                stages.add(
                    StageProgress(
                        type = SyncStageType.IMESSAGE,
                        name = "iMessage sync",
                        status = StageStatus.ERROR,
                        progress = 0f,
                        weight = 0.6f,
                        errorMessage = iMessageState.message,
                        isCorrupted = iMessageState.isCorrupted
                    )
                )
            }
            is SyncState.Completed -> {
                // Only show completed stage if other stages are active
                if (smsState !is SmsImportState.Idle || categState !is CategorizationState.Idle) {
                    stages.add(
                        StageProgress(
                            type = SyncStageType.IMESSAGE,
                            name = "iMessage sync",
                            status = StageStatus.COMPLETE,
                            progress = 1f,
                            weight = 0.6f
                        )
                    )
                }
            }
            SyncState.Idle -> {
                // Don't add idle stages unless other stages are active
                if (smsState !is SmsImportState.Idle || categState !is CategorizationState.Idle) {
                    stages.add(
                        StageProgress(
                            type = SyncStageType.IMESSAGE,
                            name = "iMessage sync",
                            status = StageStatus.WAITING,
                            weight = 0.6f
                        )
                    )
                }
            }
        }

        // SMS import stage (weight: 25%)
        when (smsState) {
            is SmsImportState.Importing -> {
                hasAnyActivity = true
                if (iMessageState !is SyncState.Syncing) {
                    currentStage = if (smsState.total == 0) "Preparing SMS import..." else "Importing SMS messages..."
                }
                stages.add(
                    StageProgress(
                        type = SyncStageType.SMS_IMPORT,
                        name = "SMS import",
                        status = StageStatus.IN_PROGRESS,
                        progress = smsState.progress,
                        weight = 0.25f,
                        detail = when {
                            smsState.total > 0 -> "${smsState.current} of ${smsState.total} threads"
                            else -> "Preparing..."
                        }
                    )
                )
            }
            is SmsImportState.Error -> {
                hasAnyActivity = true
                hasError = true
                if (iMessageState !is SyncState.Syncing && iMessageState !is SyncState.Error) {
                    currentStage = "SMS import failed"
                }
                stages.add(
                    StageProgress(
                        type = SyncStageType.SMS_IMPORT,
                        name = "SMS import",
                        status = StageStatus.ERROR,
                        progress = 0f,
                        weight = 0.25f,
                        errorMessage = smsState.message
                    )
                )
            }
            SmsImportState.Complete -> {
                if (iMessageState is SyncState.Syncing || categState !is CategorizationState.Idle) {
                    stages.add(
                        StageProgress(
                            type = SyncStageType.SMS_IMPORT,
                            name = "SMS import",
                            status = StageStatus.COMPLETE,
                            progress = 1f,
                            weight = 0.25f
                        )
                    )
                }
            }
            SmsImportState.Idle -> {
                if (iMessageState is SyncState.Syncing || categState !is CategorizationState.Idle) {
                    stages.add(
                        StageProgress(
                            type = SyncStageType.SMS_IMPORT,
                            name = "SMS import",
                            status = StageStatus.SKIPPED,
                            weight = 0.25f
                        )
                    )
                }
            }
        }

        // Categorization stage (weight: 15%)
        when (categState) {
            is CategorizationState.Categorizing -> {
                hasAnyActivity = true
                if (iMessageState !is SyncState.Syncing && smsState !is SmsImportState.Importing) {
                    currentStage = "Organizing messages..."
                }
                stages.add(
                    StageProgress(
                        type = SyncStageType.CATEGORIZATION,
                        name = "Organizing",
                        status = StageStatus.IN_PROGRESS,
                        progress = categState.progress,
                        weight = 0.15f,
                        detail = if (categState.total > 0) "${categState.current} of ${categState.total} conversations" else null
                    )
                )
            }
            is CategorizationState.Error -> {
                hasAnyActivity = true
                hasError = true
                stages.add(
                    StageProgress(
                        type = SyncStageType.CATEGORIZATION,
                        name = "Organizing",
                        status = StageStatus.ERROR,
                        progress = 0f,
                        weight = 0.15f,
                        errorMessage = categState.message
                    )
                )
            }
            CategorizationState.Complete -> {
                if (stages.isNotEmpty()) {
                    stages.add(
                        StageProgress(
                            type = SyncStageType.CATEGORIZATION,
                            name = "Organizing",
                            status = StageStatus.COMPLETE,
                            progress = 1f,
                            weight = 0.15f
                        )
                    )
                }
            }
            CategorizationState.Idle -> {
                if (iMessageState is SyncState.Syncing || smsState is SmsImportState.Importing) {
                    stages.add(
                        StageProgress(
                            type = SyncStageType.CATEGORIZATION,
                            name = "Organizing",
                            status = StageStatus.WAITING,
                            weight = 0.15f
                        )
                    )
                }
            }
        }

        // Return null if no activity
        if (!hasAnyActivity) return null

        val overallProgress = UnifiedSyncProgress.calculateOverallProgress(stages)

        return UnifiedSyncProgress(
            overallProgress = overallProgress,
            currentStage = currentStage,
            stages = stages,
            isExpanded = isExpanded,
            hasError = hasError,
            canRetry = true
        )
    }

    private fun buildIMessageDetail(state: SyncState.Syncing): String? {
        return buildString {
            if (state.totalChats > 0) {
                append("${state.processedChats}/${state.totalChats} chats")
            }
            if (state.syncedMessages > 0) {
                if (isNotEmpty()) append(" • ")
                if (state.totalMessagesExpected > 0) {
                    append("${state.syncedMessages} of ${state.totalMessagesExpected} messages")
                } else {
                    append("${state.syncedMessages} messages")
                }
            }
        }.takeIf { it.isNotEmpty() }
    }

    // Public methods to update SMS and categorization state (called from ViewModel)

    fun updateSmsImportState(state: SmsImportState) {
        _smsImportState.value = state
    }

    fun updateCategorizationState(state: CategorizationState) {
        _categorizationState.value = state
    }

    fun toggleExpanded() {
        _isExpanded.value = !_isExpanded.value
    }

    fun dismissSyncError() {
        // Reset error states
        when {
            _iMessageSyncState.value is SyncState.Error -> {
                // Let SyncService handle its own error clearing
            }
            _smsImportState.value is SmsImportState.Error -> {
                _smsImportState.value = SmsImportState.Idle
            }
            _categorizationState.value is CategorizationState.Error -> {
                _categorizationState.value = CategorizationState.Idle
            }
        }
        // Clear unified progress if all are idle/complete
        if (_iMessageSyncState.value is SyncState.Idle &&
            _smsImportState.value is SmsImportState.Idle &&
            _categorizationState.value is CategorizationState.Idle) {
            _unifiedSyncProgress.value = null
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

/**
 * State for SMS import progress.
 */
sealed class SmsImportState {
    data object Idle : SmsImportState()
    data class Importing(
        val progress: Float,
        val current: Int = 0,
        val total: Int = 0
    ) : SmsImportState()
    data object Complete : SmsImportState()
    data class Error(val message: String) : SmsImportState()
}

/**
 * State for categorization progress.
 */
sealed class CategorizationState {
    data object Idle : CategorizationState()
    data class Categorizing(
        val progress: Float,
        val current: Int = 0,
        val total: Int = 0
    ) : CategorizationState()
    data object Complete : CategorizationState()
    data class Error(val message: String) : CategorizationState()
}
