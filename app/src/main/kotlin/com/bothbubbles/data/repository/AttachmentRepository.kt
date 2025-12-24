package com.bothbubbles.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.AttachmentWithDate
import com.bothbubbles.data.local.db.dao.MediaWithSender
import timber.log.Timber
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.dto.MessageDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for attachment operations.
 *
 * Responsibilities:
 * - Local database access for attachments
 * - Sync attachments from server DTOs
 * - Save attachments to gallery
 * - URI metadata queries
 *
 * Download operations are delegated to [AttachmentDownloadManager].
 */
@Singleton
class AttachmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentDao: AttachmentDao,
    private val api: BothBubblesApi,
    private val downloadManager: AttachmentDownloadManager,
    private val settingsDataStore: SettingsDataStore
) {

    // ===== Local Operations =====

    fun observeAttachmentsForMessage(messageGuid: String): Flow<List<AttachmentEntity>> =
        attachmentDao.observeAttachmentsForMessage(messageGuid)

    suspend fun getAttachment(guid: String): AttachmentEntity? =
        attachmentDao.getAttachmentByGuid(guid)

    suspend fun getAttachmentByGuid(guid: String): AttachmentEntity? =
        attachmentDao.getAttachmentByGuid(guid)

    suspend fun getAttachmentsForMessage(messageGuid: String): List<AttachmentEntity> =
        attachmentDao.getAttachmentsForMessage(messageGuid)

    /**
     * Batch fetch attachments for multiple messages.
     */
    suspend fun getAttachmentsForMessages(messageGuids: List<String>): List<AttachmentEntity> =
        attachmentDao.getAttachmentsForMessages(messageGuids)

    /**
     * Get all attachments for a chat (images, videos, files).
     */
    fun getAttachmentsForChat(chatGuid: String): Flow<List<AttachmentEntity>> =
        attachmentDao.getAttachmentsForChat(chatGuid)

    /**
     * Get images only for a chat.
     */
    fun getImagesForChat(chatGuid: String): Flow<List<AttachmentEntity>> =
        attachmentDao.getImagesForChat(chatGuid)

    /**
     * Get videos only for a chat.
     */
    fun getVideosForChat(chatGuid: String): Flow<List<AttachmentEntity>> =
        attachmentDao.getVideosForChat(chatGuid)

    /**
     * Get cached (downloaded) media for a chat.
     */
    suspend fun getCachedMediaForChat(chatGuid: String): List<AttachmentEntity> =
        attachmentDao.getCachedMediaForChat(chatGuid)

    /**
     * Get all media attachments for a chat with sender information.
     * Used by MediaViewer to show sender avatar/name and allow swiping through all media.
     * Includes media regardless of download status.
     */
    suspend fun getMediaWithSenderForChat(chatGuid: String): List<MediaWithSender> =
        attachmentDao.getMediaWithSenderForChat(chatGuid)

    /**
     * Save an attachment to the device's gallery (images/videos) or Downloads folder.
     * Uses MediaStore for Android 10+ (scoped storage), falls back to direct file access for older versions.
     *
     * @param localPath Path to the local file to save
     * @param mimeType MIME type of the file
     * @param fileName Original filename
     * @return Result containing the saved URI on success
     */
    suspend fun saveToGallery(localPath: String, mimeType: String?, fileName: String?): Result<Uri> {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(localPath)
                if (!file.exists()) {
                    return@withContext Result.failure(IOException("File not found: $localPath"))
                }

                val isImage = mimeType?.startsWith("image") == true
                val isVideo = mimeType?.startsWith("video") == true
                val displayName = fileName ?: file.name

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: "application/octet-stream")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relativePath = when {
                            isImage -> Environment.DIRECTORY_PICTURES + "/BothBubbles"
                            isVideo -> Environment.DIRECTORY_MOVIES + "/BothBubbles"
                            else -> Environment.DIRECTORY_DOWNLOADS + "/BothBubbles"
                        }
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val collection = when {
                    isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }

                val uri = context.contentResolver.insert(collection, contentValues)
                    ?: return@withContext Result.failure(IOException("Failed to create media entry"))

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: return@withContext Result.failure(IOException("Failed to open output stream"))

                // Clear pending flag on Android 10+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }

                Timber.d("Saved media to gallery: $uri")
                Result.success(uri)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save media to gallery")
                Result.failure(e)
            }
        }
    }

    /**
     * Observe image count for a chat.
     */
    fun observeImageCountForChat(chatGuid: String): Flow<Int> =
        attachmentDao.observeImageCountForChat(chatGuid)

    /**
     * Observe other media count (videos, audio, documents) for a chat.
     */
    fun observeOtherMediaCountForChat(chatGuid: String): Flow<Int> =
        attachmentDao.observeOtherMediaCountForChat(chatGuid)

    /**
     * Observe recent images for a chat (for preview).
     */
    fun observeRecentImagesForChat(chatGuid: String, limit: Int = 5): Flow<List<AttachmentEntity>> =
        attachmentDao.observeRecentImagesForChat(chatGuid, limit)

    /**
     * Get media (images/videos) with dates for gallery display with grouping.
     */
    fun getMediaWithDatesForChat(chatGuid: String): Flow<List<AttachmentWithDate>> =
        attachmentDao.getMediaWithDatesForChat(chatGuid)

    /**
     * Get media with dates for multiple chats (merged conversations).
     */
    fun getMediaWithDatesForChats(chatGuids: List<String>): Flow<List<AttachmentWithDate>> =
        attachmentDao.getMediaWithDatesForChats(chatGuids)

    // ===== Sync Operations =====

    /**
     * Sync attachments from a MessageDto to the local database.
     * Used during message sync to ensure attachments are tracked locally.
     *
     * @param messageDto The message DTO containing attachment information
     * @param tempMessageGuid Optional temp GUID to clean up temp attachments from
     * @return Result indicating success or failure
     */
    suspend fun syncAttachmentsFromDto(messageDto: MessageDto, tempMessageGuid: String? = null): Result<Unit> = runCatching {
        // Delete any temp attachments that were created for immediate display
        tempMessageGuid?.let { tempGuid ->
            attachmentDao.deleteAttachmentsForMessage(tempGuid)
        }

        val attachments = messageDto.attachments
        if (attachments.isNullOrEmpty()) return@runCatching

        val serverAddress = settingsDataStore.serverAddress.first()

        attachments.forEach { attachmentDto ->
            // webUrl is base download URL - AuthInterceptor adds guid param, AttachmentRepository adds original=true for stickers
            val webUrl = "$serverAddress/api/v1/attachment/${attachmentDto.guid}/download"

            // Check if this attachment already exists (e.g., from IMessageSenderStrategy.syncOutboundAttachments)
            val existingAttachment = attachmentDao.getAttachmentByGuid(attachmentDto.guid)

            Timber.d("[AttachmentSync] syncAttachmentsFromDto: guid=${attachmentDto.guid}, " +
                "existing=${existingAttachment != null}, existingMessageGuid=${existingAttachment?.messageGuid}, " +
                "incomingMessageGuid=${messageDto.guid}, existingLocalPath=${existingAttachment?.localPath}")

            if (existingAttachment != null) {
                // Attachment already exists - check if it's for the same message (duplicate) or different (self-message)
                if (existingAttachment.messageGuid == messageDto.guid) {
                    // Same message - true duplicate, skip
                    Timber.d("[AttachmentSync] syncAttachmentsFromDto: duplicate for same message, skipping")
                    return@forEach
                }

                // Different message (self-message case) - create a copy for this message
                // Use a modified GUID since attachment.guid must be unique
                Timber.d("[AttachmentSync] syncAttachmentsFromDto: self-message detected, creating copy for inbound")
                val inboundGuid = "${attachmentDto.guid}-inbound"

                // Check if we already created the inbound copy
                val existingInboundCopy = attachmentDao.getAttachmentByGuid(inboundGuid)
                if (existingInboundCopy != null) {
                    // Inbound copy exists - but check if it needs localPath update
                    // This handles the race condition where inbound copy was created before
                    // the outbound attachment got its permanent localPath
                    if (existingInboundCopy.localPath == null && existingAttachment.localPath != null) {
                        Timber.d("[AttachmentSync] syncAttachmentsFromDto: updating inbound copy localPath from original: ${existingAttachment.localPath}")
                        attachmentDao.updateLocalPath(inboundGuid, existingAttachment.localPath)
                        attachmentDao.updateTransferState(inboundGuid, TransferState.DOWNLOADED.name)
                    } else {
                        Timber.d("[AttachmentSync] syncAttachmentsFromDto: inbound copy already exists (localPath=${existingInboundCopy.localPath}), skipping")
                    }
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

            // Check for content-based duplicate: same message + same file name
            // This catches cases where the server sends the same attachment with different GUIDs
            // (e.g., proper server GUID "09029F3C-..." vs fallback "at_0_<messageGuid>")
            val transferName = attachmentDto.transferName
            if (transferName != null) {
                val contentDuplicate = attachmentDao.getAttachmentByMessageAndName(messageDto.guid, transferName)
                if (contentDuplicate != null) {
                    Timber.d("[AttachmentSync] syncAttachmentsFromDto: content duplicate detected " +
                        "(existingGuid=${contentDuplicate.guid}, newGuid=${attachmentDto.guid}, " +
                        "transferName=$transferName), skipping")
                    return@forEach
                }
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
                localPath = null,  // New attachment - will be downloaded if inbound
                transferState = transferState,
                transferProgress = if (attachmentDto.isOutgoing) 1f else 0f
            )
            attachmentDao.insertAttachment(attachment)
        }
    }

    // ===== Download Operations (delegated to AttachmentDownloadManager) =====

    /**
     * Download an attachment from the server.
     * Delegates to [AttachmentDownloadManager].
     */
    suspend fun downloadAttachment(
        attachmentGuid: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = downloadManager.downloadAttachment(attachmentGuid, onProgress)

    /**
     * Download all pending attachments for a chat.
     * Delegates to [AttachmentDownloadManager].
     */
    suspend fun downloadPendingForChat(
        chatGuid: String,
        onProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    ): Result<Int> = downloadManager.downloadPendingForChat(chatGuid, onProgress)

    /**
     * Download all pending attachments for multiple chats.
     * Delegates to [AttachmentDownloadManager].
     */
    suspend fun downloadPendingForChats(
        chatGuids: List<String>,
        onProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    ): Result<Int> = downloadManager.downloadPendingForChats(chatGuids, onProgress)

    /**
     * Get the local file for an attachment, downloading if necessary.
     * Delegates to [AttachmentDownloadManager].
     */
    suspend fun getAttachmentFile(attachmentGuid: String): Result<File> =
        downloadManager.getAttachmentFile(attachmentGuid)

    /**
     * Delete a downloaded attachment file.
     * Delegates to [AttachmentDownloadManager].
     */
    suspend fun deleteLocalFile(attachmentGuid: String): Result<Unit> =
        downloadManager.deleteLocalFile(attachmentGuid)

    /**
     * Clear all downloaded attachments.
     * Delegates to [AttachmentDownloadManager].
     */
    suspend fun clearAllDownloads(): Result<Unit> = downloadManager.clearAllDownloads()

    /**
     * Get total size of downloaded attachments.
     * Delegates to [AttachmentDownloadManager].
     */
    suspend fun getDownloadedSize(): Long = downloadManager.getDownloadedSize()

    /**
     * Get the size of an attachment from a content URI.
     * Used for validating attachment sizes before sending.
     *
     * @param uri Content URI of the attachment
     * @return Size in bytes, or null if unable to determine
     */
    suspend fun getAttachmentSize(uri: Uri): Long? = withContext(Dispatchers.IO) {
        try {
            // Try to get size from content resolver (works for content:// URIs)
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        return@withContext cursor.getLong(sizeIndex)
                    }
                }
            }

            // Fallback: open stream and read length
            context.contentResolver.openInputStream(uri)?.use { input ->
                var size = 0L
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    size += bytesRead
                }
                return@withContext size
            }

            null
        } catch (e: Exception) {
            Timber.w(e, "Could not determine size for $uri")
            null
        }
    }

    // ===== URI Metadata Helpers =====

    /**
     * Get MIME type for a URI
     */
    suspend fun getMimeType(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get display name for a URI
     */
    suspend fun getFileName(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return@withContext cursor.getString(nameIndex)
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
