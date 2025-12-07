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
    val domain: String, // Extracted domain for display
    val isCoordinates: Boolean = false // True if this was detected from raw coordinates
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

    // Coordinate patterns to detect raw lat/lng in message text
    // These will be converted to Google Maps URLs for preview
    private val COORDINATE_PATTERNS = listOf(
        // Decimal degrees with optional space: 37.7749, -122.4194 or 37.7749,-122.4194
        Pattern.compile(
            """(-?\d{1,3}\.\d{3,8}),\s*(-?\d{1,3}\.\d{3,8})""",
            Pattern.CASE_INSENSITIVE
        ),
        // GPS format: N 37.7749, W 122.4194 or N37.7749 W122.4194
        Pattern.compile(
            """([NS])\s*(\d{1,3}\.\d{3,8}),?\s*([EW])\s*(\d{1,3}\.\d{3,8})""",
            Pattern.CASE_INSENSITIVE
        )
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
     * Also detects raw coordinates and converts them to Google Maps URLs
     */
    fun getFirstUrl(text: String?): DetectedUrl? {
        if (text.isNullOrBlank()) return null

        // First check for regular URLs
        val urls = detectUrls(text)

        // Also check for raw coordinates
        val coordinates = detectCoordinates(text)

        // Return whichever comes first in the text
        val allDetected = (urls + coordinates).sortedBy { it.startIndex }
        return allDetected.firstOrNull()
    }

    /**
     * Detects raw coordinates in text and converts them to Google Maps URLs
     * Examples: "37.7749, -122.4194" or "N 37.7749, W 122.4194"
     */
    fun detectCoordinates(text: String): List<DetectedUrl> {
        val detectedCoords = mutableListOf<DetectedUrl>()
        val coveredRanges = mutableListOf<IntRange>()

        // First pattern: decimal degrees (37.7749, -122.4194)
        val decimalMatcher = COORDINATE_PATTERNS[0].matcher(text)
        while (decimalMatcher.find()) {
            val matchedText = decimalMatcher.group()
            val startIndex = decimalMatcher.start()
            val endIndex = decimalMatcher.end()

            // Check for overlaps
            val overlaps = coveredRanges.any { range ->
                startIndex < range.last && endIndex > range.first
            }
            if (overlaps) continue

            val lat = decimalMatcher.group(1)?.toDoubleOrNull() ?: continue
            val lng = decimalMatcher.group(2)?.toDoubleOrNull() ?: continue

            // Validate coordinate ranges
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) continue

            // Don't match if this looks like it's already part of a URL
            val beforeMatch = if (startIndex > 0) text.substring(maxOf(0, startIndex - 10), startIndex) else ""
            if (beforeMatch.contains("maps.google") || beforeMatch.contains("maps.apple") ||
                beforeMatch.contains("?q=") || beforeMatch.contains("@")) continue

            val googleMapsUrl = "https://maps.google.com/?q=$lat,$lng"
            detectedCoords.add(
                DetectedUrl(
                    startIndex = startIndex,
                    endIndex = endIndex,
                    matchedText = matchedText,
                    url = googleMapsUrl,
                    domain = "maps.google.com",
                    isCoordinates = true
                )
            )
            coveredRanges.add(startIndex until endIndex)
        }

        // Second pattern: GPS format (N 37.7749, W 122.4194)
        val gpsMatcher = COORDINATE_PATTERNS[1].matcher(text)
        while (gpsMatcher.find()) {
            val matchedText = gpsMatcher.group()
            val startIndex = gpsMatcher.start()
            val endIndex = gpsMatcher.end()

            // Check for overlaps
            val overlaps = coveredRanges.any { range ->
                startIndex < range.last && endIndex > range.first
            }
            if (overlaps) continue

            val nsDir = gpsMatcher.group(1)?.uppercase() ?: continue
            val latVal = gpsMatcher.group(2)?.toDoubleOrNull() ?: continue
            val ewDir = gpsMatcher.group(3)?.uppercase() ?: continue
            val lngVal = gpsMatcher.group(4)?.toDoubleOrNull() ?: continue

            // Convert to signed coordinates
            val lat = if (nsDir == "S") -latVal else latVal
            val lng = if (ewDir == "W") -lngVal else lngVal

            // Validate coordinate ranges
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) continue

            val googleMapsUrl = "https://maps.google.com/?q=$lat,$lng"
            detectedCoords.add(
                DetectedUrl(
                    startIndex = startIndex,
                    endIndex = endIndex,
                    matchedText = matchedText,
                    url = googleMapsUrl,
                    domain = "maps.google.com",
                    isCoordinates = true
                )
            )
            coveredRanges.add(startIndex until endIndex)
        }

        return detectedCoords.sortedBy { it.startIndex }
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
