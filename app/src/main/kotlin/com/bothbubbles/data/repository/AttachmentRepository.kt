package com.bothbubbles.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.services.media.ThumbnailManager
import com.bothbubbles.util.GifProcessor
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import com.bothbubbles.data.remote.api.BothBubblesApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentDao: AttachmentDao,
    private val api: BothBubblesApi,
    private val okHttpClient: OkHttpClient,
    private val thumbnailManager: ThumbnailManager
) {
    companion object {
        private const val TAG = "AttachmentRepository"
    }

    private val attachmentsDir: File by lazy {
        File(context.filesDir, "attachments").also { it.mkdirs() }
    }

    // ===== Local Operations =====

    fun observeAttachmentsForMessage(messageGuid: String): Flow<List<AttachmentEntity>> =
        attachmentDao.observeAttachmentsForMessage(messageGuid)

    suspend fun getAttachment(guid: String): AttachmentEntity? =
        attachmentDao.getAttachmentByGuid(guid)

    suspend fun getAttachmentsForMessage(messageGuid: String): List<AttachmentEntity> =
        attachmentDao.getAttachmentsForMessage(messageGuid)

    // ===== Download Operations =====

    /**
     * Download an attachment from the server
     */
    suspend fun downloadAttachment(
        attachmentGuid: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val attachment = attachmentDao.getAttachmentByGuid(attachmentGuid)
                ?: throw Exception("Attachment not found")

            // Check if already downloaded
            attachment.localPath?.let { path ->
                val existingFile = File(path)
                if (existingFile.exists()) {
                    return@runCatching existingFile
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

            Log.d(TAG, "Downloading attachment ${attachmentGuid}, isSticker=${attachment.isSticker}")

            // Try download (with original=true for stickers first)
            try {
                Log.d(TAG, "Attempting download from: $downloadUrl")
                downloadFile(downloadUrl, outputFile, onProgress)
                succeeded = true
                Log.d(TAG, "Download succeeded, file size: ${outputFile.length()} bytes")
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Download failed with original=true, will retry without: ${e.message}")
            }

            // If sticker download failed with original=true, retry without it
            if (!succeeded && attachment.isSticker && downloadUrl != baseDownloadUrl) {
                try {
                    Log.d(TAG, "Retrying without original=true: $baseDownloadUrl")
                    downloadFile(baseDownloadUrl, outputFile, onProgress)
                    succeeded = true
                    Log.d(TAG, "Fallback download succeeded, file size: ${outputFile.length()} bytes")
                } catch (e: Exception) {
                    lastError = e
                    Log.e(TAG, "Fallback download also failed: ${e.message}")
                }
            }

            if (!succeeded) {
                throw lastError ?: Exception("Download failed")
            }

            // Convert HEIC/HEIF to PNG for stickers (Android's HEIC support is unreliable)
            val isHeic = isHeicFile(outputFile)
            Log.d(TAG, "Checking if HEIC: isSticker=${attachment.isSticker}, isHeic=$isHeic")
            var finalFile = if (attachment.isSticker && isHeic) {
                Log.d(TAG, "Converting HEIC sticker to PNG...")
                val converted = convertHeicToPng(outputFile, attachmentGuid)
                // Check if conversion actually succeeded (PNG file exists and is larger than 0)
                if (converted.absolutePath.endsWith(".png") && converted.exists() && converted.length() > 0) {
                    converted
                } else {
                    // HEIC conversion failed - Android can't decode this HEIC (likely HEVC with alpha)
                    // Fall back to downloading JPEG version (no transparency but at least it displays)
                    Log.w(TAG, "HEIC conversion failed, falling back to JPEG download")
                    outputFile.delete()
                    downloadFile(baseDownloadUrl, outputFile, onProgress)
                    Log.d(TAG, "Fallback JPEG download succeeded, file size: ${outputFile.length()} bytes")
                    outputFile
                }
            } else {
                outputFile
            }

            // Fix GIFs with zero-delay frames (causes them to play too fast)
            if (attachment.mimeType?.startsWith("image/gif") == true && finalFile.exists()) {
                try {
                    val originalBytes = finalFile.readBytes()
                    val fixedBytes = GifProcessor.fixSpeedyGif(originalBytes)
                    if (fixedBytes !== originalBytes) {
                        finalFile.writeBytes(fixedBytes)
                        Log.d(TAG, "Applied GIF speed fix to ${attachment.guid}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply GIF speed fix", e)
                }
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
                    Log.d(TAG, "Generated thumbnail for $attachmentGuid at $thumbnailPath")
                }
            }

            // Update database with file path and thumbnail
            attachmentDao.updateLocalPath(attachmentGuid, finalFile.absolutePath)
            if (thumbnailPath != null) {
                attachmentDao.updateThumbnailPath(attachmentGuid, thumbnailPath)
            }

            finalFile
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
                throw Exception("Download failed: ${downloadResponse.code}")
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
                Log.e(TAG, "HeifCoder failed to decode HEIC")
                return heicFile
            }

            Log.d(TAG, "HeifCoder decoded bitmap: ${bitmap.width}x${bitmap.height}, hasAlpha=${bitmap.hasAlpha()}")

            FileOutputStream(pngFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }

            // Delete the original HEIC file
            heicFile.delete()

            Log.d(TAG, "Converted HEIC to PNG: ${pngFile.absolutePath}, size: ${pngFile.length()} bytes")
            return pngFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert HEIC to PNG with HeifCoder", e)
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
                    android.util.Log.w("AttachmentRepository", "Failed to download ${attachment.guid}", e)
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
                    android.util.Log.w("AttachmentRepository", "Failed to download ${attachment.guid}", e)
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
}
