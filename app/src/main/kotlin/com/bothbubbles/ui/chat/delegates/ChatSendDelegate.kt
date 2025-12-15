package com.bothbubbles.ui.chat.delegates

import android.util.Log
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.PendingMessageRepository
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sound.SoundManager
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.PendingMessage
import com.bothbubbles.ui.chat.QueuedMessageUiModel
import com.bothbubbles.ui.chat.state.SendState
import com.bothbubbles.ui.util.toStable
import com.bothbubbles.util.PerformanceProfiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Data for optimistic UI insertion when a message is queued.
 */
data class QueuedMessageInfo(
    val guid: String,
    val text: String?,
    val dateCreated: Long,
    val hasAttachments: Boolean,
    val replyToGuid: String?,
    val effectId: String?
)

/**
 * Delegate that handles all message sending operations for ChatViewModel.
 *
 * Responsibilities:
 * - Sending text messages and attachments
 * - Message queue management (offline-first via WorkManager)
 * - Retry failed messages
 * - Forward messages
 * - Reply state management
 * - Typing indicator coordination
 *
 * This delegate follows the composition pattern where ChatViewModel
 * delegates specific concerns to focused helper classes.
 *
 * Usage in ChatViewModel:
 * ```kotlin
 * class ChatViewModel @Inject constructor(
 *     private val chatSendDelegate: ChatSendDelegate,
 *     ...
 * ) : ViewModel() {
 *     init {
 *         chatSendDelegate.initialize(chatGuid, viewModelScope)
 *     }
 *
 *     fun sendMessage() = chatSendDelegate.sendMessage(...)
 * }
 * ```
 */
class ChatSendDelegate @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val chatRepository: ChatRepository,
    private val messageSendingService: MessageSendingService,
    private val socketService: SocketService,
    private val soundManager: SoundManager
) {
    companion object {
        private const val TAG = "ChatSendDelegate"
    }

    // State
    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope

    // Typing indicator state
    private var typingDebounceJob: Job? = null
    private var isCurrentlyTyping = false
    private var lastStartedTypingTime = 0L
    private val typingDebounceMs = 3000L
    private val typingCooldownMs = 500L

    // ============================================================================
    // CONSOLIDATED SEND STATE
    // Single StateFlow containing all send-related state for reduced recompositions.
    // ChatScreen collects this directly instead of individual flows.
    // ============================================================================
    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state.asStateFlow()

    /**
     * Initialize the delegate with the chat context.
     * Must be called before any send operations.
     */
    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid
        this.scope = scope
    }

    /**
     * Send a message with optional attachments.
     *
     * @param text Message text
     * @param attachments List of attachments to send
     * @param effectId Optional effect ID (e.g. "invisible ink")
     * @param currentSendMode Current send mode (iMessage or SMS)
     * @param isLocalSmsChat True if this is a local SMS/MMS chat
     * @param onClearInput Callback to clear input UI state
     * @param onDraftCleared Callback when draft should be cleared from persistence
     * @param onQueued Callback when message is queued, provides data for optimistic UI insert
     */
    fun sendMessage(
        text: String,
        attachments: List<PendingAttachmentInput>,
        effectId: String? = null,
        currentSendMode: ChatSendMode,
        isLocalSmsChat: Boolean,
        onClearInput: () -> Unit,
        onDraftCleared: () -> Unit,
        onQueued: ((QueuedMessageInfo) -> Unit)? = null
    ) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() && attachments.isEmpty()) return

        // Stop typing indicator immediately when sending
        cancelTypingIndicator()

        val sendStartTime = System.currentTimeMillis()
        Log.d(TAG, "⏱️ [DELEGATE] sendMessage() CALLED on thread: ${Thread.currentThread().name}")

        scope.launch {
            Log.d(TAG, "⏱️ [DELEGATE] coroutine START: +${System.currentTimeMillis() - sendStartTime}ms, thread: ${Thread.currentThread().name}")
            val sendId = PerformanceProfiler.start("Message.send", "${trimmedText.take(20)}...")
            val replyToGuid = _state.value.replyingToGuid

            // Clear UI state immediately for responsive feel
            onClearInput()
            _state.update { it.copy(replyingToGuid = null) }
            onDraftCleared()
            Log.d(TAG, "⏱️ [DELEGATE] UI cleared: +${System.currentTimeMillis() - sendStartTime}ms")

            // Determine delivery mode based on chat type and current send mode
            val deliveryMode = determineDeliveryMode(
                isLocalSmsChat = isLocalSmsChat,
                currentSendMode = currentSendMode,
                hasAttachments = attachments.isNotEmpty()
            )

            // OPTIMISTIC INSERTION (Phase 1):
            // Generate GUID and insert into UI *before* touching the database.
            // This eliminates the ~50ms DB write latency from the visual feedback loop.
            val tempGuid = "temp-${java.util.UUID.randomUUID()}"
            val creationTime = System.currentTimeMillis()

            Log.d(TAG, "⏱️ [DELEGATE] calling onQueued callback (OPTIMISTIC): +${System.currentTimeMillis() - sendStartTime}ms")
            onQueued?.invoke(
                QueuedMessageInfo(
                    guid = tempGuid,
                    text = trimmedText.ifBlank { null },
                    dateCreated = creationTime,
                    hasAttachments = attachments.isNotEmpty(),
                    replyToGuid = replyToGuid,
                    effectId = effectId
                )
            )
            Log.d(TAG, "⏱️ [DELEGATE] onQueued callback returned: +${System.currentTimeMillis() - sendStartTime}ms")

            // Queue message for offline-first delivery via WorkManager
            // CRITICAL: Run on IO dispatcher to prevent blocking the main thread (which delays UI rendering)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val queueStart = System.currentTimeMillis()
                Log.d(TAG, "⏱️ [DELEGATE] calling queueMessage (IO): +${System.currentTimeMillis() - sendStartTime}ms")
                pendingMessageRepository.queueMessage(
                    chatGuid = chatGuid,
                    text = trimmedText,
                    replyToGuid = replyToGuid,
                    effectId = effectId,
                    attachments = attachments,
                    deliveryMode = deliveryMode,
                    forcedLocalId = tempGuid // Pass the GUID we already displayed
                ).fold(
                    onSuccess = { localId ->
                        Log.d(TAG, "⏱️ [DELEGATE] queueMessage returned: +${System.currentTimeMillis() - queueStart}ms (total: ${System.currentTimeMillis() - sendStartTime}ms)")
                        Log.d(TAG, "Message queued successfully: $localId")
                        PerformanceProfiler.end(sendId, "queued")

                        // Play sound for SMS delivery (optimistic)
                        val isSmsSend = isLocalSmsChat || currentSendMode == ChatSendMode.SMS
                        if (isSmsSend) {
                            soundManager.playSendSound()
                        }
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Failed to queue message", e)
                        _state.update { it.copy(sendError = "Failed to queue message: ${e.message}") }
                        PerformanceProfiler.end(sendId, "queue-failed: ${e.message}")
                        // TODO: We should probably remove the optimistic message here if DB failed
                    }
                )
            }
        }
    }

    /**
     * Send message with explicit delivery mode override.
     */
    fun sendMessageVia(
        text: String,
        attachments: List<PendingAttachmentInput>,
        deliveryMode: MessageDeliveryMode,
        onClearInput: () -> Unit,
        onDraftCleared: () -> Unit
    ) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() && attachments.isEmpty()) return

        scope.launch {
            val isLocalSms = deliveryMode == MessageDeliveryMode.LOCAL_SMS ||
                            deliveryMode == MessageDeliveryMode.LOCAL_MMS

            // Clear UI state immediately
            onClearInput()
            onDraftCleared()

            // Queue message for offline-first delivery
            pendingMessageRepository.queueMessage(
                chatGuid = chatGuid,
                text = trimmedText,
                attachments = attachments,
                deliveryMode = deliveryMode
            ).fold(
                onSuccess = { localId ->
                    Log.d(TAG, "Message queued via $deliveryMode: $localId")
                    if (isLocalSms) {
                        soundManager.playSendSound()
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to queue message via $deliveryMode", e)
                    _state.update { it.copy(sendError = "Failed to queue message: ${e.message}") }
                }
            )
        }
    }

    /**
     * Set the message to reply to (for swipe-to-reply).
     */
    fun setReplyTo(messageGuid: String) {
        _state.update { it.copy(replyingToGuid = messageGuid) }
    }

    /**
     * Clear the reply state.
     */
    fun clearReply() {
        _state.update { it.copy(replyingToGuid = null) }
    }

    /**
     * Retry sending a failed message.
     */
    fun retryMessage(messageGuid: String) {
        scope.launch {
            messageSendingService.retryMessage(messageGuid)
        }
    }

    /**
     * Retry a failed iMessage as SMS/MMS.
     */
    fun retryMessageAsSms(messageGuid: String) {
        scope.launch {
            messageSendingService.retryAsSms(messageGuid).fold(
                onSuccess = { /* Message was successfully sent via SMS */ },
                onFailure = { e ->
                    _state.update { it.copy(sendError = e.message) }
                }
            )
        }
    }

    /**
     * Forward a message to another conversation.
     */
    fun forwardMessage(messageGuid: String, targetChatGuid: String) {
        scope.launch {
            _state.update { it.copy(isForwarding = true) }
            messageSendingService.forwardMessage(messageGuid, targetChatGuid).fold(
                onSuccess = {
                    _state.update { it.copy(isForwarding = false, forwardSuccess = true) }
                    soundManager.playSendSound()
                },
                onFailure = { e ->
                    _state.update { it.copy(isForwarding = false, sendError = "Failed to forward: ${e.message}") }
                }
            )
        }
    }

    /**
     * Check if a failed message can be retried as SMS.
     */
    suspend fun canRetryAsSms(messageGuid: String): Boolean {
        return messageSendingService.canRetryAsSms(messageGuid)
    }

    /**
     * Clear the forward success flag.
     */
    fun clearForwardSuccess() {
        _state.update { it.copy(forwardSuccess = false) }
    }

    /**
     * Clear the send error.
     */
    fun clearSendError() {
        _state.update { it.copy(sendError = null) }
    }

    // ============================================================================
    // STATE UPDATE METHODS (called by ChatViewModel to update send-related state)
    // ============================================================================

    /**
     * Update isSending state.
     */
    fun setIsSending(isSending: Boolean) {
        _state.update { it.copy(isSending = isSending) }
    }

    /**
     * Update send progress (0.0 to 1.0).
     */
    fun setSendProgress(progress: Float) {
        _state.update { it.copy(sendProgress = progress) }
    }

    /**
     * Update pending messages list.
     */
    fun updatePendingMessages(messages: List<PendingMessage>) {
        _state.update { it.copy(pendingMessages = messages.toStable()) }
    }

    /**
     * Add a pending message.
     */
    fun addPendingMessage(message: PendingMessage) {
        _state.update { state ->
            val updated = state.pendingMessages.toMutableList()
            updated.add(message)
            state.copy(pendingMessages = updated.toStable())
        }
    }

    /**
     * Remove a pending message by GUID.
     */
    fun removePendingMessage(guid: String) {
        _state.update { state ->
            val updated = state.pendingMessages.filterNot { it.tempGuid == guid }
            state.copy(pendingMessages = updated.toStable())
        }
    }

    /**
     * Update a pending message's progress.
     */
    fun updatePendingMessageProgress(guid: String, progress: Float) {
        _state.update { state ->
            val updated = state.pendingMessages.map { msg ->
                if (msg.tempGuid == guid) msg.copy(progress = progress) else msg
            }
            state.copy(pendingMessages = updated.toStable())
        }
    }

    /**
     * Update queued messages list from database observer.
     */
    fun updateQueuedMessages(messages: List<QueuedMessageUiModel>) {
        _state.update { it.copy(queuedMessages = messages.toStable()) }
    }

    /**
     * Calculate aggregate progress from pending messages.
     */
    fun calculateAggregateProgress(): Float {
        val pending = _state.value.pendingMessages
        if (pending.isEmpty()) return 0f
        return pending.sumOf { it.progress.toDouble() }.toFloat() / pending.size
    }

    // ===== Typing Indicator Management =====

    /**
     * Start typing indicator (debounced).
     * Call this when user starts typing.
     */
    fun startTyping(isPrivateApiEnabled: Boolean, isTypingIndicatorsEnabled: Boolean) {
        if (!isPrivateApiEnabled || !isTypingIndicatorsEnabled) return

        val now = System.currentTimeMillis()
        if (!isCurrentlyTyping && now - lastStartedTypingTime > typingCooldownMs) {
            isCurrentlyTyping = true
            lastStartedTypingTime = now
            socketService.sendStartedTyping(chatGuid)
        }

        // Debounce: cancel previous job and start new one
        typingDebounceJob?.cancel()
        typingDebounceJob = scope.launch {
            kotlinx.coroutines.delay(typingDebounceMs)
            if (isCurrentlyTyping) {
                isCurrentlyTyping = false
                socketService.sendStoppedTyping(chatGuid)
            }
        }
    }

    /**
     * Stop typing indicator immediately.
     * Call this when user clears input or sends message.
     */
    fun cancelTypingIndicator() {
        typingDebounceJob?.cancel()
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            socketService.sendStoppedTyping(chatGuid)
        }
    }

    // ===== Private Helpers =====

    private fun determineDeliveryMode(
        isLocalSmsChat: Boolean,
        currentSendMode: ChatSendMode,
        hasAttachments: Boolean
    ): MessageDeliveryMode {
        return when {
            // Local SMS chats always use local delivery
            isLocalSmsChat -> if (hasAttachments) MessageDeliveryMode.LOCAL_MMS else MessageDeliveryMode.LOCAL_SMS
            // If user has selected SMS mode, use local SMS from Android
            currentSendMode == ChatSendMode.SMS -> if (hasAttachments) MessageDeliveryMode.LOCAL_MMS else MessageDeliveryMode.LOCAL_SMS
            // iMessage mode uses server
            currentSendMode == ChatSendMode.IMESSAGE -> MessageDeliveryMode.IMESSAGE
            // Fallback to auto
            else -> MessageDeliveryMode.AUTO
        }
    }
}
