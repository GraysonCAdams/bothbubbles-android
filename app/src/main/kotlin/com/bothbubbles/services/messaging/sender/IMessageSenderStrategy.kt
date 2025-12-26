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
import com.bothbubbles.data.local.db.dao.SocialMediaLinkDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.core.model.entity.SocialMediaLinkEntity
import com.bothbubbles.core.model.entity.SocialMediaPlatform
import java.security.MessageDigest
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.ReactionClassifier
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.FileStreamingRequestBody
import com.bothbubbles.core.network.api.UriStreamingRequestBody
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
import com.bothbubbles.util.parsing.HtmlEntityDecoder
import com.bothbubbles.util.error.MessageErrorCode
import com.bothbubbles.util.error.NetworkError
import com.bothbubbles.seam.stitches.StitchRegistry
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
    private val unifiedChatDao: UnifiedChatDao,
    private val api: BothBubblesApi,
    private val settingsDataStore: SettingsDataStore,
    private val videoCompressor: VideoCompressor,
    private val imageCompressor: ImageCompressor,
    private val attachmentPersistenceManager: AttachmentPersistenceManager,
    private val socialMediaLinkDao: SocialMediaLinkDao,
    private val stitchRegistry: StitchRegistry
) : MessageSenderStrategy {

    companion object {
        // Instagram URL patterns - matches IncomingMessageHandler
        private val INSTAGRAM_PATTERN = Regex(
            """https?://(?:www\.)?instagram\.com/(?:reel|reels|p|share/reel|share/p)/[A-Za-z0-9_-]+[^\s]*"""
        )

        // TikTok URL patterns - matches IncomingMessageHandler
        private val TIKTOK_PATTERN = Regex(
            """https?://(?:www\.|vm\.|vt\.)?tiktok\.com/[^\s]+"""
        )
    }

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
        // Parse attributedBody from JSON if present
        val attributedBody = options.attributedBodyJson?.let { json ->
            try {
                attributedBodyAdapter.fromJson(json)
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse attributedBodyJson")
                null
            }
        }

        val result = if (options.hasAttachments) {
            sendWithAttachments(options, attributedBody)
        } else {
            sendTextOnly(options, attributedBody)
        }
        return result
    }

    override fun resetUploadProgress() {
        _uploadProgress.value = null
    }

    /**
     * Gets the stitch ID for a chat.
     * Returns "bluebubbles" for iMessage chats, "sms" for fallback.
     */
    private fun getStitchId(chatGuid: String): String {
        return stitchRegistry.getStitchForChat(chatGuid)?.id ?: "bluebubbles"
    }

    private suspend fun sendTextOnly(options: SendOptions, attributedBody: AttributedBodyDto?): SendResult {
        val tempGuid = options.tempGuid ?: "temp-${UUID.randomUUID()}"

        try {
            val existingMessage = messageDao.getMessageByGuid(tempGuid)
            if (existingMessage != null) {
                if (existingMessage.error != 0) {
                    messageDao.updateErrorStatus(tempGuid, 0)
                }
            }

            if (existingMessage == null) {
                val now = System.currentTimeMillis()
                val chat = chatDao.getChatByGuid(options.chatGuid)
                val unifiedChatId = chat?.unifiedChatId
                val stitchId = getStitchId(options.chatGuid)
                val tempMessage = MessageEntity(
                    guid = tempGuid,
                    chatGuid = options.chatGuid,
                    unifiedChatId = unifiedChatId,
                    text = options.text,
                    subject = options.subject,
                    dateCreated = now,
                    isFromMe = true,
                    threadOriginatorGuid = options.replyToGuid,
                    expressiveSendStyleId = options.effectId,
                    messageSource = MessageSource.IMESSAGE.name,
                    stitchId = stitchId
                )
                messageDao.insertMessage(tempMessage)
                // Update unified chat's latest message
                unifiedChatId?.let { unifiedId ->
                    unifiedChatDao.updateLatestMessageIfNewer(
                        id = unifiedId,
                        date = now,
                        text = options.text,
                        guid = tempGuid,
                        isFromMe = true,
                        hasAttachments = false,
                        source = MessageSource.IMESSAGE.name,
                        dateDelivered = null,
                        dateRead = null,
                        error = 0
                    )
                }
            }

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

            val body = response.body()
            if (!response.isSuccessful || body == null || body.status != 200) {
                val errorMessage = body?.message ?: body?.error?.message ?: ""
                Timber.e("API failed: code=${response.code()}, message=$errorMessage")

                // Check if server says message is already in transit
                if (isAlreadyInTransitError(errorMessage)) {
                    Timber.i("Server reports message already in transit - waiting for confirmation")
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
            return if (serverMessage != null) {
                try {
                    messageDao.replaceGuid(tempGuid, serverMessage.guid)
                } catch (e: Exception) {
                    Timber.w("Non-critical error replacing GUID: ${e.message}")
                }

                // Store social media links for outgoing messages
                storeSocialMediaLinks(
                    messageGuid = serverMessage.guid,
                    chatGuid = options.chatGuid,
                    text = options.text,
                    timestamp = serverMessage.dateCreated ?: System.currentTimeMillis()
                )

                SendResult.Success(serverMessage.toEntity(options.chatGuid))
            } else {
                messageDao.updateErrorStatus(tempGuid, 0)
                val message = existingMessage ?: messageDao.getMessageByGuid(tempGuid)
                    ?: return SendResult.Failure(
                        MessageError.SendFailed(tempGuid, "Failed to find temp message"),
                        tempGuid
                    )

                // Store social media links for outgoing messages (using temp guid if no server response)
                storeSocialMediaLinks(
                    messageGuid = message.guid,
                    chatGuid = options.chatGuid,
                    text = options.text,
                    timestamp = message.dateCreated
                )

                SendResult.Success(message)
            }
        } catch (e: Exception) {
            Timber.e(e, "sendTextOnly exception: ${e.message}")
            val errorCode = MessageErrorCode.fromException(e)
            messageDao.updateMessageError(tempGuid, errorCode, e.message)
            return SendResult.Failure(e, tempGuid)
        }
    }

    private suspend fun sendWithAttachments(options: SendOptions, attributedBody: AttributedBodyDto?): SendResult {
        val tempGuid = options.tempGuid ?: "temp-${UUID.randomUUID()}"

        try {
            val existingMessage = messageDao.getMessageByGuid(tempGuid)
            val tempAttachmentGuids = options.attachments.indices.map { "$tempGuid-att-$it" }

            if (existingMessage == null) {
                val chat = chatDao.getChatByGuid(options.chatGuid)
                val unifiedChatId = chat?.unifiedChatId
                val stitchId = getStitchId(options.chatGuid)
                database.withTransaction {
                    val tempMessage = MessageEntity(
                        guid = tempGuid,
                        chatGuid = options.chatGuid,
                        unifiedChatId = unifiedChatId,
                        text = options.text.ifBlank { null },
                        subject = options.subject,
                        dateCreated = System.currentTimeMillis(),
                        isFromMe = true,
                        hasAttachments = true,
                        threadOriginatorGuid = options.replyToGuid,
                        expressiveSendStyleId = options.effectId,
                        messageSource = MessageSource.IMESSAGE.name,
                        stitchId = stitchId
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

            // Update unified chat's latest message
            val now = System.currentTimeMillis()
            val previewText = options.text.ifBlank { "[Attachment]" }
            chatDao.getChatByGuid(options.chatGuid)?.unifiedChatId?.let { unifiedId ->
                unifiedChatDao.updateLatestMessageIfNewer(
                    id = unifiedId,
                    date = now,
                    text = previewText,
                    guid = tempGuid,
                    isFromMe = true,
                    hasAttachments = true,
                    source = MessageSource.IMESSAGE.name,
                    dateDelivered = null,
                    dateRead = null,
                    error = 0
                )
            }

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

                // Store social media links for outgoing messages with attachments
                // Combine text and captions for link detection
                val fullText = buildString {
                    append(options.text)
                    options.attachments.mapNotNull { it.caption }.filter { it.isNotBlank() }.forEach {
                        if (isNotEmpty()) append("\n")
                        append(it)
                    }
                }
                storeSocialMediaLinks(
                    messageGuid = serverMessage.guid,
                    chatGuid = options.chatGuid,
                    text = fullText,
                    timestamp = serverMessage.dateCreated ?: System.currentTimeMillis()
                )

                return SendResult.Success(serverMessage.toEntity(options.chatGuid))
            }

            messageDao.updateErrorStatus(tempGuid, 0)
            val message = existingMessage ?: messageDao.getMessageByGuid(tempGuid)
                ?: return SendResult.Failure(
                    MessageError.SendFailed(tempGuid, "Failed to find temp message"),
                    tempGuid
                )

            // Store social media links for outgoing messages (using temp guid if no server response)
            storeSocialMediaLinks(
                messageGuid = message.guid,
                chatGuid = options.chatGuid,
                text = options.text,
                timestamp = message.dateCreated
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

        // Detect vLocation files and override MIME type
        // Android returns text/vcard for .vcf files, but vLocation needs text/x-vlocation
        val fileNameLower = fileName.lowercase()
        val isVLocation = fileNameLower.contains(".loc.vcf") || fileNameLower.contains("-cl.loc")
        if (isVLocation) {
            mimeType = "text/x-vlocation"
        }

        val isVideo = mimeType.startsWith("video/")
        val shouldCompressVideo = isVideo && settingsDataStore.compressVideosBeforeUpload.first()
        val isImage = imageCompressor.isCompressible(mimeType)
        val shouldCompressImage = isImage && imageQuality != AttachmentQuality.AUTO && imageQuality != AttachmentQuality.ORIGINAL

        // Track compressed file path for cleanup and streaming source selection
        var compressedPath: String? = null

        if (shouldCompressImage) {
            val compressionResult = imageCompressor.compress(uri, imageQuality) { progress ->
                _uploadProgress.value = UploadProgress(fileName, (progress * 30).toLong(), 100, attachmentIndex, totalAttachments)
            }

            if (compressionResult != null) {
                compressedPath = compressionResult.path
                if (compressionResult.path.endsWith(".jpg")) {
                    mimeType = "image/jpeg"
                    fileName = fileName.substringBeforeLast('.') + ".jpg"
                }
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
                mimeType = "video/mp4"
                fileName = fileName.substringBeforeLast('.') + ".mp4"
            }
        }

        // Create streaming request body - never loads entire file into memory
        // Uses FileStreamingRequestBody for compressed temp files, UriStreamingRequestBody for original URIs
        val streamingRequestBody = if (compressedPath != null) {
            FileStreamingRequestBody(
                file = File(compressedPath),
                contentType = mimeType.toMediaTypeOrNull()
            ) { bytesWritten, totalBytes ->
                _uploadProgress.value = UploadProgress(fileName, bytesWritten, totalBytes, attachmentIndex, totalAttachments)
            }
        } else {
            UriStreamingRequestBody(
                contentResolver = contentResolver,
                uri = uri,
                contentType = mimeType.toMediaTypeOrNull()
            ) { bytesWritten, totalBytes ->
                _uploadProgress.value = UploadProgress(fileName, bytesWritten, totalBytes, attachmentIndex, totalAttachments)
            }
        }

        val filePart = MultipartBody.Part.createFormData("attachment", fileName, streamingRequestBody)

        val response = api.sendAttachment(
            chatGuid = chatGuid.toRequestBody("text/plain".toMediaTypeOrNull()),
            tempGuid = tempGuid.toRequestBody("text/plain".toMediaTypeOrNull()),
            name = fileName.toRequestBody("text/plain".toMediaTypeOrNull()),
            method = "private-api".toRequestBody("text/plain".toMediaTypeOrNull()),
            attachment = filePart
        )

        compressedPath?.let {
            try {
                File(it).delete()
            } catch (e: Exception) {
                Timber.d(e, "Failed to delete compressed file: $it")
            }
        }

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

        val stitchId = getStitchId(chatGuid)

        return MessageEntity(
            guid = guid,
            chatGuid = chatGuid,
            handleId = handleId,
            senderAddress = handle?.address,
            text = HtmlEntityDecoder.decode(text),
            subject = HtmlEntityDecoder.decode(subject),
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
            stitchId = stitchId,
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

    /**
     * Detects and stores social media links from outgoing message text.
     * This populates the social_media_links table for the Reels feed.
     * Mirrors the logic in IncomingMessageHandler.storeSocialMediaLinks().
     */
    private suspend fun storeSocialMediaLinks(
        messageGuid: String,
        chatGuid: String,
        text: String?,
        timestamp: Long
    ) {
        if (text.isNullOrBlank()) return
        val links = mutableListOf<SocialMediaLinkEntity>()

        // Find Instagram URLs
        for (match in INSTAGRAM_PATTERN.findAll(text)) {
            val url = match.value
            links.add(
                SocialMediaLinkEntity(
                    urlHash = hashUrl(url),
                    url = url,
                    messageGuid = messageGuid,
                    chatGuid = chatGuid,
                    platform = SocialMediaPlatform.INSTAGRAM.name,
                    senderAddress = null, // Outgoing, so no sender address
                    isFromMe = true,
                    sentTimestamp = timestamp,
                    isDownloaded = false,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        // Find TikTok URLs
        for (match in TIKTOK_PATTERN.findAll(text)) {
            val url = match.value
            links.add(
                SocialMediaLinkEntity(
                    urlHash = hashUrl(url),
                    url = url,
                    messageGuid = messageGuid,
                    chatGuid = chatGuid,
                    platform = SocialMediaPlatform.TIKTOK.name,
                    senderAddress = null, // Outgoing, so no sender address
                    isFromMe = true,
                    sentTimestamp = timestamp,
                    isDownloaded = false,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        if (links.isNotEmpty()) {
            try {
                socialMediaLinkDao.insertAll(links)
                Timber.d("[SocialMedia] Stored ${links.size} social media link(s) from outgoing message $messageGuid")
            } catch (e: Exception) {
                Timber.w(e, "[SocialMedia] Failed to store social media links for outgoing message")
            }
        }
    }

    /**
     * Hash URL for deduplication (matches IncomingMessageHandler.hashUrl and SocialMediaCacheManager.hashUrl).
     */
    private fun hashUrl(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
