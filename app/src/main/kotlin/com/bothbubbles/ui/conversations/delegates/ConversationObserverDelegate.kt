package com.bothbubbles.ui.conversations.delegates

import timber.log.Timber
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.UnifiedChatRepository
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.sync.StageProgress
import com.bothbubbles.services.sync.StageStatus
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.services.sync.SyncState
import com.bothbubbles.services.sync.SyncStageType
import com.bothbubbles.services.sync.UnifiedSyncProgress
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Delegate responsible for observing database and socket changes.
 * Handles real-time updates from sync, socket events, and typing indicators.
 *
 * Phase 8: Uses AssistedInject for lifecycle-safe construction and
 * SharedFlow events instead of callbacks for ViewModel coordination.
 */
@OptIn(FlowPreview::class)
class ConversationObserverDelegate @AssistedInject constructor(
    private val unifiedChatRepository: UnifiedChatRepository,
    private val chatRepository: ChatRepository,
    private val socketConnection: SocketConnection,
    private val syncService: SyncService,
    private val settingsDataStore: SettingsDataStore,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ConversationObserverDelegate
    }


    // ============================================================================
    // Event Flow - Phase 8: Replaces callbacks
    // ============================================================================

    private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    // ============================================================================
    // State Flows - exposed to ViewModel for UI state composition
    // ============================================================================

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

    // Reconnecting indicator state (shown after 5 seconds of disconnection)
    private val _showReconnectingIndicator = MutableStateFlow(false)
    val showReconnectingIndicator: StateFlow<Boolean> = _showReconnectingIndicator.asStateFlow()

    // Job for the disconnection timer
    private var disconnectionTimerJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val RECONNECTING_INDICATOR_DELAY_MS = 5000L
    }

    // Unified sync progress (combines iMessage sync, SMS import, and categorization)
    private val _unifiedSyncProgress = MutableStateFlow<UnifiedSyncProgress?>(null)
    val unifiedSyncProgress: StateFlow<UnifiedSyncProgress?> = _unifiedSyncProgress.asStateFlow()

    // Internal state for building unified progress
    private val _iMessageSyncState = MutableStateFlow<SyncState>(SyncState.Idle)
    private val _smsImportState = MutableStateFlow<SmsImportState>(SmsImportState.Idle)
    private val _categorizationState = MutableStateFlow<CategorizationState>(CategorizationState.Idle)
    private val _isExpanded = MutableStateFlow(false)

    // Total unread count from database (matches app icon badge)
    private val _totalUnreadCount = MutableStateFlow(0)
    val totalUnreadCount: StateFlow<Int> = _totalUnreadCount.asStateFlow()

    // ============================================================================
    // Initialization - Phase 8: All setup happens in init block
    // ============================================================================

    init {
        startGracePeriod()
        observeDataChanges()
        observeConnectionState()
        observeTypingIndicators()
        observeSyncState()
        observeNewMessagesFromSocket()
        observeMessageUpdatesFromSocket()
        observeChatReadStatusFromSocket()
        observeTotalUnreadCount()
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
     * Triggers on any change to unified chats, group chats, typing indicators, or read status.
     */
    private fun observeDataChanges() {
        scope.launch {
            combine(
                unifiedChatRepository.observeActiveCount(),
                chatRepository.observeGroupChatCount(),
                chatRepository.observeNonGroupChatCount(),
                chatRepository.observeUnreadChatCount(),  // Triggers on mark as read
                _typingChats
            ) { _, _, _, _, _ -> Unit }
                .debounce(100) // Reduced debounce for faster UI updates
                .collect {
                    _events.emit(ConversationEvent.DataChanged)
                }
        }
    }

    /**
     * Observe connection state changes.
     * Manages the reconnecting indicator timer - shows after 5 seconds of disconnection.
     * Only shows when iMessage is enabled (not in SMS-only mode).
     */
    private fun observeConnectionState() {
        scope.launch {
            // Combine connection state with smsOnlyMode to determine if indicator should show
            combine(
                socketConnection.connectionState,
                settingsDataStore.smsOnlyMode
            ) { state, smsOnlyMode ->
                state to smsOnlyMode
            }.collect { (state, smsOnlyMode) ->
                // Track if we ever connected (for banner logic)
                if (state == ConnectionState.CONNECTED) {
                    wasEverConnected = true
                    // Cancel any pending timer and hide indicator immediately
                    disconnectionTimerJob?.cancel()
                    disconnectionTimerJob = null
                    _showReconnectingIndicator.value = false
                } else if (state != ConnectionState.NOT_CONFIGURED && !smsOnlyMode) {
                    // Start timer if disconnected, server is configured, and not in SMS-only mode
                    // Only start if not already running
                    if (disconnectionTimerJob == null) {
                        disconnectionTimerJob = scope.launch {
                            delay(RECONNECTING_INDICATOR_DELAY_MS)
                            _showReconnectingIndicator.value = true
                        }
                    }
                } else {
                    // NOT_CONFIGURED or SMS-only mode - no iMessage, don't show indicator
                    disconnectionTimerJob?.cancel()
                    disconnectionTimerJob = null
                    _showReconnectingIndicator.value = false
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
            socketConnection.events
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
                Timber.d("SyncState changed: $state")
                _iMessageSyncState.value = state
            }
        }

        // Combine all sync states into unified progress
        scope.launch {
            combine(
                _iMessageSyncState,
                _smsImportState,
                _categorizationState,
                _isExpanded,
                settingsDataStore.initialSyncComplete
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                val iMessageState = values[0] as? SyncState ?: SyncState.Idle
                val smsState = values[1] as? SmsImportState ?: SmsImportState.Idle
                val categState = values[2] as? CategorizationState ?: CategorizationState.Idle
                val expanded = values[3] as? Boolean ?: false
                val initialSyncComplete = values[4] as? Boolean ?: false
                buildUnifiedProgress(iMessageState, smsState, categState, expanded, initialSyncComplete)
            }.collect { unified ->
                if (unified != null) {
                    Timber.d("UnifiedProgress: stage=${unified.currentStage}, progress=${unified.overallProgress}, stages=${unified.stages.size}")
                }
                _unifiedSyncProgress.value = unified
            }
        }
    }

    /**
     * Build unified sync progress from individual states.
     * @param initialSyncComplete If true, categorization progress is hidden (runs silently in background)
     */
    private fun buildUnifiedProgress(
        iMessageState: SyncState,
        smsState: SmsImportState,
        categState: CategorizationState,
        isExpanded: Boolean,
        initialSyncComplete: Boolean
    ): UnifiedSyncProgress? {
        // After initial sync, categorization runs silently in the background
        val showCategorization = !initialSyncComplete
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
                if (smsState !is SmsImportState.Idle || (showCategorization && categState !is CategorizationState.Idle)) {
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
                if (smsState !is SmsImportState.Idle || (showCategorization && categState !is CategorizationState.Idle)) {
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
                if (iMessageState is SyncState.Syncing || (showCategorization && categState !is CategorizationState.Idle)) {
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
                if (iMessageState is SyncState.Syncing || (showCategorization && categState !is CategorizationState.Idle)) {
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

        // Categorization stage (weight: 15%) - only shown during initial sync
        if (showCategorization) {
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
                append("${state.processedChats} of ${state.totalChats} chats")
            }
            if (state.syncedMessages > 0) {
                if (isNotEmpty()) append(" • ")
                append("${state.syncedMessages} messages")
            }
        }.takeIf { it.isNotEmpty() }
    }

    // ============================================================================
    // Public Methods - State updates (called from ViewModel)
    // ============================================================================

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
            socketConnection.events
                .filterIsInstance<SocketEvent.NewMessage>()
                .collect { event ->
                    Timber.d("Socket: New message for ${event.chatGuid}")
                    _events.emit(ConversationEvent.NewMessage)
                }
        }
    }

    /**
     * Observe message updates from socket for immediate UI updates.
     * Handles read receipts, delivery status, edits, and reactions.
     */
    private fun observeMessageUpdatesFromSocket() {
        scope.launch {
            socketConnection.events
                .filterIsInstance<SocketEvent.MessageUpdated>()
                .collect { event ->
                    Timber.d("Socket: Message updated for ${event.chatGuid}")
                    _events.emit(ConversationEvent.MessageUpdated)
                }
        }
    }

    /**
     * Observe chat read status changes from socket for immediate unread badge updates.
     * When a chat is marked as read/unread (e.g., from another device), update the UI instantly.
     */
    private fun observeChatReadStatusFromSocket() {
        scope.launch {
            socketConnection.events
                .filterIsInstance<SocketEvent.ChatReadStatusChanged>()
                .collect { event ->
                    Timber.d("Socket: Chat read status changed ${event.chatGuid}, isRead: ${event.isRead}")
                    _events.emit(ConversationEvent.ChatReadStatusChanged(event.chatGuid, event.isRead))
                }
        }
    }

    /**
     * Observe total unread count from database (unified chats table).
     * Uses the same source as conversation list items for consistency.
     */
    private fun observeTotalUnreadCount() {
        scope.launch {
            unifiedChatRepository.observeTotalUnreadCount()
                .collect { count ->
                    _totalUnreadCount.value = count
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
        socketConnection.retryNow()
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
