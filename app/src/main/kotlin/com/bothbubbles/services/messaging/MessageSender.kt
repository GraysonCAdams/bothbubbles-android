package com.bothbubbles.services.messaging

import android.net.Uri
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.model.PendingAttachmentInput
import kotlinx.coroutines.flow.SharedFlow
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
     * Events emitted when a temp GUID is replaced with the server GUID.
     * UI components should observe this to update cached message state.
     */
    val guidReplacementEvents: SharedFlow<GuidReplacementEvent>

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
        attachments: List<PendingAttachmentInput> = emptyList(),
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
     * @param selectedMessageText The text of the message being reacted on (required by BlueBubbles server)
     */
    suspend fun sendReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String,
        selectedMessageText: String? = null,
        partIndex: Int = 0
    ): Result<MessageEntity>

    /**
     * Remove a reaction.
     * @param selectedMessageText The text of the message being reacted on (required by BlueBubbles server)
     */
    suspend fun removeReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String,
        selectedMessageText: String? = null,
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
     * Delete a failed message from the local database.
     */
    suspend fun deleteFailedMessage(messageGuid: String)

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
