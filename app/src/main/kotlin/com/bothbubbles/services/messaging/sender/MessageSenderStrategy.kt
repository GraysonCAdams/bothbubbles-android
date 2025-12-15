package com.bothbubbles.services.messaging.sender

import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.services.messaging.MessageDeliveryMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Options for sending a message.
 */
data class SendOptions(
    val chatGuid: String,
    val text: String,
    val replyToGuid: String? = null,
    val effectId: String? = null,
    val subject: String? = null,
    val attachments: List<PendingAttachmentInput> = emptyList(),
    val subscriptionId: Int = -1,
    val tempGuid: String? = null
) {
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
}

/**
 * Result of a send operation.
 */
sealed class SendResult {
    data class Success(val message: MessageEntity) : SendResult()
    data class Failure(val error: Throwable, val tempGuid: String? = null) : SendResult()

    fun toResult(): Result<MessageEntity> = when (this) {
        is Success -> Result.success(message)
        is Failure -> Result.failure(error)
    }

    companion object {
        fun fromResult(result: Result<MessageEntity>): SendResult =
            result.fold(
                onSuccess = { Success(it) },
                onFailure = { Failure(it) }
            )
    }
}

/**
 * Upload progress tracking.
 */
data class UploadProgress(
    val fileName: String,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val attachmentIndex: Int,
    val totalAttachments: Int
) {
    val progress: Float
        get() = if (totalBytes > 0) bytesUploaded.toFloat() / totalBytes else 0f

    val isComplete: Boolean
        get() = bytesUploaded >= totalBytes
}

/**
 * Strategy interface for sending messages.
 *
 * Implementations handle different sending methods:
 * - [IMessageSenderStrategy]: Via BlueBubbles server
 * - [SmsSenderStrategy]: Via local Android SMS/MMS APIs
 */
interface MessageSenderStrategy {

    /**
     * Returns true if this strategy can handle the given delivery mode.
     */
    fun canHandle(deliveryMode: MessageDeliveryMode): Boolean

    /**
     * Executes the send operation.
     */
    suspend fun send(options: SendOptions): SendResult

    /**
     * Upload progress for attachment sends (optional).
     */
    val uploadProgress: StateFlow<UploadProgress?>?
        get() = null

    /**
     * Reset upload progress state.
     */
    fun resetUploadProgress() {}
}
