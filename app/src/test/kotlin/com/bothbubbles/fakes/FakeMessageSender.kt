package com.bothbubbles.fakes

import android.net.Uri
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.services.messaging.MessageSender
import com.bothbubbles.services.messaging.UploadProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake implementation of [MessageSender] for unit testing.
 *
 * This fake records all method calls and allows tests to configure results.
 *
 * Usage:
 * ```kotlin
 * val fakeMessageSender = FakeMessageSender()
 *
 * // Configure the result
 * fakeMessageSender.sendUnifiedResult = Result.success(createTestMessage())
 *
 * // Use in ViewModel
 * val viewModel = ChatViewModel(messageSender = fakeMessageSender, ...)
 *
 * // Trigger action
 * viewModel.sendMessage("Hello")
 *
 * // Verify call was made
 * assertEquals(1, fakeMessageSender.sendUnifiedCalls.size)
 * assertEquals("Hello", fakeMessageSender.sendUnifiedCalls[0].text)
 * ```
 */
class FakeMessageSender : MessageSender {

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    override val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress

    private val _guidReplacementEvents = kotlinx.coroutines.flow.MutableSharedFlow<com.bothbubbles.services.messaging.GuidReplacementEvent>()
    override val guidReplacementEvents: kotlinx.coroutines.flow.SharedFlow<com.bothbubbles.services.messaging.GuidReplacementEvent> = _guidReplacementEvents

    // ===== Configurable Results =====

    var sendUnifiedResult: Result<MessageEntity> = Result.failure(NotImplementedError("Configure sendUnifiedResult"))
    var sendMessageResult: Result<MessageEntity> = Result.failure(NotImplementedError("Configure sendMessageResult"))
    var sendReactionResult: Result<MessageEntity> = Result.failure(NotImplementedError("Configure sendReactionResult"))
    var removeReactionResult: Result<Unit> = Result.success(Unit)
    var editMessageResult: Result<MessageEntity> = Result.failure(NotImplementedError("Configure editMessageResult"))
    var unsendMessageResult: Result<Unit> = Result.success(Unit)
    var retryMessageResult: Result<MessageEntity> = Result.failure(NotImplementedError("Configure retryMessageResult"))
    var retryAsSmsResult: Result<MessageEntity> = Result.failure(NotImplementedError("Configure retryAsSmsResult"))
    var canRetryAsSmsResult: Boolean = false
    var forwardMessageResult: Result<MessageEntity> = Result.failure(NotImplementedError("Configure forwardMessageResult"))

    // ===== Call Recording =====

    val sendUnifiedCalls = mutableListOf<SendUnifiedCall>()
    val sendMessageCalls = mutableListOf<SendMessageCall>()
    val sendReactionCalls = mutableListOf<SendReactionCall>()
    val removeReactionCalls = mutableListOf<RemoveReactionCall>()
    val editMessageCalls = mutableListOf<EditMessageCall>()
    val unsendMessageCalls = mutableListOf<UnsendMessageCall>()
    val retryMessageCalls = mutableListOf<String>()
    val retryAsSmsCalls = mutableListOf<String>()
    val canRetryAsSmsCalls = mutableListOf<String>()
    val forwardMessageCalls = mutableListOf<ForwardMessageCall>()
    var resetUploadProgressCalled = false

    // ===== Interface Implementations =====

    override suspend fun sendUnified(
        chatGuid: String,
        text: String,
        replyToGuid: String?,
        effectId: String?,
        subject: String?,
        attachments: List<PendingAttachmentInput>,
        deliveryMode: MessageDeliveryMode,
        subscriptionId: Int,
        tempGuid: String?,
        attributedBodyJson: String?
    ): Result<MessageEntity> {
        sendUnifiedCalls.add(SendUnifiedCall(chatGuid, text, replyToGuid, effectId, subject, attachments, deliveryMode, subscriptionId, tempGuid))
        return sendUnifiedResult
    }

    override suspend fun sendMessage(
        chatGuid: String,
        text: String,
        replyToGuid: String?,
        effectId: String?,
        subject: String?,
        providedTempGuid: String?
    ): Result<MessageEntity> {
        sendMessageCalls.add(SendMessageCall(chatGuid, text, replyToGuid, effectId, subject, providedTempGuid))
        return sendMessageResult
    }

    override suspend fun sendReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String,
        selectedMessageText: String?,
        partIndex: Int
    ): Result<MessageEntity> {
        sendReactionCalls.add(SendReactionCall(chatGuid, messageGuid, reaction, selectedMessageText, partIndex))
        return sendReactionResult
    }

    override suspend fun removeReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String,
        selectedMessageText: String?,
        partIndex: Int
    ): Result<Unit> {
        removeReactionCalls.add(RemoveReactionCall(chatGuid, messageGuid, reaction, selectedMessageText, partIndex))
        return removeReactionResult
    }

    override suspend fun editMessage(
        chatGuid: String,
        messageGuid: String,
        newText: String,
        partIndex: Int
    ): Result<MessageEntity> {
        editMessageCalls.add(EditMessageCall(chatGuid, messageGuid, newText, partIndex))
        return editMessageResult
    }

    override suspend fun unsendMessage(
        chatGuid: String,
        messageGuid: String,
        partIndex: Int
    ): Result<Unit> {
        unsendMessageCalls.add(UnsendMessageCall(chatGuid, messageGuid, partIndex))
        return unsendMessageResult
    }

    override suspend fun retryMessage(messageGuid: String): Result<MessageEntity> {
        retryMessageCalls.add(messageGuid)
        return retryMessageResult
    }

    override suspend fun retryAsSms(messageGuid: String): Result<MessageEntity> {
        retryAsSmsCalls.add(messageGuid)
        return retryAsSmsResult
    }

    override suspend fun canRetryAsSms(messageGuid: String): Boolean {
        canRetryAsSmsCalls.add(messageGuid)
        return canRetryAsSmsResult
    }

    override suspend fun deleteFailedMessage(messageGuid: String) {
        // No-op in fake
    }

    override suspend fun forwardMessage(
        messageGuid: String,
        targetChatGuid: String
    ): Result<MessageEntity> {
        forwardMessageCalls.add(ForwardMessageCall(messageGuid, targetChatGuid))
        return forwardMessageResult
    }

    override fun resetUploadProgress() {
        resetUploadProgressCalled = true
        _uploadProgress.value = null
    }

    // ===== Test Helpers =====

    /**
     * Reset all recorded calls and configurable results to defaults.
     */
    fun reset() {
        sendUnifiedCalls.clear()
        sendMessageCalls.clear()
        sendReactionCalls.clear()
        removeReactionCalls.clear()
        editMessageCalls.clear()
        unsendMessageCalls.clear()
        retryMessageCalls.clear()
        retryAsSmsCalls.clear()
        canRetryAsSmsCalls.clear()
        forwardMessageCalls.clear()
        resetUploadProgressCalled = false
        _uploadProgress.value = null
    }

    /**
     * Simulate upload progress updates.
     */
    fun simulateUploadProgress(progress: UploadProgress?) {
        _uploadProgress.value = progress
    }

    // ===== Call Data Classes =====

    data class SendUnifiedCall(
        val chatGuid: String,
        val text: String,
        val replyToGuid: String?,
        val effectId: String?,
        val subject: String?,
        val attachments: List<PendingAttachmentInput>,
        val deliveryMode: MessageDeliveryMode,
        val subscriptionId: Int,
        val tempGuid: String?
    )

    data class SendMessageCall(
        val chatGuid: String,
        val text: String,
        val replyToGuid: String?,
        val effectId: String?,
        val subject: String?,
        val providedTempGuid: String?
    )

    data class SendReactionCall(
        val chatGuid: String,
        val messageGuid: String,
        val reaction: String,
        val selectedMessageText: String?,
        val partIndex: Int
    )

    data class RemoveReactionCall(
        val chatGuid: String,
        val messageGuid: String,
        val reaction: String,
        val selectedMessageText: String?,
        val partIndex: Int
    )

    data class EditMessageCall(
        val chatGuid: String,
        val messageGuid: String,
        val newText: String,
        val partIndex: Int
    )

    data class UnsendMessageCall(
        val chatGuid: String,
        val messageGuid: String,
        val partIndex: Int
    )

    data class ForwardMessageCall(
        val messageGuid: String,
        val targetChatGuid: String
    )
}
