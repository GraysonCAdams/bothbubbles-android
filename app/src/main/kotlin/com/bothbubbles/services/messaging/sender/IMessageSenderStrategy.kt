package com.bothbubbles.services.messaging.sender

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
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
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.ProgressRequestBody
import com.bothbubbles.core.network.api.dto.AttributedBodyDto
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.core.network.api.dto.SendMessageRequest
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.bothbubbles.services.media.ImageCompressor
import com.bothbubbles.services.media.VideoCompressor
import com.bothbubbles.services.messaging.AttachmentPersistenceManager
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.util.error.AttachmentErrorState
import com.bothbubbles.util.error.MessageError
import com.bothbubbles.util.error.MessageErrorCode
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
    private val imageCompressor: ImageCompressor,
    private val attachmentPersistenceManager: AttachmentPersistenceManager
) : MessageSenderStrategy {

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    override val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    // Moshi adapter for parsing attributedBody JSON
    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val attributedBodyAdapter by lazy {
        moshi.adapter(AttributedBodyDto::class.java)
    }

    override fun canHandle(deliveryMode: MessageDeliveryMode): Boolean {
        return deliveryMode == MessageDeliveryMode.IMESSAGE
    }

    override suspend fun send(options: SendOptions): SendResult {
        Timber.i("[SEND_TRACE] ── IMessageSenderStrategy.send START ──")
        Timber.i("[SEND_TRACE] hasAttachments=${options.hasAttachments}, text=\"${options.text.take(30)}...\"")

        // Parse attributedBody from JSON if present
        val attributedBody = options.attributedBodyJson?.let { json ->
            try {
                attributedBodyAdapter.fromJson(json)
            } catch (e: Exception) {
                Timber.w(e, "[SEND_TRACE] Failed to parse attributedBodyJson")
                null
            }
        }

        val result = if (options.hasAttachments) {
            sendWithAttachments(options, attributedBody)
        } else {
            sendTextOnly(options, attributedBody)
        }
        Timber.i("[SEND_TRACE] ── IMessageSenderStrategy.send END: ${if (result is SendResult.Success) "SUCCESS" else "FAILURE"} ──")
        return result
    }

    override fun resetUploadProgress() {
        _uploadProgress.value = null
    }

    private suspend fun sendTextOnly(options: SendOptions, attributedBody: AttributedBodyDto?): SendResult {
        val sendStart = System.currentTimeMillis()
        val tempGuid = options.tempGuid ?: "temp-${UUID.randomUUID()}"
        Timber.i("[SEND_TRACE] sendTextOnly: tempGuid=$tempGuid, hasAttributedBody=${attributedBody != null}")

        try {
            val existingMessage = messageDao.getMessageByGuid(tempGuid)
            Timber.i("[SEND_TRACE] existingMessage=${existingMessage != null} +${System.currentTimeMillis() - sendStart}ms")
            if (existingMessage != null) {
                if (existingMessage.error != 0) {
                    Timber.i("[SEND_TRACE] Retry: clearing error on existing temp message $tempGuid")
                    messageDao.updateErrorStatus(tempGuid, 0)
                }
            }

            if (existingMessage == null) {
                Timber.i("[SEND_TRACE] Creating temp message entity +${System.currentTimeMillis() - sendStart}ms")
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
                Timber.i("[SEND_TRACE] Temp message inserted +${System.currentTimeMillis() - sendStart}ms")
            }

            Timber.i("[SEND_TRACE] ▶▶▶ Calling api.sendMessage (NETWORK) +${System.currentTimeMillis() - sendStart}ms")
            val apiStart = System.currentTimeMillis()
            val response = api.sendMessage(
                SendMessageRequest(
                    chatGuid = options.chatGuid,
                    message = options.text,
                    tempGuid = tempGuid,
                    selectedMessageGuid = options.replyToGuid,
                    effectId = options.effectId,
                    subject = options.subject,
                    attributedBody = attributedBody
                )
            )
            Timber.i("[SEND_TRACE] ◀◀◀ api.sendMessage RETURNED: ${System.currentTimeMillis() - apiStart}ms (status=${response.code()})")

            val body = response.body()
            if (!response.isSuccessful || body == null || body.status != 200) {
                val errorMessage = body?.message ?: body?.error?.message ?: ""
                Timber.e("[SEND_TRACE] API FAILED: code=${response.code()}, message=$errorMessage")

                // Check if server says message is already in transit
                if (isAlreadyInTransitError(errorMessage)) {
                    Timber.i("[SEND_TRACE] Server reports message already in transit - waiting for confirmation")
                    // Try to find the server GUID from an existing message
                    val existingServerMessage = messageDao.getMessageByGuid(tempGuid)
                    return SendResult.AlreadyInTransit(
                        serverGuid = existingServerMessage?.guid?.takeIf { !it.startsWith("temp-") },
                        tempGuid = tempGuid
                    )
                }

                // Parse error code from response - check for specific error codes like 22
                val errorCode = MessageErrorCode.parseFromMessage(body?.message)
                    ?: MessageErrorCode.parseFromMessage(body?.error?.message)
                    ?: if (response.code() >= 500) MessageErrorCode.SERVER_ERROR
                    else MessageErrorCode.GENERIC_ERROR
                messageDao.updateMessageError(tempGuid, errorCode, body?.message)
                return SendResult.Failure(
                    MessageError.SendFailed(tempGuid, body?.message ?: MessageErrorCode.getUserMessage(errorCode)),
                    tempGuid
                )
            }

            val serverMessage = body.data
            Timber.i("[SEND_TRACE] API SUCCESS: serverGuid=${serverMessage?.guid} +${System.currentTimeMillis() - sendStart}ms")
            return if (serverMessage != null) {
                try {
                    Timber.i("[SEND_TRACE] Replacing temp GUID with server GUID +${System.currentTimeMillis() - sendStart}ms")
                    messageDao.replaceGuid(tempGuid, serverMessage.guid)
                    Timber.i("[SEND_TRACE] GUID replaced: $tempGuid -> ${serverMessage.guid}")
                } catch (e: Exception) {
                    Timber.w("[SEND_TRACE] Non-critical error replacing GUID: ${e.message}")
                }
                Timber.i("[SEND_TRACE] sendTextOnly SUCCESS: ${System.currentTimeMillis() - sendStart}ms total")
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
            Timber.e("[SEND_TRACE] sendTextOnly EXCEPTION: ${e.message}")
            val errorCode = MessageErrorCode.fromException(e)
            messageDao.updateMessageError(tempGuid, errorCode, e.message)
            return SendResult.Failure(e, tempGuid)
        }
    }

    private suspend fun sendWithAttachments(options: SendOptions, attributedBody: AttributedBodyDto?): SendResult {
        val sendStart = System.currentTimeMillis()
        val tempGuid = options.tempGuid ?: "temp-${UUID.randomUUID()}"
        Timber.i("[SEND_TRACE] sendWithAttachments: tempGuid=$tempGuid, attachments=${options.attachments.size}, hasAttributedBody=${attributedBody != null}")

        try {
            val existingMessage = messageDao.getMessageByGuid(tempGuid)
            val tempAttachmentGuids = options.attachments.indices.map { "$tempGuid-att-$it" }
            Timber.i("[SEND_TRACE] existingMessage=${existingMessage != null} +${System.currentTimeMillis() - sendStart}ms")

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
                    val errorCode = MessageErrorCode.fromException(error)
                    messageDao.updateMessageError(tempGuid, errorCode, error.message)
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
                        subject = options.subject,
                        attributedBody = attributedBody
                    )
                )

                val body = response.body()
                if (!response.isSuccessful || body == null || body.status != 200) {
                    val errorCode = MessageErrorCode.parseFromMessage(body?.message)
                        ?: MessageErrorCode.parseFromMessage(body?.error?.message)
                        ?: if (response.code() >= 500) MessageErrorCode.SERVER_ERROR
                        else MessageErrorCode.GENERIC_ERROR
                    messageDao.updateMessageError(tempGuid, errorCode, body?.message)
                    return SendResult.Failure(
                        MessageError.SendFailed(tempGuid, body?.message ?: MessageErrorCode.getUserMessage(errorCode)),
                        tempGuid
                    )
                }
                lastResponse = body.data
            }

            lastResponse?.let { serverMessage ->
                // 1. Capture local paths BEFORE any DB operations
                val tempAttachments = attachmentDao.getAttachmentsForMessage(tempGuid)
                val pendingLocalPaths = tempAttachments.mapNotNull { it.localPath }

                // 2. Relocate files from pending_attachments to permanent attachments directory
                // This preserves local files through GUID replacement, preventing re-download
                val serverAttachmentGuids = serverMessage.attachments?.map { it.guid } ?: emptyList()
                val relocatedPaths = if (pendingLocalPaths.isNotEmpty() && serverAttachmentGuids.isNotEmpty()) {
                    attachmentPersistenceManager.relocateAttachments(pendingLocalPaths, serverAttachmentGuids)
                } else {
                    pendingLocalPaths.map { null }
                }

                // 3. Delete temp attachments BEFORE replacing message GUID
                // They reference tempGuid which will become orphaned after replaceGuid
                attachmentDao.deleteAttachmentsForMessage(tempGuid)

                // 4. Perform the replacement (safe with DAO race handling)
                try {
                    messageDao.replaceGuid(tempGuid, serverMessage.guid)
                } catch (e: Exception) {
                    // Fallback: DAO fix should handle this, but ensure temp is deleted
                    Timber.e(e, "Failed to replace GUID, cleaning up temp message")
                    runCatching { messageDao.deleteMessage(tempGuid) }
                }

                // 5. Sync attachments with server GUIDs and relocated paths (permanent location)
                // This creates new attachment entries with server GUIDs linked to the server message
                syncOutboundAttachments(serverMessage, relocatedPaths.filterNotNull())

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
            val errorCode = MessageErrorCode.fromException(e)
            messageDao.updateMessageError(tempGuid, errorCode, e.message)
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
        val attachments = messageDto.attachments
        if (attachments.isNullOrEmpty()) return
        val serverAddress = settingsDataStore.serverAddress.first()

        Timber.d("[AttachmentSync] syncOutboundAttachments: messageGuid=${messageDto.guid}, " +
            "attachmentCount=${attachments.size}, preservedLocalPaths=$preservedLocalPaths")

        attachments.forEachIndexed { index, attachmentDto ->
            val localPath = preservedLocalPaths.getOrNull(index)
            Timber.d("[AttachmentSync] syncOutboundAttachments: guid=${attachmentDto.guid}, localPath=$localPath")

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
                localPath = localPath,
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

    /**
     * Check if an error message indicates the message is already in transit.
     * This happens when we retry after a timeout and the server has already received
     * the original message.
     */
    private fun isAlreadyInTransitError(errorMessage: String): Boolean {
        val lowerMessage = errorMessage.lowercase()
        return lowerMessage.contains("already sent") ||
               lowerMessage.contains("already in progress") ||
               lowerMessage.contains("already exists") ||
               lowerMessage.contains("duplicate") ||
               lowerMessage.contains("in transit") ||
               lowerMessage.contains("already queued") ||
               lowerMessage.contains("already processing")
    }
}
