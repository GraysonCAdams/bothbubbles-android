package com.bothbubbles.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.drawable.IconCompat

/**
 * Renders group avatar collages for notifications and bubbles.
 *
 * Creates composite images with 2-4 participant avatars arranged in various layouts:
 * - 2 participants: Offset overlapping circles
 * - 3 participants: Triangle arrangement
 * - 4 participants: 2x2 grid
 *
 * Extracted from AvatarGenerator to separate group rendering concerns.
 */
object GroupAvatarRenderer {

    /**
     * Generate a group avatar collage bitmap for notifications.
     * Creates a composite image with 2-4 participant avatars arranged in a grid/layout.
     * Uses TRANSPARENT background so avatars composite cleanly in notifications.
     *
     * @param context Application context
     * @param names List of participant names (up to 4 will be shown)
     * @param sizePx The size of the output bitmap in pixels
     * @param circleCrop If true, leaves background transparent. If false, fills background.
     * @return A bitmap with multiple avatars
     */
    fun generateGroupCollageBitmap(
        context: Context,
        names: List<String>,
        sizePx: Int,
        circleCrop: Boolean = true
    ): Bitmap {
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
                drawTwoParticipantLayout(context, canvas, names, sizePx)
            }
            3 -> {
                drawThreeParticipantLayout(context, canvas, names, sizePx)
            }
            else -> {
                drawFourParticipantLayout(context, canvas, names, sizePx)
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
     * @param circleCrop If true, leaves background transparent. If false, fills background.
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
            canvas.drawColor(0xFFF5F5F5.toInt()) // Light gray
        }

        val displayCount = minOf(sortedNames.size, 4)

        when (displayCount) {
            0, 1 -> {
                drawGroupIcon(canvas, sizePx)
            }
            2 -> {
                drawTwoParticipantLayoutWithPhotos(context, canvas, sortedNames, sortedAvatarPaths, sizePx)
            }
            3 -> {
                drawThreeParticipantLayoutWithPhotos(context, canvas, sortedNames, sortedAvatarPaths, sizePx)
            }
            else -> {
                drawFourParticipantLayoutWithPhotos(context, canvas, sortedNames, sortedAvatarPaths, sizePx)
            }
        }

        return bitmap
    }

    /**
     * Generate an IconCompat for group notifications with actual contact photos.
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
     */
    fun generateGroupIconCompat(context: Context, names: List<String>, sizePx: Int): IconCompat {
        val bitmap = generateGroupCollageBitmap(context, names, sizePx)
        return IconCompat.createWithBitmap(bitmap)
    }

    /**
     * Generate an adaptive IconCompat for group notifications/bubbles.
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

    // ===== Layout Methods (without photos) =====

    private fun drawTwoParticipantLayout(
        context: Context,
        canvas: Canvas,
        names: List<String>,
        sizePx: Int
    ) {
        val smallSize = (sizePx * 0.65f).toInt()
        val offset = sizePx - smallSize

        // First avatar at top-left
        val avatar1 = AvatarGenerator.generateBitmap(context, names[0], smallSize)
        canvas.drawBitmap(avatar1, 0f, 0f, null)
        avatar1.recycle()

        // Second avatar at bottom-right
        val avatar2 = AvatarGenerator.generateBitmap(context, names[1], smallSize)
        canvas.drawBitmap(avatar2, offset.toFloat(), offset.toFloat(), null)
        avatar2.recycle()
    }

    private fun drawThreeParticipantLayout(
        context: Context,
        canvas: Canvas,
        names: List<String>,
        sizePx: Int
    ) {
        val smallSize = (sizePx * 0.5f).toInt()
        val centerX = (sizePx - smallSize) / 2f

        // First avatar at top-center
        val avatar1 = AvatarGenerator.generateBitmap(context, names[0], smallSize)
        canvas.drawBitmap(avatar1, centerX, 0f, null)
        avatar1.recycle()

        // Second avatar at bottom-left
        val avatar2 = AvatarGenerator.generateBitmap(context, names[1], smallSize)
        canvas.drawBitmap(avatar2, 0f, (sizePx - smallSize).toFloat(), null)
        avatar2.recycle()

        // Third avatar at bottom-right
        val avatar3 = AvatarGenerator.generateBitmap(context, names[2], smallSize)
        canvas.drawBitmap(avatar3, (sizePx - smallSize).toFloat(), (sizePx - smallSize).toFloat(), null)
        avatar3.recycle()
    }

    private fun drawFourParticipantLayout(
        context: Context,
        canvas: Canvas,
        names: List<String>,
        sizePx: Int
    ) {
        val smallSize = (sizePx * 0.48f).toInt()
        val gap = (sizePx - 2 * smallSize) / 3f

        val positions = listOf(
            Pair(gap, gap),                                    // Top-left
            Pair(gap * 2 + smallSize, gap),                   // Top-right
            Pair(gap, gap * 2 + smallSize),                   // Bottom-left
            Pair(gap * 2 + smallSize, gap * 2 + smallSize)    // Bottom-right
        )

        for (i in 0 until 4) {
            val avatar = AvatarGenerator.generateBitmap(context, names[i], smallSize)
            canvas.drawBitmap(avatar, positions[i].first, positions[i].second, null)
            avatar.recycle()
        }
    }

    // ===== Layout Methods (with photos) =====

    private fun drawTwoParticipantLayoutWithPhotos(
        context: Context,
        canvas: Canvas,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int
    ) {
        val smallSize = (sizePx * 0.65f).toInt()
        val offset = sizePx - smallSize

        val avatar1 = getParticipantBitmap(context, names, avatarPaths, 0, smallSize)
        canvas.drawBitmap(avatar1, 0f, 0f, null)
        avatar1.recycle()

        val avatar2 = getParticipantBitmap(context, names, avatarPaths, 1, smallSize)
        canvas.drawBitmap(avatar2, offset.toFloat(), offset.toFloat(), null)
        avatar2.recycle()
    }

    private fun drawThreeParticipantLayoutWithPhotos(
        context: Context,
        canvas: Canvas,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int
    ) {
        val smallSize = (sizePx * 0.5f).toInt()
        val centerX = (sizePx - smallSize) / 2f

        val avatar1 = getParticipantBitmap(context, names, avatarPaths, 0, smallSize)
        canvas.drawBitmap(avatar1, centerX, 0f, null)
        avatar1.recycle()

        val avatar2 = getParticipantBitmap(context, names, avatarPaths, 1, smallSize)
        canvas.drawBitmap(avatar2, 0f, (sizePx - smallSize).toFloat(), null)
        avatar2.recycle()

        val avatar3 = getParticipantBitmap(context, names, avatarPaths, 2, smallSize)
        canvas.drawBitmap(avatar3, (sizePx - smallSize).toFloat(), (sizePx - smallSize).toFloat(), null)
        avatar3.recycle()
    }

    private fun drawFourParticipantLayoutWithPhotos(
        context: Context,
        canvas: Canvas,
        names: List<String>,
        avatarPaths: List<String?>,
        sizePx: Int
    ) {
        val smallSize = (sizePx * 0.48f).toInt()
        val gap = (sizePx - 2 * smallSize) / 3f

        val positions = listOf(
            Pair(gap, gap),
            Pair(gap * 2 + smallSize, gap),
            Pair(gap, gap * 2 + smallSize),
            Pair(gap * 2 + smallSize, gap * 2 + smallSize)
        )

        for (i in 0 until 4) {
            val avatar = getParticipantBitmap(context, names, avatarPaths, i, smallSize)
            canvas.drawBitmap(avatar, positions[i].first, positions[i].second, null)
            avatar.recycle()
        }
    }

    /**
     * Get avatar bitmap for a participant (loads photo or generates fallback).
     */
    private fun getParticipantBitmap(
        context: Context,
        names: List<String>,
        avatarPaths: List<String?>,
        index: Int,
        size: Int
    ): Bitmap {
        val avatarPath = avatarPaths.getOrNull(index)
        val name = names.getOrElse(index) { "?" }

        // Try to load contact photo first
        if (avatarPath != null) {
            val photoBitmap = ContactPhotoLoader.loadContactPhotoBitmap(context, avatarPath, size)
            if (photoBitmap != null) {
                return photoBitmap
            }
        }

        // Fall back to generated avatar (hasContactInfo = true if they had a path)
        return AvatarGenerator.generateBitmap(context, name, size, hasContactInfo = avatarPath != null)
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
}
