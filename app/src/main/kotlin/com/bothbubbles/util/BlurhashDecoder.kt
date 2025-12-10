package com.bothbubbles.util

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.vanniktech.blurhash.BlurHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility for decoding blurhash strings to bitmaps with LRU caching.
 *
 * Blurhash is a compact representation of a placeholder for an image.
 * When decoded, it produces a blurry preview that can be shown while
 * the actual image loads.
 */
object BlurhashDecoder {

    // Cache decoded bitmaps to avoid re-decoding
    // Max 50 entries (~50KB total since bitmaps are small 32x32)
    private val cache = LruCache<String, Bitmap>(50)

    // Default decode size - small since blurhash is intentionally blurry
    private const val DEFAULT_WIDTH = 32
    private const val DEFAULT_HEIGHT = 32

    /**
     * Decode a blurhash string to a Bitmap.
     *
     * @param blurhash The blurhash string to decode
     * @param width Target width for the decoded bitmap (default 32)
     * @param height Target height for the decoded bitmap (default 32)
     * @return The decoded Bitmap, or null if decoding fails
     */
    fun decode(
        blurhash: String?,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ): Bitmap? {
        if (blurhash.isNullOrBlank()) return null

        // Check cache first
        val cacheKey = "$blurhash:${width}x$height"
        cache.get(cacheKey)?.let { return it }

        // Decode blurhash
        return try {
            val bitmap = BlurHash.decode(blurhash, width, height)
            bitmap?.let { cache.put(cacheKey, it) }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decode a blurhash string to a Bitmap with aspect ratio preservation.
     *
     * @param blurhash The blurhash string to decode
     * @param aspectRatio Width/height ratio of the original image
     * @param baseSize Base size for the shorter dimension (default 32)
     * @return The decoded Bitmap, or null if decoding fails
     */
    fun decodeWithAspectRatio(
        blurhash: String?,
        aspectRatio: Float,
        baseSize: Int = DEFAULT_WIDTH
    ): Bitmap? {
        if (blurhash.isNullOrBlank()) return null

        val width: Int
        val height: Int

        if (aspectRatio > 1f) {
            // Landscape
            width = (baseSize * aspectRatio).toInt().coerceAtLeast(1)
            height = baseSize
        } else {
            // Portrait or square
            width = baseSize
            height = (baseSize / aspectRatio).toInt().coerceAtLeast(1)
        }

        return decode(blurhash, width, height)
    }

    /**
     * Clear the decoded bitmap cache.
     */
    fun clearCache() {
        cache.evictAll()
    }
}

/**
 * Composable helper to decode and remember a blurhash bitmap.
 *
 * @param blurhash The blurhash string to decode
 * @param aspectRatio Optional aspect ratio for the decoded bitmap
 * @return The decoded ImageBitmap, or null if blurhash is null or decoding fails
 */
@Composable
fun rememberBlurhashBitmap(
    blurhash: String?,
    aspectRatio: Float = 1f
): ImageBitmap? {
    // Use produceState for async decoding on first load
    // The LRU cache in BlurhashDecoder handles subsequent loads efficiently
    return produceState<ImageBitmap?>(initialValue = null, blurhash, aspectRatio) {
        if (blurhash.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            if (aspectRatio != 1f) {
                BlurhashDecoder.decodeWithAspectRatio(blurhash, aspectRatio)
            } else {
                BlurhashDecoder.decode(blurhash)
            }
        }?.asImageBitmap()
    }.value
}
