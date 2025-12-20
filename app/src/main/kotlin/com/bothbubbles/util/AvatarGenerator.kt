package com.bothbubbles.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.R
import kotlin.math.abs

/**
 * Shared avatar generation utility used by both the Compose UI (Avatar.kt)
 * and notifications (NotificationService.kt) to ensure consistent avatar appearance.
 *
 * For group avatars, see [GroupAvatarRenderer].
 * For loading contact photos, see [ContactPhotoLoader].
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
        val showBuildingIcon = when {
            isBusiness -> true
            hasContactInfo -> false
            else -> isShortCodeOrAlphanumericSender(name)
        }
        val showPersonIcon = !showBuildingIcon && !hasContactInfo && isPhoneNumber(name)

        when {
            showBuildingIcon -> {
                drawBuildingIcon(context, canvas, sizePx)
            }
            showPersonIcon -> {
                drawPersonIcon(canvas, sizePx)
            }
            else -> {
                drawInitials(canvas, name, sizePx, center)
            }
        }

        return bitmap
    }

    /**
     * Generate an IconCompat for use in notifications.
     */
    fun generateIconCompat(
        context: Context,
        name: String,
        sizePx: Int,
        hasContactInfo: Boolean = false
    ): IconCompat {
        val bitmap = generateBitmap(context, name, sizePx, hasContactInfo = hasContactInfo)
        return IconCompat.createWithBitmap(bitmap)
    }

    /**
     * Generate an adaptive IconCompat for use in notifications/bubbles.
     * Uses a full-bleed square bitmap that the system will mask.
     */
    fun generateAdaptiveIconCompat(
        context: Context,
        name: String,
        sizePx: Int,
        hasContactInfo: Boolean = false
    ): IconCompat {
        val bitmap = generateBitmap(context, name, sizePx, hasContactInfo = hasContactInfo, circleCrop = false)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    // ===== Delegation to ContactPhotoLoader =====

    /**
     * Load a contact photo from a content:// URI and convert it to a bitmap.
     * Delegates to [ContactPhotoLoader].
     */
    fun loadContactPhotoBitmap(
        context: Context,
        photoUri: String,
        sizePx: Int,
        circleCrop: Boolean = true
    ): Bitmap? = ContactPhotoLoader.loadContactPhotoBitmap(context, photoUri, sizePx, circleCrop)

    // ===== Delegation to GroupAvatarRenderer =====

    /**
     * Generate a group avatar collage bitmap for notifications.
     * Delegates to [GroupAvatarRenderer].
     */
    fun generateGroupCollageBitmap(
        context: Context,
        names: List<String>,
        sizePx: Int,
        circleCrop: Boolean = true
    ): Bitmap = GroupAvatarRenderer.generateGroupCollageBitmap(context, names, sizePx, circleCrop)

    /**
     * Generate a group avatar collage bitmap with actual contact photos.
     * Delegates to [GroupAvatarRenderer].
     */
    fun generateGroupCollageBitmapWithPhotos(
        context: Context,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int,
        circleCrop: Boolean = true
    ): Bitmap = GroupAvatarRenderer.generateGroupCollageBitmapWithPhotos(
        context, names, avatarPaths, sizePx, circleCrop
    )

    /**
     * Generate an IconCompat for group notifications with actual contact photos.
     * Delegates to [GroupAvatarRenderer].
     */
    fun generateGroupIconCompatWithPhotos(
        context: Context,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int
    ): IconCompat = GroupAvatarRenderer.generateGroupIconCompatWithPhotos(context, names, avatarPaths, sizePx)

    /**
     * Generate an IconCompat for group notifications.
     * Delegates to [GroupAvatarRenderer].
     */
    fun generateGroupIconCompat(
        context: Context,
        names: List<String>,
        sizePx: Int
    ): IconCompat = GroupAvatarRenderer.generateGroupIconCompat(context, names, sizePx)

    /**
     * Generate an adaptive IconCompat for group notifications/bubbles.
     * Delegates to [GroupAvatarRenderer].
     */
    fun generateGroupAdaptiveIconCompatWithPhotos(
        context: Context,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int
    ): IconCompat = GroupAvatarRenderer.generateGroupAdaptiveIconCompatWithPhotos(context, names, avatarPaths, sizePx)

    // ===== Private Drawing Methods =====

    private fun drawInitials(canvas: Canvas, name: String, sizePx: Int, center: Float) {
        val initials = getInitials(name)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = sizePx * 0.4f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(initials, 0, initials.length, textBounds)
        val yPos = center - textBounds.exactCenterY()

        canvas.drawText(initials, center, yPos, textPaint)
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
