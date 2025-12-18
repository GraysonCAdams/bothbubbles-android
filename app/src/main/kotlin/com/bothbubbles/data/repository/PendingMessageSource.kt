package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.entity.PendingMessageEntity
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.services.messaging.MessageDeliveryMode
import kotlinx.coroutines.flow.Flow

/**
 * Interface for pending message queue operations.
 * Allows testing message queue logic without a real database or WorkManager.
 *
 * This interface defines the contract for offline-first message sending:
 * - Queue messages for later delivery
 * - Retry/cancel failed messages
 * - Observe pending messages for UI updates
 *
 * Implementation: [PendingMessageRepository]
 */
interface PendingMessageSource {

    /**
     * Queue a message for sending.
     *
     * The message is immediately persisted and a background job is scheduled
     * to send it when network is available. A local echo is created for instant UI feedback.
     *
     * @param chatGuid Target chat GUID
     * @param text Message text (null if attachment-only)
     * @param subject Message subject (for MMS)
     * @param replyToGuid GUID of message being replied to (for threads)
     * @param effectId iMessage effect ID
     * @param attachments List of attachments to send
     * @param deliveryMode Delivery mode (AUTO, IMESSAGE, LOCAL_SMS, LOCAL_MMS)
     * @param forcedLocalId Optional local ID (for retry scenarios)
     * @return Local ID (temp GUID) of the queued message for UI tracking
     */
    suspend fun queueMessage(
        chatGuid: String,
        text: String?,
        subject: String? = null,
        replyToGuid: String? = null,
        effectId: String? = null,
        attachments: List<PendingAttachmentInput> = emptyList(),
        deliveryMode: MessageDeliveryMode = MessageDeliveryMode.AUTO,
        forcedLocalId: String? = null
    ): Result<String>

    /**
     * Retry a failed message.
     *
     * @param localId Local ID of the message to retry
     * @return Success or failure result
     */
    suspend fun retryMessage(localId: String): Result<Unit>

    /**
     * Cancel a pending message.
     * Also removes the local echo from the messages table.
     *
     * @param localId Local ID of the message to cancel
     * @return Success or failure result
     */
    suspend fun cancelMessage(localId: String): Result<Unit>

    /**
     * Observe pending messages for a specific chat.
     * Used by ChatViewModel to display queued messages with status indicators.
     *
     * @param chatGuid The chat to observe
     * @return Flow of pending messages for the chat
     */
    fun observePendingForChat(chatGuid: String): Flow<List<PendingMessageEntity>>

    /**
     * Observe count of pending messages for a chat (for badges).
     *
     * @param chatGuid The chat to observe
     * @return Flow of pending message count
     */
    fun observePendingCount(chatGuid: String): Flow<Int>

    /**
     * Re-enqueue all pending messages.
     * Called at app startup to restart stalled jobs.
     */
    suspend fun reEnqueuePendingMessages()

    /**
     * Clean up sent messages (run periodically or on startup).
     */
    suspend fun cleanupSentMessages()

    /**
     * Clean up orphaned temp messages that weren't properly replaced.
     * This handles race conditions where both temp and server messages exist.
     * Called at app startup.
     */
    suspend fun cleanupOrphanedTempMessages()

    /**
     * Get count of unsent messages (for startup indicator).
     *
     * @return Number of unsent messages
     */
    suspend fun getUnsentCount(): Int
}
