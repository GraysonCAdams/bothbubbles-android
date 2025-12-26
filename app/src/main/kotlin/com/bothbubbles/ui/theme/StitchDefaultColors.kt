package com.bothbubbles.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Default bubble colors for each Stitch platform.
 *
 * These colors are used when no custom color is set by the user.
 * Each Stitch has a distinct brand color that users recognize.
 *
 * Colors are provided for both light and dark themes where appropriate.
 */
object StitchDefaultColors {

    /**
     * Color configuration for a Stitch platform.
     *
     * @property bubbleColor The bubble background color for light theme
     * @property textColor The text color on the bubble for light theme
     * @property bubbleColorDark The bubble background color for dark theme (defaults to bubbleColor)
     * @property textColorDark The text color on the bubble for dark theme (defaults to textColor)
     */
    data class StitchColorPair(
        val bubbleColor: Color,
        val textColor: Color,
        val bubbleColorDark: Color = bubbleColor,
        val textColorDark: Color = textColor
    )

    // ===== Default Colors by Stitch ID =====

    private val defaults = mapOf(
        // iMessage via BlueBubbles - Apple's signature blue
        "bluebubbles" to StitchColorPair(
            bubbleColor = Color(0xFF007AFF),      // iOS iMessage blue (light)
            textColor = Color.White,
            bubbleColorDark = Color(0xFF0A84FF),  // iOS iMessage blue (dark)
            textColorDark = Color.White
        ),

        // SMS/MMS - Traditional SMS green
        "sms" to StitchColorPair(
            bubbleColor = Color(0xFF34C759),      // iOS SMS green (light)
            textColor = Color(0xFF202124),        // Dark text for contrast
            bubbleColorDark = Color(0xFF30D158),  // iOS SMS green (dark)
            textColorDark = Color(0xFF202124)
        ),

        // Signal (future) - Signal's signature blue
        "signal" to StitchColorPair(
            bubbleColor = Color(0xFF3A76F0),
            textColor = Color.White
        ),

        // Discord (future) - Discord's blurple
        "discord" to StitchColorPair(
            bubbleColor = Color(0xFF5865F2),
            textColor = Color.White
        ),

        // Telegram (future) - Telegram's blue
        "telegram" to StitchColorPair(
            bubbleColor = Color(0xFF0088CC),
            textColor = Color.White
        ),

        // WhatsApp (future) - WhatsApp's green
        "whatsapp" to StitchColorPair(
            bubbleColor = Color(0xFF25D366),
            textColor = Color.White
        )
    )

    // Fallback for unknown Stitches - Material Design 3 primary
    private val fallback = StitchColorPair(
        bubbleColor = Color(0xFF6750A4),
        textColor = Color.White
    )

    /**
     * Get the default color configuration for a Stitch.
     *
     * @param stitchId The Stitch identifier (e.g., "bluebubbles", "sms")
     * @return The color configuration, or a fallback if the Stitch is unknown
     */
    fun getDefaultColor(stitchId: String): StitchColorPair {
        return defaults[stitchId] ?: fallback
    }

    /**
     * Get the default bubble color for a Stitch, accounting for theme.
     *
     * @param stitchId The Stitch identifier
     * @param isDarkTheme Whether dark theme is active
     * @return The appropriate bubble color
     */
    fun getDefaultBubbleColor(stitchId: String, isDarkTheme: Boolean): Color {
        val colors = getDefaultColor(stitchId)
        return if (isDarkTheme) colors.bubbleColorDark else colors.bubbleColor
    }

    /**
     * Get all registered default colors.
     * Useful for displaying color reset options in settings.
     */
    fun getAllDefaults(): Map<String, StitchColorPair> = defaults

    /**
     * Check if a Stitch has a registered default color.
     */
    fun hasDefaultColor(stitchId: String): Boolean = stitchId in defaults
}
