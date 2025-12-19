package com.bothbubbles.util.parsing

import java.util.regex.Pattern

/**
 * Utility for parsing YouTube URLs and extracting video IDs.
 * Supports various YouTube URL formats including:
 * - Standard watch URLs: youtube.com/watch?v=VIDEO_ID
 * - Short URLs: youtu.be/VIDEO_ID
 * - Embed URLs: youtube.com/embed/VIDEO_ID
 * - Mobile URLs: m.youtube.com/watch?v=VIDEO_ID
 * - Shorts: youtube.com/shorts/VIDEO_ID
 * - Live streams: youtube.com/live/VIDEO_ID
 */
object YouTubeUrlParser {

    // YouTube video ID pattern (11 characters: letters, numbers, underscores, hyphens)
    private const val VIDEO_ID_PATTERN = "[a-zA-Z0-9_-]{11}"

    // Patterns for different YouTube URL formats
    private val YOUTUBE_PATTERNS = listOf(
        // Standard watch URL: youtube.com/watch?v=VIDEO_ID
        Pattern.compile(
            """(?:https?://)?(?:www\.|m\.)?youtube\.com/watch\?.*v=($VIDEO_ID_PATTERN)""",
            Pattern.CASE_INSENSITIVE
        ),
        // Short URL: youtu.be/VIDEO_ID
        Pattern.compile(
            """(?:https?://)?youtu\.be/($VIDEO_ID_PATTERN)""",
            Pattern.CASE_INSENSITIVE
        ),
        // Embed URL: youtube.com/embed/VIDEO_ID
        Pattern.compile(
            """(?:https?://)?(?:www\.)?youtube\.com/embed/($VIDEO_ID_PATTERN)""",
            Pattern.CASE_INSENSITIVE
        ),
        // Shorts URL: youtube.com/shorts/VIDEO_ID
        Pattern.compile(
            """(?:https?://)?(?:www\.|m\.)?youtube\.com/shorts/($VIDEO_ID_PATTERN)""",
            Pattern.CASE_INSENSITIVE
        ),
        // Live URL: youtube.com/live/VIDEO_ID
        Pattern.compile(
            """(?:https?://)?(?:www\.|m\.)?youtube\.com/live/($VIDEO_ID_PATTERN)""",
            Pattern.CASE_INSENSITIVE
        ),
        // YouTube Music: music.youtube.com/watch?v=VIDEO_ID
        Pattern.compile(
            """(?:https?://)?music\.youtube\.com/watch\?.*v=($VIDEO_ID_PATTERN)""",
            Pattern.CASE_INSENSITIVE
        )
    )

    /**
     * Parsed YouTube video information
     */
    data class YouTubeVideo(
        val videoId: String,
        val originalUrl: String,
        val thumbnailUrl: String = getThumbnailUrl(videoId),
        val isShort: Boolean = false,
        val startTimeSeconds: Int? = null  // Timestamp to start playback at
    ) {
        companion object {
            /**
             * Returns the standard thumbnail URL for a YouTube video.
             * Available sizes:
             * - default.jpg (120x90)
             * - mqdefault.jpg (320x180) - recommended for inline previews
             * - hqdefault.jpg (480x360)
             * - sddefault.jpg (640x480)
             * - maxresdefault.jpg (1280x720) - may not exist for all videos
             */
            fun getThumbnailUrl(videoId: String, quality: ThumbnailQuality = ThumbnailQuality.MEDIUM): String {
                return "https://img.youtube.com/vi/$videoId/${quality.filename}"
            }
        }
    }

    // Timestamp patterns for YouTube URLs
    // Supports: t=120, t=2m30s, t=1h2m30s, start=120
    private val TIMESTAMP_PATTERNS = listOf(
        // Simple seconds: t=120 or start=120
        Pattern.compile("""[?&](?:t|start)=(\d+)(?:s)?(?:&|$)""", Pattern.CASE_INSENSITIVE),
        // Minutes and seconds: t=2m30s or t=2m
        Pattern.compile("""[?&]t=(\d+)m(\d+)?s?(?:&|$)""", Pattern.CASE_INSENSITIVE),
        // Hours, minutes, seconds: t=1h2m30s
        Pattern.compile("""[?&]t=(\d+)h(\d+)?m?(\d+)?s?(?:&|$)""", Pattern.CASE_INSENSITIVE)
    )

    /**
     * Thumbnail quality options
     */
    enum class ThumbnailQuality(val filename: String) {
        DEFAULT("default.jpg"),       // 120x90
        MEDIUM("mqdefault.jpg"),      // 320x180 - best for inline chat
        HIGH("hqdefault.jpg"),        // 480x360
        STANDARD("sddefault.jpg"),    // 640x480
        MAX("maxresdefault.jpg")      // 1280x720 - may not exist
    }

    /**
     * Extracts the video ID from a YouTube URL.
     * Returns null if the URL is not a valid YouTube video URL.
     */
    fun extractVideoId(url: String): String? {
        for (pattern in YOUTUBE_PATTERNS) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    /**
     * Parses a YouTube URL and returns video information.
     * Returns null if the URL is not a valid YouTube video URL.
     */
    fun parseUrl(url: String): YouTubeVideo? {
        val videoId = extractVideoId(url) ?: return null
        val isShort = url.contains("/shorts/", ignoreCase = true)
        val startTime = extractTimestamp(url)
        return YouTubeVideo(
            videoId = videoId,
            originalUrl = url,
            isShort = isShort,
            startTimeSeconds = startTime
        )
    }

    /**
     * Extracts the start timestamp from a YouTube URL.
     * Supports formats: t=120, t=2m30s, t=1h2m30s, start=120
     * Returns the timestamp in seconds, or null if no timestamp found.
     */
    fun extractTimestamp(url: String): Int? {
        // Try simple seconds format first: t=120 or start=120
        val simpleMatch = TIMESTAMP_PATTERNS[0].matcher(url)
        if (simpleMatch.find()) {
            return simpleMatch.group(1)?.toIntOrNull()
        }

        // Try minutes and seconds: t=2m30s
        val minutesMatch = TIMESTAMP_PATTERNS[1].matcher(url)
        if (minutesMatch.find()) {
            val minutes = minutesMatch.group(1)?.toIntOrNull() ?: 0
            val seconds = minutesMatch.group(2)?.toIntOrNull() ?: 0
            return minutes * 60 + seconds
        }

        // Try hours, minutes, seconds: t=1h2m30s
        val hoursMatch = TIMESTAMP_PATTERNS[2].matcher(url)
        if (hoursMatch.find()) {
            val hours = hoursMatch.group(1)?.toIntOrNull() ?: 0
            val minutes = hoursMatch.group(2)?.toIntOrNull() ?: 0
            val seconds = hoursMatch.group(3)?.toIntOrNull() ?: 0
            return hours * 3600 + minutes * 60 + seconds
        }

        return null
    }

    /**
     * Checks if a URL is a YouTube video URL.
     */
    fun isYouTubeUrl(url: String): Boolean {
        return extractVideoId(url) != null
    }

    /**
     * Checks if a URL is a YouTube Shorts URL.
     */
    fun isYouTubeShort(url: String): Boolean {
        return url.contains("/shorts/", ignoreCase = true) && isYouTubeUrl(url)
    }

    /**
     * Gets the best thumbnail URL for a video ID.
     * Falls back to medium quality which is guaranteed to exist.
     */
    fun getThumbnailUrl(videoId: String): String {
        return YouTubeVideo.getThumbnailUrl(videoId, ThumbnailQuality.MEDIUM)
    }

    /**
     * Gets a high-quality thumbnail URL for fullscreen/media viewer.
     */
    fun getHighQualityThumbnailUrl(videoId: String): String {
        return YouTubeVideo.getThumbnailUrl(videoId, ThumbnailQuality.HIGH)
    }
}
