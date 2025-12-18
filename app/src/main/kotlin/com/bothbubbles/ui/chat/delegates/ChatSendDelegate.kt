package com.bothbubbles.ui.chat.delegates

import timber.log.Timber
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.PendingMessageEntity
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.data.repository.PendingMessageSource
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.services.messaging.MessageSender
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sound.SoundPlayer
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.PendingMessage
import com.bothbubbles.ui.chat.QueuedMessageUiModel
import com.bothbubbles.ui.chat.state.SendState
import com.bothbubbles.ui.util.toStable
import com.bothbubbles.util.PerformanceProfiler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Data for optimistic UI insertion when a message is queued.
 * Phase 4: Added messageSource to support ViewModel-coordinated optimistic insertion.
 */
data class QueuedMessageInfo(
    val guid: String,
    val text: String?,
    val dateCreated: Long,
    val hasAttachments: Boolean,
    val replyToGuid: String?,
    val effectId: String?,
    val messageSource: String = "IMESSAGE"
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
 * Uses AssistedInject to receive runtime parameters at construction time,
 * eliminating the need for a separate initialize() call.
 *
 * Phase 2: Uses MessageSender and SocketConnection interfaces instead of
 * concrete services for improved testability.
 */
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageSource: PendingMessageSource,
    private val messageSender: MessageSender,
    private val socketConnection: SocketConnection,
    private val soundPlayer: SoundPlayer,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope
) {

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }


    // Phase 4: Delegate references removed - ChatViewModel now coordinates all cross-delegate interactions.
    // The sendCurrentMessage() method has been replaced with queueMessage() which returns QueuedMessageInfo
    // for the ViewModel to use when inserting optimistic messages.

    // Typing indicator state
    private var typingDebounceJob: Job? = null
    private var isCurrentlyTyping = false
    private var lastStartedTypingTime = 0L
    private val typingDebounceMs = 3000L
    private val typingCooldownMs = 500L

    // ============================================================================
    // DUPLICATE DETECTION TRACKING
    // Tracks recent sends to detect potential duplicate messages.
    // This helps diagnose issues where users accidentally send the same message twice.
    // ============================================================================
    private data class RecentSend(
        val textHash: Int,
        val textPreview: String,
        val timestamp: Long,
        val tempGuid: String
    )
    private val recentSends = mutableListOf<RecentSend>()
    private val maxRecentSends = 10
    private val duplicateWarningWindowMs = 5 * 60 * 1000L // 5 minutes

    // ============================================================================
    // CONSOLIDATED SEND STATE
    // Single StateFlow containing all send-related state for reduced recompositions.
    // ChatScreen collects this directly instead of individual flows.
    // ============================================================================
    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state.asStateFlow()

    init {
        // Observe upload progress from MessageSender
        observeUploadProgress()

        // Observe queued messages from database
        observeQueuedMessages()
    }

    // Phase 4: setDelegates() removed - ChatViewModel now coordinates all cross-delegate interactions
    // Phase 4: sendCurrentMessage() removed - use queueMessageForSending() and let ViewModel coordinate

    /**
     * Queue a message for sending and return info for optimistic UI update.
     * Does NOT interact with other delegates - the ViewModel handles coordination.
     *
     * Phase 4: This method replaces the old sendCurrentMessage() which had hidden delegate coupling.
     * The ViewModel now coordinates: get input → call this → insert optimistic → clear input.
     *
     * @return QueuedMessageInfo for the ViewModel to use for optimistic UI insertion
     */
    suspend fun queueMessageForSending(
        text: String,
        attachments: List<PendingAttachmentInput>,
        currentSendMode: ChatSendMode,
        isLocalSmsChat: Boolean,
        effectId: String? = null,
        attributedBodyJson: String? = null
    ): Result<QueuedMessageInfo> {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() && attachments.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message text and attachments are both empty"))
        }

        val sendStartTime = System.currentTimeMillis()
        Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
        Timber.i("[SEND_TRACE] STEP 1: queueMessageForSending() CALLED at $sendStartTime")
        Timber.i("[SEND_TRACE] Text: \"${trimmedText.take(50)}${if (trimmedText.length > 50) "..." else ""}\"")
        Timber.i("[SEND_TRACE] Attachments: ${attachments.size}, SendMode: $currentSendMode, IsLocalSms: $isLocalSmsChat")

        // Log each attachment in detail
        attachments.forEachIndexed { index, att ->
            Timber.tag("AttachmentDebug").i("📤 SEND Attachment[$index]: uri=${att.uri}")
            Timber.tag("AttachmentDebug").i("   mimeType=${att.mimeType}, name=${att.name}, size=${att.size}")
            Timber.tag("AttachmentDebug").i("   caption=${att.caption}")
        }

        val sendId = PerformanceProfiler.start("Message.send", "${trimmedText.take(20)}...")
        val replyToGuid = _state.value.replyingToGuid

        // Clear reply state (this is internal to send delegate)
        _state.update { it.copy(replyingToGuid = null) }

        // Determine delivery mode based on chat type and current send mode
        val deliveryMode = determineDeliveryMode(
            isLocalSmsChat = isLocalSmsChat,
            currentSendMode = currentSendMode,
            hasAttachments = attachments.isNotEmpty()
        )

        // Generate GUID for optimistic UI
        val tempGuid = "temp-${java.util.UUID.randomUUID()}"
        val creationTime = System.currentTimeMillis()
        Timber.i("[SEND_TRACE] STEP 2: Generated tempGuid=$tempGuid +${System.currentTimeMillis() - sendStartTime}ms")

        // ═══════════════════════════════════════════════════════════════════════════
        // DUPLICATE DETECTION: Check if same text was sent recently
        // ═══════════════════════════════════════════════════════════════════════════
        val textHash = trimmedText.hashCode()
        val textPreview = trimmedText.take(30)

        // Clean up old entries
        val cutoffTime = creationTime - duplicateWarningWindowMs
        recentSends.removeAll { it.timestamp < cutoffTime }

        // Check for potential duplicate
        val potentialDuplicate = recentSends.find { it.textHash == textHash }
        if (potentialDuplicate != null) {
            val timeSinceLastSend = creationTime - potentialDuplicate.timestamp
            val timeSinceSeconds = timeSinceLastSend / 1000
            Timber.w("[DUPLICATE_DETECT] ⚠️ ════════════════════════════════════════════════════")
            Timber.w("[DUPLICATE_DETECT] ⚠️ POTENTIAL DUPLICATE MESSAGE DETECTED!")
            Timber.w("[DUPLICATE_DETECT] ⚠️ Text: \"$textPreview...\"")
            Timber.w("[DUPLICATE_DETECT] ⚠️ Same text hash sent ${timeSinceSeconds}s ago")
            Timber.w("[DUPLICATE_DETECT] ⚠️ Previous tempGuid: ${potentialDuplicate.tempGuid}")
            Timber.w("[DUPLICATE_DETECT] ⚠️ Current tempGuid: $tempGuid")
            Timber.w("[DUPLICATE_DETECT] ⚠️ Previous timestamp: ${potentialDuplicate.timestamp}")
            Timber.w("[DUPLICATE_DETECT] ⚠️ Current timestamp: $creationTime")
            Timber.w("[DUPLICATE_DETECT] ⚠️ Chat: $chatGuid")
            Timber.w("[DUPLICATE_DETECT] ⚠️ ════════════════════════════════════════════════════")
        } else {
            Timber.d("[DUPLICATE_DETECT] No duplicate detected for text hash $textHash")
        }

        // Record this send for future duplicate detection
        recentSends.add(RecentSend(
            textHash = textHash,
            textPreview = textPreview,
            timestamp = creationTime,
            tempGuid = tempGuid
        ))
        if (recentSends.size > maxRecentSends) {
            recentSends.removeAt(0)
        }
        Timber.d("[DUPLICATE_DETECT] Tracking ${recentSends.size} recent sends for this chat")

        // Determine message source for the optimistic model
        val messageSource = when {
            isLocalSmsChat -> MessageSource.LOCAL_SMS.name
            currentSendMode == ChatSendMode.SMS -> MessageSource.LOCAL_SMS.name
            else -> MessageSource.IMESSAGE.name
        }

        // Create info for optimistic UI update (returned to ViewModel)
        val queuedInfo = QueuedMessageInfo(
            guid = tempGuid,
            text = trimmedText.ifBlank { null },
            dateCreated = creationTime,
            hasAttachments = attachments.isNotEmpty(),
            replyToGuid = replyToGuid,
            effectId = effectId,
            messageSource = messageSource
        )

        // Queue message for offline-first delivery via WorkManager (on IO dispatcher)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Timber.i("[SEND_TRACE] STEP 3: On IO thread, calling queueMessage +${System.currentTimeMillis() - sendStartTime}ms")
            val queueStart = System.currentTimeMillis()
            pendingMessageSource.queueMessage(
                chatGuid = chatGuid,
                text = trimmedText,
                replyToGuid = replyToGuid,
                effectId = effectId,
                attachments = attachments,
                deliveryMode = deliveryMode,
                forcedLocalId = tempGuid,
                attributedBodyJson = attributedBodyJson
            ).fold(
                onSuccess = { localId ->
                    Timber.i("[SEND_TRACE] STEP 4: queueMessage SUCCESS (took ${System.currentTimeMillis() - queueStart}ms) +${System.currentTimeMillis() - sendStartTime}ms total")
                    Timber.i("[SEND_TRACE] ══════════════════════════════════════════════════════════")
                    PerformanceProfiler.end(sendId, "queued")

                    // Play send sound for all message types (iMessage and SMS)
                    soundPlayer.playSendSound()

                    Result.success(queuedInfo)
                },
                onFailure = { e ->
                    Timber.e("[SEND_TRACE] STEP 4: queueMessage FAILED: ${e.message} +${System.currentTimeMillis() - sendStartTime}ms")
                    _state.update { it.copy(sendError = "Failed to queue message: ${e.message}") }
                    PerformanceProfiler.end(sendId, "queue-failed: ${e.message}")
                    Result.failure(e)
                }
            )
        }
    }

    /**
     * Observe upload progress from MessageSendingService for determinate progress bar.
     * Updates the first pending message with attachments and recalculates aggregate progress.
     * Progress is managed by this delegate's SendState.
     */
    private fun observeUploadProgress() {
        scope.launch {
            messageSender.uploadProgress.collect { progress ->
                if (progress != null) {
                    // Calculate individual message progress (0.0 to 1.0)
                    val attachmentBase = progress.attachmentIndex.toFloat() / progress.totalAttachments
                    val currentProgress = progress.progress / progress.totalAttachments
                    val messageProgress = attachmentBase + currentProgress

                    // Update pending message progress
                    val currentState = _state.value
                    val pendingList = currentState.pendingMessages.toList()
                    val attachmentIndex = pendingList.indexOfFirst { it.hasAttachments }
                    if (attachmentIndex >= 0) {
                        updatePendingMessageProgress(
                            pendingList[attachmentIndex].tempGuid,
                            messageProgress
                        )
                    }
                    // Update aggregate progress
                    setSendProgress(calculateAggregateProgress())
                }
                // Don't reset progress to 0 when progress is null - let completion handlers manage that
            }
        }
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
     * @param onRemoveOptimistic Callback to remove optimistic message when DB insertion fails
     */
    fun sendMessage(
        text: String,
        attachments: List<PendingAttachmentInput>,
        effectId: String? = null,
        currentSendMode: ChatSendMode,
        isLocalSmsChat: Boolean,
        onClearInput: () -> Unit,
        onDraftCleared: () -> Unit,
        onQueued: ((QueuedMessageInfo) -> Unit)? = null,
        onRemoveOptimistic: ((String) -> Unit)? = null
    ) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() && attachments.isEmpty()) return

        // Stop typing indicator immediately when sending
        cancelTypingIndicator()

        val sendStartTime = System.currentTimeMillis()
        Timber.d("⏱️ [DELEGATE] sendMessage() CALLED on thread: ${Thread.currentThread().name}")

        scope.launch {
            Timber.d("⏱️ [DELEGATE] coroutine START: +${System.currentTimeMillis() - sendStartTime}ms, thread: ${Thread.currentThread().name}")
            val sendId = PerformanceProfiler.start("Message.send", "${trimmedText.take(20)}...")
            val replyToGuid = _state.value.replyingToGuid

            // Clear UI state immediately for responsive feel
            onClearInput()
            _state.update { it.copy(replyingToGuid = null) }
            onDraftCleared()
            Timber.d("⏱️ [DELEGATE] UI cleared: +${System.currentTimeMillis() - sendStartTime}ms")

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

            Timber.d("⏱️ [DELEGATE] calling onQueued callback (OPTIMISTIC): +${System.currentTimeMillis() - sendStartTime}ms")
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
            Timber.d("⏱️ [DELEGATE] onQueued callback returned: +${System.currentTimeMillis() - sendStartTime}ms")

            // Queue message for offline-first delivery via WorkManager
            // CRITICAL: Run on IO dispatcher to prevent blocking the main thread (which delays UI rendering)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val queueStart = System.currentTimeMillis()
                Timber.d("⏱️ [DELEGATE] calling queueMessage (IO): +${System.currentTimeMillis() - sendStartTime}ms")
                pendingMessageSource.queueMessage(
                    chatGuid = chatGuid,
                    text = trimmedText,
                    replyToGuid = replyToGuid,
                    effectId = effectId,
                    attachments = attachments,
                    deliveryMode = deliveryMode,
                    forcedLocalId = tempGuid // Pass the GUID we already displayed
                ).fold(
                    onSuccess = { localId ->
                        Timber.d("⏱️ [DELEGATE] queueMessage returned: +${System.currentTimeMillis() - queueStart}ms (total: ${System.currentTimeMillis() - sendStartTime}ms)")
                        Timber.d("Message queued successfully: $localId")
                        PerformanceProfiler.end(sendId, "queued")

                        // Play send sound for all message types (iMessage and SMS)
                        soundPlayer.playSendSound()
                    },
                    onFailure = { e ->
                        Timber.e(e, "Failed to queue message")
                        _state.update { it.copy(sendError = "Failed to queue message: ${e.message}") }
                        PerformanceProfiler.end(sendId, "queue-failed: ${e.message}")
                        // Remove the optimistic message from UI since DB insertion failed
                        onRemoveOptimistic?.invoke(tempGuid)
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
            // Clear UI state immediately
            onClearInput()
            onDraftCleared()

            // Queue message for offline-first delivery
            pendingMessageSource.queueMessage(
                chatGuid = chatGuid,
                text = trimmedText,
                attachments = attachments,
                deliveryMode = deliveryMode
            ).fold(
                onSuccess = { localId ->
                    Timber.d("Message queued via $deliveryMode: $localId")
                    // Play send sound for all message types (iMessage and SMS)
                    soundPlayer.playSendSound()
                },
                onFailure = { e ->
                    Timber.e(e, "Failed to queue message via $deliveryMode")
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
            messageSender.retryMessage(messageGuid)
        }
    }

    /**
     * Retry a failed iMessage as SMS/MMS.
     */
    fun retryMessageAsSms(messageGuid: String) {
        scope.launch {
            messageSender.retryAsSms(messageGuid).fold(
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
            messageSender.forwardMessage(messageGuid, targetChatGuid).fold(
                onSuccess = {
                    _state.update { it.copy(isForwarding = false, forwardSuccess = true) }
                    soundPlayer.playSendSound()
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
        return messageSender.canRetryAsSms(messageGuid)
    }

    /**
     * Delete a failed message from the local database.
     */
    fun deleteFailedMessage(messageGuid: String) {
        scope.launch {
            messageSender.deleteFailedMessage(messageGuid)
        }
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

    // ===== Queued Message Management =====

    /**
     * Observe queued messages from the database for offline-first UI.
     * These are messages that have been queued for sending but not yet delivered.
     */
    private fun observeQueuedMessages() {
        scope.launch {
            pendingMessageSource.observePendingForChat(chatGuid)
                .collect { pending ->
                    updateQueuedMessages(pending.map { it.toQueuedUiModel() })
                }
        }
    }

    /**
     * Retry a failed queued message.
     */
    fun retryQueuedMessage(localId: String) {
        scope.launch {
            pendingMessageSource.retryMessage(localId)
                .onFailure { e ->
                    Timber.e(e, "Failed to retry message: $localId")
                    _state.update { it.copy(sendError = "Failed to retry: ${e.message}") }
                }
        }
    }

    /**
     * Cancel a queued message.
     */
    fun cancelQueuedMessage(localId: String) {
        scope.launch {
            pendingMessageSource.cancelMessage(localId)
                .onFailure { e ->
                    Timber.e(e, "Failed to cancel message: $localId")
                    _state.update { it.copy(sendError = "Failed to cancel: ${e.message}") }
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
            socketConnection.sendStartedTyping(chatGuid)
        }

        // Debounce: cancel previous job and start new one
        typingDebounceJob?.cancel()
        typingDebounceJob = scope.launch {
            kotlinx.coroutines.delay(typingDebounceMs)
            if (isCurrentlyTyping) {
                isCurrentlyTyping = false
                socketConnection.sendStoppedTyping(chatGuid)
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
            socketConnection.sendStoppedTyping(chatGuid)
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
