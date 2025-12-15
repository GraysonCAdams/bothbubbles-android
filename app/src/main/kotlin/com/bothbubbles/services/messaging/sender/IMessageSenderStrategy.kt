package com.bothbubbles.services.messaging.sender

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.withTransaction
import com.bothbubbles.data.local.db.BothBubblesDatabase
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.ReactionClassifier
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.ProgressRequestBody
import com.bothbubbles.data.remote.api.dto.MessageDto
import com.bothbubbles.data.remote.api.dto.SendMessageRequest
import com.bothbubbles.services.media.ImageCompressor
import com.bothbubbles.services.media.VideoCompressor
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.util.error.AttachmentErrorState
import com.bothbubbles.util.error.MessageError
import com.bothbubbles.util.error.NetworkError
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
 * Strategy for sending messages via BlueBubbles server (iMessage).
 *
 * Handles:
 * - Text-only iMessage sends
 * - Messages with attachments (with compression and upload progress)
 * - Local echo creation for optimistic UI updates
 */
@Singleton
class IMessageSenderStrategy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BothBubblesDatabase,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val attachmentDao: AttachmentDao,
    private val api: BothBubblesApi,
    private val settingsDataStore: SettingsDataStore,
    private val videoCompressor: VideoCompressor,
    private val imageCompressor: ImageCompressor
) : MessageSenderStrategy {

    companion object {
        private const val TAG = "IMessageSenderStrategy"
    }

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    override val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    override fun canHandle(deliveryMode: MessageDeliveryMode): Boolean {
        return deliveryMode == MessageDeliveryMode.IMESSAGE
    }

    override suspend fun send(options: SendOptions): SendResult {
        return if (options.hasAttachments) {
            sendWithAttachments(options)
        } else {
            sendTextOnly(options)
        }
    }

    override fun resetUploadProgress() {
        _uploadProgress.value = null
    }

    private suspend fun sendTextOnly(options: SendOptions): SendResult {
        val tempGuid = options.tempGuid ?: "temp-${UUID.randomUUID()}"

        try {
            val existingMessage = messageDao.getMessageByGuid(tempGuid)
            if (existingMessage != null) {
                if (existingMessage.error != 0) {
                    Log.d(TAG, "Retry: clearing error on existing temp message $tempGuid")
                    messageDao.updateErrorStatus(tempGuid, 0)
                }
            }

            if (existingMessage == null) {
                val tempMessage = MessageEntity(
                    guid = tempGuid,
                    chatGuid = options.chatGuid,
                    text = options.text,
                    subject = options.subject,
                    dateCreated = System.currentTimeMillis(),
                    isFromMe = true,
                    threadOriginatorGuid = options.replyToGuid,
                    expressiveSendStyleId = options.effectId,
                    messageSource = MessageSource.IMESSAGE.name
                )
                messageDao.insertMessage(tempMessage)
                chatDao.updateLastMessage(options.chatGuid, System.currentTimeMillis(), options.text)
            }

            val response = api.sendMessage(
                SendMessageRequest(
                    chatGuid = options.chatGuid,
                    message = options.text,
                    tempGuid = tempGuid,
                    selectedMessageGuid = options.replyToGuid,
                    effectId = options.effectId,
                    subject = options.subject
                )
            )

            val body = response.body()
            if (!response.isSuccessful || body == null || body.status != 200) {
                messageDao.updateErrorStatus(tempGuid, 1)
                return SendResult.Failure(
                    MessageError.SendFailed(tempGuid, body?.message ?: "Failed to send message"),
                    tempGuid
                )
            }

            val serverMessage = body.data
            return if (serverMessage != null) {
                try {
                    messageDao.replaceGuid(tempGuid, serverMessage.guid)
                } catch (e: Exception) {
                    Log.w(TAG, "Non-critical error replacing GUID: ${e.message}")
                }
                SendResult.Success(serverMessage.toEntity(options.chatGuid))
            } else {
                messageDao.updateErrorStatus(tempGuid, 0)
                val message = existingMessage ?: messageDao.getMessageByGuid(tempGuid)
                    ?: return SendResult.Failure(
                        MessageError.SendFailed(tempGuid, "Failed to find temp message"),
                        tempGuid
                    )
                SendResult.Success(message)
            }
        } catch (e: Exception) {
            messageDao.updateErrorStatus(tempGuid, 1)
            return SendResult.Failure(e, tempGuid)
        }
    }

    private suspend fun sendWithAttachments(options: SendOptions): SendResult {
        val tempGuid = options.tempGuid ?: "temp-${UUID.randomUUID()}"

        try {
            val existingMessage = messageDao.getMessageByGuid(tempGuid)
            val tempAttachmentGuids = options.attachments.indices.map { "$tempGuid-att-$it" }

            if (existingMessage == null) {
                database.withTransaction {
                    val tempMessage = MessageEntity(
                        guid = tempGuid,
                        chatGuid = options.chatGuid,
                        text = options.text.ifBlank { null },
                        subject = options.subject,
                        dateCreated = System.currentTimeMillis(),
                        isFromMe = true,
                        hasAttachments = true,
                        threadOriginatorGuid = options.replyToGuid,
                        expressiveSendStyleId = options.effectId,
                        messageSource = MessageSource.IMESSAGE.name
                    )
                    messageDao.insertMessage(tempMessage)

                    options.attachments.forEachIndexed { index, input ->
                        val tempAttGuid = tempAttachmentGuids[index]
                        val mimeType = input.mimeType.takeIf { !it.isNullOrBlank() }
                            ?: context.contentResolver.getType(input.uri)
                            ?: "application/octet-stream"
                        val fileName = getFileName(input.uri) ?: "attachment"
                        val (width, height) = getMediaDimensions(input.uri, mimeType)

                        val tempAttachment = AttachmentEntity(
                            guid = tempAttGuid,
                            messageGuid = tempGuid,
                            mimeType = mimeType,
                            transferName = fileName,
                            isOutgoing = true,
                            localPath = input.uri.toString(),
                            width = width,
                            height = height,
                            transferState = TransferState.UPLOADING.name,
                            transferProgress = 0f
                        )
                        attachmentDao.insertAttachment(tempAttachment)
                    }
                }
            } else if (existingMessage.error != 0) {
                messageDao.updateErrorStatus(tempGuid, 0)
            }

            chatDao.updateLastMessage(options.chatGuid, System.currentTimeMillis(), options.text.ifBlank { "[Attachment]" })

            var lastResponse: MessageDto? = null
            val defaultQualityStr = settingsDataStore.defaultImageQuality.first()
            val defaultImageQuality = AttachmentQuality.fromString(defaultQualityStr)

            options.attachments.forEachIndexed { index, input ->
                val tempAttGuid = tempAttachmentGuids[index]

                val existingAttachment = attachmentDao.getAttachmentByGuid(tempAttGuid)
                if (existingAttachment?.transferState == TransferState.UPLOADED.name) {
                    return@forEachIndexed
                }

                val attachmentResult = uploadAttachment(
                    chatGuid = options.chatGuid,
                    tempGuid = tempAttGuid,
                    uri = input.uri,
                    attachmentIndex = index,
                    totalAttachments = options.attachments.size,
                    imageQuality = defaultImageQuality
                )

                if (attachmentResult.isFailure) {
                    val error = attachmentResult.exceptionOrNull() ?: Exception("Upload failed")
                    val errorState = AttachmentErrorState.fromException(error)
                    messageDao.updateErrorStatus(tempGuid, 1)
                    attachmentDao.markTransferFailedWithError(tempAttGuid, errorState.type, errorState.userMessage)
                    _uploadProgress.value = null
                    return SendResult.Failure(error, tempGuid)
                }

                attachmentDao.markUploaded(tempAttGuid)
                lastResponse = attachmentResult.getOrNull()
            }

            _uploadProgress.value = null

            var messageText = options.text
            val captions = options.attachments.mapNotNull { it.caption }.filter { it.isNotBlank() }
            if (captions.isNotEmpty()) {
                if (messageText.isNotBlank()) messageText += "\n"
                messageText += captions.joinToString("\n")
            }

            if (messageText.isNotBlank()) {
                val response = api.sendMessage(
                    SendMessageRequest(
                        chatGuid = options.chatGuid,
                        message = messageText,
                        tempGuid = tempGuid,
                        selectedMessageGuid = options.replyToGuid,
                        effectId = options.effectId,
                        subject = options.subject
                    )
                )

                val body = response.body()
                if (!response.isSuccessful || body == null || body.status != 200) {
                    messageDao.updateErrorStatus(tempGuid, 1)
                    return SendResult.Failure(
                        MessageError.SendFailed(tempGuid, body?.message ?: "Failed to send message"),
                        tempGuid
                    )
                }
                lastResponse = body.data
            }

            lastResponse?.let { serverMessage ->
                try {
                    val tempAttachments = attachmentDao.getAttachmentsForMessage(tempGuid)
                    val preservedLocalPaths = tempAttachments.mapNotNull { att ->
                        att.localPath?.takeUnless { it.contains("/pending_attachments/") }
                    }
                    messageDao.replaceGuid(tempGuid, serverMessage.guid)
                    syncOutboundAttachments(serverMessage, preservedLocalPaths)
                } catch (e: Exception) {
                    Log.w(TAG, "Non-critical error: ${e.message}")
                }
                return SendResult.Success(serverMessage.toEntity(options.chatGuid))
            }

            messageDao.updateErrorStatus(tempGuid, 0)
            val message = existingMessage ?: messageDao.getMessageByGuid(tempGuid)
                ?: return SendResult.Failure(
                    MessageError.SendFailed(tempGuid, "Failed to find temp message"),
                    tempGuid
                )
            return SendResult.Success(message)

        } catch (e: Exception) {
            messageDao.updateErrorStatus(tempGuid, 1)
            _uploadProgress.value = null
            return SendResult.Failure(e, tempGuid)
        }
    }

    private suspend fun uploadAttachment(
        chatGuid: String,
        tempGuid: String,
        uri: Uri,
        attachmentIndex: Int,
        totalAttachments: Int,
        imageQuality: AttachmentQuality
    ): Result<MessageDto> = runCatching {
        val contentResolver = context.contentResolver
        var fileName = getFileName(uri) ?: "attachment"
        var mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        val isVideo = mimeType.startsWith("video/")
        val shouldCompressVideo = isVideo && settingsDataStore.compressVideosBeforeUpload.first()
        val isImage = imageCompressor.isCompressible(mimeType)
        val shouldCompressImage = isImage && imageQuality != AttachmentQuality.AUTO && imageQuality != AttachmentQuality.ORIGINAL

        val bytes: ByteArray
        var compressedPath: String? = null

        if (shouldCompressImage) {
            val compressionResult = imageCompressor.compress(uri, imageQuality) { progress ->
                _uploadProgress.value = UploadProgress(fileName, (progress * 30).toLong(), 100, attachmentIndex, totalAttachments)
            }

            if (compressionResult != null) {
                compressedPath = compressionResult.path
                bytes = File(compressedPath).readBytes()
                if (compressionResult.path.endsWith(".jpg")) {
                    mimeType = "image/jpeg"
                    fileName = fileName.substringBeforeLast('.') + ".jpg"
                }
            } else {
                bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw MessageError.UnsupportedAttachment("unknown")
            }
        } else if (shouldCompressVideo) {
            val qualityStr = settingsDataStore.videoCompressionQuality.first()
            val quality = when (qualityStr) {
                "original" -> VideoCompressor.Companion.Quality.ORIGINAL
                "high" -> VideoCompressor.Companion.Quality.HIGH
                "medium" -> VideoCompressor.Companion.Quality.MEDIUM
                "low" -> VideoCompressor.Companion.Quality.LOW
                else -> VideoCompressor.Companion.Quality.MEDIUM
            }

            compressedPath = videoCompressor.compress(uri, quality) { progress ->
                _uploadProgress.value = UploadProgress(fileName, (progress * 50).toLong(), 100, attachmentIndex, totalAttachments)
            }

            if (compressedPath != null) {
                bytes = File(compressedPath).readBytes()
                mimeType = "video/mp4"
                fileName = fileName.substringBeforeLast('.') + ".mp4"
            } else {
                bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw MessageError.UnsupportedAttachment("unknown")
            }
        } else {
            bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw MessageError.UnsupportedAttachment("unknown")
        }

        val baseRequestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val progressRequestBody = ProgressRequestBody(baseRequestBody) { bytesWritten, contentLength ->
            _uploadProgress.value = UploadProgress(fileName, bytesWritten, contentLength, attachmentIndex, totalAttachments)
        }

        val filePart = MultipartBody.Part.createFormData("attachment", fileName, progressRequestBody)

        val response = api.sendAttachment(
            chatGuid = chatGuid.toRequestBody("text/plain".toMediaTypeOrNull()),
            tempGuid = tempGuid.toRequestBody("text/plain".toMediaTypeOrNull()),
            name = fileName.toRequestBody("text/plain".toMediaTypeOrNull()),
            method = "private-api".toRequestBody("text/plain".toMediaTypeOrNull()),
            attachment = filePart
        )

        compressedPath?.let { try { File(it).delete() } catch (e: Exception) { } }

        val body = response.body()
        if (!response.isSuccessful || body == null || body.status != 200) {
            throw NetworkError.ServerError(response.code(), body?.message ?: "Upload failed")
        }

        body.data ?: throw NetworkError.ServerError(response.code(), "No message returned")
    }

    private suspend fun syncOutboundAttachments(messageDto: MessageDto, preservedLocalPaths: List<String>) {
        if (messageDto.attachments.isNullOrEmpty()) return
        val serverAddress = settingsDataStore.serverAddress.first()

        messageDto.attachments.forEachIndexed { index, attachmentDto ->
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
                localPath = preservedLocalPaths.getOrNull(index),
                transferState = TransferState.UPLOADED.name,
                transferProgress = 1f
            )
            attachmentDao.insertAttachment(attachment)
        }
    }

    private fun getFileName(uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }
            else -> uri.lastPathSegment
        }
    }

    private fun getMediaDimensions(uri: Uri, mimeType: String): Pair<Int?, Int?> {
        return try {
            when {
                mimeType.startsWith("image/") -> {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(inputStream, null, options)
                        Pair(options.outWidth.takeIf { it > 0 }, options.outHeight.takeIf { it > 0 })
                    } ?: Pair(null, null)
                }
                mimeType.startsWith("video/") -> {
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
            Pair(null, null)
        }
    }

    private fun MessageDto.toEntity(chatGuid: String): MessageEntity {
        val source = when {
            handle?.service?.equals("SMS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            handle?.service?.equals("RCS", ignoreCase = true) == true -> MessageSource.SERVER_SMS.name
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS.name
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
            isReactionDb = ReactionClassifier.isReaction(associatedMessageGuid, associatedMessageType)
        )
    }
}
