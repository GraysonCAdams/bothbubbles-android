package com.bothbubbles.services.linkpreview

import android.content.Context
import android.location.Geocoder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of fetching link preview metadata
 */
sealed class LinkMetadataResult {
    data class Success(val metadata: LinkMetadata) : LinkMetadataResult()
    data class Error(val message: String) : LinkMetadataResult()
    data object NoPreview : LinkMetadataResult()
}

/**
 * Extracted metadata from a URL
 */
data class LinkMetadata(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val faviconUrl: String?,
    val siteName: String?,
    val contentType: String?,
    val embedHtml: String? = null,
    val authorName: String? = null,
    val authorUrl: String? = null
)

/**
 * oEmbed provider configuration
 */
private data class OEmbedProvider(
    val name: String,
    val endpoint: String,
    val urlPatterns: List<Regex>
)

/**
 * Service for fetching link preview metadata from URLs.
 *
 * Uses oEmbed API for supported platforms (YouTube, Twitter, TikTok, Reddit, etc.)
 * and falls back to Open Graph meta tag parsing for other sites.
 */
@Singleton
class LinkPreviewService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Geocoder for reverse geocoding map coordinates to addresses
    private val geocoder by lazy { Geocoder(context, Locale.getDefault()) }

    companion object {
        private const val TAG = "LinkPreviewService"
        private const val TIMEOUT_MS = 8000L
        private const val MAX_CONTENT_LENGTH = 512 * 1024 // 512KB max for HTML
        private const val USER_AGENT = "BlueBubbles/1.0 (Android; Link Preview Bot)"
        private const val MAX_CONCURRENT_REQUESTS = 5

        // oEmbed providers with their endpoints and URL patterns
        private val OEMBED_PROVIDERS = listOf(
            OEmbedProvider(
                name = "YouTube",
                endpoint = "https://www.youtube.com/oembed?url=%s&format=json",
                urlPatterns = listOf(
                    """youtube\.com/watch""".toRegex(),
                    """youtu\.be/""".toRegex(),
                    """youtube\.com/shorts/""".toRegex(),
                    """youtube\.com/embed/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Twitter/X",
                endpoint = "https://publish.twitter.com/oembed?url=%s",
                urlPatterns = listOf(
                    """(?:twitter|x)\.com/[^/]+/status/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "TikTok",
                endpoint = "https://www.tiktok.com/oembed?url=%s",
                urlPatterns = listOf(
                    """tiktok\.com/""".toRegex(),
                    """vm\.tiktok\.com/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Reddit",
                endpoint = "https://www.reddit.com/oembed?url=%s",
                urlPatterns = listOf(
                    """reddit\.com/r/[^/]+/comments/""".toRegex(),
                    """redd\.it/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Vimeo",
                endpoint = "https://vimeo.com/api/oembed.json?url=%s",
                urlPatterns = listOf(
                    """vimeo\.com/\d+""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Spotify",
                endpoint = "https://open.spotify.com/oembed?url=%s",
                urlPatterns = listOf(
                    """open\.spotify\.com/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "SoundCloud",
                endpoint = "https://soundcloud.com/oembed?url=%s&format=json",
                urlPatterns = listOf(
                    """soundcloud\.com/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Imgur",
                endpoint = "https://api.imgur.com/oembed.json?url=%s",
                urlPatterns = listOf(
                    """imgur\.com/""".toRegex()
                )
            ),
            OEmbedProvider(
                name = "Giphy",
                endpoint = "https://giphy.com/services/oembed?url=%s",
                urlPatterns = listOf(
                    """giphy\.com/""".toRegex()
                )
            )
        )

        // Maps URL patterns for synthetic preview generation
        // Each pattern captures (latitude, longitude) as groups 1 and 2
        private data class MapsPattern(
            val regex: Regex,
            val siteName: String
        )

        private val MAPS_PATTERNS = listOf(
            // Google Maps ?q= format (our sending format): maps.google.com/?q=37.7749,-122.4194
            MapsPattern(
                """maps\.google\.com/?\?q=(-?\d+\.?\d*),(-?\d+\.?\d*)""".toRegex(RegexOption.IGNORE_CASE),
                "Google Maps"
            ),
            // Google Maps @ format: google.com/maps/@37.7749,-122.4194,15z
            MapsPattern(
                """google\.com/maps.*@(-?\d+\.?\d*),(-?\d+\.?\d*)""".toRegex(RegexOption.IGNORE_CASE),
                "Google Maps"
            ),
            // Apple Maps ?ll= format: maps.apple.com/?ll=37.7749,-122.4194
            MapsPattern(
                """maps\.apple\.com.*[?&]ll=(-?\d+\.?\d*),(-?\d+\.?\d*)""".toRegex(RegexOption.IGNORE_CASE),
                "Apple Maps"
            ),
            // Apple Maps ?q= format: maps.apple.com/?q=37.7749,-122.4194
            MapsPattern(
                """maps\.apple\.com.*[?&]q=(-?\d+\.?\d*),(-?\d+\.?\d*)""".toRegex(RegexOption.IGNORE_CASE),
                "Apple Maps"
            )
        )
    }

    // Rate limiting: max concurrent requests
    private val rateLimiter = Semaphore(MAX_CONCURRENT_REQUESTS)

    // Separate OkHttpClient for link previews (no auth interceptor)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Fetches metadata from a URL using oEmbed if available, falling back to Open Graph
     */
    suspend fun fetchMetadata(url: String): LinkMetadataResult = withContext(Dispatchers.IO) {
        rateLimiter.withPermit {
            try {
                // First, try maps preview for Google Maps / Apple Maps URLs
                val mapsResult = tryMapsPreview(url)
                if (mapsResult != null) {
                    Log.d(TAG, "Maps preview successful for: $url")
                    return@withPermit mapsResult
                }

                // Second, try oEmbed for supported providers
                val oembedResult = tryOEmbed(url)
                if (oembedResult != null) {
                    Log.d(TAG, "oEmbed successful for: $url")
                    return@withPermit oembedResult
                }

                // Fall back to Open Graph parsing
                Log.d(TAG, "Falling back to Open Graph for: $url")
                fetchOpenGraphMetadata(url)

            } catch (e: IOException) {
                Log.w(TAG, "Network error fetching metadata for $url", e)
                LinkMetadataResult.Error(e.message ?: "Network error")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching metadata for $url", e)
                LinkMetadataResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Tries to generate a synthetic preview for Google Maps / Apple Maps URLs.
     * Extracts coordinates from the URL, generates a static map image, and reverse geocodes the address.
     */
    private suspend fun tryMapsPreview(url: String): LinkMetadataResult? {
        // Find matching maps pattern
        for (pattern in MAPS_PATTERNS) {
            val match = pattern.regex.find(url) ?: continue

            // Extract coordinates from regex groups
            val lat = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: continue
            val lng = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: continue

            // Validate coordinate ranges
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                Log.w(TAG, "Invalid coordinates in maps URL: lat=$lat, lng=$lng")
                continue
            }

            Log.d(TAG, "Detected ${pattern.siteName} URL with coordinates: $lat, $lng")

            // Generate static map image URL
            val staticMapUrl = buildStaticMapUrl(lat, lng)

            // Try to reverse geocode the address
            val address = reverseGeocode(lat, lng)

            // Build the preview metadata
            val title = address ?: "Shared Location"
            val description = "%.6f, %.6f".format(lat, lng)

            return LinkMetadataResult.Success(
                LinkMetadata(
                    title = title,
                    description = description,
                    imageUrl = staticMapUrl,
                    faviconUrl = null,
                    siteName = pattern.siteName,
                    contentType = "location"
                )
            )
        }

        return null
    }

    /**
     * Builds a static map image URL using OpenStreetMap (free, no API key required)
     */
    private fun buildStaticMapUrl(lat: Double, lng: Double): String {
        return "https://staticmap.openstreetmap.de/staticmap.php?" +
            "center=$lat,$lng&zoom=15&size=400x200&markers=$lat,$lng,red-pushpin"
    }

    /**
     * Reverse geocodes coordinates to a human-readable address using Android Geocoder
     */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            addresses?.firstOrNull()?.getAddressLine(0)
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocoding failed: ${e.message}")
            null
        }
    }

    /**
     * Tries to fetch metadata via oEmbed for supported providers
     */
    private fun tryOEmbed(url: String): LinkMetadataResult? {
        // Find matching provider
        val provider = OEMBED_PROVIDERS.find { provider ->
            provider.urlPatterns.any { pattern -> pattern.containsMatchIn(url) }
        } ?: return null

        Log.d(TAG, "Using oEmbed provider: ${provider.name} for $url")

        return try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val oembedUrl = provider.endpoint.format(encodedUrl)

            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "oEmbed HTTP error ${response.code} for ${provider.name}")
                return null
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.w(TAG, "Empty oEmbed response for ${provider.name}")
                return null
            }

            parseOEmbedResponse(body, provider.name, url)

        } catch (e: Exception) {
            Log.w(TAG, "oEmbed failed for ${provider.name}: ${e.message}")
            null
        }
    }

    /**
     * Parses an oEmbed JSON response into LinkMetadata
     */
    private fun parseOEmbedResponse(json: String, providerName: String, originalUrl: String): LinkMetadataResult {
        return try {
            val obj = JSONObject(json)

            val title = obj.optString("title").takeIf { it.isNotBlank() }
            val authorName = obj.optString("author_name").takeIf { it.isNotBlank() }
            val authorUrl = obj.optString("author_url").takeIf { it.isNotBlank() }
            val thumbnailUrl = obj.optString("thumbnail_url").takeIf { it.isNotBlank() }
            val html = obj.optString("html").takeIf { it.isNotBlank() }
            val type = obj.optString("type").takeIf { it.isNotBlank() } ?: "link"
            val providerNameFromResponse = obj.optString("provider_name").takeIf { it.isNotBlank() }

            // For video types, try to extract a description from the embed
            val description = when {
                authorName != null && type == "video" -> "Video by $authorName"
                authorName != null && type == "rich" -> "Post by $authorName"
                else -> null
            }

            // Check if we got useful metadata
            if (title == null && thumbnailUrl == null) {
                Log.d(TAG, "No useful metadata from oEmbed for $providerName")
                return LinkMetadataResult.NoPreview
            }

            LinkMetadataResult.Success(
                LinkMetadata(
                    title = title?.take(200)?.trim(),
                    description = description?.take(500)?.trim(),
                    imageUrl = thumbnailUrl,
                    faviconUrl = null, // oEmbed doesn't provide favicon
                    siteName = providerNameFromResponse ?: providerName,
                    contentType = type,
                    embedHtml = html,
                    authorName = authorName,
                    authorUrl = authorUrl
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse oEmbed JSON: ${e.message}")
            LinkMetadataResult.Error("Failed to parse oEmbed response")
        }
    }

    /**
     * Fetches metadata using Open Graph meta tags (fallback)
     */
    private fun fetchOpenGraphMetadata(url: String): LinkMetadataResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.w(TAG, "HTTP error ${response.code} for $url")
            return LinkMetadataResult.Error("HTTP ${response.code}")
        }

        val contentType = response.header("Content-Type") ?: ""
        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0

        // Check content length
        if (contentLength > MAX_CONTENT_LENGTH) {
            Log.w(TAG, "Content too large ($contentLength bytes) for $url")
            return LinkMetadataResult.Error("Content too large")
        }

        // For images/videos, return basic metadata
        when {
            contentType.startsWith("image/") -> {
                return LinkMetadataResult.Success(
                    LinkMetadata(
                        title = null,
                        description = null,
                        imageUrl = url,
                        faviconUrl = null,
                        siteName = null,
                        contentType = "image"
                    )
                )
            }
            contentType.startsWith("video/") -> {
                return LinkMetadataResult.Success(
                    LinkMetadata(
                        title = null,
                        description = null,
                        imageUrl = null,
                        faviconUrl = null,
                        siteName = null,
                        contentType = "video"
                    )
                )
            }
            !contentType.contains("html") -> {
                Log.d(TAG, "Non-HTML content type: $contentType for $url")
                return LinkMetadataResult.NoPreview
            }
        }

        // Parse HTML for Open Graph / meta tags
        val html = response.body?.string()
        if (html.isNullOrBlank()) {
            return LinkMetadataResult.Error("Empty response body")
        }

        // Limit HTML size to prevent memory issues
        val truncatedHtml = if (html.length > MAX_CONTENT_LENGTH) {
            html.substring(0, MAX_CONTENT_LENGTH)
        } else {
            html
        }

        return parseHtmlMetadata(truncatedHtml, url)
    }

    /**
     * Parses HTML to extract Open Graph and meta tag metadata
     */
    private fun parseHtmlMetadata(html: String, url: String): LinkMetadataResult {
        val title = extractMetaContent(html, "og:title")
            ?: extractMetaContent(html, "twitter:title")
            ?: extractTitleTag(html)

        val description = extractMetaContent(html, "og:description")
            ?: extractMetaContent(html, "twitter:description")
            ?: extractMetaContent(html, "description")

        var imageUrl = extractMetaContent(html, "og:image")
            ?: extractMetaContent(html, "twitter:image")
            ?: extractMetaContent(html, "twitter:image:src")

        val siteName = extractMetaContent(html, "og:site_name")
            ?: extractMetaContent(html, "application-name")

        val faviconUrl = extractFavicon(html, url)

        val contentType = extractMetaContent(html, "og:type") ?: "website"

        // Resolve relative URLs
        imageUrl = imageUrl?.let { resolveUrl(it, url) }

        // Check if we got any useful metadata
        if (title == null && description == null && imageUrl == null) {
            return LinkMetadataResult.NoPreview
        }

        return LinkMetadataResult.Success(
            LinkMetadata(
                title = title?.take(200)?.trim(),
                description = description?.take(500)?.trim(),
                imageUrl = imageUrl,
                faviconUrl = resolveUrl(faviconUrl, url),
                siteName = siteName?.take(100)?.trim(),
                contentType = contentType
            )
        )
    }

    /**
     * Extracts content from meta tags with property or name attribute
     */
    private fun extractMetaContent(html: String, property: String): String? {
        // Match property="..." content="..."
        val propertyRegex = """<meta[^>]+property=["']$property["'][^>]+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        propertyRegex.find(html)?.groupValues?.get(1)?.let { return decodeHtmlEntities(it) }

        // Match content="..." property="..."
        val propertyAltRegex = """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']$property["']""".toRegex(RegexOption.IGNORE_CASE)
        propertyAltRegex.find(html)?.groupValues?.get(1)?.let { return decodeHtmlEntities(it) }

        // Match name="..." content="..."
        val nameRegex = """<meta[^>]+name=["']$property["'][^>]+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        nameRegex.find(html)?.groupValues?.get(1)?.let { return decodeHtmlEntities(it) }

        // Match content="..." name="..."
        val nameAltRegex = """<meta[^>]+content=["']([^"']+)["'][^>]+name=["']$property["']""".toRegex(RegexOption.IGNORE_CASE)
        nameAltRegex.find(html)?.groupValues?.get(1)?.let { return decodeHtmlEntities(it) }

        return null
    }

    /**
     * Extracts the page title from the <title> tag
     */
    private fun extractTitleTag(html: String): String? {
        val regex = """<title[^>]*>([^<]+)</title>""".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)?.let { decodeHtmlEntities(it.trim()) }
    }

    /**
     * Extracts the favicon URL from link tags or uses default
     */
    private fun extractFavicon(html: String, baseUrl: String): String? {
        // Try to find icon link tags
        val iconRegex = """<link[^>]+rel=["'](?:shortcut\s+)?icon["'][^>]+href=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        iconRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Try alternate order
        val iconAltRegex = """<link[^>]+href=["']([^"']+)["'][^>]+rel=["'](?:shortcut\s+)?icon["']""".toRegex(RegexOption.IGNORE_CASE)
        iconAltRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Try apple-touch-icon
        val appleIconRegex = """<link[^>]+rel=["']apple-touch-icon["'][^>]+href=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        appleIconRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Default to /favicon.ico
        return "/favicon.ico"
    }

    /**
     * Resolves a potentially relative URL against a base URL
     */
    private fun resolveUrl(url: String?, baseUrl: String): String? {
        if (url == null) return null

        return try {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> {
                    val base = URI(baseUrl)
                    "${base.scheme}://${base.host}$url"
                }
                else -> {
                    val base = URI(baseUrl)
                    val basePath = baseUrl.substringBeforeLast("/")
                    "$basePath/$url"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve URL: $url against $baseUrl", e)
            null
        }
    }

    /**
     * Decodes common HTML entities
     */
    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#x27;", "'")
            .replace("&#x2F;", "/")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
    }
}
