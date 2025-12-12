package com.bothbubbles.services.messaging

import android.util.Log
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.ReactionClassifier
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.dto.MessageDto
import com.bothbubbles.services.nameinference.NameInferenceService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service responsible for handling incoming messages from the server.
 *
 * This service processes messages received via Socket.IO or FCM push notifications,
 * ensuring proper deduplication and coordination with the local database.
 *
 * Key responsibilities:
 * - Message deduplication (handles race conditions between FCM and Socket.IO)
 * - Attachment synchronization for received messages
 * - Chat metadata updates (last message, unread counts)
 * - Name inference from incoming message patterns
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val attachmentDao: AttachmentDao,
    private val settingsDataStore: SettingsDataStore,
    private val nameInferenceService: NameInferenceService
) {
    companion object {
        private const val TAG = "IncomingMessageHandler"
    }

    /**
     * Handle a new message from server (via Socket.IO or push)
     *
     * This method is safe against duplicate processing - if the same message arrives
     * via both FCM and Socket.IO, the unread count will only be incremented once.
     */
    suspend fun handleIncomingMessage(messageDto: MessageDto, chatGuid: String): MessageEntity {
        val message = messageDto.toEntity(chatGuid)

        // CRITICAL: Check if message already exists BEFORE any side effects
        // This prevents duplicate unread count increments when message arrives via both FCM and Socket.IO
        val existingMessage = messageDao.getMessageByGuid(message.guid)
        if (existingMessage != null) {
            Log.d(TAG, "Message ${message.guid} already exists, skipping duplicate processing")
            return existingMessage
        }

        // Insert the message - use insertOrIgnore to handle race conditions
        // If another thread beats us, the insert will be ignored
        val insertResult = messageDao.insertMessage(message)
        if (insertResult == -1L) {
            // Another thread inserted this message first - return the existing one
            Log.d(TAG, "Message ${message.guid} was inserted by another thread")
            return messageDao.getMessageByGuid(message.guid) ?: message
        }

        // We successfully inserted - safe to update chat metadata
        // Only this thread will execute this block for this message
        if (!message.isFromMe) {
            chatDao.updateLastMessage(chatGuid, message.dateCreated, message.text)
            // Use atomic increment instead of read-modify-write to prevent race conditions
            chatDao.incrementUnreadCount(chatGuid)

            // Try to infer sender name from self-introduction patterns (e.g., "Hey it's John")
            message.handleId?.let { handleId ->
                nameInferenceService.processIncomingMessage(handleId.toLong(), message.text)
            }
        }

        // Sync attachments
        syncIncomingAttachments(messageDto)

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

    /**
     * Sync attachments for an incoming message to local database.
     * Incoming attachments are marked as PENDING for auto-download.
     */
    suspend fun syncIncomingAttachments(messageDto: MessageDto, tempMessageGuid: String? = null) {
        // Delete any temp attachments that were created for immediate display
        tempMessageGuid?.let { tempGuid ->
            attachmentDao.deleteAttachmentsForMessage(tempGuid)
        }

        if (messageDto.attachments.isNullOrEmpty()) return

        val serverAddress = settingsDataStore.serverAddress.first()

        messageDto.attachments.forEach { attachmentDto ->
            // webUrl is base download URL - AuthInterceptor adds guid param, AttachmentRepository adds original=true for stickers
            val webUrl = "$serverAddress/api/v1/attachment/${attachmentDto.guid}/download"

            // Determine transfer state based on direction:
            // - Outbound (isOutgoing=true): Already uploaded, mark as UPLOADED
            // - Inbound: Needs download, mark as PENDING for auto-download
            val transferState = if (attachmentDto.isOutgoing) {
                TransferState.UPLOADED.name
            } else {
                TransferState.PENDING.name
            }

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
                isSticker = attachmentDto.isSticker,
                webUrl = webUrl,
                transferState = transferState,
                transferProgress = if (attachmentDto.isOutgoing) 1f else 0f
            )
            attachmentDao.insertAttachment(attachment)
        }
    }

    /**
     * Convert a MessageDto to a MessageEntity
     */
    private fun MessageDto.toEntity(chatGuid: String): MessageEntity {
        // Determine message source based on handle's service or chat GUID prefix
        val source = when {
            handle?.service?.equals("SMS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            handle?.service?.equals("RCS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("SMS;-;") -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("RCS;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("mms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
            else -> MessageSource.IMESSAGE.name
        }

        return MessageEntity(
            guid = guid,
            chatGuid = chatGuid,
            handleId = handleId,
            senderAddress = handle?.address,
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
            messageSource = source,
            // Compute is_reaction using centralized logic for efficient SQL queries
            isReactionDb = ReactionClassifier.isReaction(associatedMessageGuid, associatedMessageType)
        )
    }
}
