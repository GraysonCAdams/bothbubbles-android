package com.bothbubbles.services.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages thumbnail generation and caching for attachments.
 *
 * Thumbnails are stored in a dedicated cache directory and are much smaller
 * than full-size images, enabling faster loading and reduced memory usage
 * when scrolling through message lists.
 */
@Singleton
class ThumbnailManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ThumbnailManager"
        private const val THUMBNAIL_DIR = "thumbnails"
        private const val THUMBNAIL_MAX_SIZE = 300 // Max dimension in pixels
        private const val THUMBNAIL_QUALITY = 80 // JPEG quality
    }

    private val thumbnailDir: File by lazy {
        File(context.cacheDir, THUMBNAIL_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Generate a thumbnail for an image file.
     *
     * @param sourcePath Path to the source image
     * @param attachmentGuid Unique identifier for the attachment
     * @return Path to the generated thumbnail, or null if generation failed
     */
    suspend fun generateImageThumbnail(
        sourcePath: String,
        attachmentGuid: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                Log.w(TAG, "Source file doesn't exist: $sourcePath")
                return@withContext null
            }

            val thumbnailFile = getThumbnailFile(attachmentGuid)
            if (thumbnailFile.exists()) {
                return@withContext thumbnailFile.absolutePath
            }

            // Decode with inSampleSize for memory efficiency
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourcePath, options)

            val sampleSize = calculateSampleSize(
                options.outWidth,
                options.outHeight,
                THUMBNAIL_MAX_SIZE
            )

            options.apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize
            }

            val bitmap = BitmapFactory.decodeFile(sourcePath, options)
                ?: return@withContext null

            // Scale to exact thumbnail size
            val scaledBitmap = scaleBitmap(bitmap, THUMBNAIL_MAX_SIZE)
            bitmap.recycle()

            // Save thumbnail
            saveThumbnail(scaledBitmap, thumbnailFile)
            scaledBitmap.recycle()

            thumbnailFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate image thumbnail for $attachmentGuid", e)
            null
        }
    }

    /**
     * Generate a thumbnail from a video file.
     *
     * @param sourcePath Path to the source video
     * @param attachmentGuid Unique identifier for the attachment
     * @return Path to the generated thumbnail, or null if generation failed
     */
    suspend fun generateVideoThumbnail(
        sourcePath: String,
        attachmentGuid: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                Log.w(TAG, "Source video doesn't exist: $sourcePath")
                return@withContext null
            }

            val thumbnailFile = getThumbnailFile(attachmentGuid)
            if (thumbnailFile.exists()) {
                return@withContext thumbnailFile.absolutePath
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourcePath)

                // Get frame at 1 second or first frame
                val bitmap = retriever.getFrameAtTime(
                    1_000_000, // 1 second in microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.getFrameAtTime(0)

                if (bitmap == null) {
                    Log.w(TAG, "Could not extract frame from video: $sourcePath")
                    return@withContext null
                }

                // Scale to thumbnail size
                val scaledBitmap = scaleBitmap(bitmap, THUMBNAIL_MAX_SIZE)
                bitmap.recycle()

                // Save thumbnail
                saveThumbnail(scaledBitmap, thumbnailFile)
                scaledBitmap.recycle()

                thumbnailFile.absolutePath
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate video thumbnail for $attachmentGuid", e)
            null
        }
    }

    /**
     * Get the thumbnail path for an attachment if it exists.
     *
     * @param attachmentGuid Unique identifier for the attachment
     * @return Path to the thumbnail, or null if it doesn't exist
     */
    fun getThumbnailPath(attachmentGuid: String): String? {
        val file = getThumbnailFile(attachmentGuid)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Delete thumbnail for an attachment.
     *
     * @param attachmentGuid Unique identifier for the attachment
     */
    fun deleteThumbnail(attachmentGuid: String) {
        getThumbnailFile(attachmentGuid).delete()
    }

    /**
     * Clear all thumbnails from cache.
     */
    fun clearAllThumbnails() {
        thumbnailDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Get the total size of cached thumbnails in bytes.
     */
    fun getCacheSize(): Long {
        return thumbnailDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun getThumbnailFile(attachmentGuid: String): File {
        // Sanitize the GUID for use as filename
        val safeGuid = attachmentGuid.replace(Regex("[^a-zA-Z0-9-_]"), "_")
        return File(thumbnailDir, "${safeGuid}.jpg")
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxSize * 2 || height / sampleSize > maxSize * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val scale = minOf(
            maxSize.toFloat() / width,
            maxSize.toFloat() / height
        )

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun saveThumbnail(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
        }
    }
}
