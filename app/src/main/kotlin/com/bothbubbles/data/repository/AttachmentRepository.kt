package com.bothbubbles.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.bothbubbles.data.local.db.dao.AttachmentDao
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.AttachmentWithDate
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.services.media.ThumbnailManager
import com.bothbubbles.util.GifProcessor
import com.bothbubbles.util.NetworkConfig
import com.bothbubbles.util.retryWithBackoff
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentDao: AttachmentDao,
    private val api: BothBubblesApi,
    private val okHttpClient: OkHttpClient,
    private val thumbnailManager: ThumbnailManager,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        /** Max file size for in-memory GIF processing (10MB) - larger GIFs skip speed fix */
        private const val MAX_GIF_PROCESS_SIZE = 10L * 1024 * 1024

        /** Max file size for in-memory HEIC conversion (15MB) - larger files use fallback */
        private const val MAX_HEIC_CONVERT_SIZE = 15L * 1024 * 1024
    }

    private val attachmentsDir: File by lazy {
        File(context.filesDir, "attachments").also { it.mkdirs() }
    }

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
     */
    suspend fun syncAttachmentsFromDto(messageDto: MessageDto, tempMessageGuid: String? = null) {
        // Delete any temp attachments that were created for immediate display
        tempMessageGuid?.let { tempGuid ->
            attachmentDao.deleteAttachmentsForMessage(tempGuid)
        }

        val attachments = messageDto.attachments
        if (attachments.isNullOrEmpty()) return

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

    // ===== Download Operations =====

    /**
     * Download an attachment from the server.
     * Retries with exponential backoff on transient network errors.
     */
    suspend fun downloadAttachment(
        attachmentGuid: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            retryWithBackoff(
                times = NetworkConfig.ATTACHMENT_RETRY_ATTEMPTS,
                initialDelayMs = NetworkConfig.ATTACHMENT_INITIAL_DELAY_MS,
                maxDelayMs = NetworkConfig.ATTACHMENT_MAX_DELAY_MS
            ) {
                downloadAttachmentInternal(attachmentGuid, onProgress)
            }
        }
    }

    /**
     * Internal download logic, called by downloadAttachment with retry wrapper.
     */
    private suspend fun downloadAttachmentInternal(
        attachmentGuid: String,
        onProgress: ((Float) -> Unit)? = null
    ): File {
        val attachment = attachmentDao.getAttachmentByGuid(attachmentGuid)
            ?: throw Exception("Attachment not found")

        // Check if already downloaded - short-circuit to avoid redundant downloads
        // This handles the case where outbound attachments were relocated to permanent storage
        attachment.localPath?.let { path ->
            // Handle both raw paths and file:// URIs
            val filePath = if (path.startsWith("file://")) {
                Uri.parse(path).path ?: path
            } else {
                path
            }
            val existingFile = File(filePath)
            if (existingFile.exists()) {
                Timber.d("Attachment $attachmentGuid already exists at $filePath, skipping download")
                // Ensure transfer state is marked as DOWNLOADED
                if (attachment.transferState != TransferState.DOWNLOADED.name &&
                    attachment.transferState != TransferState.UPLOADED.name) {
                    attachmentDao.updateTransferState(attachmentGuid, TransferState.DOWNLOADED.name)
                }
                onProgress?.invoke(1f)
                return existingFile
            }
        }

        // Get download URL from webUrl or throw
        val baseDownloadUrl = attachment.webUrl
            ?: throw Exception("No download URL available")

        // Create file with proper extension
        val extension = getExtensionFromMimeType(attachment.mimeType)
        val fileName = "${attachmentGuid}${extension}"
        val outputFile = File(attachmentsDir, fileName)

        // For stickers, try with original=true first to get HEIC with transparency
        // Fall back to regular download if server returns error
        val downloadUrl = if (attachment.isSticker && !baseDownloadUrl.contains("original=true")) {
            val separator = if (baseDownloadUrl.contains("?")) "&" else "?"
            "${baseDownloadUrl}${separator}original=true"
        } else {
            baseDownloadUrl
        }

        var succeeded = false
        var lastError: Exception? = null

        Timber.d("Downloading attachment $attachmentGuid, isSticker=${attachment.isSticker}")

        // Try download (with original=true for stickers first)
        try {
            Timber.d("Attempting download from: $downloadUrl")
            downloadFile(downloadUrl, outputFile, onProgress)
            succeeded = true
            Timber.d("Download succeeded, file size: ${outputFile.length()} bytes")
        } catch (e: Exception) {
            lastError = e
            Timber.w("Download failed with original=true, will retry without: ${e.message}")
        }

        // If sticker download failed with original=true, retry without it
        if (!succeeded && attachment.isSticker && downloadUrl != baseDownloadUrl) {
            try {
                Timber.d("Retrying without original=true: $baseDownloadUrl")
                downloadFile(baseDownloadUrl, outputFile, onProgress)
                succeeded = true
                Timber.d("Fallback download succeeded, file size: ${outputFile.length()} bytes")
            } catch (e: Exception) {
                lastError = e
                Timber.e(e, "Fallback download also failed")
            }
        }

        if (!succeeded) {
            throw lastError ?: Exception("Download failed")
        }

        // Convert HEIC/HEIF to PNG for stickers (Android's HEIC support is unreliable)
        val isHeic = isHeicFile(outputFile)
        val fileSize = outputFile.length()
        Timber.d("Checking if HEIC: isSticker=${attachment.isSticker}, isHeic=$isHeic, size=$fileSize")
        var finalFile = if (attachment.isSticker && isHeic) {
            // Skip in-memory conversion for large files to prevent OOM
            if (fileSize > MAX_HEIC_CONVERT_SIZE) {
                Timber.w("HEIC file too large for conversion (${fileSize / 1024 / 1024}MB), using JPEG fallback")
                outputFile.delete()
                downloadFile(baseDownloadUrl, outputFile, onProgress)
                outputFile
            } else {
                Timber.d("Converting HEIC sticker to PNG...")
                val converted = convertHeicToPng(outputFile, attachmentGuid)
                // Check if conversion actually succeeded (PNG file exists and is larger than 0)
                if (converted.absolutePath.endsWith(".png") && converted.exists() && converted.length() > 0) {
                    converted
                } else {
                    // HEIC conversion failed - Android can't decode this HEIC (likely HEVC with alpha)
                    // Fall back to downloading JPEG version (no transparency but at least it displays)
                    Timber.w("HEIC conversion failed, falling back to JPEG download")
                    outputFile.delete()
                    downloadFile(baseDownloadUrl, outputFile, onProgress)
                    Timber.d("Fallback JPEG download succeeded, file size: ${outputFile.length()} bytes")
                    outputFile
                }
            }
        } else {
            outputFile
        }

        // Fix GIFs with zero-delay frames (causes them to play too fast)
        // Skip for large GIFs to prevent OOM from loading entire file into memory
        val gifSize = finalFile.length()
        if (attachment.mimeType?.startsWith("image/gif") == true && finalFile.exists() && gifSize <= MAX_GIF_PROCESS_SIZE) {
            try {
                val originalBytes = finalFile.readBytes()
                val fixedBytes = GifProcessor.fixSpeedyGif(originalBytes)
                if (fixedBytes !== originalBytes) {
                    finalFile.writeBytes(fixedBytes)
                    Timber.d("Applied GIF speed fix to ${attachment.guid}")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to apply GIF speed fix")
            }
        } else if (gifSize > MAX_GIF_PROCESS_SIZE) {
            Timber.d("Skipping GIF speed fix for large file (${gifSize / 1024 / 1024}MB)")
        }

        // Generate thumbnail for images and videos (skip GIFs - they're already small)
        var thumbnailPath: String? = null
        if (finalFile.exists() && !attachment.isSticker) {
            thumbnailPath = when {
                attachment.isImage && attachment.mimeType != "image/gif" -> {
                    thumbnailManager.generateImageThumbnail(
                        sourcePath = finalFile.absolutePath,
                        attachmentGuid = attachmentGuid
                    )
                }
                attachment.isVideo -> {
                    thumbnailManager.generateVideoThumbnail(
                        sourcePath = finalFile.absolutePath,
                        attachmentGuid = attachmentGuid
                    )
                }
                else -> null
            }
            if (thumbnailPath != null) {
                Timber.d("Generated thumbnail for $attachmentGuid at $thumbnailPath")
            }
        }

        // Update database with file path and thumbnail
        attachmentDao.updateLocalPath(attachmentGuid, finalFile.absolutePath)
        if (thumbnailPath != null) {
            attachmentDao.updateThumbnailPath(attachmentGuid, thumbnailPath)
        }

        return finalFile
    }

    /**
     * Helper to perform the actual file download
     */
    private fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: ((Float) -> Unit)?
    ) {
        val request = Request.Builder()
            .url(url)
            .build()

        okHttpClient.newCall(request).execute().use { downloadResponse ->
            if (!downloadResponse.isSuccessful) {
                throw IOException("Download failed: ${downloadResponse.code}")
            }

            val body = downloadResponse.body
                ?: throw Exception("Empty response body")

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            FileOutputStream(outputFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (totalBytes > 0) {
                            onProgress?.invoke(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if a file is HEIC/HEIF format by reading magic bytes
     */
    private fun isHeicFile(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(12)
                input.read(buffer)
                // HEIC/HEIF files have "ftyp" at offset 4 and "heic", "heix", "hevc", or "mif1" at offset 8
                val ftyp = String(buffer, 4, 4)
                val brand = String(buffer, 8, 4)
                ftyp == "ftyp" && brand in listOf("heic", "heix", "hevc", "mif1", "msf1")
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Convert HEIC file to PNG to preserve transparency using avif-coder library
     */
    private fun convertHeicToPng(heicFile: File, attachmentGuid: String): File {
        val pngFile = File(attachmentsDir, "${attachmentGuid}.png")
        var bitmap: Bitmap? = null

        try {
            // Use avif-coder library which properly handles HEIC with alpha channels
            val heifCoder = HeifCoder()
            val heicBytes = heicFile.readBytes()
            bitmap = heifCoder.decode(heicBytes)

            if (bitmap == null) {
                Timber.e("HeifCoder failed to decode HEIC")
                return heicFile
            }

            Timber.d("HeifCoder decoded bitmap: ${bitmap.width}x${bitmap.height}, hasAlpha=${bitmap.hasAlpha()}")

            FileOutputStream(pngFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }

            // Delete the original HEIC file
            heicFile.delete()

            Timber.d("Converted HEIC to PNG: ${pngFile.absolutePath}, size: ${pngFile.length()} bytes")
            return pngFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to convert HEIC to PNG with HeifCoder")
            // Return original file if conversion fails
            return heicFile
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Download all pending attachments for a chat.
     * Downloads sequentially to avoid overwhelming network.
     *
     * @param chatGuid The chat GUID to download attachments for
     * @param onProgress Progress callback with (downloaded, total) counts
     * @return Number of successfully downloaded attachments
     */
    suspend fun downloadPendingForChat(
        chatGuid: String,
        onProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val pending = attachmentDao.getPendingDownloadsForChat(chatGuid)
            if (pending.isEmpty()) return@runCatching 0

            var downloaded = 0
            pending.forEachIndexed { index, attachment ->
                onProgress?.invoke(index, pending.size)
                try {
                    downloadAttachment(attachment.guid).getOrThrow()
                    downloaded++
                } catch (e: Exception) {
                    // Log but continue with other attachments
                    Timber.w(e, "Failed to download ${attachment.guid}")
                }
            }
            onProgress?.invoke(pending.size, pending.size)
            downloaded
        }
    }

    /**
     * Download all pending attachments for multiple chats (for merged conversations).
     * Downloads sequentially to avoid overwhelming network.
     *
     * @param chatGuids List of chat GUIDs to download attachments for
     * @param onProgress Progress callback with (downloaded, total) counts
     * @return Number of successfully downloaded attachments
     */
    suspend fun downloadPendingForChats(
        chatGuids: List<String>,
        onProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val pending = attachmentDao.getPendingDownloadsForChats(chatGuids)
            if (pending.isEmpty()) return@runCatching 0

            var downloaded = 0
            pending.forEachIndexed { index, attachment ->
                onProgress?.invoke(index, pending.size)
                try {
                    downloadAttachment(attachment.guid).getOrThrow()
                    downloaded++
                } catch (e: Exception) {
                    // Log but continue with other attachments
                    Timber.w(e, "Failed to download ${attachment.guid}")
                }
            }
            onProgress?.invoke(pending.size, pending.size)
            downloaded
        }
    }

    /**
     * Get the local file for an attachment, downloading if necessary
     */
    suspend fun getAttachmentFile(attachmentGuid: String): Result<File> {
        val attachment = attachmentDao.getAttachmentByGuid(attachmentGuid)
            ?: return Result.failure(Exception("Attachment not found"))

        // Check if already downloaded
        attachment.localPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                return Result.success(file)
            }
        }

        // Download if not available locally
        return downloadAttachment(attachmentGuid)
    }

    /**
     * Delete a downloaded attachment file
     */
    suspend fun deleteLocalFile(attachmentGuid: String): Result<Unit> = runCatching {
        val attachment = attachmentDao.getAttachmentByGuid(attachmentGuid)
        attachment?.localPath?.let { path ->
            File(path).delete()
            attachmentDao.updateLocalPath(attachmentGuid, null)
        }
    }

    /**
     * Clear all downloaded attachments
     */
    suspend fun clearAllDownloads(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            attachmentsDir.listFiles()?.forEach { it.delete() }
            attachmentDao.clearAllLocalPaths()
        }
    }

    /**
     * Get total size of downloaded attachments
     */
    suspend fun getDownloadedSize(): Long = withContext(Dispatchers.IO) {
        attachmentsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

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

    // ===== Upload Operations =====
    // Note: Attachment uploads are handled by MessageRepository.sendUnified()
    // which provides progress tracking via MessageRepository.uploadProgress StateFlow

    // ===== Private Helpers =====

    private fun getExtensionFromMimeType(mimeType: String?): String {
        return when {
            mimeType == null -> ""
            mimeType.startsWith("image/jpeg") -> ".jpg"
            mimeType.startsWith("image/png") -> ".png"
            mimeType.startsWith("image/gif") -> ".gif"
            mimeType.startsWith("image/heic") -> ".heic"
            mimeType.startsWith("image/heif") -> ".heif"
            mimeType.startsWith("image/webp") -> ".webp"
            mimeType.startsWith("video/mp4") -> ".mp4"
            mimeType.startsWith("video/quicktime") -> ".mov"
            mimeType.startsWith("video/") -> ".mp4"
            mimeType.startsWith("audio/mp4") -> ".m4a"
            mimeType.startsWith("audio/mpeg") -> ".mp3"
            mimeType.startsWith("audio/") -> ".m4a"
            mimeType.startsWith("application/pdf") -> ".pdf"
            else -> ""
        }
    }

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
