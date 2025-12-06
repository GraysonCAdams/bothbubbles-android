package com.bluebubbles.ui.components

import java.net.URI
import java.util.regex.Pattern

/**
 * Represents a detected URL in message text with its position
 */
data class DetectedUrl(
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String,
    val url: String, // Normalized URL with protocol
    val domain: String // Extracted domain for display
)

/**
 * Utility object for detecting URLs in message text for link previews
 */
object UrlParsingUtils {

    // URL patterns (ordered from most specific to least specific)
    private val URL_PATTERNS = listOf(
        // Full URLs with http/https protocol
        Pattern.compile(
            """https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]""",
            Pattern.CASE_INSENSITIVE
        ),
        // URLs starting with www. (without protocol)
        Pattern.compile(
            """\bwww\.[-a-zA-Z0-9+&@#/%?=~_|!:,.;]+\.[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]""",
            Pattern.CASE_INSENSITIVE
        )
    )

    // Common video platform domains for special handling
    private val VIDEO_DOMAINS = setOf(
        "youtube.com", "youtu.be", "www.youtube.com", "m.youtube.com",
        "tiktok.com", "www.tiktok.com", "vm.tiktok.com",
        "twitter.com", "www.twitter.com", "x.com", "www.x.com",
        "instagram.com", "www.instagram.com",
        "reddit.com", "www.reddit.com", "old.reddit.com", "v.redd.it",
        "vimeo.com", "www.vimeo.com"
    )

    // Tracking parameters to strip from URLs
    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "dclid", "msclkid", "twclid",
        "igshid", "ref_src", "ref_url", "s", "t", "si"
    )

    /**
     * Detects all URLs in the given text
     * @return List of detected URLs sorted by their position in the text
     */
    fun detectUrls(text: String): List<DetectedUrl> {
        val detectedUrls = mutableListOf<DetectedUrl>()
        val coveredRanges = mutableListOf<IntRange>()

        URL_PATTERNS.forEach { pattern ->
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val matchedText = matcher.group()
                val startIndex = matcher.start()
                val endIndex = matcher.end()

                // Check if this range overlaps with already detected URLs
                val overlaps = coveredRanges.any { range ->
                    startIndex < range.last && endIndex > range.first
                }

                if (!overlaps && isValidUrl(matchedText)) {
                    val normalizedUrl = normalizeUrl(matchedText)
                    val domain = extractDomain(normalizedUrl)

                    detectedUrls.add(
                        DetectedUrl(
                            startIndex = startIndex,
                            endIndex = endIndex,
                            matchedText = matchedText,
                            url = normalizedUrl,
                            domain = domain
                        )
                    )
                    coveredRanges.add(startIndex until endIndex)
                }
            }
        }

        return detectedUrls.sortedBy { it.startIndex }
    }

    /**
     * Gets the first URL from text (for link preview purposes)
     */
    fun getFirstUrl(text: String?): DetectedUrl? {
        if (text.isNullOrBlank()) return null
        return detectUrls(text).firstOrNull()
    }

    /**
     * Checks if the given text contains any detectable URLs
     */
    fun containsUrls(text: String): Boolean {
        return detectUrls(text).isNotEmpty()
    }

    /**
     * Checks if the URL is from a known video platform
     */
    fun isVideoUrl(url: String): Boolean {
        val domain = extractDomain(url).lowercase()
        return VIDEO_DOMAINS.any { videoDomain ->
            domain == videoDomain || domain.endsWith(".$videoDomain")
        }
    }

    /**
     * Detects the video platform from a URL
     */
    fun detectVideoPlatform(url: String): VideoPlatform {
        val domain = extractDomain(url).lowercase()
        return when {
            domain.contains("youtube") || domain.contains("youtu.be") -> VideoPlatform.YOUTUBE
            domain.contains("tiktok") -> VideoPlatform.TIKTOK
            domain.contains("twitter") || domain.contains("x.com") -> VideoPlatform.TWITTER
            domain.contains("instagram") -> VideoPlatform.INSTAGRAM
            domain.contains("reddit") || domain.contains("redd.it") -> VideoPlatform.REDDIT
            domain.contains("vimeo") -> VideoPlatform.VIMEO
            else -> VideoPlatform.GENERIC
        }
    }

    /**
     * Validates that a matched string is a valid URL
     */
    private fun isValidUrl(url: String): Boolean {
        // Must have at least one dot (domain separator)
        if (!url.contains(".")) return false

        // Must not be just a file extension pattern
        if (url.matches(Regex("""^\.\w+$"""))) return false

        // Must have a valid TLD (at least 2 characters after last dot)
        val afterLastDot = url.substringAfterLast(".")
            .takeWhile { it.isLetter() }
        if (afterLastDot.length < 2) return false

        return true
    }

    /**
     * Normalizes a URL to a standard format
     */
    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()

        // Add https:// if no protocol
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)) {
            normalized = "https://$normalized"
        }

        // Upgrade http to https (optional, for security)
        // normalized = normalized.replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")

        return normalized
    }

    /**
     * Strips tracking parameters from a URL for cleaner display and caching
     */
    fun stripTrackingParams(url: String): String {
        return try {
            val uri = URI(url)
            val query = uri.query ?: return url

            val filteredParams = query.split("&")
                .filter { param ->
                    val key = param.substringBefore("=").lowercase()
                    key !in TRACKING_PARAMS
                }
                .joinToString("&")

            if (filteredParams.isEmpty()) {
                url.substringBefore("?")
            } else {
                "${url.substringBefore("?")}?$filteredParams"
            }
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Extracts the domain from a URL for display
     */
    fun extractDomain(url: String): String {
        return try {
            val normalized = if (url.startsWith("http")) url else "https://$url"
            URI(normalized).host?.removePrefix("www.") ?: url
        } catch (e: Exception) {
            // Fallback: try to extract domain manually
            url.removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore(":")
        }
    }

    /**
     * Generates a hash key for URL caching (normalizes URL first)
     */
    fun generateUrlHash(url: String): String {
        val normalized = stripTrackingParams(normalizeUrl(url)).lowercase()
        return normalized.hashCode().toString(16)
    }
}

/**
 * Enum representing known video platforms for special handling
 */
enum class VideoPlatform {
    YOUTUBE,
    TIKTOK,
    TWITTER,
    INSTAGRAM,
    REDDIT,
    VIMEO,
    GENERIC
}
