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
            Regex("""(?:https?://)?(?:www\.)?instagram\.com/(?:p|reel|reels|tv)/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:https?://)?(?:www\.)?instagr\.am/(?:p|reel)/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)
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
     * Extracts video URL from Instagram using a scraping approach.
     *
     * Instagram doesn't have a simple free API, so we use the same approach
     * as open source tools like snapinsta - fetching the page and extracting
     * video URLs from the HTML/JSON data.
     */
    private fun extractInstagramVideo(url: String): SocialMediaResult {
        // First, try to get the page with a mobile user agent
        val mobileUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", mobileUserAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SocialMediaResult.Error("HTTP ${response.code}: ${response.message}")
            }

            val html = response.body?.string()
            if (html.isNullOrBlank()) {
                return SocialMediaResult.Error("Empty response from Instagram")
            }

            return parseInstagramHtml(html)
        }
    }

    private fun parseInstagramHtml(html: String): SocialMediaResult {
        // Try to find video URL in various possible locations in the HTML

        // Method 1: Look for og:video meta tag
        val ogVideoRegex = """<meta\s+(?:property|name)=["']og:video["']\s+content=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        ogVideoRegex.find(html)?.groupValues?.get(1)?.let { videoUrl ->
            val decodedUrl = decodeHtmlEntities(videoUrl)
            if (decodedUrl.isNotBlank() && (decodedUrl.contains(".mp4") || decodedUrl.contains("video"))) {
                Timber.d("Found Instagram video via og:video: $decodedUrl")
                return SocialMediaResult.Success(videoUrl = decodedUrl)
            }
        }

        // Method 2: Look for video_url in JSON data embedded in the page
        val videoUrlRegex = """"video_url"\s*:\s*"([^"]+)"""".toRegex()
        videoUrlRegex.find(html)?.groupValues?.get(1)?.let { videoUrl ->
            val decodedUrl = videoUrl.replace("\\/", "/").replace("\\u0026", "&")
            if (decodedUrl.isNotBlank()) {
                Timber.d("Found Instagram video via video_url JSON: $decodedUrl")
                return SocialMediaResult.Success(videoUrl = decodedUrl)
            }
        }

        // Method 3: Look for contentUrl in JSON-LD
        val contentUrlRegex = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex()
        contentUrlRegex.find(html)?.groupValues?.get(1)?.let { videoUrl ->
            val decodedUrl = videoUrl.replace("\\/", "/").replace("\\u0026", "&")
            if (decodedUrl.isNotBlank()) {
                Timber.d("Found Instagram video via contentUrl: $decodedUrl")
                return SocialMediaResult.Success(videoUrl = decodedUrl)
            }
        }

        // Method 4: Look for video source in HTML5 video tags
        val videoSrcRegex = """<video[^>]*\s+src=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        videoSrcRegex.find(html)?.groupValues?.get(1)?.let { videoUrl ->
            val decodedUrl = decodeHtmlEntities(videoUrl)
            if (decodedUrl.isNotBlank()) {
                Timber.d("Found Instagram video via video src: $decodedUrl")
                return SocialMediaResult.Success(videoUrl = decodedUrl)
            }
        }

        // Check if this is actually a photo post, not a video
        val isPhotoPost = html.contains("\"is_video\":false") ||
                         (html.contains("og:image") && !html.contains("og:video"))

        if (isPhotoPost) {
            return SocialMediaResult.NotSupported
        }

        // If we get here, extraction failed - Instagram may require login or have blocked the request
        Timber.w("Could not extract Instagram video URL - may require authentication")
        return SocialMediaResult.Error("Could not extract video. Instagram may require login for this content.")
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
    }
}
