package com.bothbubbles.services.messaging

import android.net.Uri
import com.bothbubbles.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for message sending operations.
 * Allows mocking in tests without modifying the concrete implementation.
 *
 * This interface defines the contract for sending messages via various channels:
 * - iMessage via BlueBubbles server
 * - Local SMS/MMS via Android's telephony APIs
 *
 * Implementation: [MessageSendingService]
 */
interface MessageSender {

    /**
     * Current upload progress for attachment sends.
     */
    val uploadProgress: StateFlow<UploadProgress?>

    /**
     * Send a message via the appropriate channel.
     * This is the primary method for sending messages that auto-selects the delivery mode.
     */
    suspend fun sendUnified(
        chatGuid: String,
        text: String,
        replyToGuid: String? = null,
        effectId: String? = null,
        subject: String? = null,
        attachments: List<Uri> = emptyList(),
        deliveryMode: MessageDeliveryMode = MessageDeliveryMode.AUTO,
        subscriptionId: Int = -1,
        tempGuid: String? = null
    ): Result<MessageEntity>

    /**
     * Send a new message via iMessage.
     */
    suspend fun sendMessage(
        chatGuid: String,
        text: String,
        replyToGuid: String? = null,
        effectId: String? = null,
        subject: String? = null,
        providedTempGuid: String? = null
    ): Result<MessageEntity>

    /**
     * Send a reaction/tapback.
     */
    suspend fun sendReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String,
        partIndex: Int = 0
    ): Result<MessageEntity>

    /**
     * Remove a reaction.
     */
    suspend fun removeReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String,
        partIndex: Int = 0
    ): Result<Unit>

    /**
     * Edit a message (iOS 16+).
     */
    suspend fun editMessage(
        chatGuid: String,
        messageGuid: String,
        newText: String,
        partIndex: Int = 0
    ): Result<MessageEntity>

    /**
     * Unsend a message (iOS 16+).
     */
    suspend fun unsendMessage(
        chatGuid: String,
        messageGuid: String,
        partIndex: Int = 0
    ): Result<Unit>

    /**
     * Retry sending a failed message.
     */
    suspend fun retryMessage(messageGuid: String): Result<MessageEntity>

    /**
     * Retry sending a failed iMessage as SMS/MMS.
     */
    suspend fun retryAsSms(messageGuid: String): Result<MessageEntity>

    /**
     * Check if a failed iMessage can be retried as SMS.
     */
    suspend fun canRetryAsSms(messageGuid: String): Boolean

    /**
     * Forward a message to another conversation.
     */
    suspend fun forwardMessage(
        messageGuid: String,
        targetChatGuid: String
    ): Result<MessageEntity>

    /**
     * Reset upload progress state.
     */
    fun resetUploadProgress()
}
