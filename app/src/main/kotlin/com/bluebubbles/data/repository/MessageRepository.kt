package com.bluebubbles.data.repository

import android.net.Uri
import com.bluebubbles.data.local.db.dao.AttachmentDao
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.AttachmentEntity
import com.bluebubbles.data.local.db.entity.MessageEntity
import com.bluebubbles.data.local.db.entity.MessageSource
import com.bluebubbles.data.remote.api.BlueBubblesApi
import com.bluebubbles.data.remote.api.dto.EditMessageRequest
import com.bluebubbles.data.remote.api.dto.MessageDto
import com.bluebubbles.data.remote.api.dto.SendMessageRequest
import com.bluebubbles.data.remote.api.dto.SendReactionRequest
import com.bluebubbles.data.remote.api.dto.UnsendMessageRequest
import com.bluebubbles.services.sms.MmsSendService
import com.bluebubbles.services.sms.SmsSendService
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Message delivery mode - determines how messages are routed
 */
enum class MessageDeliveryMode {
    IMESSAGE,      // Via BlueBubbles server (iMessage/SMS via Mac)
    LOCAL_SMS,     // Direct SMS from Android device
    LOCAL_MMS,     // Direct MMS from Android device
    AUTO           // Auto-select based on chat type
}

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val attachmentDao: AttachmentDao,
    private val api: BlueBubblesApi,
    private val smsSendService: SmsSendService,
    private val mmsSendService: MmsSendService
) {
    // ===== Local Operations =====

    fun observeMessagesForChat(chatGuid: String, limit: Int, offset: Int): Flow<List<MessageEntity>> =
        messageDao.observeMessagesForChat(chatGuid, limit, offset)

    fun observeMessage(guid: String): Flow<MessageEntity?> =
        messageDao.observeMessageByGuid(guid)

    suspend fun getMessage(guid: String): MessageEntity? =
        messageDao.getMessageByGuid(guid)

    fun getReactionsForMessage(messageGuid: String): Flow<List<MessageEntity>> =
        messageDao.getReactionsForMessage(messageGuid)

    fun getRepliesForMessage(messageGuid: String): Flow<List<MessageEntity>> =
        messageDao.getRepliesForMessage(messageGuid)

    fun searchMessages(query: String, limit: Int = 100): Flow<List<MessageEntity>> =
        messageDao.searchMessages(query, limit)

    // ===== Remote Operations =====

    /**
     * Fetch messages for a chat from server
     */
    suspend fun syncMessagesForChat(
        chatGuid: String,
        limit: Int = 50,
        offset: Int = 0,
        after: Long? = null,
        before: Long? = null
    ): Result<List<MessageEntity>> = runCatching {
        val response = api.getChatMessages(
            guid = chatGuid,
            limit = limit,
            offset = offset,
            sort = "DESC"
        )

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            throw Exception(body?.message ?: "Failed to fetch messages")
        }

        val messages = body.data.orEmpty().map { it.toEntity(chatGuid) }

        // Insert messages and their attachments
        messages.forEach { message ->
            messageDao.insertOrUpdateMessage(message)
        }

        // Sync attachments
        body.data.orEmpty().forEach { messageDto ->
            syncMessageAttachments(messageDto)
        }

        messages
    }

    /**
     * Send a new message
     */
    suspend fun sendMessage(
        chatGuid: String,
        text: String,
        replyToGuid: String? = null,
        effectId: String? = null,
        subject: String? = null
    ): Result<MessageEntity> = runCatching {
        // Create temporary message for immediate UI feedback
        val tempGuid = "temp-${UUID.randomUUID()}"
        val tempMessage = MessageEntity(
            guid = tempGuid,
            chatGuid = chatGuid,
            text = text,
            subject = subject,
            dateCreated = System.currentTimeMillis(),
            isFromMe = true,
            threadOriginatorGuid = replyToGuid,
            expressiveSendStyleId = effectId,
            messageSource = MessageSource.IMESSAGE.name
        )
        messageDao.insertMessage(tempMessage)

        // Update chat's last message
        chatDao.updateLastMessage(chatGuid, System.currentTimeMillis(), text)

        // Send to server
        val response = api.sendMessage(
            SendMessageRequest(
                chatGuid = chatGuid,
                message = text,
                tempGuid = tempGuid,
                selectedMessageGuid = replyToGuid,
                effectId = effectId,
                subject = subject
            )
        )

        val body = response.body()
        if (!response.isSuccessful || body == null || body.status != 200) {
            // Mark message as failed
            messageDao.updateErrorStatus(tempGuid, 1)
            throw Exception(body?.message ?: "Failed to send message")
        }

        // Replace temp GUID with server GUID
        val serverMessage = body.data
        if (serverMessage != null) {
            messageDao.replaceGuid(tempGuid, serverMessage.guid)
            serverMessage.toEntity(chatGuid)
        } else {
            // Server didn't return the message, mark as sent
            messageDao.updateErrorStatus(tempGuid, 0)
            tempMessage
        }
    }

    /**
     * Send a reaction/tapback
     */
    suspend fun sendReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String, // e.g., "love", "like", "dislike", "laugh", "emphasize", "question"
        partIndex: Int = 0
    ): Result<MessageEntity> = runCatching {
        val response = api.sendReaction(
            SendReactionRequest(
                chatGuid = chatGuid,
                selectedMessageGuid = messageGuid,
                reaction = reaction,
                partIndex = partIndex
            )
        )

        val body = response.body()
        if (!response.isSuccessful || body == null || body.status != 200) {
            throw Exception(body?.message ?: "Failed to send reaction")
        }

        val reactionMessage = body.data ?: throw Exception("No reaction returned")
        val entity = reactionMessage.toEntity(chatGuid)
        messageDao.insertMessage(entity)

        // Update parent message to show it has reactions
        messageDao.updateReactionStatus(messageGuid, true)

        entity
    }

    /**
     * Remove a reaction - sends the same reaction again to toggle it off
     */
    suspend fun removeReaction(
        chatGuid: String,
        messageGuid: String,
        reaction: String,
        partIndex: Int = 0
    ): Result<Unit> = runCatching {
        // In iMessage, sending the same reaction again removes it
        val removeReaction = "-$reaction" // Prefix with - to remove
        api.sendReaction(
            SendReactionRequest(
                chatGuid = chatGuid,
                selectedMessageGuid = messageGuid,
                reaction = removeReaction,
                partIndex = partIndex
            )
        )
        Unit
    }

    /**
     * Edit a message (iOS 16+)
     */
    suspend fun editMessage(
        chatGuid: String,
        messageGuid: String,
        newText: String,
        partIndex: Int = 0
    ): Result<MessageEntity> = runCatching {
        val response = api.editMessage(
            guid = messageGuid,
            request = EditMessageRequest(
                editedMessage = newText,
                partIndex = partIndex
            )
        )

        val body = response.body()
        if (!response.isSuccessful || body == null || body.status != 200) {
            throw Exception(body?.message ?: "Failed to edit message")
        }

        // Update local message
        messageDao.updateMessageText(messageGuid, newText, System.currentTimeMillis())

        messageDao.getMessageByGuid(messageGuid) ?: throw Exception("Message not found")
    }

    /**
     * Unsend a message (iOS 16+)
     */
    suspend fun unsendMessage(
        chatGuid: String,
        messageGuid: String,
        partIndex: Int = 0
    ): Result<Unit> = runCatching {
        api.unsendMessage(
            guid = messageGuid,
            request = UnsendMessageRequest(partIndex = partIndex)
        )

        // Soft delete locally
        messageDao.softDeleteMessage(messageGuid)
    }

    /**
     * Retry sending a failed message
     */
    suspend fun retryMessage(messageGuid: String): Result<MessageEntity> = runCatching {
        val message = messageDao.getMessageByGuid(messageGuid)
            ?: throw Exception("Message not found")

        // Reset error status
        messageDao.updateErrorStatus(messageGuid, 0)

        // Determine delivery mode from original message source
        val deliveryMode = when (message.messageSource) {
            MessageSource.LOCAL_SMS.name -> MessageDeliveryMode.LOCAL_SMS
            MessageSource.LOCAL_MMS.name -> MessageDeliveryMode.LOCAL_MMS
            else -> MessageDeliveryMode.IMESSAGE
        }

        // Re-send
        sendUnified(
            chatGuid = message.chatGuid,
            text = message.text ?: "",
            replyToGuid = message.threadOriginatorGuid,
            effectId = message.expressiveSendStyleId,
            subject = message.subject,
            deliveryMode = deliveryMode
        ).getOrThrow()
    }

    // ===== Unified Send Operations =====

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
        subscriptionId: Int = -1
    ): Result<MessageEntity> {
        // Determine actual delivery mode
        val actualMode = when (deliveryMode) {
            MessageDeliveryMode.AUTO -> determineDeliveryMode(chatGuid, attachments.isNotEmpty())
            else -> deliveryMode
        }

        return when (actualMode) {
            MessageDeliveryMode.LOCAL_SMS -> sendLocalSms(chatGuid, text, subscriptionId)
            MessageDeliveryMode.LOCAL_MMS -> sendLocalMms(chatGuid, text, attachments, subject, subscriptionId)
            else -> sendMessage(chatGuid, text, replyToGuid, effectId, subject)
        }
    }

    /**
     * Send a message via local SMS
     */
    private suspend fun sendLocalSms(
        chatGuid: String,
        text: String,
        subscriptionId: Int = -1
    ): Result<MessageEntity> {
        // Extract address from chat GUID (format: "sms;-;+1234567890")
        val address = extractAddressFromChatGuid(chatGuid)
            ?: return Result.failure(Exception("Invalid chat GUID for SMS"))

        return smsSendService.sendSms(address, text, chatGuid, subscriptionId)
    }

    /**
     * Send a message via local MMS
     */
    private suspend fun sendLocalMms(
        chatGuid: String,
        text: String?,
        attachments: List<Uri>,
        subject: String? = null,
        subscriptionId: Int = -1
    ): Result<MessageEntity> {
        // Extract addresses from chat GUID
        val addresses = extractAddressesFromChatGuid(chatGuid)
        if (addresses.isEmpty()) {
            return Result.failure(Exception("Invalid chat GUID for MMS"))
        }

        return mmsSendService.sendMms(
            recipients = addresses,
            text = text,
            attachments = attachments,
            chatGuid = chatGuid,
            subject = subject,
            subscriptionId = subscriptionId
        )
    }

    /**
     * Determine the best delivery mode for a chat
     */
    private suspend fun determineDeliveryMode(
        chatGuid: String,
        hasAttachments: Boolean
    ): MessageDeliveryMode {
        val chat = chatDao.getChatByGuid(chatGuid)

        // Check if it's a local SMS/MMS chat
        if (chatGuid.startsWith("sms;-;") || chatGuid.startsWith("mms;-;")) {
            // Use MMS if group or has attachments
            return if (chat?.isGroup == true || hasAttachments) {
                MessageDeliveryMode.LOCAL_MMS
            } else {
                MessageDeliveryMode.LOCAL_SMS
            }
        }

        // Default to iMessage for other chats
        return MessageDeliveryMode.IMESSAGE
    }

    /**
     * Extract a single address from a chat GUID (for SMS)
     */
    private fun extractAddressFromChatGuid(chatGuid: String): String? {
        // Format: "sms;-;+1234567890" or "iMessage;-;+1234567890"
        val parts = chatGuid.split(";-;")
        return if (parts.size == 2) parts[1] else null
    }

    /**
     * Extract multiple addresses from a chat GUID (for MMS groups)
     */
    private fun extractAddressesFromChatGuid(chatGuid: String): List<String> {
        val parts = chatGuid.split(";-;")
        if (parts.size != 2) return emptyList()

        // For group MMS: "mms;-;+1234567890,+0987654321"
        return parts[1].split(",").filter { it.isNotBlank() }
    }

    /**
     * Check if a chat is a local SMS/MMS chat
     */
    fun isLocalSmsChat(chatGuid: String): Boolean {
        return chatGuid.startsWith("sms;-;") || chatGuid.startsWith("mms;-;")
    }

    /**
     * Get the message source type for a chat
     */
    suspend fun getMessageSourceForChat(chatGuid: String): MessageSource {
        return when {
            chatGuid.startsWith("sms;-;") -> MessageSource.LOCAL_SMS
            chatGuid.startsWith("mms;-;") -> MessageSource.LOCAL_MMS
            else -> MessageSource.IMESSAGE
        }
    }

    /**
     * Delete a message locally
     */
    suspend fun deleteMessageLocally(messageGuid: String) {
        messageDao.deleteMessage(messageGuid)
    }

    // ===== Incoming Message Handling =====

    /**
     * Handle a new message from server (via Socket.IO or push)
     */
    suspend fun handleIncomingMessage(messageDto: MessageDto, chatGuid: String): MessageEntity {
        val message = messageDto.toEntity(chatGuid)
        messageDao.insertOrUpdateMessage(message)

        // Update chat's last message and unread count
        if (!message.isFromMe) {
            val chat = chatDao.getChatByGuid(chatGuid)
            chatDao.updateLastMessage(chatGuid, message.dateCreated, message.text)
            chatDao.updateUnreadCount(chatGuid, (chat?.unreadCount ?: 0) + 1)
        }

        // Sync attachments
        syncMessageAttachments(messageDto)

        return message
    }

    /**
     * Handle message update (read receipt, delivery, edit, etc.)
     */
    suspend fun handleMessageUpdate(messageDto: MessageDto, chatGuid: String) {
        val existingMessage = messageDao.getMessageByGuid(messageDto.guid)
        if (existingMessage != null) {
            val updated = messageDto.toEntity(chatGuid).copy(id = existingMessage.id)
            messageDao.updateMessage(updated)
        }
    }

    // ===== Private Helpers =====

    private suspend fun syncMessageAttachments(messageDto: MessageDto) {
        messageDto.attachments?.forEach { attachmentDto ->
            val attachment = AttachmentEntity(
                guid = attachmentDto.guid,
                messageGuid = messageDto.guid,
                originalRowId = attachmentDto.originalRowId,
                uti = attachmentDto.uti,
                mimeType = attachmentDto.mimeType,
                transferName = attachmentDto.transferName,
                totalBytes = attachmentDto.totalBytes,
                isOutgoing = attachmentDto.isOutgoing,
                hideAttachment = attachmentDto.hideAttachment,
                width = attachmentDto.width,
                height = attachmentDto.height,
                hasLivePhoto = attachmentDto.hasLivePhoto,
                isSticker = attachmentDto.isSticker
            )
            attachmentDao.insertAttachment(attachment)
        }
    }

    private fun MessageDto.toEntity(chatGuid: String): MessageEntity {
        return MessageEntity(
            guid = guid,
            chatGuid = chatGuid,
            handleId = handleId,
            text = text,
            subject = subject,
            dateCreated = dateCreated ?: System.currentTimeMillis(),
            dateRead = dateRead,
            dateDelivered = dateDelivered,
            dateEdited = dateEdited,
            datePlayed = datePlayed,
            isFromMe = isFromMe,
            error = error,
            itemType = itemType,
            groupTitle = groupTitle,
            groupActionType = groupActionType,
            balloonBundleId = balloonBundleId,
            associatedMessageGuid = associatedMessageGuid,
            associatedMessagePart = associatedMessagePart,
            associatedMessageType = associatedMessageType,
            expressiveSendStyleId = expressiveSendStyleId,
            threadOriginatorGuid = threadOriginatorGuid,
            threadOriginatorPart = threadOriginatorPart,
            hasAttachments = attachments?.isNotEmpty() == true,
            hasReactions = hasReactions,
            bigEmoji = bigEmoji,
            wasDeliveredQuietly = wasDeliveredQuietly,
            didNotifyRecipient = didNotifyRecipient,
            messageSource = MessageSource.IMESSAGE.name
        )
    }
}
