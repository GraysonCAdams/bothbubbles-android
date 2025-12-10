package com.bothbubbles.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.abs

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
     * Generate a bitmap avatar with colored circle and initials (or person icon).
     * Used for notifications where Compose components aren't available.
     *
     * @param name The contact name to generate avatar for
     * @param sizePx The size of the bitmap in pixels (typically 128 for notifications)
     * @return A circular bitmap with colored background and white initials/icon
     */
    fun generateBitmap(name: String, sizePx: Int): Bitmap {
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

        if (isPhoneNumber(name)) {
            // Draw a simple person silhouette for phone numbers
            drawPersonIcon(canvas, sizePx)
        } else {
            // Draw initials
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

        return bitmap
    }

    /**
     * Generate an IconCompat for use in notifications.
     *
     * @param name The contact name to generate avatar for
     * @param sizePx The size of the bitmap in pixels
     * @return An IconCompat that can be used with Person.Builder.setIcon()
     */
    fun generateIconCompat(name: String, sizePx: Int): IconCompat {
        val bitmap = generateBitmap(name, sizePx)
        return IconCompat.createWithBitmap(bitmap)
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
}
