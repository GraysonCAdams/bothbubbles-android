package com.bothbubbles.services.socialmedia

import android.content.Context
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.SocialMediaLinkDao
import com.bothbubbles.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Download progress state for a video.
 */
data class DownloadProgress(
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val progress: Float = 0f, // 0.0 to 1.0
    val isComplete: Boolean = false,
    val error: String? = null
)

/**
 * Cached video metadata.
 */
data class CachedVideo(
    val originalUrl: String,
    val messageGuid: String,
    val chatGuid: String? = null,
    val platform: SocialMediaPlatform,
    val localPath: String,
    val senderName: String? = null,
    val senderAddress: String? = null,
    val sentTimestamp: Long = 0L,
    val cachedAt: Long = System.currentTimeMillis(),
    /** Whether this video has been viewed in the Reels feed */
    val viewedInReels: Boolean = false,
    /** Timestamp when video was last viewed in Reels feed (0 if never viewed) */
    val lastViewedAt: Long = 0L
)

/**
 * Event emitted when a video is cached or removed.
 * Used to notify observers (like ChatReelsDelegate) to refresh their state.
 */
data class VideoCacheEvent(
    val originalUrl: String,
    val chatGuid: String?,
    val eventType: CacheEventType
)

enum class CacheEventType {
    ADDED,
    REMOVED
}

/**
 * Interface for managing social media video caching.
 * Allows for dependency injection and testing.
 */
interface SocialMediaCacher {
    /** Flow of cache events for observing cache changes. */
    val cacheEvents: SharedFlow<VideoCacheEvent>
    /** Gets the local cached file path for a video URL, or null if not cached. */
    suspend fun getCachedPath(originalUrl: String): String?

    /** Gets cached video metadata if available. */
    fun getCachedMetadata(originalUrl: String): CachedVideo?

    /** Gets all cached videos ordered by most recent, with viewed status populated. */
    suspend fun getAllCachedVideos(): List<CachedVideo>

    /** Gets cached videos for a specific chat, with viewed status populated. */
    suspend fun getCachedVideosForChat(chatGuid: String): List<CachedVideo>

    /** Gets cached videos for multiple chats (merged chats), with viewed status populated. */
    suspend fun getCachedVideosForChats(chatGuids: List<String>): List<CachedVideo>

    /** Removes duplicate cache entries for the same video. Returns count removed. */
    suspend fun deduplicateCache(): Int

    /** Updates metadata for a cached video. Used to repair broken metadata. */
    fun updateCachedVideoMetadata(
        originalUrl: String,
        chatGuid: String?,
        senderName: String?,
        senderAddress: String?,
        sentTimestamp: Long
    )

    /** Gets all cached videos with empty/missing chatGuid (for repair). */
    suspend fun getCachedVideosWithEmptyChatGuid(): List<CachedVideo>

    /** Marks a video as viewed in the Reels feed. */
    fun markVideoAsViewed(originalUrl: String)

    /** Checks if a video has been viewed in the Reels feed. */
    fun isVideoViewed(originalUrl: String): Boolean

    /** Downloads and caches a video, returning progress updates. */
    suspend fun downloadAndCache(
        videoUrl: String,
        originalUrl: String,
        messageGuid: String,
        chatGuid: String? = null,
        platform: SocialMediaPlatform,
        senderName: String? = null,
        senderAddress: String? = null,
        sentTimestamp: Long = 0L
    ): StateFlow<DownloadProgress>

    /** Gets the active download progress for a URL, if a download is in progress. */
    fun getDownloadProgress(originalUrl: String): StateFlow<DownloadProgress>?

    /** Calculates total size of cached videos. */
    suspend fun getCacheSize(): Long

    /** Clears all cached videos. */
    suspend fun clearCache(): Long

    /** Removes a specific cached video. */
    suspend fun removeFromCache(originalUrl: String): Boolean

    /** Gets the last viewed timestamp for a video (0 if never viewed). */
    fun getLastViewedAt(originalUrl: String): Long

    /**
     * Updates cached video metadata (for repairing incorrect sender info).
     * @param originalUrl The original post URL
     * @param senderName The corrected sender display name
     * @param senderAddress The corrected sender address (phone/email)
     */
    fun updateVideoMetadata(originalUrl: String, senderName: String?, senderAddress: String?)

    /**
     * Cleans up stale cached videos based on view status and age.
     * - Watched videos not re-watched in 1 week are deleted
     * - Unwatched videos older than 2 weeks are deleted
     * @return Number of videos deleted
     */
    suspend fun cleanupStaleVideos(): Int
}

/**
 * Manages caching of downloaded social media videos.
 * Videos are stored in the app's cache directory under "social_media_videos".
 */
@Singleton
class SocialMediaCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val messageDao: MessageDao,
    private val socialMediaLinkDao: SocialMediaLinkDao
) : SocialMediaCacher {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Cache directory for social media videos
    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
    }

    // In-memory cache of video metadata keyed by original URL hash
    private val videoMetadataCache = ConcurrentHashMap<String, CachedVideo>()

    // Active download progress streams
    private val downloadProgressMap = ConcurrentHashMap<String, MutableStateFlow<DownloadProgress>>()

    // SharedFlow for cache change events
    private val _cacheEvents = MutableSharedFlow<VideoCacheEvent>(extraBufferCapacity = 10)
    override val cacheEvents: SharedFlow<VideoCacheEvent> = _cacheEvents.asSharedFlow()

    // SharedPreferences for persisting viewed status
    private val viewedPrefs by lazy {
        context.getSharedPreferences(VIEWED_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // SharedPreferences for persisting video metadata
    private val metadataPrefs by lazy {
        context.getSharedPreferences(METADATA_PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val CACHE_DIR_NAME = "social_media_videos"
        private const val VIDEO_EXTENSION = ".mp4"
        private const val MAX_CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB
        private const val VIEWED_PREFS_NAME = "social_media_viewed"
        private const val METADATA_PREFS_NAME = "social_media_metadata"
        private const val LAST_VIEWED_PREFIX = "last_viewed_"
        // Cleanup thresholds
        private const val WATCHED_STALE_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000 // 1 week
        private const val UNWATCHED_STALE_THRESHOLD_MS = 14L * 24 * 60 * 60 * 1000 // 2 weeks
    }

    init {
        // Load persisted metadata on initialization
        loadPersistedMetadata()
    }

    /**
     * Load video metadata from SharedPreferences into memory cache.
     */
    private fun loadPersistedMetadata() {
        try {
            metadataPrefs.all.forEach { (hash, json) ->
                if (json is String) {
                    parseMetadataJson(hash, json)?.let { video ->
                        // Only add if the video file still exists
                        val file = File(cacheDir, "$hash$VIDEO_EXTENSION")
                        if (file.exists() && file.length() > 0) {
                            videoMetadataCache[hash] = video
                        }
                    }
                }
            }
            Timber.d("[SocialMediaCache] Loaded ${videoMetadataCache.size} cached video metadata entries")
        } catch (e: Exception) {
            Timber.e(e, "[SocialMediaCache] Failed to load persisted metadata")
        }
    }

    /**
     * Recovers metadata for orphaned cached videos by matching them to messages in the database.
     * This is needed when videos were cached before metadata persistence was added.
     * Should be called once at startup or when Reels feed is first accessed.
     *
     * @return Number of videos recovered
     */
    suspend fun recoverOrphanedMetadata(): Int = withContext(ioDispatcher) {
        try {
            // Get all cached video files
            val cachedFiles = cacheDir.listFiles()
                ?.filter { it.isFile && it.extension == "mp4" }
                ?.associate { it.nameWithoutExtension to it.absolutePath }
                ?: return@withContext 0

            // Find orphaned files (files without metadata)
            val orphanedHashes = cachedFiles.keys.filter { hash ->
                !videoMetadataCache.containsKey(hash)
            }

            if (orphanedHashes.isEmpty()) {
                Timber.d("[SocialMediaCache] No orphaned cache files found")
                return@withContext 0
            }

            Timber.d("[SocialMediaCache] Found ${orphanedHashes.size} orphaned cache files, attempting recovery")

            // Query messages with social media URLs
            val messagesWithUrls = messageDao.getMessagesWithSocialMediaUrls()
            Timber.d("[SocialMediaCache] Found ${messagesWithUrls.size} messages with social media URLs")

            var recoveredCount = 0

            // Extract URLs from messages and try to match with orphaned files
            for (message in messagesWithUrls) {
                val text = message.text ?: continue
                val urls = extractSocialMediaUrls(text)

                for (url in urls) {
                    val hash = hashUrl(url)
                    if (hash in orphanedHashes && cachedFiles.containsKey(hash)) {
                        // Found a match! Recover metadata
                        val platform = detectPlatform(url) ?: SocialMediaPlatform.INSTAGRAM
                        val cachedVideo = CachedVideo(
                            originalUrl = url,
                            messageGuid = message.guid,
                            chatGuid = message.chatGuid,
                            platform = platform,
                            localPath = cachedFiles[hash]!!,
                            senderName = null, // Not available from message
                            senderAddress = message.senderAddress,
                            sentTimestamp = message.dateCreated,
                            cachedAt = System.currentTimeMillis()
                        )
                        videoMetadataCache[hash] = cachedVideo
                        persistMetadata(hash, cachedVideo)
                        recoveredCount++
                        Timber.d("[SocialMediaCache] Recovered metadata for: ${url.take(50)}... -> chat=${message.chatGuid}")
                    }
                }
            }

            Timber.i("[SocialMediaCache] Recovered $recoveredCount orphaned video metadata entries")
            recoveredCount
        } catch (e: Exception) {
            Timber.e(e, "[SocialMediaCache] Failed to recover orphaned metadata")
            0
        }
    }

    /**
     * Extract social media URLs from text.
     * Note: For Instagram, we only extract /reel/ and /reels/ URLs, not /p/ (posts).
     */
    private fun extractSocialMediaUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        // Instagram patterns - reels only, not posts (/p/)
        val instagramPattern = Regex("""https?://(?:www\.)?instagram\.com/(?:reel|reels)/[A-Za-z0-9_-]+[^\s]*""")
        urls.addAll(instagramPattern.findAll(text).map { it.value })

        // TikTok patterns
        val tiktokPattern = Regex("""https?://(?:www\.|vm\.)?tiktok\.com/[^\s]+""")
        urls.addAll(tiktokPattern.findAll(text).map { it.value })

        return urls
    }

    /**
     * Detect platform from URL.
     */
    private fun detectPlatform(url: String): SocialMediaPlatform? {
        return when {
            url.contains("instagram.com") -> SocialMediaPlatform.INSTAGRAM
            url.contains("tiktok.com") -> SocialMediaPlatform.TIKTOK
            else -> null
        }
    }

    /**
     * Save video metadata to SharedPreferences.
     */
    private fun persistMetadata(hash: String, video: CachedVideo) {
        try {
            val json = buildMetadataJson(video)
            metadataPrefs.edit().putString(hash, json).apply()
        } catch (e: Exception) {
            Timber.e(e, "[SocialMediaCache] Failed to persist metadata for $hash")
        }
    }

    /**
     * Remove video metadata from SharedPreferences.
     */
    private fun removePersistedMetadata(hash: String) {
        metadataPrefs.edit().remove(hash).apply()
    }

    private fun buildMetadataJson(video: CachedVideo): String {
        return org.json.JSONObject().apply {
            put("originalUrl", video.originalUrl)
            put("messageGuid", video.messageGuid)
            put("chatGuid", video.chatGuid ?: "")
            put("platform", video.platform.name)
            put("localPath", video.localPath)
            put("senderName", video.senderName ?: "")
            put("senderAddress", video.senderAddress ?: "")
            put("sentTimestamp", video.sentTimestamp)
            put("cachedAt", video.cachedAt)
        }.toString()
    }

    private fun parseMetadataJson(hash: String, json: String): CachedVideo? {
        return try {
            val obj = org.json.JSONObject(json)
            CachedVideo(
                originalUrl = obj.getString("originalUrl"),
                messageGuid = obj.getString("messageGuid"),
                chatGuid = obj.optString("chatGuid").takeIf { it.isNotBlank() },
                platform = SocialMediaPlatform.valueOf(obj.getString("platform")),
                localPath = obj.getString("localPath"),
                senderName = obj.optString("senderName").takeIf { it.isNotBlank() },
                senderAddress = obj.optString("senderAddress").takeIf { it.isNotBlank() },
                sentTimestamp = obj.optLong("sentTimestamp", 0L),
                cachedAt = obj.optLong("cachedAt", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Timber.w(e, "[SocialMediaCache] Failed to parse metadata JSON for $hash")
            null
        }
    }

    /**
     * Gets the local cached file path for a video URL, or null if not cached.
     */
    override suspend fun getCachedPath(originalUrl: String): String? = withContext(ioDispatcher) {
        val hash = hashUrl(originalUrl)
        val file = File(cacheDir, "$hash$VIDEO_EXTENSION")
        if (file.exists() && file.length() > 0) {
            file.absolutePath
        } else {
            null
        }
    }

    /**
     * Gets cached video metadata if available.
     */
    override fun getCachedMetadata(originalUrl: String): CachedVideo? {
        val hash = hashUrl(originalUrl)
        return videoMetadataCache[hash]
    }

    /**
     * Gets all cached videos ordered by most recent, with viewed status and last viewed timestamp populated.
     */
    override suspend fun getAllCachedVideos(): List<CachedVideo> = withContext(ioDispatcher) {
        videoMetadataCache.values
            .sortedByDescending { it.cachedAt }
            .filter { File(it.localPath).exists() }
            .map { video ->
                video.copy(
                    viewedInReels = isVideoViewed(video.originalUrl),
                    lastViewedAt = getLastViewedAt(video.originalUrl)
                )
            }
    }

    /**
     * Gets cached videos for a specific chat, with viewed status populated.
     */
    override suspend fun getCachedVideosForChat(chatGuid: String): List<CachedVideo> = withContext(ioDispatcher) {
        getAllCachedVideos().filter { it.chatGuid == chatGuid }
    }

    /**
     * Gets cached videos for multiple chats (merged chats), with viewed status populated.
     */
    override suspend fun getCachedVideosForChats(chatGuids: List<String>): List<CachedVideo> = withContext(ioDispatcher) {
        val guidSet = chatGuids.toSet()
        getAllCachedVideos().filter { it.chatGuid in guidSet }
    }

    /**
     * Gets cached videos with empty/missing chatGuid (for metadata repair).
     */
    override suspend fun getCachedVideosWithEmptyChatGuid(): List<CachedVideo> = withContext(ioDispatcher) {
        getAllCachedVideos().filter { it.chatGuid.isNullOrEmpty() }
    }

    /**
     * Updates metadata for a cached video. Used to repair broken metadata.
     */
    override fun updateCachedVideoMetadata(
        originalUrl: String,
        chatGuid: String?,
        senderName: String?,
        senderAddress: String?,
        sentTimestamp: Long
    ) {
        val hash = hashUrl(originalUrl)
        val existing = videoMetadataCache[hash] ?: return

        val updated = existing.copy(
            chatGuid = chatGuid ?: existing.chatGuid,
            senderName = senderName ?: existing.senderName,
            senderAddress = senderAddress ?: existing.senderAddress,
            sentTimestamp = if (sentTimestamp > 0) sentTimestamp else existing.sentTimestamp
        )

        videoMetadataCache[hash] = updated
        persistMetadata(hash, updated)
        Timber.d("[SocialMediaCache] Updated metadata for ${originalUrl}: chatGuid=$chatGuid, sender=$senderName")
    }

    /**
     * Marks a video as viewed in the Reels feed and updates last viewed timestamp.
     */
    override fun markVideoAsViewed(originalUrl: String) {
        val hash = hashUrl(originalUrl)
        val now = System.currentTimeMillis()
        viewedPrefs.edit()
            .putBoolean(hash, true)
            .putLong(LAST_VIEWED_PREFIX + hash, now)
            .apply()
        // Update in-memory cache
        videoMetadataCache[hash]?.let { video ->
            videoMetadataCache[hash] = video.copy(viewedInReels = true, lastViewedAt = now)
        }
    }

    /**
     * Checks if a video has been viewed in the Reels feed.
     */
    override fun isVideoViewed(originalUrl: String): Boolean {
        val hash = hashUrl(originalUrl)
        return viewedPrefs.getBoolean(hash, false)
    }

    /**
     * Gets the last viewed timestamp for a video (0 if never viewed).
     */
    override fun getLastViewedAt(originalUrl: String): Long {
        val hash = hashUrl(originalUrl)
        return viewedPrefs.getLong(LAST_VIEWED_PREFIX + hash, 0L)
    }

    /**
     * Clears all viewed status (for testing or reset).
     */
    fun clearViewedStatus() {
        viewedPrefs.edit().clear().apply()
    }

    /**
     * Updates cached video metadata (for repairing incorrect sender info).
     *
     * This is used by SocialMediaLinkMigrationHelper to fix videos that were
     * incorrectly attributed to "You" due to tapback messages.
     */
    override fun updateVideoMetadata(originalUrl: String, senderName: String?, senderAddress: String?) {
        val hash = hashUrl(originalUrl)
        val existingVideo = videoMetadataCache[hash] ?: return

        val updatedVideo = existingVideo.copy(
            senderName = senderName,
            senderAddress = senderAddress
        )

        // Update in-memory cache
        videoMetadataCache[hash] = updatedVideo

        // Persist to SharedPreferences
        persistMetadata(hash, updatedVideo)

        Timber.d("[SocialMediaCache] Updated metadata for $hash: senderName='$senderName'")
    }

    /**
     * Downloads and caches a video, returning the local path.
     * Provides progress updates via the returned StateFlow.
     *
     * @param videoUrl The direct video URL to download
     * @param originalUrl The original social media URL (used as cache key)
     * @param messageGuid The message GUID this video belongs to
     * @param chatGuid The chat GUID this video belongs to (for filtering by chat)
     * @param platform The social media platform
     * @param metadata Optional metadata about the sender
     */
    override suspend fun downloadAndCache(
        videoUrl: String,
        originalUrl: String,
        messageGuid: String,
        chatGuid: String?,
        platform: SocialMediaPlatform,
        senderName: String?,
        senderAddress: String?,
        sentTimestamp: Long
    ): StateFlow<DownloadProgress> {
        val hash = hashUrl(originalUrl)
        val progressFlow = MutableStateFlow(DownloadProgress())
        downloadProgressMap[hash] = progressFlow

        // Check if already cached
        val existingPath = getCachedPath(originalUrl)
        if (existingPath != null) {
            progressFlow.value = DownloadProgress(
                progress = 1f,
                isComplete = true
            )
            return progressFlow.asStateFlow()
        }

        // Start download in background
        withContext(ioDispatcher) {
            try {
                val targetFile = File(cacheDir, "$hash$VIDEO_EXTENSION")
                val tempFile = File(cacheDir, "$hash.tmp")

                val request = Request.Builder()
                    .url(videoUrl)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        progressFlow.value = DownloadProgress(
                            error = "HTTP ${response.code}: ${response.message}"
                        )
                        return@withContext
                    }

                    val body = response.body ?: run {
                        progressFlow.value = DownloadProgress(error = "Empty response body")
                        return@withContext
                    }

                    val totalBytes = body.contentLength()
                    var bytesDownloaded = 0L

                    FileOutputStream(tempFile).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            var read: Int

                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesDownloaded += read

                                val progress = if (totalBytes > 0) {
                                    bytesDownloaded.toFloat() / totalBytes.toFloat()
                                } else {
                                    0f
                                }

                                progressFlow.value = DownloadProgress(
                                    bytesDownloaded = bytesDownloaded,
                                    totalBytes = totalBytes,
                                    progress = progress.coerceIn(0f, 1f)
                                )
                            }
                        }
                    }

                    // Rename temp file to final
                    tempFile.renameTo(targetFile)

                    // Store metadata
                    val cachedVideo = CachedVideo(
                        originalUrl = originalUrl,
                        messageGuid = messageGuid,
                        chatGuid = chatGuid,
                        platform = platform,
                        localPath = targetFile.absolutePath,
                        senderName = senderName,
                        senderAddress = senderAddress,
                        sentTimestamp = sentTimestamp
                    )
                    videoMetadataCache[hash] = cachedVideo
                    persistMetadata(hash, cachedVideo)

                    // Mark the link as downloaded in the social_media_links table
                    try {
                        socialMediaLinkDao.markAsDownloaded(hash)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to mark link as downloaded: $originalUrl")
                    }

                    progressFlow.value = DownloadProgress(
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        progress = 1f,
                        isComplete = true
                    )

                    // Notify observers that a new video was cached
                    _cacheEvents.tryEmit(
                        VideoCacheEvent(
                            originalUrl = originalUrl,
                            chatGuid = chatGuid,
                            eventType = CacheEventType.ADDED
                        )
                    )

                    // Clean up old cache if needed
                    cleanupCacheIfNeeded()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to download video: $videoUrl")
                progressFlow.value = DownloadProgress(error = e.message ?: "Download failed")
            } finally {
                downloadProgressMap.remove(hash)
            }
        }

        return progressFlow.asStateFlow()
    }

    /**
     * Gets the active download progress for a URL, if a download is in progress.
     */
    override fun getDownloadProgress(originalUrl: String): StateFlow<DownloadProgress>? {
        val hash = hashUrl(originalUrl)
        return downloadProgressMap[hash]?.asStateFlow()
    }

    /**
     * Calculates total size of cached videos.
     */
    override suspend fun getCacheSize(): Long = withContext(ioDispatcher) {
        cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Clears all cached videos.
     */
    override suspend fun clearCache(): Long = withContext(ioDispatcher) {
        val freedBytes = getCacheSize()

        // Get all URL hashes before clearing
        val allHashes = videoMetadataCache.keys.toList()

        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        videoMetadataCache.clear()
        metadataPrefs.edit().clear().apply()
        viewedPrefs.edit().clear().apply()

        // Mark all links as not downloaded so they show as pending again
        if (allHashes.isNotEmpty()) {
            try {
                for (hash in allHashes) {
                    socialMediaLinkDao.markAsNotDownloaded(hash)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to mark links as not downloaded after cache clear")
            }
        }

        freedBytes
    }

    /**
     * Removes a specific cached video.
     */
    override suspend fun removeFromCache(originalUrl: String): Boolean = withContext(ioDispatcher) {
        val hash = hashUrl(originalUrl)
        val file = File(cacheDir, "$hash$VIDEO_EXTENSION")

        // Get chatGuid before removing metadata (for event emission)
        val chatGuid = videoMetadataCache[hash]?.chatGuid

        videoMetadataCache.remove(hash)
        removePersistedMetadata(hash)
        // Clean up viewed status prefs
        viewedPrefs.edit()
            .remove(hash)
            .remove(LAST_VIEWED_PREFIX + hash)
            .apply()

        // Mark link as not downloaded so it shows as pending again
        try {
            socialMediaLinkDao.markAsNotDownloaded(hash)
        } catch (e: Exception) {
            Timber.w(e, "Failed to mark link as not downloaded: $originalUrl")
        }

        val deleted = file.delete()

        // Notify observers that a video was removed
        if (deleted) {
            _cacheEvents.tryEmit(
                VideoCacheEvent(
                    originalUrl = originalUrl,
                    chatGuid = chatGuid,
                    eventType = CacheEventType.REMOVED
                )
            )
        }

        deleted
    }

    /**
     * Cleans up stale cached videos based on view status and age.
     * - Watched videos not re-watched in 1 week are deleted
     * - Unwatched videos older than 2 weeks are deleted
     * @return Number of videos deleted
     */
    override suspend fun cleanupStaleVideos(): Int = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val allVideos = getAllCachedVideos()
        var deletedCount = 0

        for (video in allVideos) {
            val shouldDelete = if (video.viewedInReels) {
                // Watched videos: delete if not re-watched in 1 week
                val lastViewed = video.lastViewedAt
                lastViewed > 0 && (now - lastViewed) > WATCHED_STALE_THRESHOLD_MS
            } else {
                // Unwatched videos: delete if cached more than 2 weeks ago
                (now - video.cachedAt) > UNWATCHED_STALE_THRESHOLD_MS
            }

            if (shouldDelete) {
                val hash = hashUrl(video.originalUrl)
                val file = File(video.localPath)
                if (file.delete()) {
                    videoMetadataCache.remove(hash)
                    removePersistedMetadata(hash)
                    viewedPrefs.edit()
                        .remove(hash)
                        .remove(LAST_VIEWED_PREFIX + hash)
                        .apply()

                    // Mark link as not downloaded so it can be re-cached if needed
                    try {
                        socialMediaLinkDao.markAsNotDownloaded(hash)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to mark link as not downloaded during cleanup")
                    }

                    deletedCount++
                    Timber.d("Cleaned up stale video: ${video.originalUrl.take(50)}...")
                }
            }
        }

        if (deletedCount > 0) {
            Timber.i("Cleaned up $deletedCount stale cached videos")
        }
        deletedCount
    }

    private fun cleanupCacheIfNeeded() {
        try {
            // First, clean up stale videos (watched not re-watched in 1 week, unwatched after 2 weeks)
            cleanupStaleVideosSync()

            var totalSize = cacheDir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }

            if (totalSize <= MAX_CACHE_SIZE_BYTES) return

            // Get files sorted by last modified (oldest first)
            val files = cacheDir.listFiles()
                ?.filter { it.isFile && it.extension == "mp4" }
                ?.sortedBy { it.lastModified() }
                ?: return

            for (file in files) {
                if (totalSize <= MAX_CACHE_SIZE_BYTES * 0.8) break // Keep 20% buffer

                val size = file.length()
                val hash = file.nameWithoutExtension
                if (file.delete()) {
                    totalSize -= size
                    videoMetadataCache.remove(hash)
                    removePersistedMetadata(hash)
                    viewedPrefs.edit()
                        .remove(hash)
                        .remove(LAST_VIEWED_PREFIX + hash)
                        .apply()
                    Timber.d("Cleaned up cached video: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during cache cleanup")
        }
    }

    /**
     * Synchronous version of stale cleanup for internal use.
     */
    private fun cleanupStaleVideosSync() {
        val now = System.currentTimeMillis()
        var deletedCount = 0

        for ((hash, video) in videoMetadataCache.entries.toList()) {
            val isViewed = viewedPrefs.getBoolean(hash, false)
            val lastViewedAt = viewedPrefs.getLong(LAST_VIEWED_PREFIX + hash, 0L)

            val shouldDelete = if (isViewed) {
                // Watched videos: delete if not re-watched in 1 week
                lastViewedAt > 0 && (now - lastViewedAt) > WATCHED_STALE_THRESHOLD_MS
            } else {
                // Unwatched videos: delete if cached more than 2 weeks ago
                (now - video.cachedAt) > UNWATCHED_STALE_THRESHOLD_MS
            }

            if (shouldDelete) {
                val file = File(video.localPath)
                if (file.exists() && file.delete()) {
                    videoMetadataCache.remove(hash)
                    removePersistedMetadata(hash)
                    viewedPrefs.edit()
                        .remove(hash)
                        .remove(LAST_VIEWED_PREFIX + hash)
                        .apply()
                    deletedCount++
                }
            }
        }

        if (deletedCount > 0) {
            Timber.i("Cleaned up $deletedCount stale cached videos during cache maintenance")
        }
    }

    private fun hashUrl(url: String): String {
        // Normalize URL before hashing to prevent duplicates from query param variations
        val normalizedUrl = normalizeUrl(url)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(normalizedUrl.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Normalize URL by stripping query parameters and cleaning up trailing junk.
     * This ensures the same video always gets the same hash regardless of tracking params.
     *
     * Example:
     * - https://www.instagram.com/reel/DSleht5E4HE/?igsh=MTZ0ejBzdjlrNjM3Mg==
     * - https://www.instagram.com/reel/DSleht5E4HE/?igsh=abc123
     * Both normalize to: https://www.instagram.com/reel/DSleht5E4HE
     */
    private fun normalizeUrl(url: String): String {
        return try {
            val cleanUrl = url.replace("\"", "").replace("'", "")
            val uri = android.net.Uri.parse(cleanUrl)
            val pathOnly = uri.path?.trimEnd('/') ?: ""
            "${uri.scheme}://${uri.host}$pathOnly"
        } catch (e: Exception) {
            // Fallback: strip query params
            url.substringBefore("?").trimEnd('/')
        }
    }

    /**
     * Remove duplicate cache entries for the same video.
     * Keeps the entry with the most complete sender info.
     * Returns the number of duplicates removed.
     */
    override suspend fun deduplicateCache(): Int = withContext(ioDispatcher) {
        try {
            val allVideos = videoMetadataCache.values.toList()
            val videosByNormalizedUrl = allVideos.groupBy { normalizeUrl(it.originalUrl) }

            var removedCount = 0
            for ((normalizedUrl, videos) in videosByNormalizedUrl) {
                if (videos.size > 1) {
                    // Keep the one with the best metadata (has sender name and address)
                    val sorted = videos.sortedWith(compareByDescending<CachedVideo> {
                        !it.senderName.isNullOrBlank() && !it.senderAddress.isNullOrBlank()
                    }.thenByDescending {
                        !it.senderName.isNullOrBlank()
                    }.thenByDescending {
                        it.cachedAt
                    })

                    val keep = sorted.first()
                    val remove = sorted.drop(1)

                    for (video in remove) {
                        val oldHash = videoMetadataCache.entries.find { it.value == video }?.key
                        if (oldHash != null) {
                            videoMetadataCache.remove(oldHash)
                            removePersistedMetadata(oldHash)
                            removedCount++
                            Timber.d("[SocialMediaCache] Removed duplicate for ${normalizedUrl.takeLast(30)}")
                        }
                    }
                }
            }

            Timber.i("[SocialMediaCache] Deduplicated cache: removed $removedCount duplicates")
            removedCount
        } catch (e: Exception) {
            Timber.e(e, "[SocialMediaCache] Failed to deduplicate cache")
            0
        }
    }
}
