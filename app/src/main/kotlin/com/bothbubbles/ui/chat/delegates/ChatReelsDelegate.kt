package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.services.socialmedia.CachedVideo
import com.bothbubbles.services.socialmedia.DownloadProgress
import com.bothbubbles.services.socialmedia.SocialMediaCacheManager
import com.bothbubbles.services.socialmedia.SocialMediaDownloadService
import com.bothbubbles.services.socialmedia.SocialMediaPlatform
import com.bothbubbles.services.socialmedia.SocialMediaResult
import com.bothbubbles.ui.components.reels.ReelItem
import com.bothbubbles.ui.components.reels.ReelsTapback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Delegate for managing Reels feed state in ChatScreen.
 * Handles loading cached videos, checking settings, managing tapbacks,
 * and orchestrating video downloads.
 *
 * Sorting order for Reels feed:
 * 1. Unread videos first, oldest to newest (chronological catchup)
 * 2. Read videos after, newest to oldest (most relevant rewatches first)
 */
class ChatReelsDelegate @Inject constructor(
    private val downloadService: SocialMediaDownloadService,
    private val cacheManager: SocialMediaCacheManager
) {
    private lateinit var scope: CoroutineScope
    private var chatGuid: String = ""

    private val _state = MutableStateFlow(ReelsState())
    val state: StateFlow<ReelsState> = _state.asStateFlow()

    // Track active downloads
    private val activeDownloads = mutableMapOf<String, StateFlow<DownloadProgress>>()

    /**
     * Initialize the delegate with chat context.
     */
    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid
        this.scope = scope
        loadReelsState()
    }

    /**
     * Loads the reels state including settings and cached videos for this chat.
     * Videos are sorted: unread (oldest→newest) then read (newest→oldest).
     */
    private fun loadReelsState() {
        scope.launch {
            val isEnabled = downloadService.isReelsFeedEnabled()
            val cachedVideos = cacheManager.getCachedVideosForChat(chatGuid)

            _state.value = ReelsState(
                isEnabled = isEnabled,
                cachedVideos = cachedVideos,
                reelItems = sortAndMapVideos(cachedVideos)
            )
        }
    }

    /**
     * Sorts videos and maps to ReelItems.
     * Order: unread (oldest→newest) then read (newest→oldest).
     */
    private fun sortAndMapVideos(videos: List<CachedVideo>): List<ReelItem> {
        val (unread, read) = videos.partition { !it.viewedInReels }

        // Unread: oldest first (ascending by timestamp)
        val sortedUnread = unread.sortedBy { it.sentTimestamp }
        // Read: newest first (descending by timestamp)
        val sortedRead = read.sortedByDescending { it.sentTimestamp }

        val combined = sortedUnread + sortedRead

        return combined.map { video ->
            // Preserve existing tapback if we have one
            val existingTapback = _state.value.reelItems
                .find { it.originalUrl == video.originalUrl }
                ?.currentTapback

            ReelItem.fromCached(video, existingTapback)
        }
    }

    /**
     * Refreshes the cached videos list and re-sorts.
     */
    fun refreshCachedVideos() {
        scope.launch {
            val cachedVideos = cacheManager.getCachedVideosForChat(chatGuid)
            _state.value = _state.value.copy(
                cachedVideos = cachedVideos,
                reelItems = sortAndMapVideos(cachedVideos)
            )
        }
    }

    /**
     * Marks a video as viewed in the Reels feed.
     * Called when user spends time on a video.
     */
    fun markVideoAsViewed(originalUrl: String) {
        cacheManager.markVideoAsViewed(originalUrl)
        // Refresh to update sort order
        refreshCachedVideos()
    }

    /**
     * Starts downloading a pending video.
     * Updates the ReelItem with download progress.
     */
    fun startDownload(originalUrl: String) {
        // Check if already downloading
        if (activeDownloads.containsKey(originalUrl)) return

        scope.launch {
            try {
                // Find the reel item
                val reelIndex = _state.value.reelItems.indexOfFirst { it.originalUrl == originalUrl }
                if (reelIndex < 0) return@launch

                val reel = _state.value.reelItems[reelIndex]
                if (reel.isCached) return@launch // Already cached

                // Detect platform
                val platform = downloadService.detectPlatform(originalUrl) ?: return@launch

                // Check download permission
                val permission = downloadService.canDownload()
                if (permission is com.bothbubbles.services.socialmedia.DownloadPermission.Blocked) {
                    updateReelDownloadError(reelIndex, permission.reason)
                    return@launch
                }

                // Mark as downloading
                updateReelDownloading(reelIndex, true)

                // Extract video URL
                val result = downloadService.extractVideoUrl(originalUrl, platform)
                if (result !is SocialMediaResult.Success) {
                    val error = (result as? SocialMediaResult.Error)?.message ?: "Failed to extract video"
                    updateReelDownloadError(reelIndex, error)
                    return@launch
                }

                // Start download
                val progressFlow = downloadService.downloadAndCacheVideo(
                    result = result,
                    originalUrl = originalUrl,
                    messageGuid = reel.messageGuid,
                    chatGuid = chatGuid,
                    platform = platform,
                    senderName = reel.senderName,
                    senderAddress = reel.senderAddress,
                    sentTimestamp = reel.sentTimestamp
                )

                activeDownloads[originalUrl] = progressFlow
                updateReelDownloadProgress(reelIndex, progressFlow)

                // Wait for completion
                progressFlow.collect { progress ->
                    if (progress.isComplete) {
                        activeDownloads.remove(originalUrl)
                        // Refresh to get the cached video
                        refreshCachedVideos()
                    } else if (progress.error != null) {
                        activeDownloads.remove(originalUrl)
                        updateReelDownloadError(reelIndex, progress.error)
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "Failed to download video: $originalUrl")
                val reelIndex = _state.value.reelItems.indexOfFirst { it.originalUrl == originalUrl }
                if (reelIndex >= 0) {
                    updateReelDownloadError(reelIndex, e.message ?: "Download failed")
                }
                activeDownloads.remove(originalUrl)
            }
        }
    }

    private fun updateReelDownloading(index: Int, isDownloading: Boolean) {
        val items = _state.value.reelItems.toMutableList()
        if (index in items.indices) {
            items[index] = items[index].copy(isDownloading = isDownloading, downloadError = null)
            _state.value = _state.value.copy(reelItems = items)
        }
    }

    private fun updateReelDownloadProgress(index: Int, progress: StateFlow<DownloadProgress>) {
        val items = _state.value.reelItems.toMutableList()
        if (index in items.indices) {
            items[index] = items[index].copy(downloadProgress = progress)
            _state.value = _state.value.copy(reelItems = items)
        }
    }

    private fun updateReelDownloadError(index: Int, error: String) {
        val items = _state.value.reelItems.toMutableList()
        if (index in items.indices) {
            items[index] = items[index].copy(
                isDownloading = false,
                downloadError = error,
                downloadProgress = null
            )
            _state.value = _state.value.copy(reelItems = items)
        }
    }

    /**
     * Updates the tapback for a specific reel.
     */
    fun updateTapback(
        messageGuid: String,
        url: String,
        tapback: ReelsTapback?
    ) {
        val currentItems = _state.value.reelItems.toMutableList()
        val index = currentItems.indexOfFirst {
            it.messageGuid == messageGuid && it.originalUrl == url
        }

        if (index >= 0) {
            val existingTapback = currentItems[index].currentTapback
            // Toggle off if same tapback selected
            val newTapback = if (existingTapback == tapback) null else tapback
            currentItems[index] = currentItems[index].copy(currentTapback = newTapback)
            _state.value = _state.value.copy(reelItems = currentItems)
        }
    }

    /**
     * Gets the index of a reel by its original URL.
     */
    fun getReelIndex(originalUrl: String): Int {
        return _state.value.reelItems.indexOfFirst { it.originalUrl == originalUrl }
    }

    /**
     * Checks if there are any reel videos (cached or pending) for this chat.
     */
    fun hasReelVideos(): Boolean = _state.value.reelItems.isNotEmpty()

    /**
     * Checks if the Reels feed feature is enabled.
     */
    fun isReelsFeedEnabled(): Boolean = _state.value.isEnabled
}

/**
 * State for the Reels feed.
 */
data class ReelsState(
    val isEnabled: Boolean = false,
    val cachedVideos: List<CachedVideo> = emptyList(),
    val reelItems: List<ReelItem> = emptyList()
)
