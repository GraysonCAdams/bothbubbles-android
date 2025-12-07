package com.bothbubbles.data.repository

import android.content.Context
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
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
    private val okHttpClient: OkHttpClient
) {
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
            val downloadUrl = attachment.webUrl
                ?: throw Exception("No download URL available")

            // Create file with proper extension
            val extension = getExtensionFromMimeType(attachment.mimeType)
            val fileName = "${attachmentGuid}${extension}"
            val outputFile = File(attachmentsDir, fileName)

            val request = Request.Builder()
                .url(downloadUrl)
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

            // Update database with file path
            attachmentDao.updateLocalPath(attachmentGuid, outputFile.absolutePath)

            outputFile
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
