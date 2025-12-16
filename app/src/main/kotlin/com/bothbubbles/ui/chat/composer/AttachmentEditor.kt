package com.bothbubbles.ui.chat.composer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import timber.log.Timber
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Data class representing an edit request for an attachment.
 */
data class EditRequest(
    val uri: Uri,
    val attachmentId: String
)

/**
 * Result from editing an attachment.
 */
sealed class EditResult {
    data class Success(val editedUri: Uri, val attachmentId: String) : EditResult()
    data class Cancelled(val attachmentId: String) : EditResult()
    data class Error(val attachmentId: String, val message: String) : EditResult()
}

/**
 * Helper class for editing attachments using system or fallback editors.
 */
class AttachmentEditor(private val context: Context) {
        private const val AUTHORITY = "com.bothbubbles.fileprovider"
    private val editCacheDir: File by lazy {
        File(context.cacheDir, "edited_attachments").apply { mkdirs() }
    }

    /**
     * Check if the device has a system image editor available.
     */
    fun hasSystemEditor(): Boolean {
        val testIntent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(Uri.parse("content://test"), "image/jpeg")
        }
        return testIntent.resolveActivity(context.packageManager) != null
    }

    /**
     * Create an intent to launch the system image editor.
     */
    fun createSystemEditorIntent(uri: Uri): Intent? {
        val mimeType = context.contentResolver.getType(uri) ?: "image/*"

        // First try ACTION_EDIT
        val editIntent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        if (editIntent.resolveActivity(context.packageManager) != null) {
            return Intent.createChooser(editIntent, "Edit image")
        }

        // Fallback to crop intent (often available even when edit isn't)
        val cropIntent = Intent("com.android.camera.action.CROP").apply {
            setDataAndType(uri, mimeType)
            putExtra("crop", "true")
            putExtra("aspectX", 0)
            putExtra("aspectY", 0)
            putExtra("return-data", false)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (cropIntent.resolveActivity(context.packageManager) != null) {
            return cropIntent
        }

        Timber.w("No system editor found")
        return null
    }

    /**
     * Create a temporary output file for edited images.
     */
    fun createOutputUri(attachmentId: String): Uri {
        val outputFile = File(editCacheDir, "edit_${attachmentId}_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, AUTHORITY, outputFile)
    }

    /**
     * Rotate an image by the specified degrees and save to a new file.
     * @return URI of the rotated image, or null if rotation failed.
     */
    fun rotateImage(uri: Uri, degrees: Float): Uri? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val matrix = Matrix().apply { postRotate(degrees) }
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()

            val outputFile = File(editCacheDir, "rotated_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outputFile).use { out ->
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            rotatedBitmap.recycle()

            FileProvider.getUriForFile(context, AUTHORITY, outputFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to rotate image")
            null
        }
    }

    /**
     * Crop an image to the specified rectangle and save to a new file.
     * @return URI of the cropped image, or null if cropping failed.
     */
    fun cropImage(uri: Uri, left: Int, top: Int, width: Int, height: Int): Uri? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)
            bitmap.recycle()

            val outputFile = File(editCacheDir, "cropped_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outputFile).use { out ->
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            croppedBitmap.recycle()

            FileProvider.getUriForFile(context, AUTHORITY, outputFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to crop image")
            null
        }
    }

    /**
     * Clean up old edited files.
     */
    fun cleanupCache() {
        editCacheDir.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000) {
                file.delete()
            }
        }
    }
}

/**
 * Composable helper to remember an AttachmentEditor instance.
 */
@Composable
fun rememberAttachmentEditor(): AttachmentEditor {
    val context = LocalContext.current
    return remember { AttachmentEditor(context) }
}
