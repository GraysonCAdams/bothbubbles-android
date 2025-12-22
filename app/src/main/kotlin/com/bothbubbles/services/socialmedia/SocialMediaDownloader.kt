package com.bothbubbles.services.socialmedia

/**
 * Supported social media platforms for video downloading.
 */
enum class SocialMediaPlatform(val displayName: String, val domain: String) {
    TIKTOK("TikTok", "tiktok.com"),
    INSTAGRAM("Instagram", "instagram.com")
}

/**
 * Result of a social media video extraction attempt.
 */
sealed interface SocialMediaResult {
    /**
     * Successfully extracted video URL(s).
     * @param videoUrl Direct URL to the video stream
     * @param hdVideoUrl Optional HD quality video URL
     * @param audioUrl Optional separate audio URL
     * @param thumbnailUrl Optional thumbnail image URL
     */
    data class Success(
        val videoUrl: String,
        val hdVideoUrl: String? = null,
        val audioUrl: String? = null,
        val thumbnailUrl: String? = null
    ) : SocialMediaResult

    /**
     * Extraction failed with an error.
     */
    data class Error(val message: String, val cause: Throwable? = null) : SocialMediaResult

    /**
     * The URL is not supported or not a video.
     */
    data object NotSupported : SocialMediaResult
}

/**
 * Interface for extracting video URLs from social media platforms.
 */
interface SocialMediaDownloader {
    /**
     * Detects which platform (if any) a URL belongs to.
     * @return The platform, or null if not a supported social media URL
     */
    fun detectPlatform(url: String): SocialMediaPlatform?

    /**
     * Checks if downloading is enabled for the given platform.
     */
    suspend fun isDownloadEnabled(platform: SocialMediaPlatform): Boolean

    /**
     * Extracts the video URL from a social media post URL.
     * @param url The original post URL (e.g., TikTok or Instagram Reel link)
     * @return Result containing the direct video URL or an error
     */
    suspend fun extractVideoUrl(url: String): SocialMediaResult

    /**
     * Extracts video URL for a specific platform.
     */
    suspend fun extractVideoUrl(url: String, platform: SocialMediaPlatform): SocialMediaResult
}
