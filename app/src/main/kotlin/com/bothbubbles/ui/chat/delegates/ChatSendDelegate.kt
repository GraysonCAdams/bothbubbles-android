package com.bothbubbles.ui.chat.delegates

import android.util.Log
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.PendingMessageRepository
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sound.SoundManager
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.util.PerformanceProfiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val messageRepository: MessageRepository,
    private val pendingMessageRepository: PendingMessageRepository,
    private val chatRepository: ChatRepository,
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

    // Reply state
    private val _replyingToGuid = MutableStateFlow<String?>(null)
    val replyingToGuid: StateFlow<String?> = _replyingToGuid.asStateFlow()

    // Forwarding state
    private val _isForwarding = MutableStateFlow(false)
    val isForwarding: StateFlow<Boolean> = _isForwarding.asStateFlow()

    private val _forwardSuccess = MutableStateFlow(false)
    val forwardSuccess: StateFlow<Boolean> = _forwardSuccess.asStateFlow()

    // Error state
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    /**
     * Initialize the delegate with the chat context.
     * Must be called before any send operations.
     */
    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid
        this.scope = scope
    }

    /**
     * Send a message with optional attachments and effects.
     *
     * @param text The message text (can be empty if attachments present)
     * @param attachments List of attachment URIs
     * @param effectId Optional iMessage effect (bubble or screen effect)
     * @param currentSendMode Current send mode (iMessage or SMS)
     * @param isLocalSmsChat True if this is a local SMS/MMS chat
     * @param onClearInput Callback to clear input UI state
     * @param onDraftCleared Callback when draft should be cleared from persistence
     */
    fun sendMessage(
        text: String,
        attachments: List<android.net.Uri>,
        effectId: String? = null,
        currentSendMode: ChatSendMode,
        isLocalSmsChat: Boolean,
        onClearInput: () -> Unit,
        onDraftCleared: () -> Unit
    ) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() && attachments.isEmpty()) return

        // Stop typing indicator immediately when sending
        cancelTypingIndicator()

        scope.launch {
            val sendId = PerformanceProfiler.start("Message.send", "${trimmedText.take(20)}...")
            val replyToGuid = _replyingToGuid.value

            // Clear UI state immediately for responsive feel
            onClearInput()
            _replyingToGuid.value = null
            onDraftCleared()

            // Determine delivery mode based on chat type and current send mode
            val deliveryMode = determineDeliveryMode(
                isLocalSmsChat = isLocalSmsChat,
                currentSendMode = currentSendMode,
                hasAttachments = attachments.isNotEmpty()
            )

            // Queue message for offline-first delivery via WorkManager
            pendingMessageRepository.queueMessage(
                chatGuid = chatGuid,
                text = trimmedText,
                replyToGuid = replyToGuid,
                effectId = effectId,
                attachments = attachments,
                deliveryMode = deliveryMode
            ).fold(
                onSuccess = { localId ->
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
                    _sendError.value = "Failed to queue message: ${e.message}"
                    PerformanceProfiler.end(sendId, "queue-failed: ${e.message}")
                }
            )
        }
    }

    /**
     * Send message with explicit delivery mode override.
     */
    fun sendMessageVia(
        text: String,
        attachments: List<android.net.Uri>,
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
                    _sendError.value = "Failed to queue message: ${e.message}"
                }
            )
        }
    }

    /**
     * Set the message to reply to (for swipe-to-reply).
     */
    fun setReplyTo(messageGuid: String) {
        _replyingToGuid.value = messageGuid
    }

    /**
     * Clear the reply state.
     */
    fun clearReply() {
        _replyingToGuid.value = null
    }

    /**
     * Retry sending a failed message.
     */
    fun retryMessage(messageGuid: String) {
        scope.launch {
            messageRepository.retryMessage(messageGuid)
        }
    }

    /**
     * Retry a failed iMessage as SMS/MMS.
     */
    fun retryMessageAsSms(messageGuid: String) {
        scope.launch {
            messageRepository.retryAsSms(messageGuid).fold(
                onSuccess = { /* Message was successfully sent via SMS */ },
                onFailure = { e ->
                    _sendError.value = e.message
                }
            )
        }
    }

    /**
     * Forward a message to another conversation.
     */
    fun forwardMessage(messageGuid: String, targetChatGuid: String) {
        scope.launch {
            _isForwarding.value = true
            messageRepository.forwardMessage(messageGuid, targetChatGuid).fold(
                onSuccess = {
                    _isForwarding.value = false
                    _forwardSuccess.value = true
                    soundManager.playSendSound()
                },
                onFailure = { e ->
                    _isForwarding.value = false
                    _sendError.value = "Failed to forward: ${e.message}"
                }
            )
        }
    }

    /**
     * Check if a failed message can be retried as SMS.
     */
    suspend fun canRetryAsSms(messageGuid: String): Boolean {
        return messageRepository.canRetryAsSms(messageGuid)
    }

    /**
     * Clear the forward success flag.
     */
    fun clearForwardSuccess() {
        _forwardSuccess.value = false
    }

    /**
     * Clear the send error.
     */
    fun clearSendError() {
        _sendError.value = null
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
