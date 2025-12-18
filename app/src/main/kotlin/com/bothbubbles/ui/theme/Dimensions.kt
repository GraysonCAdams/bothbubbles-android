package com.bothbubbles.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Media sizing constants for consistent attachment display across the app.
 * Based on Apple iMessage behavior: fixed width, varying heights with constraints.
 */
object MediaSizing {
    /** Maximum width for media attachments in message bubbles */
    val MAX_WIDTH = 250.dp

    /** Minimum height to prevent panoramas from being too thin */
    val MIN_HEIGHT = 100.dp

    /** Maximum height to allow tall screenshots without being excessive */
    val MAX_HEIGHT = 400.dp

    /** Corner radius for media attachments */
    val CORNER_RADIUS = 12.dp

    /** Maximum width for borderless media (stickers, standalone images) */
    val BORDERLESS_MAX_WIDTH = 240.dp

    /** Maximum width for placed stickers on other messages */
    val STICKER_MAX_WIDTH = 140.dp
}
