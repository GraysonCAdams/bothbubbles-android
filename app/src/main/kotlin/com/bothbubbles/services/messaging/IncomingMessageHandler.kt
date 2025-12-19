package com.bothbubbles.services.messaging

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.ReactionClassifier
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.services.nameinference.NameInferenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val handleDao: HandleDao,
    private val attachmentDao: AttachmentDao,
    private val settingsDataStore: SettingsDataStore,
    private val nameInferenceService: NameInferenceService,
    private val api: BothBubblesApi,
    @ApplicationScope private val applicationScope: CoroutineScope
) : IncomingMessageProcessor {
    /**
     * Handle a new message from server (via Socket.IO or push)
     *
     * This method is safe against duplicate processing - if the same message arrives
     * via both FCM and Socket.IO, the unread count will only be incremented once.
     */
    override suspend fun handleIncomingMessage(messageDto: MessageDto, chatGuid: String): MessageEntity {
        val localHandleId = resolveLocalHandleId(messageDto)
        val message = messageDto.toEntity(chatGuid, localHandleId)

        // CRITICAL: Check if message already exists BEFORE any side effects
        // This prevents duplicate unread count increments when message arrives via both FCM and Socket.IO
        val existingMessage = messageDao.getMessageByGuid(message.guid)
        if (existingMessage != null) {
            Timber.d("Message ${message.guid} already exists, skipping duplicate processing")
            return existingMessage
        }

        // Insert the message - use insertOrIgnore to handle race conditions
        // If another thread beats us, the insert will be ignored
        val insertResult = messageDao.insertMessage(message)
        if (insertResult == -1L) {
            // Another thread inserted this message first - return the existing one
            Timber.d("Message ${message.guid} was inserted by another thread")
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
    override suspend fun handleMessageUpdate(messageDto: MessageDto, chatGuid: String) {
        val existingMessage = messageDao.getMessageByGuid(messageDto.guid)
        if (existingMessage != null) {
            val localHandleId = resolveLocalHandleId(messageDto)
            val updated = messageDto.toEntity(chatGuid, localHandleId).copy(id = existingMessage.id)
            messageDao.updateMessage(updated)
        }
    }

    /**
     * Sync attachments for an incoming message to local database.
     * Incoming attachments are marked as PENDING for auto-download.
     * Blurhash is fetched asynchronously to not block message processing.
     */
    override suspend fun syncIncomingAttachments(messageDto: MessageDto, tempMessageGuid: String?) {
        // Delete any temp attachments that were created for immediate display
        tempMessageGuid?.let { tempGuid ->
            attachmentDao.deleteAttachmentsForMessage(tempGuid)
        }

        val attachments = messageDto.attachments
        if (attachments.isNullOrEmpty()) return

        val serverAddress = settingsDataStore.serverAddress.first()
        val attachmentGuidsForBlurhash = mutableListOf<String>()

        attachments.forEach { attachmentDto ->
            // webUrl is base download URL - AuthInterceptor adds guid param, AttachmentRepository adds original=true for stickers
            val webUrl = "$serverAddress/api/v1/attachment/${attachmentDto.guid}/download"

            // Check if this attachment already exists (e.g., from IMessageSenderStrategy)
            val existingAttachment = attachmentDao.getAttachmentByGuid(attachmentDto.guid)

            Timber.d("[AttachmentSync] syncIncomingAttachments: guid=${attachmentDto.guid}, " +
                "existing=${existingAttachment != null}, existingMessageGuid=${existingAttachment?.messageGuid}, " +
                "incomingMessageGuid=${messageDto.guid}, existingLocalPath=${existingAttachment?.localPath}")

            if (existingAttachment != null) {
                // Attachment already exists - check if it's for the same message (duplicate) or different (self-message)
                if (existingAttachment.messageGuid == messageDto.guid) {
                    // Same message - true duplicate, skip
                    Timber.d("[AttachmentSync] syncIncomingAttachments: duplicate for same message, skipping")
                    return@forEach
                }

                // Different message (self-message case) - create a copy for this message
                // Use a modified GUID since attachment.guid must be unique
                Timber.d("[AttachmentSync] syncIncomingAttachments: self-message detected, creating copy for inbound")
                val inboundGuid = "${attachmentDto.guid}-inbound"

                // Check if we already created the inbound copy
                val existingInboundCopy = attachmentDao.getAttachmentByGuid(inboundGuid)
                if (existingInboundCopy != null) {
                    Timber.d("[AttachmentSync] syncIncomingAttachments: inbound copy already exists, skipping")
                    return@forEach
                }

                val inboundAttachment = AttachmentEntity(
                    guid = inboundGuid,
                    messageGuid = messageDto.guid,
                    originalRowId = attachmentDto.originalRowId,
                    uti = attachmentDto.uti,
                    mimeType = attachmentDto.mimeType,
                    transferName = attachmentDto.transferName,
                    totalBytes = attachmentDto.totalBytes,
                    isOutgoing = false,  // This is the received copy
                    hideAttachment = attachmentDto.hideAttachment,
                    width = attachmentDto.width,
                    height = attachmentDto.height,
                    hasLivePhoto = attachmentDto.hasLivePhoto,
                    isSticker = attachmentDto.isSticker,
                    webUrl = webUrl,
                    localPath = existingAttachment.localPath,  // Reuse existing local file!
                    transferState = if (existingAttachment.localPath != null) TransferState.DOWNLOADED.name else TransferState.PENDING.name,
                    transferProgress = if (existingAttachment.localPath != null) 1f else 0f
                )
                attachmentDao.insertAttachment(inboundAttachment)
                return@forEach
            }

            // Determine transfer state based on direction:
            // - Outbound (isOutgoing=true): Already uploaded, mark as UPLOADED
            // - Inbound: Needs download, mark as PENDING for auto-download
            val transferState = when {
                attachmentDto.isOutgoing -> TransferState.UPLOADED.name
                else -> TransferState.PENDING.name
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
                localPath = null,  // New inbound attachment - will be downloaded
                transferState = transferState,
                transferProgress = if (attachmentDto.isOutgoing) 1f else 0f
            )
            attachmentDao.insertAttachment(attachment)

            // Collect image/video attachments for blurhash fetching
            val mimeType = attachmentDto.mimeType ?: ""
            if (!attachmentDto.isOutgoing && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))) {
                attachmentGuidsForBlurhash.add(attachmentDto.guid)
            }
        }

        // Fetch blurhash asynchronously for inbound media attachments
        // This doesn't block message processing but provides colorful placeholders
        if (attachmentGuidsForBlurhash.isNotEmpty()) {
            applicationScope.launch {
                fetchBlurhashesForAttachments(attachmentGuidsForBlurhash)
            }
        }
    }

    /**
     * Fetch blurhash from server for the given attachments and update the database.
     * Called asynchronously to not block message processing.
     */
    private suspend fun fetchBlurhashesForAttachments(guids: List<String>) {
        for (guid in guids) {
            try {
                val response = api.getAttachmentBlurhash(guid)
                if (response.isSuccessful) {
                    val blurhash = response.body()?.data
                    if (!blurhash.isNullOrBlank()) {
                        attachmentDao.updateBlurhash(guid, blurhash)
                        Timber.d("Updated blurhash for attachment $guid")
                    }
                }
            } catch (e: Exception) {
                // Blurhash is optional - don't fail on errors
                Timber.w(e, "Failed to fetch blurhash for attachment $guid")
            }
        }
    }

    /**
     * Resolve the local handle ID from server data.
     *
     * The server sends its internal handle row ID (handleId), but our local database
     * may have a different ID for the same handle. We need to map server ID -> local ID.
     *
     * Strategy:
     * 1. If embedded handle has address+service, look up by those (most reliable)
     * 2. Otherwise, look up by server's original_row_id
     * 3. Fall back to null if no match found
     */
    private suspend fun resolveLocalHandleId(messageDto: MessageDto): Long? {
        // First try: embedded handle with address and service (most reliable)
        messageDto.handle?.let { handleDto ->
            val localHandle = handleDao.getHandleByAddressAndService(
                handleDto.address,
                handleDto.service
            )
            if (localHandle != null) {
                return localHandle.id
            }
            // Try any service if exact match not found
            val anyHandle = handleDao.getHandleByAddressAny(handleDto.address)
            if (anyHandle != null) {
                return anyHandle.id
            }
        }

        // Second try: look up by server's original_row_id
        messageDto.handleId?.let { serverHandleId ->
            val localHandle = handleDao.getHandleByOriginalRowId(serverHandleId.toInt())
            if (localHandle != null) {
                return localHandle.id
            }
        }

        // No match found - this is okay for sent messages (isFromMe=true)
        return null
    }

    /**
     * Convert a MessageDto to a MessageEntity
     */
    private fun MessageDto.toEntity(chatGuid: String, localHandleId: Long?): MessageEntity {
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
            handleId = localHandleId,
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
