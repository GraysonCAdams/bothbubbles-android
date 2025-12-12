package com.bothbubbles.services.messaging

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.ProgressRequestBody
import com.bothbubbles.data.remote.api.dto.EditMessageRequest
import com.bothbubbles.data.remote.api.dto.MessageDto
import com.bothbubbles.data.remote.api.dto.SendMessageRequest
import com.bothbubbles.data.remote.api.dto.SendReactionRequest
import com.bothbubbles.data.remote.api.dto.UnsendMessageRequest
import com.bothbubbles.services.media.VideoCompressor
import com.bothbubbles.services.sms.MmsSendService
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.services.sms.SmsSendService
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
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

/**
 * Represents the progress of an attachment upload
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
 * Service responsible for sending messages via various channels.
 *
 * This service encapsulates all message sending logic including:
 * - iMessage via BlueBubbles server
 * - Local SMS/MMS via Android's telephony APIs
 * - Attachment uploading with compression
 * - Delivery mode determination and fallback handling
 *
 * This is part of the services layer and coordinates between:
 * - Data layer (DAOs for persistence)
 * - Remote API (BlueBubbles server)
 * - SMS/MMS services (carrier messaging)
 */
@Singleton
class MessageSendingService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val attachmentDao: AttachmentDao,
    private val api: BothBubblesApi,
    private val smsSendService: SmsSendService,
    private val mmsSendService: MmsSendService,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore,
    private val chatFallbackTracker: ChatFallbackTracker,
    private val videoCompressor: VideoCompressor
) {
    companion object {
        private const val TAG = "MessageSendingService"
    }

    // ===== Upload Progress Tracking =====

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    /**
     * Reset upload progress state
     */
    fun resetUploadProgress() {
        _uploadProgress.value = null
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
        subscriptionId: Int = -1,
        tempGuid: String? = null // Stable ID for retry idempotency
    ): Result<MessageEntity> {
        // Determine actual delivery mode
        val actualMode = when (deliveryMode) {
            MessageDeliveryMode.AUTO -> determineDeliveryMode(chatGuid, attachments.isNotEmpty())
            else -> deliveryMode
        }

        return when (actualMode) {
            MessageDeliveryMode.LOCAL_SMS -> {
                ensureCarrierReady()?.let { return Result.failure(it) }
                sendLocalSms(chatGuid, text, subscriptionId)
            }
            MessageDeliveryMode.LOCAL_MMS -> {
                ensureCarrierReady(requireMms = true)?.let { return Result.failure(it) }
                sendLocalMms(chatGuid, text, attachments, subject, subscriptionId)
            }
            else -> {
                // iMessage mode - handle attachments via BlueBubbles API
                if (attachments.isNotEmpty()) {
                    sendIMessageWithAttachments(chatGuid, text, attachments, replyToGuid, effectId, subject, tempGuid)
                } else {
                    sendMessage(chatGuid, text, replyToGuid, effectId, subject, tempGuid)
                }
            }
        }
    }

    /**
     * Send a new message via iMessage.
     * If the send fails and autoRetryAsSms is enabled, automatically retries via SMS.
     */
    suspend fun sendMessage(
        chatGuid: String,
        text: String,
        replyToGuid: String? = null,
        effectId: String? = null,
        subject: String? = null,
        providedTempGuid: String? = null // Stable ID for retry idempotency
    ): Result<MessageEntity> = runCatching {
        // Use provided tempGuid if available (for retries), otherwise generate new one
        val tempGuid = providedTempGuid ?: "temp-${UUID.randomUUID()}"

        // Check if this is a retry (temp message already exists)
        val existingMessage = messageDao.getByGuid(tempGuid)
        if (existingMessage != null) {
            Log.d(TAG, "Retry detected for text message tempGuid=$tempGuid, checking if already sent")
            // If the message was already successfully sent (has no error), return it
            if (existingMessage.error == 0) {
                return@runCatching existingMessage
            }
            // Otherwise continue to retry sending
        }

        // Create temporary message for immediate UI feedback (only if not retry)
        if (existingMessage == null) {
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
        } else {
            Log.d(TAG, "Retry: reusing existing temp message for $tempGuid")
        }

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
            // iMessage send failed - check if we should auto-retry as SMS
            val autoRetry = settingsDataStore.autoRetryAsSms.first()
            val address = extractAddressFromChatGuid(chatGuid)
            val canFallback = address?.isPhoneNumber() == true

            if (autoRetry && canFallback) {
                ensureCarrierReadyOrThrow()
                Log.i(TAG, "iMessage failed, auto-retrying as SMS: $tempGuid")

                // Update message source to SMS and clear error
                messageDao.updateMessageSource(tempGuid, MessageSource.LOCAL_SMS.name)
                messageDao.updateErrorStatus(tempGuid, 0)

                // Enter fallback mode for this chat
                chatFallbackTracker.enterFallbackMode(chatGuid, FallbackReason.IMESSAGE_FAILED)

                // Send via local SMS
                val smsResult = smsSendService.sendSms(address, text, chatGuid)
                if (smsResult.isSuccess) {
                    // Replace the temp message with the SMS message
                    messageDao.deleteMessage(tempGuid)
                    return@runCatching smsResult.getOrThrow()
                } else {
                    // SMS also failed - mark as failed
                    messageDao.updateErrorStatus(tempGuid, 1)
                    throw smsResult.exceptionOrNull() ?: Exception("SMS send failed")
                }
            } else {
                // Mark message as failed (no auto-retry)
                messageDao.updateErrorStatus(tempGuid, 1)
                throw Exception(body?.message ?: "Failed to send message")
            }
        }

        // Replace temp GUID with server GUID
        val serverMessage = body.data
        if (serverMessage != null) {
            // Once we have a server response, the message was sent successfully
            // Wrap post-send operations so failures don't cause duplicate sends on retry
            try {
                messageDao.replaceGuid(tempGuid, serverMessage.guid)
            } catch (e: Exception) {
                Log.w(TAG, "Non-critical error replacing GUID after successful send: ${e.message}")
            }
            serverMessage.toEntity(chatGuid)
        } else {
            // Server didn't return the message, mark as sent
            messageDao.updateErrorStatus(tempGuid, 0)
            // Return existing message on retry, or fetch the one we just created
            existingMessage ?: messageDao.getByGuid(tempGuid)
                ?: throw Exception("Failed to find temp message: $tempGuid")
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

    /**
     * Retry sending a failed iMessage as SMS/MMS.
     * This marks the original message as superseded and sends a new message via SMS.
     */
    suspend fun retryAsSms(messageGuid: String): Result<MessageEntity> = runCatching {
        val message = messageDao.getMessageByGuid(messageGuid)
            ?: throw Exception("Message not found")

        val chat = chatDao.getChatByGuid(message.chatGuid)

        // Check if we can send SMS for this chat
        val address = extractAddressFromChatGuid(message.chatGuid)
        if (address == null || !address.isPhoneNumber()) {
            throw Exception("Cannot send SMS to this contact (no phone number)")
        }

        // Soft delete the original failed message
        messageDao.softDeleteMessage(messageGuid)

        // Enter fallback mode for this chat
        chatFallbackTracker.enterFallbackMode(message.chatGuid, FallbackReason.IMESSAGE_FAILED)

        // Determine SMS/MMS based on whether it had attachments or is group
        val deliveryMode = if (message.hasAttachments || chat?.isGroup == true) {
            MessageDeliveryMode.LOCAL_MMS
        } else {
            MessageDeliveryMode.LOCAL_SMS
        }

        ensureCarrierReadyOrThrow(requireMms = deliveryMode == MessageDeliveryMode.LOCAL_MMS)

        Log.i(TAG, "Retrying message $messageGuid as $deliveryMode")

        // Re-send via SMS/MMS
        sendUnified(
            chatGuid = message.chatGuid,
            text = message.text ?: "",
            subject = message.subject,
            deliveryMode = deliveryMode
        ).getOrThrow()
    }

    /**
     * Check if a failed iMessage can be retried as SMS
     */
    suspend fun canRetryAsSms(messageGuid: String): Boolean {
        val message = messageDao.getMessageByGuid(messageGuid) ?: return false

        // Only failed iMessages can be retried as SMS
        if (message.error == 0 || message.messageSource != MessageSource.IMESSAGE.name) {
            return false
        }

        // Check if we have a phone number
        val address = extractAddressFromChatGuid(message.chatGuid)
        return address?.isPhoneNumber() == true
    }

    /**
     * Forward a message to another conversation.
     * Copies the message text and attachments to the target chat.
     */
    suspend fun forwardMessage(
        messageGuid: String,
        targetChatGuid: String
    ): Result<MessageEntity> = runCatching {
        val originalMessage = messageDao.getMessageByGuid(messageGuid)
            ?: throw Exception("Original message not found")

        // Get attachments for the original message
        val attachments = attachmentDao.getAttachmentsForMessage(messageGuid)

        // Convert attachment entities to URIs if they have local paths
        val attachmentUris = attachments.mapNotNull { attachment ->
            attachment.localPath?.let { path ->
                try {
                    Uri.parse(path)
                } catch (e: Exception) {
                    null
                }
            } ?: attachment.webUrl?.let { url ->
                // For web URLs, we'll need to download first or just skip for now
                // In practice, most forwarded messages will have local paths
                null
            }
        }

        Log.d(TAG, "Forwarding message $messageGuid to $targetChatGuid with ${attachmentUris.size} attachments")

        // Send the message to the target chat
        sendUnified(
            chatGuid = targetChatGuid,
            text = originalMessage.text ?: "",
            subject = originalMessage.subject,
            attachments = attachmentUris,
            deliveryMode = MessageDeliveryMode.AUTO
        ).getOrThrow()
    }

    // ===== Private Send Methods =====

    /**
     * Send a message with attachments via BlueBubbles API.
     * Uploads attachments first, then sends the text message.
     */
    private suspend fun sendIMessageWithAttachments(
        chatGuid: String,
        text: String,
        attachments: List<Uri>,
        replyToGuid: String? = null,
        effectId: String? = null,
        subject: String? = null,
        providedTempGuid: String? = null // Stable ID for retry idempotency
    ): Result<MessageEntity> = runCatching {
        // Use provided tempGuid if available (for retries), otherwise generate new one
        val tempGuid = providedTempGuid ?: "temp-${UUID.randomUUID()}"

        // Check if this is a retry (temp message already exists)
        val existingMessage = messageDao.getByGuid(tempGuid)
        val isRetry = existingMessage != null

        // Build attachment GUIDs list
        val tempAttachmentGuids = attachments.indices.map { index -> "$tempGuid-att-$index" }

        if (!isRetry) {
            // First attempt - create temporary message for immediate UI feedback
            val tempMessage = MessageEntity(
                guid = tempGuid,
                chatGuid = chatGuid,
                text = text.ifBlank { null },
                subject = subject,
                dateCreated = System.currentTimeMillis(),
                isFromMe = true,
                hasAttachments = true,
                threadOriginatorGuid = replyToGuid,
                expressiveSendStyleId = effectId,
                messageSource = MessageSource.IMESSAGE.name
            )
            messageDao.insertMessage(tempMessage)

            // Create temporary attachment records for immediate display
            attachments.forEachIndexed { index, uri ->
                val tempAttGuid = tempAttachmentGuids[index]

                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = getFileName(uri) ?: "attachment"
                val (width, height) = getMediaDimensions(uri, mimeType)

                val tempAttachment = AttachmentEntity(
                    guid = tempAttGuid,
                    messageGuid = tempGuid,
                    mimeType = mimeType,
                    transferName = fileName,
                    isOutgoing = true,
                    localPath = uri.toString(),
                    width = width,
                    height = height,
                    transferState = TransferState.UPLOADING.name,
                    transferProgress = 0f
                )
                attachmentDao.insertAttachment(tempAttachment)
            }
        } else {
            Log.d(TAG, "Retry detected for tempGuid=$tempGuid, reusing existing temp records")
        }

        // Update chat's last message
        chatDao.updateLastMessage(chatGuid, System.currentTimeMillis(), text.ifBlank { "[Attachment]" })

        var lastResponse: MessageDto? = null

        // Upload each attachment with progress tracking
        // On retry, skip attachments that were already successfully uploaded
        val totalAttachments = attachments.size
        attachments.forEachIndexed { index, uri ->
            val tempAttGuid = tempAttachmentGuids[index]

            // Check if this attachment was already uploaded (retry case)
            val existingAttachment = attachmentDao.getByGuid(tempAttGuid)
            if (existingAttachment?.transferState == TransferState.UPLOADED.name) {
                Log.d(TAG, "Skipping already-uploaded attachment: $tempAttGuid")
                return@forEachIndexed
            }

            val attachmentResult = uploadAttachment(
                chatGuid = chatGuid,
                tempGuid = tempAttGuid,
                uri = uri,
                attachmentIndex = index,
                totalAttachments = totalAttachments
            )
            if (attachmentResult.isFailure) {
                // Mark message and attachment as failed
                messageDao.updateErrorStatus(tempGuid, 1)
                attachmentDao.markTransferFailed(tempAttGuid)
                _uploadProgress.value = null
                throw attachmentResult.exceptionOrNull() ?: Exception("Failed to upload attachment")
            }

            // Mark this attachment as uploaded
            attachmentDao.markUploaded(tempAttGuid)
            lastResponse = attachmentResult.getOrNull()
        }

        // Reset progress after all attachments uploaded
        _uploadProgress.value = null

        // Send text message if present
        if (text.isNotBlank()) {
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
                messageDao.updateErrorStatus(tempGuid, 1)
                throw Exception(body?.message ?: "Failed to send message")
            }
            lastResponse = body.data
        }

        // Replace temp GUID with server GUID if we got one
        lastResponse?.let { serverMessage ->
            // Once we have a server response, the message was sent successfully
            // Wrap post-send operations so failures don't cause duplicate sends on retry
            try {
                messageDao.replaceGuid(tempGuid, serverMessage.guid)
                // Sync attachments from the server response to local database
                syncOutboundAttachments(serverMessage, tempMessageGuid = tempGuid)
            } catch (e: Exception) {
                // Log but don't throw - message was sent, these are non-critical cleanup ops
                Log.w(TAG, "Non-critical error after successful send: ${e.message}")
            }
            serverMessage.toEntity(chatGuid)
        } ?: run {
            messageDao.updateErrorStatus(tempGuid, 0)
            // Return existing message on retry, or fetch it if we just created it
            existingMessage ?: messageDao.getByGuid(tempGuid)
                ?: throw Exception("Failed to find temp message: $tempGuid")
        }
    }

    /**
     * Upload a single attachment to BlueBubbles server with progress tracking.
     * Videos are compressed before upload if compression is enabled.
     */
    private suspend fun uploadAttachment(
        chatGuid: String,
        tempGuid: String,
        uri: Uri,
        attachmentIndex: Int = 0,
        totalAttachments: Int = 1
    ): Result<MessageDto> = runCatching {
        val contentResolver = context.contentResolver

        // Get file name from URI
        var fileName = getFileName(uri) ?: "attachment"

        // Get MIME type
        var mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        // Check if this is a video that should be compressed
        val isVideo = mimeType.startsWith("video/")
        val shouldCompress = isVideo && settingsDataStore.compressVideosBeforeUpload.first()

        val bytes: ByteArray
        var compressedPath: String? = null

        if (shouldCompress) {
            // Get compression quality from settings
            val qualityStr = settingsDataStore.videoCompressionQuality.first()
            val quality = when (qualityStr) {
                "original" -> VideoCompressor.Companion.Quality.ORIGINAL
                "high" -> VideoCompressor.Companion.Quality.HIGH
                "medium" -> VideoCompressor.Companion.Quality.MEDIUM
                "low" -> VideoCompressor.Companion.Quality.LOW
                else -> VideoCompressor.Companion.Quality.MEDIUM
            }

            Log.d(TAG, "Compressing video with quality: $qualityStr")

            compressedPath = videoCompressor.compress(uri, quality) { progress ->
                // Report compression progress (0-50% of total upload progress)
                _uploadProgress.value = UploadProgress(
                    fileName = fileName,
                    bytesUploaded = (progress * 50).toLong(),
                    totalBytes = 100,
                    attachmentIndex = attachmentIndex,
                    totalAttachments = totalAttachments
                )
            }

            if (compressedPath != null) {
                val compressedFile = File(compressedPath)
                bytes = compressedFile.readBytes()
                mimeType = "video/mp4"
                fileName = fileName.substringBeforeLast('.') + ".mp4"
                Log.d(TAG, "Video compressed: ${compressedFile.length() / 1024} KB")
            } else {
                // Compression failed, use original
                Log.w(TAG, "Video compression failed, using original")
                bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Cannot read attachment")
            }
        } else {
            // Read file bytes directly
            bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Cannot read attachment")
        }

        Log.d(TAG, "Uploading attachment: $fileName ($mimeType, ${bytes.size} bytes)")

        // Create progress-tracking request body
        val baseRequestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val progressRequestBody = ProgressRequestBody(baseRequestBody) { bytesWritten, contentLength ->
            _uploadProgress.value = UploadProgress(
                fileName = fileName,
                bytesUploaded = bytesWritten,
                totalBytes = contentLength,
                attachmentIndex = attachmentIndex,
                totalAttachments = totalAttachments
            )
        }

        val filePart = MultipartBody.Part.createFormData("attachment", fileName, progressRequestBody)

        val response = api.sendAttachment(
            chatGuid = chatGuid.toRequestBody("text/plain".toMediaTypeOrNull()),
            tempGuid = tempGuid.toRequestBody("text/plain".toMediaTypeOrNull()),
            name = fileName.toRequestBody("text/plain".toMediaTypeOrNull()),
            method = "private-api".toRequestBody("text/plain".toMediaTypeOrNull()),
            attachment = filePart
        )

        // Clean up compressed file if we created one
        compressedPath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete compressed file: $path", e)
            }
        }

        val body = response.body()
        if (!response.isSuccessful || body == null || body.status != 200) {
            throw Exception(body?.message ?: "Failed to upload attachment")
        }

        body.data ?: throw Exception("No message returned from attachment upload")
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

    // ===== Delivery Mode Determination =====

    /**
     * Determine the best delivery mode for a chat based on:
     * 1. SMS-only mode setting
     * 2. Chat-level SMS fallback mode
     * 3. Chat type (SMS/MMS prefix)
     * 4. Server connection status
     * 5. Whether fallback to SMS is possible (phone number available)
     */
    private suspend fun determineDeliveryMode(
        chatGuid: String,
        hasAttachments: Boolean
    ): MessageDeliveryMode {
        val chat = chatDao.getChatByGuid(chatGuid)

        // Check if SMS-only mode is enabled in settings
        if (settingsDataStore.smsOnlyMode.first()) {
            return if (chat?.isGroup == true || hasAttachments) {
                MessageDeliveryMode.LOCAL_MMS
            } else {
                MessageDeliveryMode.LOCAL_SMS
            }
        }

        // Check if chat is in SMS fallback mode (due to previous iMessage failure)
        if (chatFallbackTracker.isInFallbackMode(chatGuid)) {
            val address = extractAddressFromChatGuid(chatGuid)
            if (address?.isPhoneNumber() == true) {
                return if (chat?.isGroup == true || hasAttachments) {
                    MessageDeliveryMode.LOCAL_MMS
                } else {
                    MessageDeliveryMode.LOCAL_SMS
                }
            }
        }

        // Check if it's already a local SMS/MMS/RCS chat
        if (chatGuid.startsWith("sms;-;", ignoreCase = true) ||
            chatGuid.startsWith("mms;-;", ignoreCase = true) ||
            chatGuid.startsWith("RCS;-;", ignoreCase = true)) {
            // Use MMS if group or has attachments
            return if (chat?.isGroup == true || hasAttachments) {
                MessageDeliveryMode.LOCAL_MMS
            } else {
                MessageDeliveryMode.LOCAL_SMS
            }
        }

        // Check server connection for iMessage chats
        val isConnected = socketService.connectionState.value == ConnectionState.CONNECTED
        if (!isConnected) {
            // Server disconnected - try SMS fallback if we have phone numbers
            val address = extractAddressFromChatGuid(chatGuid)
            val canFallbackToSms = address?.isPhoneNumber() == true

            if (canFallbackToSms) {
                val capability = smsPermissionHelper.getSmsCapabilityStatus()
                if (capability.isDefaultSmsApp && capability.canSendSms) {
                    chatFallbackTracker.enterFallbackMode(chatGuid, FallbackReason.SERVER_DISCONNECTED)
                    return if (chat?.isGroup == true || hasAttachments) {
                        MessageDeliveryMode.LOCAL_MMS
                    } else {
                        MessageDeliveryMode.LOCAL_SMS
                    }
                } else {
                    Log.w(TAG, "Cannot enter SMS fallback for $chatGuid: default SMS role missing")
                }
            }
        }

        // Default to iMessage for connected server or non-phone-number chats
        return MessageDeliveryMode.IMESSAGE
    }

    // ===== Helper Methods =====

    /**
     * Sync attachments for an outbound message after server confirms upload.
     */
    private suspend fun syncOutboundAttachments(messageDto: MessageDto, tempMessageGuid: String? = null) {
        // Delete any temp attachments that were created for immediate display
        tempMessageGuid?.let { tempGuid ->
            attachmentDao.deleteAttachmentsForMessage(tempGuid)
        }

        if (messageDto.attachments.isNullOrEmpty()) return

        val serverAddress = settingsDataStore.serverAddress.first()

        messageDto.attachments.forEach { attachmentDto ->
            val webUrl = "$serverAddress/api/v1/attachment/${attachmentDto.guid}/download"

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
                transferState = TransferState.UPLOADED.name,
                transferProgress = 1f
            )
            attachmentDao.insertAttachment(attachment)
        }
    }

    /**
     * Get file name from a content URI
     */
    private fun getFileName(uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) {
                        cursor.getString(nameIndex)
                    } else null
                }
            }
            "file" -> uri.lastPathSegment
            else -> uri.lastPathSegment
        }
    }

    /**
     * Get media dimensions (width, height) from a content URI.
     * Returns (null, null) if dimensions cannot be determined.
     */
    private fun getMediaDimensions(uri: Uri, mimeType: String): Pair<Int?, Int?> {
        return try {
            when {
                mimeType.startsWith("image/") -> {
                    // Use BitmapFactory to get image dimensions without loading full bitmap
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeStream(inputStream, null, options)
                        Pair(options.outWidth.takeIf { it > 0 }, options.outHeight.takeIf { it > 0 })
                    } ?: Pair(null, null)
                }
                mimeType.startsWith("video/") -> {
                    // Use MediaMetadataRetriever for video dimensions
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                        Pair(width, height)
                    } finally {
                        retriever.release()
                    }
                }
                else -> Pair(null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get media dimensions for $uri", e)
            Pair(null, null)
        }
    }

    /**
     * Check if carrier messaging is ready. Returns an exception if not ready, null if ready.
     */
    private fun ensureCarrierReady(requireMms: Boolean = false): IllegalStateException? {
        val status = smsPermissionHelper.getSmsCapabilityStatus()
        if (!status.deviceSupportsSms) {
            return IllegalStateException("This device cannot send SMS/MMS messages")
        }
        if (!status.isDefaultSmsApp) {
            return IllegalStateException("Set BlueBubbles as the default SMS app to send carrier messages")
        }
        if (!status.canSendSms) {
            return IllegalStateException("Grant SMS permissions to BlueBubbles to send carrier messages")
        }
        if (requireMms && !status.deviceSupportsMms) {
            return IllegalStateException("This device cannot send MMS messages")
        }
        return null
    }

    /**
     * Throws if carrier messaging is not ready. Use inside runCatching blocks.
     */
    private fun ensureCarrierReadyOrThrow(requireMms: Boolean = false) {
        ensureCarrierReady(requireMms)?.let { throw it }
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
     * Check if a string looks like a phone number
     */
    private fun String.isPhoneNumber(): Boolean {
        val cleaned = this.replace(Regex("[^0-9+]"), "")
        return cleaned.startsWith("+") || (cleaned.length >= 10 && cleaned.all { it.isDigit() })
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
            isReactionDb = com.bothbubbles.data.local.db.entity.ReactionClassifier.isReaction(associatedMessageGuid, associatedMessageType)
        )
    }
}
