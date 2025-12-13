package com.bothbubbles.data.model

/**
 * Image quality settings for attachment uploads.
 *
 * Controls compression level applied to images before sending.
 * Higher quality = larger file size = slower upload but better image.
 *
 * @param maxDimension Maximum width/height in pixels. 0 means server decides, -1 means no limit.
 * @param jpegQuality JPEG compression quality (0-100). Higher = better quality, larger file.
 * @param displayName User-friendly name for settings UI.
 * @param description Short description of the trade-offs.
 */
enum class AttachmentQuality(
    val maxDimension: Int,
    val jpegQuality: Int,
    val displayName: String,
    val description: String
) {
    /**
     * Let the server/system decide compression.
     * Uses default settings without client-side processing.
     */
    AUTO(
        maxDimension = 0,
        jpegQuality = 0,
        displayName = "Auto",
        description = "System default quality"
    ),

    /**
     * Standard quality - good balance of size and quality.
     * Suitable for most messaging, ~200KB per image.
     */
    STANDARD(
        maxDimension = 1600,
        jpegQuality = 70,
        displayName = "Standard",
        description = "Smaller files, faster sending"
    ),

    /**
     * High quality - larger files but better image fidelity.
     * Good for sharing photos you want to look good, ~1MB per image.
     */
    HIGH(
        maxDimension = 3000,
        jpegQuality = 85,
        displayName = "High",
        description = "Better quality, larger files"
    ),

    /**
     * Original quality - no compression applied.
     * Sends the image exactly as captured/selected.
     */
    ORIGINAL(
        maxDimension = -1,
        jpegQuality = 100,
        displayName = "Original",
        description = "Full resolution, largest files"
    );

    companion object {
        /**
         * Default quality for new users.
         */
        val DEFAULT = STANDARD

        /**
         * Parse from stored string value, falling back to default.
         */
        fun fromString(value: String?): AttachmentQuality {
            if (value == null) return DEFAULT
            return try {
                valueOf(value.uppercase())
            } catch (e: IllegalArgumentException) {
                DEFAULT
            }
        }
    }
}
