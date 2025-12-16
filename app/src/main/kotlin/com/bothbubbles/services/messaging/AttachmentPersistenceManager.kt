package com.bothbubbles.services.messaging

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages persistence of attachments for pending messages.
 *
 * When a message with attachments is queued for sending, the attachments must be
 * copied to app-internal storage because:
 * 1. Content URIs can expire after process death (permission revocation)
 * 2. Original files might be moved or deleted by the user
 * 3. App needs guaranteed access when WorkManager fires (potentially hours later)
 *
 * Files are stored in `filesDir/pending_attachments/` and cleaned up after successful send.
 */
@Singleton
class AttachmentPersistenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AttachmentPersistence"
        private const val PENDING_DIR = "pending_attachments"
    }

    private val pendingDir: File by lazy {
        File(context.filesDir, PENDING_DIR).also { it.mkdirs() }
    }

    /**
     * Copy an attachment from content URI to app-internal storage.
     *
     * @param uri The content URI to persist
     * @param localId Unique identifier for this attachment
     * @return Result containing persistence details or error
     */
    suspend fun persistAttachment(
        uri: Uri,
        localId: String
    ): Result<PersistenceResult> = withContext(Dispatchers.IO) {
        runCatching {
            val contentResolver = context.contentResolver

            // Get file info from content resolver
            val fileName = getFileName(uri) ?: "attachment_$localId"
            val mimeType = contentResolver.getType(uri) ?: guessMimeTypeFromName(fileName)

            // Determine extension for persisted file
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?: getExtensionFromFileName(fileName)
                ?: "bin"

            // Create destination file with unique name
            val destFileName = "${localId}.$extension"
            val destFile = File(pendingDir, destFileName)

            // Copy file content
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Cannot open input stream for $uri")

            Timber.d("Persisted attachment: $uri -> ${destFile.absolutePath} (${destFile.length()} bytes)")

            PersistenceResult(
                persistedPath = destFile.absolutePath,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = destFile.length()
            )
        }
    }

    /**
     * Clean up persisted attachment files after successful send.
     *
     * @param paths List of file paths to delete
     */
    fun cleanupAttachments(paths: List<String>) {
        paths.forEach { path ->
            try {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    Timber.d("Cleaned up attachment: $path")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete $path")
            }
        }
    }

    /**
     * Clean up orphaned attachment files.
     *
     * Called at startup to remove files that are no longer referenced by
     * any pending_attachments record (e.g., if app crashed during cleanup).
     *
     * @param referencedPaths Set of paths that are still referenced in the database
     */
    suspend fun cleanupOrphanedAttachments(referencedPaths: Set<String>) {
        withContext(Dispatchers.IO) {
            val orphanedCount = pendingDir.listFiles()?.count { file ->
                if (file.absolutePath !in referencedPaths) {
                    file.delete()
                    Timber.d("Cleaned up orphaned attachment: ${file.absolutePath}")
                    true
                } else {
                    false
                }
            } ?: 0

            if (orphanedCount > 0) {
                Timber.i("Cleaned up $orphanedCount orphaned attachments")
            }
        }
    }

    /**
     * Get the total size of all pending attachments (for storage monitoring).
     */
    suspend fun getTotalPendingSize(): Long = withContext(Dispatchers.IO) {
        pendingDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Get original file name from a content URI.
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
     * Guess MIME type from file name extension.
     */
    private fun guessMimeTypeFromName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    /**
     * Extract extension from file name.
     */
    private fun getExtensionFromFileName(fileName: String): String? {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < fileName.length - 1) {
            fileName.substring(lastDot + 1).lowercase()
        } else null
    }
}

/**
 * Result of persisting an attachment to internal storage.
 */
data class PersistenceResult(
    /** Absolute path to the persisted file */
    val persistedPath: String,
    /** Original file name */
    val fileName: String,
    /** MIME type of the file */
    val mimeType: String,
    /** File size in bytes */
    val fileSize: Long
)
