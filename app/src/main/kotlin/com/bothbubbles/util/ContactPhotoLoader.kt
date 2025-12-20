package com.bothbubbles.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.provider.ContactsContract
import timber.log.Timber
import kotlin.math.min

/**
 * Utility for loading contact photos from content:// URIs.
 *
 * Handles multiple URI formats:
 * - content://com.android.contacts/contacts/{id}/photo
 * - content://com.android.contacts/contacts/{id}/display_photo
 * - content://com.android.contacts/display_photo/{id}
 * - content://com.android.contacts/data/{id}
 *
 * Extracted from AvatarGenerator to separate photo loading concerns.
 */
object ContactPhotoLoader {

    /**
     * Load a contact photo from a content:// URI and convert it to a bitmap.
     * Used for notifications where content URIs can't be passed directly (the notification
     * system doesn't have permission to read contact photos).
     *
     * @param context Application context for ContentResolver access
     * @param photoUri The content:// URI for the contact photo
     * @param sizePx Target size for the output bitmap
     * @param circleCrop If true, crops to a circle. If false, returns square bitmap.
     * @return A bitmap of the contact photo, or null if loading fails
     */
    fun loadContactPhotoBitmap(
        context: Context,
        photoUri: String,
        sizePx: Int,
        circleCrop: Boolean = true
    ): Bitmap? {
        return try {
            val uri = Uri.parse(photoUri)
            Timber.d("Loading contact photo: $photoUri")

            // Try direct ContentResolver access first
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Timber.w("openInputStream returned null for: $photoUri, trying alternative method")
                return loadContactPhotoAlternative(context, uri, sizePx, circleCrop)
            }

            inputStream.use { stream ->
                val sourceBitmap = BitmapFactory.decodeStream(stream)
                if (sourceBitmap == null) {
                    Timber.w("Failed to decode contact photo: $photoUri")
                    return null
                }

                processPhotoBitmap(sourceBitmap, sizePx, circleCrop)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load contact photo: $photoUri")
            null
        }
    }

    /**
     * Process a raw photo bitmap: center-crop, scale, and optionally make circular.
     */
    private fun processPhotoBitmap(sourceBitmap: Bitmap, sizePx: Int, circleCrop: Boolean): Bitmap {
        // Center-crop to square first (like ContentScale.Crop in Compose)
        val croppedBitmap = centerCropToSquare(sourceBitmap)
        if (croppedBitmap != sourceBitmap) {
            sourceBitmap.recycle()
        }

        // Scale to target size
        val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, sizePx, sizePx, true)
        if (scaledBitmap != croppedBitmap) {
            croppedBitmap.recycle()
        }

        return if (circleCrop) {
            val circularBitmap = createCircularBitmap(scaledBitmap)
            if (circularBitmap != scaledBitmap) {
                scaledBitmap.recycle()
            }
            circularBitmap
        } else {
            scaledBitmap
        }
    }

    /**
     * Alternative method to load contact photo using ContactsContract API.
     */
    private fun loadContactPhotoAlternative(
        context: Context,
        photoUri: Uri,
        sizePx: Int,
        circleCrop: Boolean
    ): Bitmap? {
        return try {
            val segments = photoUri.pathSegments

            // Handle display_photo/{id} format specially - this is a photo file ID, not a contact ID
            if (segments.size >= 2 && segments[0] == "display_photo") {
                Timber.d("Detected display_photo format, attempting direct stream read: $photoUri")
                return loadDisplayPhoto(context, photoUri, sizePx, circleCrop)
            }

            // For other formats, try to extract contact ID
            val contactId = extractContactId(photoUri, segments) ?: run {
                Timber.w("Could not extract contact ID from URI: $photoUri")
                return null
            }

            loadPhotoByContactId(context, contactId, sizePx, circleCrop)
        } catch (e: Exception) {
            Timber.w(e, "Alternative photo load failed for: $photoUri")
            null
        }
    }

    /**
     * Load display_photo format directly.
     */
    private fun loadDisplayPhoto(
        context: Context,
        photoUri: Uri,
        sizePx: Int,
        circleCrop: Boolean
    ): Bitmap? {
        return try {
            context.contentResolver.openInputStream(photoUri)?.use { stream ->
                val sourceBitmap = BitmapFactory.decodeStream(stream)
                if (sourceBitmap != null) {
                    Timber.d("Successfully loaded display_photo via direct stream")
                    processPhotoBitmap(sourceBitmap, sizePx, circleCrop)
                } else {
                    Timber.w("Failed to decode display_photo stream")
                    null
                }
            }
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException reading display_photo - permission may be missing")
            null
        } catch (e: Exception) {
            Timber.w(e, "Failed to read display_photo stream")
            null
        }
    }

    /**
     * Extract contact ID from various URI formats.
     */
    private fun extractContactId(photoUri: Uri, segments: List<String>): Long? {
        return when {
            // Format: /contacts/{id}/photo or /contacts/{id}/display_photo
            segments.size >= 2 && segments[0] == "contacts" -> {
                segments.getOrNull(1)?.toLongOrNull()
            }
            // Format: /data/{id} - try to use ContentUris.parseId as fallback
            else -> {
                try {
                    ContentUris.parseId(photoUri).takeIf { it > 0 }
                } catch (e: Exception) {
                    // parseId throws if URI doesn't end with a valid ID
                    photoUri.lastPathSegment?.toLongOrNull()
                }
            }
        }
    }

    /**
     * Load photo using contact ID and ContactsContract API.
     */
    private fun loadPhotoByContactId(
        context: Context,
        contactId: Long,
        sizePx: Int,
        circleCrop: Boolean
    ): Bitmap? {
        val contactUri = ContentUris.withAppendedId(
            ContactsContract.Contacts.CONTENT_URI,
            contactId
        )

        // Use the official Android API for loading contact photos
        val photoStream = ContactsContract.Contacts.openContactPhotoInputStream(
            context.contentResolver,
            contactUri,
            true // preferHighRes
        )

        if (photoStream == null) {
            Timber.w("openContactPhotoInputStream returned null for contact: $contactId")
            return null
        }

        return photoStream.use { stream ->
            val sourceBitmap = BitmapFactory.decodeStream(stream)
            if (sourceBitmap == null) {
                Timber.w("Failed to decode photo from alternative method")
                return null
            }

            Timber.d("Successfully loaded photo via openContactPhotoInputStream for contact: $contactId")
            processPhotoBitmap(sourceBitmap, sizePx, circleCrop)
        }
    }

    /**
     * Center-crop a bitmap to make it square, like ContentScale.Crop in Compose.
     * If already square, returns the same bitmap (no copy).
     */
    private fun centerCropToSquare(source: Bitmap): Bitmap {
        if (source.width == source.height) {
            return source
        }

        val size = min(source.width, source.height)
        val xOffset = (source.width - size) / 2
        val yOffset = (source.height - size) / 2

        return Bitmap.createBitmap(source, xOffset, yOffset, size, size)
    }

    /**
     * Create a circular bitmap from a square bitmap.
     * Clips the source bitmap into a circle to match the app's avatar style.
     */
    private fun createCircularBitmap(source: Bitmap): Bitmap {
        val size = min(source.width, source.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        val radius = center

        // Draw the circular mask (full size, no padding)
        canvas.drawCircle(center, center, radius, paint)

        // Draw source using SRC_IN to clip to the circle
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)

        return output
    }
}
