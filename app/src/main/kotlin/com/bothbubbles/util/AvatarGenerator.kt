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
import android.util.Log
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.abs
import kotlin.math.min

/**
 * Shared avatar generation utility used by both the Compose UI (Avatar.kt)
 * and notifications (NotificationService.kt) to ensure consistent avatar appearance.
 */
object AvatarGenerator {
    private const val TAG = "AvatarGenerator"

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
        val normalized = name.replace(Regex("[^0-9a-zA-Z]"), "")
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
     * @param name The contact name to generate avatar for
     * @param sizePx The size of the bitmap in pixels (typically 128 for notifications)
     * @param isBusiness If true, shows building icon (for business contacts without personal name)
     * @param hasContactInfo If true, skips business heuristic (contact has saved info)
     * @return A circular bitmap with colored background and white initials/icon
     */
    fun generateBitmap(
        name: String,
        sizePx: Int,
        isBusiness: Boolean = false,
        hasContactInfo: Boolean = false
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Get consistent color based on name hash
        val backgroundColor = getAvatarColorInt(name)

        // Draw circular background
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            style = Paint.Style.FILL
        }
        val radius = sizePx / 2f
        canvas.drawCircle(radius, radius, radius, circlePaint)

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
                drawBuildingIcon(canvas, sizePx)
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

                // Center text vertically
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(initials, 0, initials.length, textBounds)
                val yPos = radius - textBounds.exactCenterY()

                canvas.drawText(initials, radius, yPos, textPaint)
            }
        }

        return bitmap
    }

    /**
     * Generate an IconCompat for use in notifications.
     *
     * @param name The contact name to generate avatar for
     * @param sizePx The size of the bitmap in pixels
     * @param hasContactInfo If true, skips business heuristic (contact has saved info)
     * @return An IconCompat that can be used with Person.Builder.setIcon()
     */
    fun generateIconCompat(name: String, sizePx: Int, hasContactInfo: Boolean = false): IconCompat {
        val bitmap = generateBitmap(name, sizePx, hasContactInfo = hasContactInfo)
        return IconCompat.createWithBitmap(bitmap)
    }

    /**
     * Load a contact photo from a content:// URI and convert it to a circular bitmap.
     * Used for notifications where content URIs can't be passed directly (the notification
     * system doesn't have permission to read contact photos).
     *
     * @param context Application context for ContentResolver access
     * @param photoUri The content:// URI for the contact photo
     * @param sizePx Target size for the output bitmap
     * @return A circular bitmap of the contact photo, or null if loading fails
     */
    fun loadContactPhotoBitmap(context: Context, photoUri: String, sizePx: Int): Bitmap? {
        return try {
            val uri = Uri.parse(photoUri)
            Log.d(TAG, "Loading contact photo: $photoUri")

            // Try direct ContentResolver access first
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.w(TAG, "openInputStream returned null for: $photoUri, trying alternative method")
                // Try alternative: use openContactPhotoInputStream which may handle permissions better
                return loadContactPhotoAlternative(context, uri, sizePx)
            }

            inputStream.use { stream ->
                // Decode bitmap
                val sourceBitmap = BitmapFactory.decodeStream(stream)
                if (sourceBitmap == null) {
                    Log.w(TAG, "Failed to decode contact photo: $photoUri")
                    return null
                }

                // Scale to target size
                val scaledBitmap = Bitmap.createScaledBitmap(sourceBitmap, sizePx, sizePx, true)
                if (scaledBitmap != sourceBitmap) {
                    sourceBitmap.recycle()
                }

                // Make it circular to match generated avatars
                val circularBitmap = createCircularBitmap(scaledBitmap)
                if (circularBitmap != scaledBitmap) {
                    scaledBitmap.recycle()
                }

                circularBitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load contact photo: $photoUri", e)
            null
        }
    }

    /**
     * Alternative method to load contact photo using ContactsContract API.
     * Extracts contact ID from URI and uses openContactPhotoInputStream.
     */
    private fun loadContactPhotoAlternative(context: Context, photoUri: Uri, sizePx: Int): Bitmap? {
        return try {
            // Try to extract contact ID using robust parsing methods
            // Supported URI formats:
            // content://com.android.contacts/contacts/{id}/photo
            // content://com.android.contacts/contacts/{id}/display_photo
            // content://com.android.contacts/data/{id}
            val segments = photoUri.pathSegments
            val contactId: Long? = when {
                // Format: /contacts/{id}/photo or /contacts/{id}/display_photo
                segments.size >= 2 && segments[0] == "contacts" -> {
                    // Use safe parsing instead of manual segment extraction
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
                Log.w(TAG, "Could not extract contact ID from URI: $photoUri")
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
                Log.w(TAG, "openContactPhotoInputStream also returned null for contact: $contactId")
                return null
            }

            photoStream.use { stream ->
                val sourceBitmap = BitmapFactory.decodeStream(stream)
                if (sourceBitmap == null) {
                    Log.w(TAG, "Failed to decode photo from alternative method")
                    return null
                }

                val scaledBitmap = Bitmap.createScaledBitmap(sourceBitmap, sizePx, sizePx, true)
                if (scaledBitmap != sourceBitmap) {
                    sourceBitmap.recycle()
                }

                val circularBitmap = createCircularBitmap(scaledBitmap)
                if (circularBitmap != scaledBitmap) {
                    scaledBitmap.recycle()
                }

                Log.d(TAG, "Successfully loaded photo via alternative method for contact: $contactId")
                circularBitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Alternative photo load failed for: $photoUri", e)
            null
        }
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
        val radius = size / 2f

        // Draw the circular mask
        canvas.drawCircle(radius, radius, radius, paint)

        // Draw the source bitmap using SRC_IN to clip to the circle
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)

        return output
    }

    /**
     * Generate a group avatar collage bitmap for notifications.
     * Creates a composite image with 2-4 participant avatars arranged in a grid/layout.
     * Uses TRANSPARENT background so avatars composite cleanly in notifications.
     *
     * @param names List of participant names (up to 4 will be shown)
     * @param sizePx The size of the output bitmap in pixels
     * @return A bitmap with multiple avatars on a transparent background
     */
    fun generateGroupCollageBitmap(names: List<String>, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Canvas starts fully transparent - we don't fill any background

        val displayCount = minOf(names.size, 4)

        when (displayCount) {
            0, 1 -> {
                // Single or no participants - draw a group icon on transparent background
                drawGroupIcon(canvas, sizePx)
            }
            2 -> {
                // Two participants - offset circles (like GroupAvatar composable)
                val smallSize = (sizePx * 0.65f).toInt()
                val offset = sizePx - smallSize

                // First avatar at top-left
                val avatar1 = generateBitmap(names[0], smallSize)
                canvas.drawBitmap(avatar1, 0f, 0f, null)
                avatar1.recycle()

                // Second avatar at bottom-right
                val avatar2 = generateBitmap(names[1], smallSize)
                canvas.drawBitmap(avatar2, offset.toFloat(), offset.toFloat(), null)
                avatar2.recycle()
            }
            3 -> {
                // Three participants - triangle arrangement
                val smallSize = (sizePx * 0.5f).toInt()
                val centerX = (sizePx - smallSize) / 2f

                // First avatar at top-center
                val avatar1 = generateBitmap(names[0], smallSize)
                canvas.drawBitmap(avatar1, centerX, 0f, null)
                avatar1.recycle()

                // Second avatar at bottom-left
                val avatar2 = generateBitmap(names[1], smallSize)
                canvas.drawBitmap(avatar2, 0f, (sizePx - smallSize).toFloat(), null)
                avatar2.recycle()

                // Third avatar at bottom-right
                val avatar3 = generateBitmap(names[2], smallSize)
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
                    val avatar = generateBitmap(names[i], smallSize)
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
     * @return A bitmap with multiple avatars on a transparent background
     */
    fun generateGroupCollageBitmapWithPhotos(
        context: Context,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int
    ): Bitmap {
        // Sort participants to prioritize those with photos
        val sortedIndices = names.indices.sortedByDescending { index ->
            avatarPaths.getOrNull(index) != null
        }
        val sortedNames = sortedIndices.map { names[it] }
        val sortedAvatarPaths = sortedIndices.map { avatarPaths.getOrNull(it) }

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

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
            return generateBitmap(name, size, hasContactInfo = avatarPath != null)
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
     * @param names List of participant names
     * @param sizePx The size of the bitmap in pixels
     * @return An IconCompat with the group collage
     */
    fun generateGroupIconCompat(names: List<String>, sizePx: Int): IconCompat {
        val bitmap = generateGroupCollageBitmap(names, sizePx)
        return IconCompat.createWithBitmap(bitmap)
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
     * Draw a simple building icon on the canvas.
     * Used for shortcodes and business contacts without personal names.
     */
    private fun drawBuildingIcon(canvas: Canvas, size: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }

        val centerX = size / 2f

        // Building dimensions
        val buildingWidth = size * 0.45f
        val buildingHeight = size * 0.55f
        val buildingLeft = centerX - buildingWidth / 2f
        val buildingTop = size * 0.22f
        val buildingBottom = buildingTop + buildingHeight

        // Draw main building rectangle
        canvas.drawRect(
            buildingLeft,
            buildingTop,
            buildingLeft + buildingWidth,
            buildingBottom,
            paint
        )

        // Draw windows (3 rows x 2 columns) using the background color
        val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.TRANSPARENT
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        val windowWidth = buildingWidth * 0.22f
        val windowHeight = buildingHeight * 0.12f
        val windowHGap = (buildingWidth - 2 * windowWidth) / 3f
        val windowVGap = buildingHeight * 0.08f
        val windowStartY = buildingTop + windowVGap

        // Draw 3 rows of 2 windows
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                val windowLeft = buildingLeft + windowHGap + col * (windowWidth + windowHGap)
                val windowTop = windowStartY + row * (windowHeight + windowVGap)
                canvas.drawRect(
                    windowLeft,
                    windowTop,
                    windowLeft + windowWidth,
                    windowTop + windowHeight,
                    windowPaint
                )
            }
        }

        // Draw door at bottom center
        val doorWidth = buildingWidth * 0.25f
        val doorHeight = buildingHeight * 0.18f
        val doorLeft = centerX - doorWidth / 2f
        canvas.drawRect(
            doorLeft,
            buildingBottom - doorHeight,
            doorLeft + doorWidth,
            buildingBottom,
            windowPaint
        )
    }
}
