package com.bothbubbles.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.TransferState
import com.bothbubbles.services.media.ThumbnailManager
import com.bothbubbles.util.GifProcessor
import com.bothbubbles.util.NetworkConfig
import com.bothbubbles.util.retryWithBackoff
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages attachment downloads, format conversions, and thumbnail generation.
 *
 * Responsibilities:
 * - Download attachments from server with retry logic
 * - Convert HEIC/HEIF stickers to PNG for compatibility
 * - Fix zero-delay GIF frames
 * - Generate thumbnails for images/videos
 * - Manage local attachment storage
 *
 * Extracted from AttachmentRepository to reduce class size and separate concerns.
 */
@Singleton
class AttachmentDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentDao: AttachmentDao,
    private val okHttpClient: OkHttpClient,
    private val thumbnailManager: ThumbnailManager
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
        attachment.localPath?.let { path ->
            val filePath = if (path.startsWith("file://")) {
                Uri.parse(path).path ?: path
            } else {
                path
            }
            val existingFile = File(filePath)
            if (existingFile.exists()) {
                Timber.d("Attachment $attachmentGuid already exists at $filePath, skipping download")
                if (attachment.transferState != TransferState.DOWNLOADED.name &&
                    attachment.transferState != TransferState.UPLOADED.name) {
                    attachmentDao.updateTransferState(attachmentGuid, TransferState.DOWNLOADED.name)
                }
                onProgress?.invoke(1f)
                return existingFile
            }
        }

        val baseDownloadUrl = attachment.webUrl
            ?: throw Exception("No download URL available")

        val extension = getExtensionFromMimeType(attachment.mimeType)
        val fileName = "${attachmentGuid}${extension}"
        val outputFile = File(attachmentsDir, fileName)

        // For stickers, try with original=true first to get HEIC with transparency
        val downloadUrl = if (attachment.isSticker && !baseDownloadUrl.contains("original=true")) {
            val separator = if (baseDownloadUrl.contains("?")) "&" else "?"
            "${baseDownloadUrl}${separator}original=true"
        } else {
            baseDownloadUrl
        }

        var succeeded = false
        var lastError: Exception? = null

        Timber.d("Downloading attachment $attachmentGuid, isSticker=${attachment.isSticker}")

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

        // Post-process the downloaded file
        var finalFile = postProcessDownload(attachment, outputFile, baseDownloadUrl, onProgress)

        // Generate thumbnail for images and videos
        val thumbnailPath = generateThumbnailIfNeeded(attachment, finalFile, attachmentGuid)

        // Update database with file path and thumbnail
        attachmentDao.updateLocalPath(attachmentGuid, finalFile.absolutePath)
        if (thumbnailPath != null) {
            attachmentDao.updateThumbnailPath(attachmentGuid, thumbnailPath)
        }

        return finalFile
    }

    /**
     * Post-process downloaded file: HEIC conversion and GIF speed fix
     */
    private fun postProcessDownload(
        attachment: AttachmentEntity,
        outputFile: File,
        baseDownloadUrl: String,
        onProgress: ((Float) -> Unit)?
    ): File {
        val isHeic = isHeicFile(outputFile)
        val fileSize = outputFile.length()
        Timber.d("Checking if HEIC: isSticker=${attachment.isSticker}, isHeic=$isHeic, size=$fileSize")

        var finalFile = if (attachment.isSticker && isHeic) {
            if (fileSize > MAX_HEIC_CONVERT_SIZE) {
                Timber.w("HEIC file too large for conversion (${fileSize / 1024 / 1024}MB), using JPEG fallback")
                outputFile.delete()
                downloadFile(baseDownloadUrl, outputFile, onProgress)
                outputFile
            } else {
                Timber.d("Converting HEIC sticker to PNG...")
                val converted = convertHeicToPng(outputFile, attachment.guid)
                if (converted.absolutePath.endsWith(".png") && converted.exists() && converted.length() > 0) {
                    converted
                } else {
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

        // Fix GIFs with zero-delay frames
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

        return finalFile
    }

    /**
     * Generate thumbnail for images and videos
     */
    private fun generateThumbnailIfNeeded(
        attachment: AttachmentEntity,
        file: File,
        attachmentGuid: String
    ): String? {
        if (!file.exists() || attachment.isSticker) return null

        return when {
            attachment.isImage && attachment.mimeType != "image/gif" -> {
                thumbnailManager.generateImageThumbnail(
                    sourcePath = file.absolutePath,
                    attachmentGuid = attachmentGuid
                )
            }
            attachment.isVideo -> {
                thumbnailManager.generateVideoThumbnail(
                    sourcePath = file.absolutePath,
                    attachmentGuid = attachmentGuid
                )
            }
            else -> null
        }?.also { path ->
            Timber.d("Generated thumbnail for $attachmentGuid at $path")
        }
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

            heicFile.delete()

            Timber.d("Converted HEIC to PNG: ${pngFile.absolutePath}, size: ${pngFile.length()} bytes")
            return pngFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to convert HEIC to PNG with HeifCoder")
            return heicFile
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Download all pending attachments for a chat.
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
                    Timber.w(e, "Failed to download ${attachment.guid}")
                }
            }
            onProgress?.invoke(pending.size, pending.size)
            downloaded
        }
    }

    /**
     * Download all pending attachments for multiple chats.
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

        attachment.localPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                return Result.success(file)
            }
        }

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
}
