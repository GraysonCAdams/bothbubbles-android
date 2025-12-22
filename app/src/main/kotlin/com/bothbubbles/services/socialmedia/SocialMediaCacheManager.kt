package com.bothbubbles.services.socialmedia

import android.content.Context
import com.bothbubbles.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val viewedInReels: Boolean = false
)

/**
 * Interface for managing social media video caching.
 * Allows for dependency injection and testing.
 */
interface SocialMediaCacher {
    /** Gets the local cached file path for a video URL, or null if not cached. */
    suspend fun getCachedPath(originalUrl: String): String?

    /** Gets cached video metadata if available. */
    fun getCachedMetadata(originalUrl: String): CachedVideo?

    /** Gets all cached videos ordered by most recent, with viewed status populated. */
    suspend fun getAllCachedVideos(): List<CachedVideo>

    /** Gets cached videos for a specific chat, with viewed status populated. */
    suspend fun getCachedVideosForChat(chatGuid: String): List<CachedVideo>

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
}

/**
 * Manages caching of downloaded social media videos.
 * Videos are stored in the app's cache directory under "social_media_videos".
 */
@Singleton
class SocialMediaCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
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

    // SharedPreferences for persisting viewed status
    private val viewedPrefs by lazy {
        context.getSharedPreferences(VIEWED_PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val CACHE_DIR_NAME = "social_media_videos"
        private const val VIDEO_EXTENSION = ".mp4"
        private const val MAX_CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB
        private const val VIEWED_PREFS_NAME = "social_media_viewed"
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
     * Gets all cached videos ordered by most recent, with viewed status populated.
     */
    override suspend fun getAllCachedVideos(): List<CachedVideo> = withContext(ioDispatcher) {
        videoMetadataCache.values
            .sortedByDescending { it.cachedAt }
            .filter { File(it.localPath).exists() }
            .map { video ->
                video.copy(viewedInReels = isVideoViewed(video.originalUrl))
            }
    }

    /**
     * Gets cached videos for a specific chat, with viewed status populated.
     */
    override suspend fun getCachedVideosForChat(chatGuid: String): List<CachedVideo> = withContext(ioDispatcher) {
        getAllCachedVideos().filter { it.chatGuid == chatGuid }
    }

    /**
     * Marks a video as viewed in the Reels feed.
     */
    override fun markVideoAsViewed(originalUrl: String) {
        val hash = hashUrl(originalUrl)
        viewedPrefs.edit().putBoolean(hash, true).apply()
        // Update in-memory cache
        videoMetadataCache[hash]?.let { video ->
            videoMetadataCache[hash] = video.copy(viewedInReels = true)
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
     * Clears all viewed status (for testing or reset).
     */
    fun clearViewedStatus() {
        viewedPrefs.edit().clear().apply()
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

                    progressFlow.value = DownloadProgress(
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        progress = 1f,
                        isComplete = true
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
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        videoMetadataCache.clear()
        freedBytes
    }

    /**
     * Removes a specific cached video.
     */
    override suspend fun removeFromCache(originalUrl: String): Boolean = withContext(ioDispatcher) {
        val hash = hashUrl(originalUrl)
        val file = File(cacheDir, "$hash$VIDEO_EXTENSION")
        videoMetadataCache.remove(hash)
        file.delete()
    }

    private fun cleanupCacheIfNeeded() {
        try {
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
                    Timber.d("Cleaned up cached video: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during cache cleanup")
        }
    }

    private fun hashUrl(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
