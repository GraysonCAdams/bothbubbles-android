package com.bothbubbles.services.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.bothbubbles.data.model.AttachmentQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of image compression operation.
 */
data class CompressionResult(
    val path: String,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val compressedWidth: Int,
    val compressedHeight: Int
) {
    val compressionRatio: Float
        get() = if (originalSizeBytes > 0) {
            1f - (compressedSizeBytes.toFloat() / originalSizeBytes)
        } else 0f

    val savedBytes: Long
        get() = originalSizeBytes - compressedSizeBytes
}

/**
 * Service for compressing images before upload.
 *
 * Supports quality presets that balance file size with image quality.
 * Preserves EXIF orientation and handles HEIC conversion.
 */
@Singleton
class ImageCompressor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ImageCompressor"

        // Supported formats that can be compressed
        private val COMPRESSIBLE_MIME_TYPES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif"
        )
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, "compressed_images").apply { mkdirs() }
    }

    /**
     * Check if a MIME type is compressible.
     */
    fun isCompressible(mimeType: String?): Boolean {
        return mimeType?.lowercase() in COMPRESSIBLE_MIME_TYPES
    }

    /**
     * Compress an image to the specified quality preset.
     *
     * @param inputUri URI of the source image
     * @param quality Quality preset to use
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return CompressionResult with path to compressed file, or null if compression failed/skipped
     */
    suspend fun compress(
        inputUri: Uri,
        quality: AttachmentQuality,
        onProgress: ((Float) -> Unit)? = null
    ): CompressionResult? = withContext(Dispatchers.IO) {
        if (quality == AttachmentQuality.AUTO || quality == AttachmentQuality.ORIGINAL) {
            Log.d(TAG, "${quality.name} quality selected, skipping compression")
            return@withContext null
        }

        onProgress?.invoke(0f)

        try {
            // Get original file size
            val originalSize = getFileSize(inputUri) ?: 0L

            // Decode bitmap with size constraints for memory efficiency
            val (bitmap, originalWidth, originalHeight) = decodeBitmapWithSize(inputUri, quality)
                ?: return@withContext null

            onProgress?.invoke(0.3f)

            // Apply EXIF rotation if needed
            val rotatedBitmap = applyExifRotation(inputUri, bitmap)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }

            onProgress?.invoke(0.5f)

            // Scale if needed
            val scaledBitmap = scaleIfNeeded(rotatedBitmap, quality)
            if (scaledBitmap != rotatedBitmap) {
                rotatedBitmap.recycle()
            }

            onProgress?.invoke(0.7f)

            // Determine output format - use JPEG for best compression, WebP for transparency
            val (format, extension) = if (hasTransparency(inputUri)) {
                Pair(Bitmap.CompressFormat.PNG, "png")
            } else {
                Pair(Bitmap.CompressFormat.JPEG, "jpg")
            }

            // Compress and save
            val outputFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.$extension")
            FileOutputStream(outputFile).use { out ->
                scaledBitmap.compress(format, quality.jpegQuality, out)
            }

            val compressedSize = outputFile.length()

            Log.d(TAG, "Compression complete: ${originalSize / 1024}KB -> ${compressedSize / 1024}KB")
            Log.d(TAG, "Dimensions: ${originalWidth}x${originalHeight} -> ${scaledBitmap.width}x${scaledBitmap.height}")

            val result = CompressionResult(
                path = outputFile.absolutePath,
                originalSizeBytes = originalSize,
                compressedSizeBytes = compressedSize,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                compressedWidth = scaledBitmap.width,
                compressedHeight = scaledBitmap.height
            )

            scaledBitmap.recycle()

            onProgress?.invoke(1f)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed", e)
            onProgress?.invoke(1f)
            null
        }
    }

    /**
     * Estimate compressed file size without actually compressing.
     */
    fun estimateCompressedSize(
        inputUri: Uri,
        quality: AttachmentQuality
    ): Long? {
        if (quality == AttachmentQuality.AUTO || quality == AttachmentQuality.ORIGINAL) {
            return getFileSize(inputUri)
        }

        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(inputUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            // Calculate output dimensions
            val (outputWidth, outputHeight) = calculateOutputDimensions(
                options.outWidth, options.outHeight, quality.maxDimension
            )

            // Estimate bytes: ~3 bytes per pixel for JPEG at this quality
            val qualityFactor = quality.jpegQuality / 100f
            val bytesPerPixel = 0.5f + (2.5f * qualityFactor) // 0.5 to 3 bytes per pixel
            (outputWidth * outputHeight * bytesPerPixel).toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to estimate compressed size", e)
            null
        }
    }

    /**
     * Clean up old compressed files.
     */
    fun cleanupCache() {
        cacheDir.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000) {
                file.delete()
            }
        }
    }

    private fun getFileSize(uri: Uri): Long? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBitmapWithSize(
        uri: Uri,
        quality: AttachmentQuality
    ): Triple<Bitmap, Int, Int>? {
        // First pass: get dimensions
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            Log.e(TAG, "Failed to decode image dimensions")
            return null
        }

        val originalWidth = options.outWidth
        val originalHeight = options.outHeight

        // Calculate sample size for memory-efficient decoding
        val sampleSize = calculateSampleSize(originalWidth, originalHeight, quality.maxDimension)

        // Second pass: decode with sample size
        options.apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }

        return bitmap?.let { Triple(it, originalWidth, originalHeight) }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (maxDimension <= 0) return 1

        var sampleSize = 1
        val maxOriginal = maxOf(width, height)

        while (maxOriginal / sampleSize > maxDimension * 2) {
            sampleSize *= 2
        }

        return sampleSize
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotation == 0f) return bitmap

            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF orientation", e)
            bitmap
        }
    }

    private fun scaleIfNeeded(bitmap: Bitmap, quality: AttachmentQuality): Bitmap {
        if (quality.maxDimension <= 0) return bitmap

        val (targetWidth, targetHeight) = calculateOutputDimensions(
            bitmap.width, bitmap.height, quality.maxDimension
        )

        if (targetWidth >= bitmap.width && targetHeight >= bitmap.height) {
            return bitmap
        }

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun calculateOutputDimensions(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        if (maxDimension <= 0 || (width <= maxDimension && height <= maxDimension)) {
            return Pair(width, height)
        }

        val aspectRatio = width.toFloat() / height.toFloat()

        return if (width > height) {
            val newWidth = maxDimension
            val newHeight = (maxDimension / aspectRatio).toInt()
            Pair(newWidth, newHeight)
        } else {
            val newHeight = maxDimension
            val newWidth = (maxDimension * aspectRatio).toInt()
            Pair(newWidth, newHeight)
        }
    }

    private fun hasTransparency(uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri)?.lowercase()
        return mimeType == "image/png" || mimeType == "image/webp"
    }
}
