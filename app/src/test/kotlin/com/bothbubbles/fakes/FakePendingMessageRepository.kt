package com.bothbubbles.fakes

import com.bothbubbles.data.local.db.entity.PendingMessageEntity
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.services.messaging.MessageDeliveryMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Fake implementation of PendingMessageRepository for testing.
 *
 * This fake records all queued messages and allows tests to verify
 * the send flow works correctly.
 *
 * Usage:
 * ```kotlin
 * val fakeRepo = FakePendingMessageRepository()
 *
 * // Use in test
 * val delegate = ChatSendDelegate(pendingMessageRepository = fakeRepo, ...)
 * delegate.sendMessage("Hello")
 *
 * // Verify
 * assertEquals(1, fakeRepo.queuedMessages.size)
 * assertEquals("Hello", fakeRepo.queuedMessages[0].text)
 * ```
 */
class FakePendingMessageRepository {

    // ===== Recorded State =====

    val queuedMessages = mutableListOf<QueuedMessage>()
    val retryMessageCalls = mutableListOf<String>()
    val cancelMessageCalls = mutableListOf<String>()

    // ===== Observable State =====

    private val _pendingForChat = MutableStateFlow<List<PendingMessageEntity>>(emptyList())

    // ===== Configurable Results =====

    var queueMessageResult: Result<String> = Result.success("temp-${UUID.randomUUID()}")
    var retryMessageResult: Result<Unit> = Result.success(Unit)
    var cancelMessageResult: Result<Unit> = Result.success(Unit)

    // ===== Interface Methods =====

    suspend fun queueMessage(
        chatGuid: String,
        text: String?,
        subject: String? = null,
        replyToGuid: String? = null,
        effectId: String? = null,
        attachments: List<PendingAttachmentInput> = emptyList(),
        deliveryMode: MessageDeliveryMode = MessageDeliveryMode.AUTO,
        forcedLocalId: String? = null
    ): Result<String> {
        val localId = forcedLocalId ?: "temp-${UUID.randomUUID()}"
        queuedMessages.add(
            QueuedMessage(
                localId = localId,
                chatGuid = chatGuid,
                text = text,
                subject = subject,
                replyToGuid = replyToGuid,
                effectId = effectId,
                attachmentCount = attachments.size,
                deliveryMode = deliveryMode
            )
        )
        return if (queueMessageResult.isSuccess) {
            Result.success(localId)
        } else {
            queueMessageResult
        }
    }

    suspend fun retryMessage(localId: String): Result<Unit> {
        retryMessageCalls.add(localId)
        return retryMessageResult
    }

    suspend fun cancelMessage(localId: String): Result<Unit> {
        cancelMessageCalls.add(localId)
        return cancelMessageResult
    }

    fun observePendingForChat(chatGuid: String): Flow<List<PendingMessageEntity>> {
        return _pendingForChat
    }

    fun observePendingCount(chatGuid: String): Flow<Int> {
        return MutableStateFlow(_pendingForChat.value.size)
    }

    // ===== Test Helpers =====

    fun reset() {
        queuedMessages.clear()
        retryMessageCalls.clear()
        cancelMessageCalls.clear()
        _pendingForChat.value = emptyList()
        queueMessageResult = Result.success("temp-${UUID.randomUUID()}")
        retryMessageResult = Result.success(Unit)
        cancelMessageResult = Result.success(Unit)
    }

    /**
     * Simulate pending messages from the database.
     */
    fun setPendingMessages(messages: List<PendingMessageEntity>) {
        _pendingForChat.value = messages
    }

    // ===== Data Classes =====

    data class QueuedMessage(
        val localId: String,
        val chatGuid: String,
        val text: String?,
        val subject: String?,
        val replyToGuid: String?,
        val effectId: String?,
        val attachmentCount: Int,
        val deliveryMode: MessageDeliveryMode
    )
}
