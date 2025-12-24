package com.bothbubbles.services.socialmedia

import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.util.NetworkConnectivityManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
        // User agent to mimic a browser
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Mobile user agent to mimic Instagram Android app (from Cobalt)
        private const val MOBILE_USER_AGENT = "Instagram 275.0.0.27.98 Android (33/13; 280dpi; 720x1423; Xiaomi; Redmi 7; onclite; qcom; en_US; 458229237)"

        // URL patterns for detection
        private val TIKTOK_PATTERNS = listOf(
            Regex("""(?:https?://)?(?:www\.)?tiktok\.com/@[^/]+/video/\d+""", RegexOption.IGNORE_CASE),
            Regex("""(?:https?://)?(?:vm|vt)\.tiktok\.com/[A-Za-z0-9]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:https?://)?(?:www\.)?tiktok\.com/t/[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)
        )

        private val INSTAGRAM_PATTERNS = listOf(
            // Standard Instagram Reels URLs (with optional query params and trailing slash)
            // Note: We intentionally exclude /p/ (posts) - only reels have downloadable video
            Regex("""(?:https?://)?(?:www\.)?instagram\.com/(?:reel|reels)/[A-Za-z0-9_-]+[/?]?""", RegexOption.IGNORE_CASE),
            // Share URLs (newer format) - reels only
            Regex("""(?:https?://)?(?:www\.)?instagram\.com/share/(?:reel|video)/[A-Za-z0-9_-]+[/?]?""", RegexOption.IGNORE_CASE),
            // Short URLs - reels only
            Regex("""(?:https?://)?(?:www\.)?instagr\.am/reel/[A-Za-z0-9_-]+[/?]?""", RegexOption.IGNORE_CASE)
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
     * Extracts video URL from TikTok by directly fetching the page and parsing embedded JSON.
     *
     * This method:
     * 1. Resolves short URLs (vm.tiktok.com, vt.tiktok.com) to full URLs
     * 2. Fetches the TikTok page with browser-like headers
     * 3. Parses __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON embedded in the page
     * 4. Extracts downloadAddr (full video with audio) or playAddr (video stream)
     */
    private fun extractTikTokVideo(url: String): SocialMediaResult {
        // Step 1: Resolve short URLs to full URLs
        val resolvedUrl = resolveTikTokShortUrl(url)
        Timber.d("[TikTok] Resolved URL: $resolvedUrl")

        // Step 2: Fetch the TikTok page
        val request = Request.Builder()
            .url(resolvedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SocialMediaResult.Error("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                return SocialMediaResult.Error("Empty response from TikTok")
            }

            return parseTikTokPageResponse(body)
        }
    }

    /**
     * Resolves TikTok short URLs (vm.tiktok.com, vt.tiktok.com, tiktok.com/t/) to full video URLs.
     */
    private fun resolveTikTokShortUrl(url: String): String {
        // Check if it's already a full URL
        if (url.contains("tiktok.com/@") && url.contains("/video/")) {
            return url
        }

        // For short URLs, follow redirects to get the full URL
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .head() // Just get headers, don't download body
            .build()

        // Use a client that doesn't auto-follow redirects so we can get the Location header
        val noRedirectClient = httpClient.newBuilder()
            .followRedirects(false)
            .build()

        return try {
            noRedirectClient.newCall(request).execute().use { response ->
                val location = response.header("Location")
                if (location != null && location.contains("tiktok.com")) {
                    Timber.d("[TikTok] Short URL resolved: $url -> $location")
                    location
                } else {
                    // If no redirect, try following with the regular client
                    httpClient.newCall(request).execute().use { followedResponse ->
                        followedResponse.request.url.toString()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "[TikTok] Failed to resolve short URL, using original")
            url
        }
    }

    /**
     * Parses the TikTok page HTML to extract video URLs from embedded JSON.
     */
    private fun parseTikTokPageResponse(html: String): SocialMediaResult {
        return try {
            // Look for __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON
            val jsonPattern = """<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">(.+?)</script>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = jsonPattern.find(html)

            if (match == null) {
                Timber.w("[TikTok] Could not find __UNIVERSAL_DATA_FOR_REHYDRATION__ in page")
                return SocialMediaResult.Error("Could not find video data in page")
            }

            val jsonStr = match.groupValues[1]
            val root = JSONObject(jsonStr)

            // Navigate to video data: __DEFAULT_SCOPE__ -> webapp.video-detail -> itemInfo -> itemStruct -> video
            val defaultScope = root.optJSONObject("__DEFAULT_SCOPE__")
            val videoDetail = defaultScope?.optJSONObject("webapp.video-detail")
            val itemInfo = videoDetail?.optJSONObject("itemInfo")
            val itemStruct = itemInfo?.optJSONObject("itemStruct")
            val video = itemStruct?.optJSONObject("video")

            if (video == null) {
                Timber.w("[TikTok] Could not navigate to video object in JSON")
                return SocialMediaResult.Error("Video data not found in response")
            }

            // Priority 1: downloadAddr (full video with audio, watermark-free)
            val downloadAddr = video.optString("downloadAddr").takeIf { it.isNotBlank() }

            // Priority 2: playAddr (video stream)
            val playAddr = video.optString("playAddr").takeIf { it.isNotBlank() }

            // Priority 3: playUrl (often audio-only, avoid if possible)
            val playUrl = video.optString("playUrl").takeIf { it.isNotBlank() }

            // Get cover/thumbnail
            val cover = video.optString("cover").takeIf { it.isNotBlank() }
                ?: video.optString("dynamicCover").takeIf { it.isNotBlank() }
                ?: video.optString("originCover").takeIf { it.isNotBlank() }

            // Choose the best video URL
            val videoUrl = downloadAddr ?: playAddr
            if (videoUrl == null) {
                Timber.w("[TikTok] No video URL found in response")
                return SocialMediaResult.Error("No video URL in response")
            }

            Timber.d("[TikTok] Found video URL via direct extraction: ${videoUrl.take(80)}...")

            SocialMediaResult.Success(
                videoUrl = videoUrl,
                hdVideoUrl = downloadAddr, // downloadAddr is typically highest quality
                audioUrl = playUrl, // playUrl is often audio-only
                thumbnailUrl = cover
            )
        } catch (e: Exception) {
            Timber.e(e, "[TikTok] Failed to parse page response")
            SocialMediaResult.Error("Failed to parse response: ${e.message}", e)
        }
    }

    /**
     * Extracts video URL from Instagram using multiple methods (matching Cobalt's approach).
     *
     * Order of methods:
     * 1. Mobile API via oEmbed (i.instagram.com) - Cobalt's primary method
     * 2. Instagram GraphQL API (doc_id approach)
     * 3. Instagram JSON API (?__a=1)
     */
    private fun extractInstagramVideo(url: String): SocialMediaResult {
        // Extract shortcode from URL
        val shortcode = extractShortcode(url)
        if (shortcode == null) {
            Timber.w("[Instagram] Could not extract shortcode from URL: $url")
            return SocialMediaResult.Error("Invalid Instagram URL")
        }

        Timber.d("[Instagram] Extracted shortcode: $shortcode from $url")

        // Method 1: Try Mobile API via oEmbed (Cobalt's primary method)
        val mobileApiResult = tryExtractViaMobileApi(shortcode)
        if (mobileApiResult is SocialMediaResult.Success) {
            return mobileApiResult
        }

        // Method 2: Try Instagram GraphQL API (like instaloader)
        val graphqlResult = tryExtractViaGraphQL(shortcode)
        if (graphqlResult is SocialMediaResult.Success) {
            return graphqlResult
        }

        // Method 3: Try Instagram JSON API
        val jsonApiResult = tryExtractViaJsonApi(shortcode)
        if (jsonApiResult is SocialMediaResult.Success) {
            return jsonApiResult
        }

        // Method 4: Try HTML page scraping (fallback)
        val htmlResult = tryExtractViaHtmlScrape(url)
        if (htmlResult is SocialMediaResult.Success) {
            return htmlResult
        }

        // All methods failed
        Timber.w("[Instagram] All extraction methods failed for $url")
        return SocialMediaResult.Error("Could not extract video. Instagram may have blocked the request.")
    }

    /**
     * Try extracting via Instagram's Mobile API (Cobalt's primary method).
     *
     * Two-step process:
     * 1. Call oEmbed API to get media_id from shortcode
     * 2. Call Mobile API with media_id to get video URL
     */
    private fun tryExtractViaMobileApi(shortcode: String): SocialMediaResult {
        return try {
            // Step 1: Get media_id via oEmbed API
            val mediaId = getMediaIdFromOEmbed(shortcode)
            if (mediaId == null) {
                Timber.d("[Instagram] Could not get media_id from oEmbed")
                return SocialMediaResult.Error("oEmbed failed to return media_id")
            }

            Timber.d("[Instagram] Got media_id: $mediaId from oEmbed")

            // Step 2: Get video URL via Mobile API
            val mediaInfoUrl = "https://i.instagram.com/api/v1/media/${mediaId}/info/"

            val request = Request.Builder()
                .url(mediaInfoUrl)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US")
                .header("X-IG-App-Locale", "en_US")
                .header("X-IG-Device-Locale", "en_US")
                .header("X-IG-Mapped-Locale", "en_US")
                .header("X-FB-HTTP-Engine", "Liger")
                .header("X-FB-Client-IP", "True")
                .header("X-FB-Server-Cluster", "True")
                .header("Content-Length", "0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("[Instagram] Mobile API returned ${response.code}")
                    return@use SocialMediaResult.Error("Mobile API failed: ${response.code}")
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return@use SocialMediaResult.Error("Empty Mobile API response")
                }

                parseMobileApiResponse(body)
            }
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] Mobile API extraction failed")
            SocialMediaResult.Error("Mobile API failed: ${e.message}")
        }
    }

    /**
     * Get media_id from Instagram's oEmbed API.
     */
    private fun getMediaIdFromOEmbed(shortcode: String): String? {
        return try {
            // Use /p/ for oEmbed - it works for all content types including reels
            // (this is what Cobalt uses - /p/ is the generic shortcode resolver)
            val postUrl = "https://www.instagram.com/p/$shortcode/"
            val oembedUrl = "https://i.instagram.com/api/v1/oembed/?url=${java.net.URLEncoder.encode(postUrl, "UTF-8")}"

            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US")
                .header("X-IG-App-Locale", "en_US")
                .header("X-IG-Device-Locale", "en_US")
                .header("X-IG-Mapped-Locale", "en_US")
                .header("X-FB-HTTP-Engine", "Liger")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("[Instagram] oEmbed API returned ${response.code}")
                    return@use null
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return@use null
                }

                val json = JSONObject(body)
                json.optString("media_id").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] oEmbed request failed")
            null
        }
    }

    /**
     * Parse Mobile API response to extract video URL.
     */
    private fun parseMobileApiResponse(json: String): SocialMediaResult {
        return try {
            val root = JSONObject(json)
            val items = root.optJSONArray("items")
            if (items == null || items.length() == 0) {
                Timber.d("[Instagram] No items in Mobile API response")
                return SocialMediaResult.Error("No items in response")
            }

            val item = items.getJSONObject(0)

            // Check if it's a video
            val mediaType = item.optInt("media_type", 0)
            if (mediaType != 2) { // 2 = video
                Timber.d("[Instagram] Content is not a video (media_type: $mediaType)")
                return SocialMediaResult.NotSupported
            }

            // Get video versions - select highest quality
            val videoVersions = item.optJSONArray("video_versions")
            if (videoVersions == null || videoVersions.length() == 0) {
                // Try single video_url field
                val singleVideoUrl = item.optString("video_url")
                if (singleVideoUrl.isNotBlank()) {
                    Timber.d("[Instagram] Found video via Mobile API (single url): ${singleVideoUrl.take(80)}...")
                    return SocialMediaResult.Success(videoUrl = singleVideoUrl)
                }
                return SocialMediaResult.Error("No video versions in response")
            }

            // Find highest resolution video
            var bestVideoUrl: String? = null
            var bestWidth = 0
            for (i in 0 until videoVersions.length()) {
                val version = videoVersions.getJSONObject(i)
                val width = version.optInt("width", 0)
                val url = version.optString("url")
                if (width > bestWidth && url.isNotBlank()) {
                    bestWidth = width
                    bestVideoUrl = url
                }
            }

            if (bestVideoUrl == null) {
                return SocialMediaResult.Error("Could not find video URL in versions")
            }

            // Get thumbnail
            val imageVersions = item.optJSONObject("image_versions2")
            val candidates = imageVersions?.optJSONArray("candidates")
            val thumbnailUrl = candidates?.optJSONObject(0)?.optString("url")

            Timber.d("[Instagram] Found video via Mobile API: ${bestVideoUrl.take(80)}...")
            SocialMediaResult.Success(
                videoUrl = bestVideoUrl,
                thumbnailUrl = thumbnailUrl
            )
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] Failed to parse Mobile API response")
            SocialMediaResult.Error("Failed to parse Mobile API: ${e.message}")
        }
    }

    /**
     * Extract shortcode from Instagram Reels URL formats.
     * Note: We only support /reel/ and /reels/ URLs, not /p/ (posts).
     */
    private fun extractShortcode(url: String): String? {
        // Match /reel/, /reels/, or /share/reel/ followed by shortcode
        // Intentionally excludes /p/ (posts) - we only want reels
        val patterns = listOf(
            """/(?:reel|reels)/([A-Za-z0-9_-]+)""".toRegex(),
            """/share/(?:reel|video)/([A-Za-z0-9_-]+)""".toRegex()
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
     * Try extracting via Instagram's GraphQL API.
     * Uses the /api/graphql endpoint with POST method (working as of Dec 2024).
     * Based on: https://github.com/ahmedrangel/instagram-media-scraper
     */
    private fun tryExtractViaGraphQL(shortcode: String): SocialMediaResult {
        return try {
            // Working doc_id from instagram-media-scraper (Dec 2024)
            val docId = "10015901848474"
            val lsd = "AVqbxe3J_YA"  // Facebook LSD token
            val variables = """{"shortcode":"$shortcode"}"""

            // Build form-encoded body
            val formBody = okhttp3.FormBody.Builder()
                .add("variables", variables)
                .add("doc_id", docId)
                .add("lsd", lsd)
                .build()

            val request = Request.Builder()
                .url("https://www.instagram.com/api/graphql")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-IG-App-ID", "936619743392459")
                .header("X-FB-LSD", lsd)
                .header("X-ASBD-ID", "129477")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/")
                .post(formBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                Timber.d("[Instagram] GraphQL API returned ${response.code}, body length: ${body?.length ?: 0}")
                if ((body?.length ?: 0) < 500) {
                    Timber.d("[Instagram] GraphQL response: $body")
                }

                if (!response.isSuccessful) {
                    return@use SocialMediaResult.Error("GraphQL failed: ${response.code}")
                }

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
     * Try extracting video URL by scraping the Instagram page HTML.
     * Looks for video URLs in:
     * 1. Open Graph meta tags (og:video)
     * 2. Embedded JSON data in script tags
     */
    private fun tryExtractViaHtmlScrape(url: String): SocialMediaResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                Timber.d("[Instagram] HTML scrape returned ${response.code}, body length: ${body?.length ?: 0}")

                if (!response.isSuccessful || body.isNullOrBlank()) {
                    return@use SocialMediaResult.Error("HTML fetch failed: ${response.code}")
                }

                // Check for login requirement
                if (body.contains("\"require_login\":true") || body.contains("login_required")) {
                    Timber.d("[Instagram] Login required for this content")
                    return@use SocialMediaResult.Error("Login required")
                }

                // Method 1: Look for og:video meta tag
                val ogVideoPattern = """<meta\s+(?:property|name)=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
                val ogVideoMatch = ogVideoPattern.find(body)
                if (ogVideoMatch != null) {
                    val videoUrl = ogVideoMatch.groupValues[1].replace("&amp;", "&")
                    if (videoUrl.isNotBlank() && videoUrl.contains(".mp4")) {
                        Timber.d("[Instagram] Found video via og:video meta tag")

                        // Try to get thumbnail
                        val ogImagePattern = """<meta\s+(?:property|name)=["']og:image["']\s+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
                        val thumbnailUrl = ogImagePattern.find(body)?.groupValues?.get(1)?.replace("&amp;", "&")

                        return@use SocialMediaResult.Success(
                            videoUrl = videoUrl,
                            thumbnailUrl = thumbnailUrl
                        )
                    }
                }

                // Method 2: Look for video URL in embedded JSON (various patterns)
                val videoPatterns = listOf(
                    """"video_url"\s*:\s*"([^"]+)"""".toRegex(),
                    """"contentUrl"\s*:\s*"([^"]+)"""".toRegex(),
                    """"playback_url"\s*:\s*"([^"]+)"""".toRegex(),
                    """"video_versions"\s*:\s*\[\s*\{\s*[^}]*"url"\s*:\s*"([^"]+)"""".toRegex()
                )

                for (pattern in videoPatterns) {
                    val match = pattern.find(body)
                    if (match != null) {
                        val videoUrl = match.groupValues[1]
                            .replace("\\u0026", "&")
                            .replace("\\\\u0026", "&")
                            .replace("\\/", "/")
                            .replace("\\\\", "\\")
                        if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains("instagram"))) {
                            Timber.d("[Instagram] Found video via embedded JSON")
                            return@use SocialMediaResult.Success(videoUrl = videoUrl)
                        }
                    }
                }

                Timber.d("[Instagram] No video URL found in HTML")
                SocialMediaResult.Error("No video URL found in page HTML")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Instagram] HTML scrape failed")
            SocialMediaResult.Error("HTML scrape failed: ${e.message}")
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
