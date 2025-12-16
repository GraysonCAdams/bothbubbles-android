package com.bothbubbles.services.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import timber.log.Timber
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory LRU cache for video thumbnail bitmaps.
 *
 * This cache is used for quick access to video thumbnails when scrolling
 * through the message list. Unlike ThumbnailManager which persists thumbnails
 * to disk, this cache is purely in-memory for faster access during the
 * current session.
 */
@Singleton
class VideoThumbnailCache @Inject constructor() {

    companion object {
        private const val TAG = "VideoThumbnailCache"
        // Cache size: ~20 video thumbnails at 300x300 @ 4 bytes/pixel = ~7.2MB
        private const val MAX_CACHE_SIZE_BYTES = 8 * 1024 * 1024 // 8MB
        private const val THUMBNAIL_SIZE = 300
    }

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    /**
     * Get a cached thumbnail or generate one if not cached.
     *
     * @param videoPath Path to the video file
     * @param attachmentGuid Unique identifier for the attachment (used as cache key)
     * @return The thumbnail bitmap, or null if generation failed
     */
    suspend fun getThumbnail(
        videoPath: String,
        attachmentGuid: String
    ): Bitmap? {
        // Check cache first
        cache.get(attachmentGuid)?.let { return it }

        // Generate thumbnail
        return withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(videoPath)

                    // Get frame at 1 second or first frame
                    val bitmap = retriever.getFrameAtTime(
                        1_000_000, // 1 second in microseconds
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: retriever.getFrameAtTime(0)

                    if (bitmap != null) {
                        // Scale to thumbnail size
                        val scaledBitmap = scaleBitmap(bitmap, THUMBNAIL_SIZE)
                        if (scaledBitmap !== bitmap) {
                            bitmap.recycle()
                        }

                        // Cache the result
                        cache.put(attachmentGuid, scaledBitmap)
                        scaledBitmap
                    } else {
                        Timber.w("Could not extract frame from video: $videoPath")
                        null
                    }
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate video thumbnail for $attachmentGuid")
                null
            }
        }
    }

    /**
     * Check if a thumbnail is already cached.
     */
    fun isCached(attachmentGuid: String): Boolean {
        return cache.get(attachmentGuid) != null
    }

    /**
     * Remove a thumbnail from cache.
     */
    fun remove(attachmentGuid: String) {
        cache.remove(attachmentGuid)
    }

    /**
     * Clear the entire cache.
     */
    fun clear() {
        cache.evictAll()
    }

    /**
     * Get current cache size in bytes.
     */
    fun size(): Int {
        return cache.size()
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
}
