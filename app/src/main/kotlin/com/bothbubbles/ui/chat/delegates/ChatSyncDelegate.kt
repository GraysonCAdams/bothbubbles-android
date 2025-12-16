package com.bothbubbles.ui.chat.delegates

import android.util.Log
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.AppLifecycleTracker
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.ui.chat.state.SyncState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import com.bothbubbles.ui.components.message.MessageUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for message syncing and adaptive polling.
 * Handles:
 * - Adaptive polling to catch missed messages when push is unreliable
 * - Foreground resume sync when app returns from background
 */
class ChatSyncDelegate @Inject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val socketService: SocketService,
    private val appLifecycleTracker: AppLifecycleTracker
) {

    companion object {
        private const val TAG = "ChatSyncDelegate"
        private const val POLL_INTERVAL_MS = 2000L // Poll every 2 seconds when socket is quiet
        private const val SOCKET_QUIET_THRESHOLD_MS = 5000L // Start polling after 5s of socket silence
    }

    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope
    private var mergedChatGuids: List<String> = emptyList()

    @Volatile
    private var lastSocketMessageTime: Long = System.currentTimeMillis()

    // ============================================================================
    // CONSOLIDATED SYNC STATE
    // Single StateFlow containing all sync-related state for reduced recompositions.
    // ============================================================================
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    // State update methods (called by ChatViewModel or external observers)
    fun setTyping(isTyping: Boolean) {
        _state.update { it.copy(isTyping = isTyping) }
    }

    fun setServerConnected(isConnected: Boolean) {
        _state.update { it.copy(isServerConnected = isConnected) }
    }

    fun setSyncing(isSyncing: Boolean) {
        _state.update { it.copy(isSyncing = isSyncing) }
    }

    fun setSmsFallbackMode(isInFallback: Boolean, reason: FallbackReason? = null) {
        _state.update { it.copy(isInSmsFallbackMode = isInFallback, fallbackReason = reason) }
    }

    fun updateLastSyncTime() {
        _state.update { it.copy(lastSyncTime = System.currentTimeMillis()) }
    }

    /**
     * Initialize the delegate.
     */
    fun initialize(
        chatGuid: String,
        scope: CoroutineScope,
        mergedChatGuids: List<String> = listOf(chatGuid)
    ) {
        this.chatGuid = chatGuid
        this.scope = scope
        this.mergedChatGuids = mergedChatGuids

        // Observe typing indicators from socket
        observeTypingIndicators()

        // Skip adaptive polling for local SMS chats
        if (!messageRepository.isLocalSmsChat(chatGuid)) {
            startAdaptivePolling()
            observeForegroundResume()
        }
    }

    /**
     * Update last socket message time.
     * Should be called when a new message is received via socket.
     */
    fun onSocketMessageReceived() {
        lastSocketMessageTime = System.currentTimeMillis()
    }

    /**
     * Adaptive polling to catch messages missed by push notifications.
     *
     * BlueBubbles server occasionally fails to push messages via Socket/FCM.
     * This polling mechanism activates when the socket has been "quiet" for
     * longer than SOCKET_QUIET_THRESHOLD_MS, fetching any messages newer than
     * what we have locally.
     */
    private fun startAdaptivePolling() {
        scope.launch {
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

                // Fetch messages (caller provides newest message timestamp)
                // This is handled by the callback passed to initialize
            }
        }
    }

    /**
     * Perform adaptive polling sync.
     * Should be called periodically when socket is quiet.
     */
    suspend fun performAdaptiveSync(newestMessage: MessageUiModel?) {
        val timeSinceLastSocketMessage = System.currentTimeMillis() - lastSocketMessageTime
        if (timeSinceLastSocketMessage < SOCKET_QUIET_THRESHOLD_MS) {
            return // Socket is active
        }

        if (!socketService.isConnected()) {
            return // Server not reachable
        }

        // Skip if chat doesn't exist yet (foreign key constraint would fail)
        if (chatRepository.getChat(chatGuid) == null) {
            return
        }

        val afterTimestamp = newestMessage?.dateCreated

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

    /**
     * Sync messages when app returns to foreground.
     */
    private fun observeForegroundResume() {
        scope.launch {
            appLifecycleTracker.foregroundState.collect { isInForeground ->
                if (isInForeground) {
                    Log.d(TAG, "App resumed - syncing for missed messages")
                    // Reset socket activity timer
                    lastSocketMessageTime = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Perform foreground resume sync.
     * Should be called when app returns from background.
     */
    suspend fun performForegroundSync(newestMessage: MessageUiModel?) {
        // Skip if chat doesn't exist yet (foreign key constraint would fail)
        if (chatRepository.getChat(chatGuid) == null) {
            return
        }

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

    // ============================================================================
    // TYPING INDICATORS
    // ============================================================================

    /**
     * Observe typing indicator events from the socket and update state.
     * Uses GUID normalization to handle format differences between server and local.
     */
    private fun observeTypingIndicators() {
        scope.launch {
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
                    setTyping(event.isTyping)
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
}
