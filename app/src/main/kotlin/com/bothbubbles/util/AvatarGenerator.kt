package com.bothbubbles.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.net.Uri
import android.provider.ContactsContract
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.R
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.min

/**
 * Shared avatar generation utility used by both the Compose UI (Avatar.kt)
 * and notifications (NotificationService.kt) to ensure consistent avatar appearance.
 */
object AvatarGenerator {
    // Google Messages-style avatar colors - muted pastels that work in light and dark mode
    private val avatarColors = listOf(
        0xFF5C6BC0.toInt(), // Soft Indigo
        0xFF26A69A.toInt(), // Muted Teal
        0xFFAB47BC.toInt(), // Soft Purple
        0xFFEC407A.toInt(), // Dusty Rose
        0xFF42A5F5.toInt(), // Soft Blue
    )

    /**
     * Get the avatar background color for a name as an Int (for Canvas/Paint).
     * Uses hash of name to ensure consistent color per contact.
     */
    fun getAvatarColorInt(name: String): Int {
        val hash = abs(name.hashCode())
        return avatarColors[hash % avatarColors.size]
    }

    /**
     * Extract initials from a name (up to 2 characters).
     * - For "John Doe" -> "JD"
     * - For "Johnny" -> "JO"
     * - For "J" -> "J"
     * - Falls back to "?" if empty
     */
    fun getInitials(name: String): String {
        // Strip emojis and other non-letter/non-digit characters at the start of each word
        val cleanedName = name.trim()
            .split(" ")
            .map { word -> word.filter { it.isLetterOrDigit() } }
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val parts = cleanedName.split(" ").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}"
            parts.size == 1 && parts.first().length >= 2 -> parts.first().take(2)
            parts.size == 1 -> parts.first().take(1)
            else -> "?"
        }.uppercase()
    }

    /**
     * Check if name looks like a phone number (7+ digits, only phone chars).
     * When true, avatar should show a Person icon instead of initials.
     */
    fun isPhoneNumber(name: String): Boolean {
        val digitsOnly = name.replace(Regex("[^0-9]"), "")
        return digitsOnly.length >= 7 && name.matches(Regex("^[+\\d\\s()\\-]+$"))
    }

    /**
     * Check if name looks like a shortcode (5-6 digit business number) or
     * alphanumeric sender ID (e.g., "GOOGLE", "AMZN", "BANK").
     * When true, avatar should show a Building icon.
     */
    fun isShortCodeOrAlphanumericSender(name: String): Boolean {
        val trimmed = name.trim()
        // Real names have spaces (e.g., "Anne Chodzko"), sender IDs don't (e.g., "GOOGLE")
        if (trimmed.contains(' ')) return false

        val normalized = trimmed.replace(Regex("[^0-9a-zA-Z]"), "")
        // Short codes: exactly 5-6 digits
        val isShortCode = Regex("""^\d{5,6}$""").matches(normalized)
        // Alphanumeric sender IDs: 3-11 letters only (e.g., "GOOGLE", "AMZN")
        val isAlphanumeric = Regex("""^[A-Za-z]{3,11}$""").matches(normalized)
        return isShortCode || isAlphanumeric
    }

    /**
     * Generate a bitmap avatar with colored circle and initials (or icon).
     * Used for notifications where Compose components aren't available.
     *
     * @param context Application context for loading resources
     * @param name The contact name to generate avatar for
     * @param sizePx The size of the bitmap in pixels (typically 128 for notifications)
     * @param isBusiness If true, shows building icon (for business contacts without personal name)
     * @param hasContactInfo If true, skips business heuristic (contact has saved info)
     * @param circleCrop If true, crops to a circle. If false, fills the square (for adaptive icons).
     * @return A bitmap with colored background and white initials/icon
     */
    fun generateBitmap(
        context: Context,
        name: String,
        sizePx: Int,
        isBusiness: Boolean = false,
        hasContactInfo: Boolean = false,
        circleCrop: Boolean = true
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Get consistent color based on name hash
        val backgroundColor = getAvatarColorInt(name)

        // Draw background (circle or square)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            style = Paint.Style.FILL
        }

        val center = sizePx / 2f
        if (circleCrop) {
            canvas.drawCircle(center, center, center, paint)
        } else {
            canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
        }

        // Determine icon type based on name and contact info
        // Only apply business heuristic if we don't have contact info (prevents "ALICE" being shown as business)
        val showBuildingIcon = when {
            isBusiness -> true
            hasContactInfo -> false // Has contact info, not a business
            else -> isShortCodeOrAlphanumericSender(name)
        }
        val showPersonIcon = !showBuildingIcon && !hasContactInfo && isPhoneNumber(name)

        when {
            // Business contacts get building icon
            showBuildingIcon -> {
                drawBuildingIcon(context, canvas, sizePx)
            }
            // Phone numbers without contact info get person icon
            showPersonIcon -> {
                drawPersonIcon(canvas, sizePx)
            }
            // Everyone else gets initials
            else -> {
                val initials = getInitials(name)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = sizePx * 0.4f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }

                // Center text vertically (use center, not radius, for positioning)
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(initials, 0, initials.length, textBounds)
                val yPos = center - textBounds.exactCenterY()

                canvas.drawText(initials, center, yPos, textPaint)
            }
        }

        return bitmap
    }

    /**
     * Generate an IconCompat for use in notifications.
     *
     * @param context Application context
     * @param name The contact name to generate avatar for
     * @param sizePx The size of the bitmap in pixels
     * @param hasContactInfo If true, skips business heuristic (contact has saved info)
     * @return An IconCompat that can be used with Person.Builder.setIcon()
     */
    fun generateIconCompat(context: Context, name: String, sizePx: Int, hasContactInfo: Boolean = false): IconCompat {
        val bitmap = generateBitmap(context, name, sizePx, hasContactInfo = hasContactInfo)
        return IconCompat.createWithBitmap(bitmap)
    }

    /**
     * Load a contact photo from a content:// URI and convert it to a bitmap.
     * Used for notifications where content URIs can't be passed directly (the notification
     * system doesn't have permission to read contact photos).
     *
     * @param context Application context for ContentResolver access
     * @param photoUri The content:// URI for the contact photo
     * @param sizePx Target size for the output bitmap
     * @param circleCrop If true, crops to a circle. If false, returns square bitmap (for adaptive icons).
     * @return A bitmap of the contact photo, or null if loading fails
     */
    fun loadContactPhotoBitmap(context: Context, photoUri: String, sizePx: Int, circleCrop: Boolean = true): Bitmap? {
        return try {
            val uri = Uri.parse(photoUri)
            Timber.d("Loading contact photo: $photoUri")

            // Try direct ContentResolver access first
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Timber.w("openInputStream returned null for: $photoUri, trying alternative method")
                // Try alternative: use openContactPhotoInputStream which may handle permissions better
                return loadContactPhotoAlternative(context, uri, sizePx, circleCrop)
            }

            inputStream.use { stream ->
                // Decode bitmap
                val sourceBitmap = BitmapFactory.decodeStream(stream)
                if (sourceBitmap == null) {
                    Timber.w("Failed to decode contact photo: $photoUri")
                    return null
                }

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

                if (circleCrop) {
                    // Make it circular to match generated avatars
                    val circularBitmap = createCircularBitmap(scaledBitmap)
                    if (circularBitmap != scaledBitmap) {
                        scaledBitmap.recycle()
                    }
                    circularBitmap
                } else {
                    scaledBitmap
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load contact photo: $photoUri")
            null
        }
    }

    /**
     * Alternative method to load contact photo using ContactsContract API.
     * Handles multiple URI formats:
     * - content://com.android.contacts/contacts/{id}/photo
     * - content://com.android.contacts/contacts/{id}/display_photo
     * - content://com.android.contacts/display_photo/{id} (hi-res photo file - NOT a contact ID!)
     * - content://com.android.contacts/data/{id}
     */
    private fun loadContactPhotoAlternative(context: Context, photoUri: Uri, sizePx: Int, circleCrop: Boolean): Bitmap? {
        return try {
            val segments = photoUri.pathSegments

            // Handle display_photo/{id} format specially - this is a photo file ID, not a contact ID
            // These URIs should be directly openable, so try one more time with explicit permission handling
            if (segments.size >= 2 && segments[0] == "display_photo") {
                Timber.d("Detected display_photo format, attempting direct stream read: $photoUri")
                return try {
                    context.contentResolver.openInputStream(photoUri)?.use { stream ->
                        val sourceBitmap = BitmapFactory.decodeStream(stream)
                        if (sourceBitmap != null) {
                            // Center-crop to square first (like ContentScale.Crop in Compose)
                            val croppedBitmap = centerCropToSquare(sourceBitmap)
                            if (croppedBitmap != sourceBitmap) sourceBitmap.recycle()
                            val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, sizePx, sizePx, true)
                            if (scaledBitmap != croppedBitmap) croppedBitmap.recycle()
                            
                            if (circleCrop) {
                                val circularBitmap = createCircularBitmap(scaledBitmap)
                                if (circularBitmap != scaledBitmap) scaledBitmap.recycle()
                                Timber.d("Successfully loaded display_photo via direct stream")
                                circularBitmap
                            } else {
                                Timber.d("Successfully loaded display_photo via direct stream")
                                scaledBitmap
                            }
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

            // For other formats, try to extract contact ID
            val contactId: Long? = when {
                // Format: /contacts/{id}/photo or /contacts/{id}/display_photo
                segments.size >= 2 && segments[0] == "contacts" -> {
                    segments.getOrNull(1)?.toLongOrNull()
                }
                // Format: /data/{id} - try to use ContentUris.parseId as fallback
                else -> {
                    try {
                        android.content.ContentUris.parseId(photoUri).takeIf { it > 0 }
                    } catch (e: Exception) {
                        // parseId throws if URI doesn't end with a valid ID
                        photoUri.lastPathSegment?.toLongOrNull()
                    }
                }
            }

            if (contactId == null) {
                Timber.w("Could not extract contact ID from URI: $photoUri")
                return null
            }

            val contactUri = android.content.ContentUris.withAppendedId(
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

            photoStream.use { stream ->
                val sourceBitmap = BitmapFactory.decodeStream(stream)
                if (sourceBitmap == null) {
                    Timber.w("Failed to decode photo from alternative method")
                    return null
                }

                // Center-crop to square first (like ContentScale.Crop in Compose)
                val croppedBitmap = centerCropToSquare(sourceBitmap)
                if (croppedBitmap != sourceBitmap) {
                    sourceBitmap.recycle()
                }

                val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, sizePx, sizePx, true)
                if (scaledBitmap != croppedBitmap) {
                    croppedBitmap.recycle()
                }

                if (circleCrop) {
                    val circularBitmap = createCircularBitmap(scaledBitmap)
                    if (circularBitmap != scaledBitmap) {
                        scaledBitmap.recycle()
                    }
                    Timber.d("Successfully loaded photo via openContactPhotoInputStream for contact: $contactId")
                    circularBitmap
                } else {
                    Timber.d("Successfully loaded photo via openContactPhotoInputStream for contact: $contactId")
                    scaledBitmap
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Alternative photo load failed for: $photoUri")
            null
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
     * No padding - fills the entire bitmap for clean display in Android bubbles.
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

    /**
     * Generate a group avatar collage bitmap for notifications.
     * Creates a composite image with 2-4 participant avatars arranged in a grid/layout.
     * Uses TRANSPARENT background so avatars composite cleanly in notifications.
     *
     * @param context Application context
     * @param names List of participant names (up to 4 will be shown)
     * @param sizePx The size of the output bitmap in pixels
     * @param circleCrop If true, leaves background transparent. If false, fills background (for adaptive icons).
     * @return A bitmap with multiple avatars
     */
    fun generateGroupCollageBitmap(context: Context, names: List<String>, sizePx: Int, circleCrop: Boolean = true): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        if (!circleCrop) {
            // Fill background for adaptive icons (prevent black background on some launchers)
            canvas.drawColor(0xFFF5F5F5.toInt()) // Light gray
        }

        val displayCount = minOf(names.size, 4)

        when (displayCount) {
            0, 1 -> {
                // Single or no participants - draw a group icon
                drawGroupIcon(canvas, sizePx)
            }
            2 -> {
                // Two participants - offset circles (like GroupAvatar composable)
                val smallSize = (sizePx * 0.65f).toInt()
                val offset = sizePx - smallSize

                // First avatar at top-left
                val avatar1 = generateBitmap(context, names[0], smallSize)
                canvas.drawBitmap(avatar1, 0f, 0f, null)
                avatar1.recycle()

                // Second avatar at bottom-right
                val avatar2 = generateBitmap(context, names[1], smallSize)
                canvas.drawBitmap(avatar2, offset.toFloat(), offset.toFloat(), null)
                avatar2.recycle()
            }
            3 -> {
                // Three participants - triangle arrangement
                val smallSize = (sizePx * 0.5f).toInt()
                val centerX = (sizePx - smallSize) / 2f

                // First avatar at top-center
                val avatar1 = generateBitmap(context, names[0], smallSize)
                canvas.drawBitmap(avatar1, centerX, 0f, null)
                avatar1.recycle()

                // Second avatar at bottom-left
                val avatar2 = generateBitmap(context, names[1], smallSize)
                canvas.drawBitmap(avatar2, 0f, (sizePx - smallSize).toFloat(), null)
                avatar2.recycle()

                // Third avatar at bottom-right
                val avatar3 = generateBitmap(context, names[2], smallSize)
                canvas.drawBitmap(avatar3, (sizePx - smallSize).toFloat(), (sizePx - smallSize).toFloat(), null)
                avatar3.recycle()
            }
            else -> {
                // Four participants - 2x2 grid
                val smallSize = (sizePx * 0.48f).toInt()
                val gap = (sizePx - 2 * smallSize) / 3f

                val positions = listOf(
                    Pair(gap, gap),                                    // Top-left
                    Pair(gap * 2 + smallSize, gap),                   // Top-right
                    Pair(gap, gap * 2 + smallSize),                   // Bottom-left
                    Pair(gap * 2 + smallSize, gap * 2 + smallSize)    // Bottom-right
                )

                for (i in 0 until 4) {
                    val avatar = generateBitmap(context, names[i], smallSize)
                    canvas.drawBitmap(avatar, positions[i].first, positions[i].second, null)
                    avatar.recycle()
                }
            }
        }

        return bitmap
    }

    /**
     * Generate a group avatar collage bitmap with actual contact photos.
     * Participants with photos are prioritized to appear first in the collage.
     *
     * @param context Application context for loading contact photos
     * @param names List of participant names (up to 4 will be shown)
     * @param avatarPaths List of avatar paths (corresponding to names, can contain nulls)
     * @param sizePx The size of the output bitmap in pixels
     * @param circleCrop If true, leaves background transparent. If false, fills background (for adaptive icons).
     * @return A bitmap with multiple avatars
     */
    fun generateGroupCollageBitmapWithPhotos(
        context: Context,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int,
        circleCrop: Boolean = true
    ): Bitmap {
        // Sort participants to prioritize those with photos
        val sortedIndices = names.indices.sortedByDescending { index ->
            avatarPaths.getOrNull(index) != null
        }
        val sortedNames = sortedIndices.map { names[it] }
        val sortedAvatarPaths = sortedIndices.map { avatarPaths.getOrNull(it) }

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (!circleCrop) {
            // Fill background for adaptive icons
            canvas.drawColor(0xFFF5F5F5.toInt()) // Light gray
        }

        val displayCount = minOf(sortedNames.size, 4)

        // Helper to get avatar bitmap (loads photo or generates fallback)
        fun getParticipantBitmap(index: Int, size: Int): Bitmap {
            val avatarPath = sortedAvatarPaths.getOrNull(index)
            val name = sortedNames.getOrElse(index) { "?" }

            // Try to load contact photo first
            if (avatarPath != null) {
                val photoBitmap = loadContactPhotoBitmap(context, avatarPath, size)
                if (photoBitmap != null) {
                    return photoBitmap
                }
            }

            // Fall back to generated avatar (hasContactInfo = true if they had a path)
            return generateBitmap(context, name, size, hasContactInfo = avatarPath != null)
        }

        when (displayCount) {
            0, 1 -> {
                drawGroupIcon(canvas, sizePx)
            }
            2 -> {
                val smallSize = (sizePx * 0.65f).toInt()
                val offset = sizePx - smallSize

                val avatar1 = getParticipantBitmap(0, smallSize)
                canvas.drawBitmap(avatar1, 0f, 0f, null)
                avatar1.recycle()

                val avatar2 = getParticipantBitmap(1, smallSize)
                canvas.drawBitmap(avatar2, offset.toFloat(), offset.toFloat(), null)
                avatar2.recycle()
            }
            3 -> {
                val smallSize = (sizePx * 0.5f).toInt()
                val centerX = (sizePx - smallSize) / 2f

                val avatar1 = getParticipantBitmap(0, smallSize)
                canvas.drawBitmap(avatar1, centerX, 0f, null)
                avatar1.recycle()

                val avatar2 = getParticipantBitmap(1, smallSize)
                canvas.drawBitmap(avatar2, 0f, (sizePx - smallSize).toFloat(), null)
                avatar2.recycle()

                val avatar3 = getParticipantBitmap(2, smallSize)
                canvas.drawBitmap(avatar3, (sizePx - smallSize).toFloat(), (sizePx - smallSize).toFloat(), null)
                avatar3.recycle()
            }
            else -> {
                val smallSize = (sizePx * 0.48f).toInt()
                val gap = (sizePx - 2 * smallSize) / 3f

                val positions = listOf(
                    Pair(gap, gap),
                    Pair(gap * 2 + smallSize, gap),
                    Pair(gap, gap * 2 + smallSize),
                    Pair(gap * 2 + smallSize, gap * 2 + smallSize)
                )

                for (i in 0 until 4) {
                    val avatar = getParticipantBitmap(i, smallSize)
                    canvas.drawBitmap(avatar, positions[i].first, positions[i].second, null)
                    avatar.recycle()
                }
            }
        }

        return bitmap
    }

    /**
     * Generate an IconCompat for group notifications with actual contact photos.
     *
     * @param context Application context for loading contact photos
     * @param names List of participant names
     * @param avatarPaths List of avatar paths (corresponding to names, can contain nulls)
     * @param sizePx The size of the bitmap in pixels
     * @return An IconCompat with the group collage
     */
    fun generateGroupIconCompatWithPhotos(
        context: Context,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int
    ): IconCompat {
        val bitmap = generateGroupCollageBitmapWithPhotos(context, names, avatarPaths, sizePx)
        return IconCompat.createWithBitmap(bitmap)
    }

    /**
     * Generate an IconCompat for group notifications.
     *
     * @param context Application context
     * @param names List of participant names
     * @param sizePx The size of the bitmap in pixels
     * @return An IconCompat with the group collage
     */
    fun generateGroupIconCompat(context: Context, names: List<String>, sizePx: Int): IconCompat {
        val bitmap = generateGroupCollageBitmap(context, names, sizePx)
        return IconCompat.createWithBitmap(bitmap)
    }

    /**
     * Generate an adaptive IconCompat for use in notifications/bubbles.
     * Uses a full-bleed square bitmap that the system will mask.
     */
    fun generateAdaptiveIconCompat(context: Context, name: String, sizePx: Int, hasContactInfo: Boolean = false): IconCompat {
        val bitmap = generateBitmap(context, name, sizePx, hasContactInfo = hasContactInfo, circleCrop = false)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /**
     * Generate an adaptive IconCompat for group notifications/bubbles.
     * Uses a full-bleed square bitmap that the system will mask.
     */
    fun generateGroupAdaptiveIconCompatWithPhotos(
        context: Context,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int
    ): IconCompat {
        val bitmap = generateGroupCollageBitmapWithPhotos(context, names, avatarPaths, sizePx, circleCrop = false)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /**
     * Draw a group icon (two person silhouettes) on the canvas.
     * Used when there's 0-1 participants in a "group".
     */
    private fun drawGroupIcon(canvas: Canvas, size: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5C6BC0.toInt() // Use first avatar color
            style = Paint.Style.FILL
        }

        // Draw circular background
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)

        // Draw two overlapping person silhouettes in white
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }

        val centerY = size * 0.45f

        // Left person (slightly smaller, behind)
        val leftHeadRadius = size * 0.11f
        val leftHeadX = size * 0.35f
        val leftHeadY = centerY - size * 0.02f
        canvas.drawCircle(leftHeadX, leftHeadY, leftHeadRadius, iconPaint)

        val leftBodyTop = leftHeadY + leftHeadRadius + size * 0.03f
        val leftBodyWidth = size * 0.2f
        val leftBodyRect = android.graphics.RectF(
            leftHeadX - leftBodyWidth,
            leftBodyTop,
            leftHeadX + leftBodyWidth,
            leftBodyTop + size * 0.25f
        )
        canvas.drawArc(leftBodyRect, 0f, -180f, true, iconPaint)

        // Right person (slightly larger, in front)
        val rightHeadRadius = size * 0.13f
        val rightHeadX = size * 0.6f
        val rightHeadY = centerY
        canvas.drawCircle(rightHeadX, rightHeadY, rightHeadRadius, iconPaint)

        val rightBodyTop = rightHeadY + rightHeadRadius + size * 0.04f
        val rightBodyWidth = size * 0.25f
        val rightBodyRect = android.graphics.RectF(
            rightHeadX - rightBodyWidth,
            rightBodyTop,
            rightHeadX + rightBodyWidth,
            rightBodyTop + size * 0.3f
        )
        canvas.drawArc(rightBodyRect, 0f, -180f, true, iconPaint)
    }

    /**
     * Draw a simple person silhouette icon on the canvas.
     * Used when the name is a phone number (no initials to show).
     */
    private fun drawPersonIcon(canvas: Canvas, size: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }

        val centerX = size / 2f
        val centerY = size / 2f

        // Head (circle)
        val headRadius = size * 0.15f
        val headCenterY = centerY - size * 0.08f
        canvas.drawCircle(centerX, headCenterY, headRadius, paint)

        // Body (arc/shoulders)
        val bodyTop = headCenterY + headRadius + size * 0.05f
        val bodyWidth = size * 0.35f
        val bodyRect = android.graphics.RectF(
            centerX - bodyWidth,
            bodyTop,
            centerX + bodyWidth,
            bodyTop + size * 0.35f
        )
        canvas.drawArc(bodyRect, 0f, -180f, true, paint)
    }

    /**
     * Draw the Material business icon on the canvas.
     * Used for shortcodes and business contacts without personal names.
     */
    private fun drawBuildingIcon(context: Context, canvas: Canvas, size: Int) {
        val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_business)
        drawable?.let {
            it.setTint(android.graphics.Color.WHITE)
            
            // Scale it down to fit nicely (approx 50% of size, centered)
            val padding = (size * 0.25f).toInt()
            it.setBounds(padding, padding, size - padding, size - padding)
            it.draw(canvas)
        }
    }
}
