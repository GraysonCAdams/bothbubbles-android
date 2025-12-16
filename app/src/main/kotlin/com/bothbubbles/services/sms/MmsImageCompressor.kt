package com.bothbubbles.services.sms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Handles image compression for MMS attachments to meet carrier size constraints.
 * Uses quality reduction and resolution scaling to compress images efficiently.
 */
object MmsImageCompressor {

    private const val TAG = "MmsImageCompressor"

    // Image resizing thresholds
    private const val MAX_IMAGE_DIMENSION = 1280
    private const val MIN_JPEG_QUALITY = 30
    private const val DEFAULT_JPEG_QUALITY = 85

    /**
     * Compress image to target size using quality reduction and optional resizing
     *
     * @param data Original image data
     * @param targetSize Target size in bytes
     * @param mimeType Original MIME type (used for logging)
     * @return Compressed JPEG image data, or null if compression fails
     */
    fun compressImage(data: ByteArray, targetSize: Int, mimeType: String): ByteArray? {
        return try {
            var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null

            // Resize if dimensions are too large
            if (bitmap.width > MAX_IMAGE_DIMENSION || bitmap.height > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() /
                    maxOf(bitmap.width, bitmap.height).toFloat()
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                if (resized != bitmap) {
                    bitmap.recycle()
                    bitmap = resized
                }
                Timber.d("Resized image to ${newWidth}x${newHeight}")
            }

            // Binary search for optimal quality
            var quality = DEFAULT_JPEG_QUALITY
            var minQuality = MIN_JPEG_QUALITY
            var maxQuality = 100
            var result: ByteArray? = null

            while (minQuality <= maxQuality) {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                val compressed = output.toByteArray()

                if (compressed.size <= targetSize) {
                    result = compressed
                    minQuality = quality + 1
                } else {
                    maxQuality = quality - 1
                }
                quality = (minQuality + maxQuality) / 2
            }

            bitmap.recycle()

            // If still too large at minimum quality, try more aggressive resize
            if (result == null || result.size > targetSize) {
                return compressImageWithResize(data, targetSize)
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "Error compressing image")
            null
        }
    }

    /**
     * Compress image with aggressive resolution downscaling
     *
     * @param data Original image data
     * @param targetSize Target size in bytes
     * @return Compressed JPEG image data, or null if compression fails
     */
    private fun compressImageWithResize(data: ByteArray, targetSize: Int): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)

            // Calculate sample size for aggressive downscaling
            var sampleSize = 2
            while (options.outWidth / sampleSize > 640 ||
                options.outHeight / sampleSize > 640
            ) {
                sampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize

            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null

            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, MIN_JPEG_QUALITY, output)
            bitmap.recycle()

            output.toByteArray()
        } catch (e: Exception) {
            Timber.e(e, "Error with aggressive resize")
            null
        }
    }
}
