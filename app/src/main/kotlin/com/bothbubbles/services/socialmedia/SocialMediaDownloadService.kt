package com.bothbubbles.services.socialmedia

import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.util.NetworkConnectivityManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Video quality options for platforms that support multiple qualities.
 */
enum class VideoQuality(val key: String, val displayName: String) {
    SD("sd", "Standard"),
    HD("hd", "HD")
}

/**
 * Result of checking if a download can proceed.
 */
sealed interface DownloadPermission {
    data object Allowed : DownloadPermission
    data class Blocked(val reason: String) : DownloadPermission
}

/**
 * Service for extracting video URLs from social media platforms.
 *
 * Uses free public APIs:
 * - TikTok: tikwm.com API
 * - Instagram: snapinst.app (via scraping approach similar to open source implementations)
 */
@Singleton
class SocialMediaDownloadService @Inject constructor(
    private val featurePreferences: FeaturePreferences,
    private val networkManager: NetworkConnectivityManager,
    private val cacheManager: SocialMediaCacheManager,
    private val messageDao: com.bothbubbles.data.local.db.dao.MessageDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : SocialMediaDownloader {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Set of message GUIDs + URL combinations that have been dismissed.
     * Format: "messageGuid:url"
     * When a user clicks "Show Original", the link is added here to prevent auto-download.
     */
    private val dismissedLinks: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    companion object {
        private const val TIKWM_API_URL = "https://www.tikwm.com/api/"

        // User agent to mimic a browser
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // URL patterns for detection
        private val TIKTOK_PATTERNS = listOf(
            Regex("""(?:https?://)?(?:www\.)?tiktok\.com/@[^/]+/video/\d+""", RegexOption.IGNORE_CASE),
            Regex("""(?:https?://)?(?:vm|vt)\.tiktok\.com/[A-Za-z0-9]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:https?://)?(?:www\.)?tiktok\.com/t/[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)
        )

        private val INSTAGRAM_PATTERNS = listOf(
            // Standard Instagram URLs (with optional query params and trailing slash)
            Regex("""(?:https?://)?(?:www\.)?instagram\.com/(?:p|reel|reels|tv)/[A-Za-z0-9_-]+[/?]?""", RegexOption.IGNORE_CASE),
            // Share URLs (newer format)
            Regex("""(?:https?://)?(?:www\.)?instagram\.com/share/(?:reel|p|video)/[A-Za-z0-9_-]+[/?]?""", RegexOption.IGNORE_CASE),
            // Short URLs
            Regex("""(?:https?://)?(?:www\.)?instagr\.am/(?:p|reel)/[A-Za-z0-9_-]+[/?]?""", RegexOption.IGNORE_CASE)
        )
    }

    override fun detectPlatform(url: String): SocialMediaPlatform? {
        return when {
            TIKTOK_PATTERNS.any { it.containsMatchIn(url) } -> SocialMediaPlatform.TIKTOK
            INSTAGRAM_PATTERNS.any { it.containsMatchIn(url) } -> SocialMediaPlatform.INSTAGRAM
            else -> null
        }
    }

    override suspend fun isDownloadEnabled(platform: SocialMediaPlatform): Boolean {
        return when (platform) {
            SocialMediaPlatform.TIKTOK -> featurePreferences.tiktokDownloaderEnabled.first()
            SocialMediaPlatform.INSTAGRAM -> featurePreferences.instagramDownloaderEnabled.first()
        }
    }

    /**
     * Checks if downloading is currently allowed based on network conditions and preferences.
     */
    suspend fun canDownload(): DownloadPermission {
        if (!networkManager.isConnected()) {
            return DownloadPermission.Blocked("No network connection")
        }

        val allowOnCellular = featurePreferences.socialMediaDownloadOnCellularEnabled.first()
        if (!networkManager.canDownload(allowOnCellular)) {
            return DownloadPermission.Blocked("Downloading is disabled on cellular. Connect to Wi-Fi or enable cellular downloads in settings.")
        }

        return DownloadPermission.Allowed
    }

    /**
     * Checks if background downloading is enabled.
     */
    suspend fun isBackgroundDownloadEnabled(): Boolean {
        return featurePreferences.socialMediaBackgroundDownloadEnabled.first()
    }

    /**
     * Gets the preferred video quality for TikTok.
     */
    suspend fun getTiktokVideoQuality(): VideoQuality {
        val qualityKey = featurePreferences.tiktokVideoQuality.first()
        return VideoQuality.entries.find { it.key == qualityKey } ?: VideoQuality.HD
    }

    /**
     * Checks if the Reels feed feature is enabled.
     */
    suspend fun isReelsFeedEnabled(): Boolean {
        // Reels feed requires background download to be enabled
        val backgroundEnabled = featurePreferences.socialMediaBackgroundDownloadEnabled.first()
        val reelsEnabled = featurePreferences.reelsFeedEnabled.first()
        return backgroundEnabled && reelsEnabled
    }

    /**
     * Checks if a specific link has been dismissed by the user.
     * @param messageGuid The GUID of the message containing the link
     * @param url The URL that was dismissed
     */
    fun isLinkDismissed(messageGuid: String, url: String): Boolean {
        return dismissedLinks.contains("$messageGuid:$url")
    }

    /**
     * Marks a link as dismissed so it won't auto-download in the future.
     * @param messageGuid The GUID of the message containing the link
     * @param url The URL to dismiss
     */
    fun dismissLink(messageGuid: String, url: String) {
        dismissedLinks.add("$messageGuid:$url")
        Timber.d("Dismissed social media link: $url for message $messageGuid")
    }

    /**
     * Clears dismissed status for a link (e.g., if user wants to re-enable downloading).
     */
    fun clearDismissedLink(messageGuid: String, url: String) {
        dismissedLinks.remove("$messageGuid:$url")
    }

    /**
     * Gets the cache manager for direct cache operations.
     */
    fun getCacheManager(): SocialMediaCacheManager = cacheManager

    /**
     * Gets the cached local path for a video if available.
     */
    suspend fun getCachedVideoPath(originalUrl: String): String? {
        return cacheManager.getCachedPath(originalUrl)
    }

    /**
     * Downloads and caches a video, returning a progress flow.
     */
    suspend fun downloadAndCacheVideo(
        result: SocialMediaResult.Success,
        originalUrl: String,
        messageGuid: String,
        chatGuid: String? = null,
        platform: SocialMediaPlatform,
        senderName: String? = null,
        senderAddress: String? = null,
        sentTimestamp: Long = 0L
    ): StateFlow<DownloadProgress> {
        // Choose quality based on preference
        val videoUrl = when (platform) {
            SocialMediaPlatform.TIKTOK -> {
                val quality = getTiktokVideoQuality()
                if (quality == VideoQuality.HD && result.hdVideoUrl != null) {
                    result.hdVideoUrl
                } else {
                    result.videoUrl
                }
            }
            SocialMediaPlatform.INSTAGRAM -> result.videoUrl
        }

        return cacheManager.downloadAndCache(
            videoUrl = videoUrl,
            originalUrl = originalUrl,
            messageGuid = messageGuid,
            chatGuid = chatGuid,
            platform = platform,
            senderName = senderName,
            senderAddress = senderAddress,
            sentTimestamp = sentTimestamp
        )
    }

    override suspend fun extractVideoUrl(url: String): SocialMediaResult {
        val platform = detectPlatform(url) ?: return SocialMediaResult.NotSupported

        if (!isDownloadEnabled(platform)) {
            return SocialMediaResult.NotSupported
        }

        return extractVideoUrl(url, platform)
    }

    override suspend fun extractVideoUrl(url: String, platform: SocialMediaPlatform): SocialMediaResult {
        return withContext(ioDispatcher) {
            try {
                when (platform) {
                    SocialMediaPlatform.TIKTOK -> extractTikTokVideo(url)
                    SocialMediaPlatform.INSTAGRAM -> extractInstagramVideo(url)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to extract video from $platform: $url")
                SocialMediaResult.Error("Failed to extract video: ${e.message}", e)
            }
        }
    }

    /**
     * Extracts video URL from TikTok using tikwm.com API.
     *
     * API: GET https://www.tikwm.com/api/?url={url}
     * Response: { "data": { "play": "video_url", "hdplay": "hd_video_url", "music": "audio_url", "cover": "thumbnail_url" } }
     */
    private fun extractTikTokVideo(url: String): SocialMediaResult {
        val apiUrl = "$TIKWM_API_URL?url=${java.net.URLEncoder.encode(url, "UTF-8")}"

        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SocialMediaResult.Error("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                return SocialMediaResult.Error("Empty response from TikWM API")
            }

            return parseTikWmResponse(body)
        }
    }

    private fun parseTikWmResponse(json: String): SocialMediaResult {
        return try {
            val root = JSONObject(json)
            val code = root.optInt("code", -1)

            if (code != 0) {
                val msg = root.optString("msg", "Unknown error")
                return SocialMediaResult.Error("TikWM error: $msg")
            }

            val data = root.optJSONObject("data")
                ?: return SocialMediaResult.Error("No data in response")

            val videoUrl = data.optString("play").takeIf { it.isNotBlank() }
                ?: return SocialMediaResult.Error("No video URL in response")

            val hdVideoUrl = data.optString("hdplay").takeIf { it.isNotBlank() }
            val audioUrl = data.optString("music").takeIf { it.isNotBlank() }
            val thumbnailUrl = data.optString("cover").takeIf { it.isNotBlank() }

            SocialMediaResult.Success(
                videoUrl = videoUrl,
                hdVideoUrl = hdVideoUrl,
                audioUrl = audioUrl,
                thumbnailUrl = thumbnailUrl
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse TikWM response")
            SocialMediaResult.Error("Failed to parse response: ${e.message}", e)
        }
    }

    /**
     * Extracts video URL from Instagram using direct GraphQL API or third-party services.
     *
     * Order of methods:
     * 1. Instagram GraphQL API (like instaloader uses)
     * 2. Instagram JSON API (?__a=1)
     * 3. Third-party fallback services
     */
    private fun extractInstagramVideo(url: String): SocialMediaResult {
        // Extract shortcode from URL
        val shortcode = extractShortcode(url)
        if (shortcode == null) {
            Timber.w("[Instagram] Could not extract shortcode from URL: $url")
            return SocialMediaResult.Error("Invalid Instagram URL")
        }

        Timber.d("[Instagram] Extracted shortcode: $shortcode from $url")

        // Method 1: Try Instagram GraphQL API (like instaloader)
        val graphqlResult = tryExtractViaGraphQL(shortcode)
        if (graphqlResult is SocialMediaResult.Success) {
            return graphqlResult
        }

        // Method 2: Try Instagram JSON API
        val jsonApiResult = tryExtractViaJsonApi(shortcode)
        if (jsonApiResult is SocialMediaResult.Success) {
            return jsonApiResult
        }

        // Method 3: Try Instagram embed page
        val embedResult = tryExtractViaEmbed(shortcode)
        if (embedResult is SocialMediaResult.Success) {
            return embedResult
        }

        // Method 4: Try Cobalt API instances
        val cobaltResult = tryExtractViaCobalt(shortcode)
        if (cobaltResult is SocialMediaResult.Success) {
            return cobaltResult
        }

        // All methods failed
        Timber.w("[Instagram] All extraction methods failed for $url")
        return SocialMediaResult.Error("Could not extract video. Instagram may have blocked the request.")
    }

    /**
     * Extract shortcode from various Instagram URL formats
     */
    private fun extractShortcode(url: String): String? {
        // Match /p/, /reel/, /reels/, /tv/, or /share/reel/ followed by shortcode
        val patterns = listOf(
            """/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""".toRegex(),
            """/share/(?:reel|p|video)/([A-Za-z0-9_-]+)""".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    /**
     * Try extracting via Instagram's GraphQL API using doc_id approach.
     * This is the method used by yt-dlp and other working extractors in 2024/2025.
     *
     * The key insight is using doc_id instead of query_hash, and parsing
     * xdt_shortcode_media instead of shortcode_media.
     */
    private fun tryExtractViaGraphQL(shortcode: String): SocialMediaResult {
        return try {
            // Working doc_id from yt-dlp (as of Dec 2024)
            val docId = "8845758582119845"
            val variables = """{"shortcode":"$shortcode","child_comment_count":3,"fetch_comment_count":40,"parent_comment_count":24,"has_threaded_comments":true}"""
            val encodedVariables = java.net.URLEncoder.encode(variables, "UTF-8")

            val graphqlUrl = "https://www.instagram.com/graphql/query/?doc_id=$docId&variables=$encodedVariables"

            val request = Request.Builder()
                .url(graphqlUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-IG-App-ID", "936619743392459")  // Instagram web app ID
                .header("X-ASBD-ID", "198387")
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("[Instagram] GraphQL API returned ${response.code}")
                    return@use SocialMediaResult.Error("GraphQL failed: ${response.code}")
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return@use SocialMediaResult.Error("Empty GraphQL response")
                }

                parseGraphQLResponse(body)
            }
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] GraphQL extraction failed")
            SocialMediaResult.Error("GraphQL failed: ${e.message}")
        }
    }

    private fun parseGraphQLResponse(json: String): SocialMediaResult {
        return try {
            val root = JSONObject(json)

            // Navigate: data -> xdt_shortcode_media (new format) or shortcode_media (legacy)
            val data = root.optJSONObject("data")
            if (data == null) {
                Timber.d("[Instagram] No 'data' in GraphQL response")
                return SocialMediaResult.Error("Invalid GraphQL response structure")
            }

            // Try new xdt_shortcode_media first (used by doc_id approach), then legacy shortcode_media
            val media = data.optJSONObject("xdt_shortcode_media")
                ?: data.optJSONObject("shortcode_media")
            if (media == null) {
                Timber.d("[Instagram] No media object in GraphQL response")
                return SocialMediaResult.Error("Media not found in response")
            }

            // Check if it's a video - check typename or is_video flag
            val typename = media.optString("__typename", "")
            val isVideo = typename.contains("Video", ignoreCase = true) ||
                         media.optBoolean("is_video", false)
            if (!isVideo) {
                Timber.d("[Instagram] Content is not a video (typename: $typename)")
                return SocialMediaResult.NotSupported
            }

            // Get video URL
            val videoUrl = media.optString("video_url", "")
            if (videoUrl.isBlank()) {
                Timber.d("[Instagram] No video_url in GraphQL response")
                return SocialMediaResult.Error("Video URL not found")
            }

            // Try multiple thumbnail fields
            val thumbnailUrl = media.optString("thumbnail_src", "").takeIf { it.isNotBlank() }
                ?: media.optString("display_url", "").takeIf { it.isNotBlank() }
                ?: media.optJSONArray("display_resources")
                    ?.optJSONObject(0)
                    ?.optString("src", "")

            Timber.d("[Instagram] Found video via GraphQL (doc_id): ${videoUrl.take(80)}...")
            SocialMediaResult.Success(
                videoUrl = videoUrl,
                thumbnailUrl = thumbnailUrl
            )
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] Failed to parse GraphQL response")
            SocialMediaResult.Error("Failed to parse GraphQL: ${e.message}")
        }
    }

    /**
     * Try extracting via Instagram's JSON API (?__a=1)
     */
    private fun tryExtractViaJsonApi(shortcode: String): SocialMediaResult {
        return try {
            // Try both /p/ and /reel/ endpoints
            val endpoints = listOf(
                "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis",
                "https://www.instagram.com/reel/$shortcode/?__a=1&__d=dis"
            )

            for (apiUrl in endpoints) {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("X-IG-App-ID", "936619743392459")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank() && !body.contains("\"require_login\"")) {
                            val result = parseJsonApiResponse(body)
                            if (result is SocialMediaResult.Success) {
                                return result
                            }
                        }
                    }
                }
            }

            SocialMediaResult.Error("JSON API failed for all endpoints")
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] JSON API extraction failed")
            SocialMediaResult.Error("JSON API failed: ${e.message}")
        }
    }

    private fun parseJsonApiResponse(json: String): SocialMediaResult {
        return try {
            val root = JSONObject(json)

            // Navigate to media items
            val items = root.optJSONObject("graphql")?.optJSONObject("shortcode_media")
                ?: root.optJSONArray("items")?.optJSONObject(0)

            if (items == null) {
                return SocialMediaResult.Error("No media items in JSON API response")
            }

            // Check if video
            val isVideo = items.optBoolean("is_video", false) ||
                         items.optInt("media_type", 0) == 2  // 2 = video

            if (!isVideo) {
                return SocialMediaResult.NotSupported
            }

            // Get video URL - try multiple possible field names
            val videoUrl = items.optString("video_url", "").takeIf { it.isNotBlank() }
                ?: items.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url", "")

            if (videoUrl.isNullOrBlank()) {
                return SocialMediaResult.Error("Video URL not found in JSON API")
            }

            val thumbnailUrl = items.optString("thumbnail_src", "")
                .takeIf { it.isNotBlank() }
                ?: items.optJSONObject("image_versions2")
                    ?.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optString("url", "")

            Timber.d("[Instagram] Found video via JSON API: $videoUrl")
            SocialMediaResult.Success(
                videoUrl = videoUrl,
                thumbnailUrl = thumbnailUrl
            )
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] Failed to parse JSON API response")
            SocialMediaResult.Error("Failed to parse JSON API: ${e.message}")
        }
    }

    /**
     * Try extracting via Instagram's embed page
     * Fetches the embed HTML and extracts video URL from meta tags
     */
    private fun tryExtractViaEmbed(shortcode: String): SocialMediaResult {
        return try {
            // Try Instagram's embed endpoint
            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/"

            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                Timber.d("[Instagram] Embed response: ${response.code}, length: ${body?.length}")

                if (!response.isSuccessful || body.isNullOrBlank()) {
                    return@use SocialMediaResult.Error("Embed page failed: ${response.code}")
                }

                parseEmbedResponse(body)
            }
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] Embed extraction failed")
            SocialMediaResult.Error("Embed failed: ${e.message}")
        }
    }

    private fun parseEmbedResponse(html: String): SocialMediaResult {
        return try {
            // Look for video URL in embed page - multiple patterns
            val patterns = listOf(
                // Direct video URL in JSON
                """"video_url"\s*:\s*"([^"]+)"""".toRegex(),
                // Content URL meta tag
                """content=["']([^"']*\.mp4[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
                // Video source in HTML
                """<source[^>]+src=["']([^"']+\.mp4[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
                // og:video meta tag
                """og:video(?::url)?["']\s+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
                """content=["']([^"']+)["']\s+(?:property|name)=["']og:video""".toRegex(RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    var videoUrl = match.groupValues[1]
                        .replace("\\u0026", "&")
                        .replace("\\/", "/")
                        .replace("&amp;", "&")

                    if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains("video"))) {
                        Timber.d("[Instagram] Found video via embed: $videoUrl")
                        return SocialMediaResult.Success(videoUrl = videoUrl)
                    }
                }
            }

            // Check if login required
            if (html.contains("loginForm") || html.contains("\"viewerId\":null")) {
                Timber.d("[Instagram] Embed page requires login")
                return SocialMediaResult.NotSupported
            }

            Timber.d("[Instagram] No video found in embed page (length: ${html.length})")
            SocialMediaResult.Error("No video URL in embed page")
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] Failed to parse embed response")
            SocialMediaResult.Error("Failed to parse embed: ${e.message}")
        }
    }

    /**
     * Try extracting via Cobalt API instances (no auth required)
     * Tries multiple public instances from instances.cobalt.best
     */
    private fun tryExtractViaCobalt(shortcode: String): SocialMediaResult {
        val instagramUrl = "https://www.instagram.com/reel/$shortcode/"

        // Public Cobalt instances that don't require JWT (from instances.cobalt.best)
        val cobaltInstances = listOf(
            "https://cobalt-backend.canine.tools/",
            "https://cobalt-api.meowing.de/",
            "https://capi.3kh0.net/"
        )

        for (cobaltUrl in cobaltInstances) {
            try {
                val jsonBody = JSONObject().apply {
                    put("url", instagramUrl)
                }

                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(cobaltUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    Timber.d("[Instagram] Cobalt ($cobaltUrl) response: ${response.code}, body: ${body?.take(300)}")

                    if (response.isSuccessful && !body.isNullOrBlank()) {
                        val result = parseCobaltResponse(body)
                        if (result is SocialMediaResult.Success) {
                            return result
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "[Instagram] Cobalt ($cobaltUrl) failed")
            }
        }

        return SocialMediaResult.Error("All Cobalt instances failed")
    }

    private fun parseCobaltResponse(json: String): SocialMediaResult {
        return try {
            val root = JSONObject(json)
            val status = root.optString("status", "")

            when (status) {
                "stream", "redirect" -> {
                    val videoUrl = root.optString("url", "")
                    if (videoUrl.isNotBlank()) {
                        Timber.d("[Instagram] Found video via Cobalt: $videoUrl")
                        SocialMediaResult.Success(videoUrl = videoUrl)
                    } else {
                        SocialMediaResult.Error("No URL in Cobalt response")
                    }
                }
                "picker" -> {
                    // Multiple options - get the first video
                    val picker = root.optJSONArray("picker")
                    if (picker != null && picker.length() > 0) {
                        val first = picker.optJSONObject(0)
                        val videoUrl = first?.optString("url", "")
                        if (!videoUrl.isNullOrBlank()) {
                            Timber.d("[Instagram] Found video via Cobalt (picker): $videoUrl")
                            return SocialMediaResult.Success(videoUrl = videoUrl)
                        }
                    }
                    SocialMediaResult.Error("No video in Cobalt picker response")
                }
                "error" -> {
                    val errorText = root.optJSONObject("text")?.optString("en", "")
                        ?: root.optString("text", "Unknown error")
                    Timber.d("[Instagram] Cobalt error: $errorText")
                    SocialMediaResult.Error("Cobalt: $errorText")
                }
                else -> {
                    Timber.d("[Instagram] Unknown Cobalt status: $status")
                    SocialMediaResult.Error("Unknown Cobalt response: $status")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] Failed to parse Cobalt response")
            SocialMediaResult.Error("Failed to parse Cobalt: ${e.message}")
        }
    }

    /**
     * Caches the most recent social media videos from messages.
     * Called on app startup when Reels is enabled and on WiFi.
     *
     * @param maxVideos Maximum number of videos to cache (default 5)
     * @return Number of videos that started downloading
     */
    suspend fun cacheRecentVideos(maxVideos: Int = 5): Int = withContext(ioDispatcher) {
        try {
            // Check if Reels is enabled
            if (!isReelsFeedEnabled()) {
                Timber.d("[SocialMedia] Reels not enabled, skipping auto-cache")
                return@withContext 0
            }

            // Check network - prefer WiFi
            if (!networkManager.isConnected()) {
                Timber.d("[SocialMedia] No network, skipping auto-cache")
                return@withContext 0
            }

            val allowCellular = featurePreferences.socialMediaDownloadOnCellularEnabled.first()
            if (!networkManager.canDownload(allowCellular)) {
                Timber.d("[SocialMedia] Not on WiFi and cellular disabled, skipping auto-cache")
                return@withContext 0
            }

            // Get recent messages with text (last 100 messages across all chats)
            val recentMessages = messageDao.getRecentMessagesWithText(limit = 100)

            val urlPattern = Regex("""https?://[^\s<>"]+""")
            var downloadedCount = 0
            val processedUrls = mutableSetOf<String>()

            for (message in recentMessages) {
                if (downloadedCount >= maxVideos) break

                val text = message.text ?: continue
                val urls = urlPattern.findAll(text).map { it.value }.toList()

                for (url in urls) {
                    if (downloadedCount >= maxVideos) break
                    if (processedUrls.contains(url)) continue

                    // Check if this URL is a supported social media platform
                    val platform = detectPlatform(url) ?: continue
                    processedUrls.add(url)

                    // Check if already cached
                    if (cacheManager.getCachedPath(url) != null) {
                        continue
                    }

                    // Check if downloading is enabled for this platform
                    if (!isDownloadEnabled(platform)) {
                        continue
                    }

                    Timber.d("[SocialMedia] Auto-caching $platform video: $url")

                    try {
                        // Extract video URL
                        val result = extractVideoUrl(url, platform)
                        if (result is SocialMediaResult.Success) {
                            // Download and cache the video
                            downloadAndCacheVideo(
                                result = result,
                                originalUrl = url,
                                messageGuid = message.guid,
                                chatGuid = message.chatGuid,
                                platform = platform,
                                senderName = if (message.isFromMe) "You" else null,
                                senderAddress = null,
                                sentTimestamp = message.dateCreated
                            )
                            downloadedCount++
                            Timber.d("[SocialMedia] Started auto-cache download for: $url")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "[SocialMedia] Failed to auto-cache: $url")
                    }
                }
            }

            Timber.i("[SocialMedia] Auto-cache complete: started $downloadedCount downloads")
            downloadedCount
        } catch (e: Exception) {
            Timber.e(e, "[SocialMedia] Error during auto-cache")
            0
        }
    }
}
