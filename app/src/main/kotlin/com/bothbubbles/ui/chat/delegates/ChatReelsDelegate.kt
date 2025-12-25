package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.core.data.prefs.SyncPreferences
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.SocialMediaLinkDao
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.services.contacts.DisplayNameResolver
import com.bothbubbles.services.socialmedia.CachedVideo
import com.bothbubbles.services.socialmedia.DownloadProgress
import com.bothbubbles.services.socialmedia.SocialMediaCacheManager
import com.bothbubbles.services.socialmedia.SocialMediaDownloadService
import com.bothbubbles.services.socialmedia.SocialMediaPlatform
import com.bothbubbles.services.socialmedia.SocialMediaResult
import com.bothbubbles.ui.components.message.ReactionUiModel
import com.bothbubbles.ui.components.message.parseReactionType
import com.bothbubbles.ui.components.reels.AttachmentVideoData
import com.bothbubbles.ui.components.reels.ReelItem
import com.bothbubbles.ui.components.reels.ReelsTapback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Delegate for managing Reels feed state in ChatScreen.
 * Handles loading cached videos, video attachments, checking settings, managing tapbacks,
 * and orchestrating video downloads.
 *
 * Sorting order for Reels feed:
 * 1. Unread videos first, oldest to newest (chronological catchup)
 * 2. Read videos after, newest to oldest (most relevant rewatches first)
 */
class ChatReelsDelegate @Inject constructor(
    private val downloadService: SocialMediaDownloadService,
    private val cacheManager: SocialMediaCacheManager,
    private val syncPreferences: SyncPreferences,
    private val featurePreferences: FeaturePreferences,
    private val handleRepository: HandleRepository,
    private val displayNameResolver: DisplayNameResolver,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val socialMediaLinkDao: SocialMediaLinkDao
) {
    private lateinit var scope: CoroutineScope
    private var chatGuid: String = ""
    private var allChatGuids: List<String> = emptyList()

    /** Timestamp when initial sync completed. Videos before this are historical. */
    private var initialSyncCompleteTimestamp: Long = 0L

    private val _state = MutableStateFlow(ReelsState())
    val state: StateFlow<ReelsState> = _state.asStateFlow()

    // Track active downloads
    private val activeDownloads = mutableMapOf<String, StateFlow<DownloadProgress>>()

    /**
     * Initialize the delegate with chat context.
     * @param chatGuid Primary chat GUID
     * @param mergedGuids All GUIDs for this chat (including the primary and any merged iMessage/SMS GUIDs)
     * @param scope Coroutine scope for async operations
     */
    fun initialize(chatGuid: String, mergedGuids: Set<String>, scope: CoroutineScope) {
        this.chatGuid = chatGuid
        this.allChatGuids = (mergedGuids + chatGuid).distinct()
        this.scope = scope
        loadReelsState()
        observeCacheChanges()
    }

    /**
     * Observes cache changes and refreshes state when videos for this chat are cached.
     * This ensures the Reels button appears when inline players cache videos.
     */
    private fun observeCacheChanges() {
        scope.launch {
            cacheManager.cacheEvents.collect { event ->
                // Check if the event is for one of this chat's GUIDs
                if (event.chatGuid in allChatGuids) {
                    Timber.d("[Reels] Cache event for this chat: ${event.eventType}, refreshing state")
                    refreshCachedVideos()
                }
            }
        }
    }

    /**
     * Loads the reels state including settings, cached videos, video attachments, and pending videos for this chat.
     * Videos are sorted: unread (oldest→newest) then read (newest→oldest).
     * Also calculates unwatched count for videos received after initial sync.
     */
    private fun loadReelsState() {
        scope.launch {
            // Get timestamp when initial sync completed
            initialSyncCompleteTimestamp = syncPreferences.initialSyncCompleteTimestamp.first()

            // Recover any orphaned cached videos (from before metadata persistence was added)
            val recoveredCount = cacheManager.recoverOrphanedMetadata()
            if (recoveredCount > 0) {
                Timber.d("[Reels] Recovered $recoveredCount orphaned video metadata entries")
            }

            // Repair cached videos with empty metadata using data from social_media_links table
            repairCachedVideoMetadata()

            val isEnabled = downloadService.isReelsFeedEnabled()
            val includeAttachments = featurePreferences.reelsIncludeVideoAttachments.first()
            val cachedVideos = cacheManager.getCachedVideosForChats(allChatGuids)

            // Find pending videos (social media links not yet cached)
            val pendingItems = findPendingVideos(cachedVideos)

            // Load video attachments if setting is enabled
            val attachmentItems = if (includeAttachments) {
                loadVideoAttachments()
            } else {
                emptyList()
            }

            // Debug logging
            Timber.d("[Reels] chatGuid=$chatGuid, isEnabled=$isEnabled, cachedVideos=${cachedVideos.size}, pendingItems=${pendingItems.size}, attachments=${attachmentItems.size}")

            // Count unwatched videos received AFTER initial sync (social media only for now)
            val unwatchedCount = cachedVideos.count { video ->
                !video.viewedInReels && video.sentTimestamp > initialSyncCompleteTimestamp
            }

            // Combine cached social media and attachment items
            val cachedReelItems = sortAndMapVideos(cachedVideos)
            val allItems = (cachedReelItems + attachmentItems + pendingItems)

            // Sort based on whether there are unwatched items:
            // - If unwatched exist: unwatched (newest→oldest) + watched (oldest→newest)
            // - If all watched: watched (newest→oldest)
            val sortedItems = sortReelsForDisplay(allItems)

            _state.value = ReelsState(
                isEnabled = isEnabled,
                cachedVideos = cachedVideos,
                reelItems = sortedItems,
                unwatchedCount = unwatchedCount
            )
        }
    }

    /**
     * Finds social media links that haven't been cached yet.
     * Uses the social_media_links table for correct sender attribution.
     */
    private suspend fun findPendingVideos(cachedVideos: List<CachedVideo>): List<ReelItem> {
        // Normalize cached URLs for comparison (handles query params and trailing junk)
        val cachedNormalizedUrls = cachedVideos.map { normalizeUrl(it.originalUrl) }.toSet()
        val pendingItems = mutableListOf<ReelItem>()

        try {
            // Query from social_media_links table (correct sender attribution, no reactions)
            // Use allChatGuids to include links from merged chats (iMessage + SMS)
            val pendingLinks = socialMediaLinkDao.getPendingLinksForChats(allChatGuids)
            Timber.d("[Reels] Found ${pendingLinks.size} pending social media links in table for ${allChatGuids.size} chat GUIDs")

            for (link in pendingLinks) {
                // Skip if already cached (compare normalized URLs to handle query param differences)
                val normalizedLinkUrl = normalizeUrl(link.url)
                if (cachedNormalizedUrls.contains(normalizedLinkUrl)) continue
                // Skip if already in pending list (shouldn't happen with unique URLs)
                if (pendingItems.any { it.originalUrl == link.url }) continue

                val platform = downloadService.detectPlatform(link.url) ?: continue

                // Look up sender display name and avatar
                val handle = link.senderAddress?.let { handleRepository.getHandleByAddressAny(it) }

                // Check if this pending video has been viewed before
                val isViewed = cacheManager.isVideoViewed(link.url)

                pendingItems.add(
                    ReelItem.pending(
                        url = link.url,
                        platform = platform,
                        messageGuid = link.messageGuid,
                        chatGuid = chatGuid,
                        senderName = if (link.isFromMe) "You" else handle?.let { resolveDisplayName(it) },
                        senderAddress = link.senderAddress,
                        sentTimestamp = link.sentTimestamp,
                        avatarPath = handle?.cachedAvatarPath,
                        viewedInReels = isViewed
                    )
                )
                Timber.d("[Reels] Added pending item: ${link.url} from message ${link.messageGuid}")
            }
        } catch (e: Exception) {
            Timber.w(e, "[Reels] Failed to find pending videos")
        }

        // Sort pending items newest first
        return pendingItems.sortedByDescending { it.sentTimestamp }
    }

    /**
     * Repairs cached videos with empty/broken metadata using data from social_media_links table.
     * This fixes videos that were downloaded before proper metadata tracking was implemented.
     */
    private suspend fun repairCachedVideoMetadata() {
        try {
            val brokenVideos = cacheManager.getCachedVideosWithEmptyChatGuid()
            if (brokenVideos.isEmpty()) return

            Timber.d("[Reels] Found ${brokenVideos.size} cached videos with empty chatGuid, attempting repair")

            for (video in brokenVideos) {
                // Look up metadata from social_media_links table
                val linkData = socialMediaLinkDao.getByUrl(video.originalUrl)
                if (linkData != null) {
                    // Found matching link, repair metadata
                    val senderName = if (linkData.isFromMe) {
                        "You"
                    } else {
                        linkData.senderAddress?.let { handleRepository.getHandleByAddressAny(it) }
                            ?.let { resolveDisplayName(it) }
                    }

                    cacheManager.updateCachedVideoMetadata(
                        originalUrl = video.originalUrl,
                        chatGuid = linkData.chatGuid,
                        senderName = senderName,
                        senderAddress = linkData.senderAddress,
                        sentTimestamp = linkData.sentTimestamp
                    )

                    // Also mark as downloaded in DB if not already
                    socialMediaLinkDao.markAsDownloaded(linkData.urlHash)

                    Timber.d("[Reels] Repaired metadata for ${video.originalUrl}: chatGuid=${linkData.chatGuid}")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "[Reels] Failed to repair cached video metadata")
        }
    }

    /**
     * Extracts social media URLs from text.
     * @deprecated Use social_media_links table instead for correct sender attribution.
     */
    private fun extractSocialMediaUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        val urlPattern = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)

        for (match in urlPattern.findAll(text)) {
            val url = match.value.trimEnd('.', ',', ')', ']', '!', '?')
            if (downloadService.detectPlatform(url) != null) {
                urls.add(url)
            }
        }

        return urls
    }

    /**
     * Loads video attachments from the chat and converts them to ReelItems.
     * Includes reactions and reply counts for each video.
     */
    private suspend fun loadVideoAttachments(): List<ReelItem> {
        return try {
            val videoAttachments = attachmentDao.getVideoAttachmentsWithSenderForChat(chatGuid)
            Timber.d("[Reels] Found ${videoAttachments.size} video attachments for chat $chatGuid")

            videoAttachments.map { video ->
                // Fetch reactions and reply count for the message
                val messageGuid = video.attachment.messageGuid
                val reactionMessages = try {
                    messageDao.getReactionsForMessageOnce(messageGuid)
                } catch (e: Exception) {
                    Timber.w(e, "[Reels] Failed to fetch reactions for attachment message $messageGuid")
                    emptyList()
                }
                val replyCount = try {
                    messageDao.getReplyCountForMessage(messageGuid)
                } catch (e: Exception) {
                    Timber.w(e, "[Reels] Failed to fetch reply count for attachment message $messageGuid")
                    0
                }

                // Convert reaction messages to ReactionUiModel
                val reactions = reactionMessages.mapNotNull { reaction ->
                    val tapback = parseReactionType(reaction.associatedMessageType)
                    if (tapback != null) {
                        val reactionHandle = reaction.senderAddress?.let { handleRepository.getHandleByAddressAny(it) }
                        ReactionUiModel(
                            tapback = tapback,
                            isFromMe = reaction.isFromMe,
                            senderName = reactionHandle?.let { resolveDisplayName(it) }
                        )
                    } else null
                }

                // Preserve existing tapback if we have one
                val existingTapback = _state.value.reelItems
                    .find { it.attachmentVideo?.guid == video.attachment.guid }
                    ?.currentTapback

                // Display name: "You" for sent messages, otherwise use centralized resolver
                val displayName = if (video.isFromMe) {
                    "You"
                } else {
                    displayNameResolver.resolveFromRawValues(
                        cachedDisplayName = video.cachedDisplayName,
                        inferredName = video.inferredName,
                        formattedAddress = video.formattedAddress,
                        senderAddress = video.senderAddress
                    )
                }

                // Create AttachmentVideoData
                val attachmentData = AttachmentVideoData(
                    guid = video.attachment.guid,
                    messageGuid = messageGuid,
                    chatGuid = video.chatGuid,
                    localPath = video.attachment.localPath ?: "",
                    thumbnailPath = video.attachment.thumbnailPath,
                    blurhash = video.attachment.blurhash,
                    transferName = video.attachment.transferName,
                    totalBytes = video.attachment.totalBytes,
                    width = video.attachment.width,
                    height = video.attachment.height,
                    senderName = displayName,
                    senderAddress = video.senderAddress,
                    sentTimestamp = video.dateCreated,
                    isFromMe = video.isFromMe,
                    viewedInReels = video.attachment.viewedInReels
                )

                ReelItem.fromAttachment(
                    video = attachmentData,
                    currentTapback = existingTapback,
                    avatarPath = video.avatarPath,
                    displayName = displayName,
                    reactions = reactions,
                    replyCount = replyCount
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "[Reels] Failed to load video attachments")
            emptyList()
        }
    }

    /**
     * Sorts videos and maps to ReelItems.
     * Reverse chronological order: newest first.
     * Looks up contact info (avatar, display name), reactions, and reply count from handles.
     */
    private suspend fun sortAndMapVideos(videos: List<CachedVideo>): List<ReelItem> {
        // Reverse chronological order - newest first
        val combined = videos.sortedByDescending { it.sentTimestamp }

        return combined.map { video ->
            // Preserve existing tapback if we have one
            val existingTapback = _state.value.reelItems
                .find { it.originalUrl == video.originalUrl }
                ?.currentTapback

            // Look up contact info from handle, with fallback to message sender
            var senderAddress = video.senderAddress
            var handle = senderAddress?.let { handleRepository.getHandleByAddressAny(it) }

            // If no sender address in video metadata, try to get it from the message
            if (senderAddress == null) {
                val message = try {
                    messageDao.getMessageByGuid(video.messageGuid)
                } catch (e: Exception) {
                    null
                }
                senderAddress = message?.senderAddress
                handle = senderAddress?.let { handleRepository.getHandleByAddressAny(it) }
            }

            val avatarPath = handle?.cachedAvatarPath
            // Use centralized resolver for consistent name display
            val displayName = handle?.let { resolveDisplayName(it) }
                ?: video.senderName
                ?: senderAddress

            // Fetch reactions and reply count for the message
            val messageGuid = video.messageGuid
            Timber.d("[Reels] Fetching reactions for messageGuid=$messageGuid, url=${video.originalUrl}")
            val reactionMessages = try {
                messageDao.getReactionsForMessageOnce(messageGuid)
            } catch (e: Exception) {
                Timber.w(e, "[Reels] Failed to fetch reactions for $messageGuid")
                emptyList()
            }
            Timber.d("[Reels] Found ${reactionMessages.size} reaction messages for $messageGuid")
            reactionMessages.forEach { reaction ->
                Timber.d("[Reels] Reaction: guid=${reaction.guid}, type=${reaction.associatedMessageType}, assocGuid=${reaction.associatedMessageGuid}, isFromMe=${reaction.isFromMe}")
            }
            val replyCount = try {
                messageDao.getReplyCountForMessage(messageGuid)
            } catch (e: Exception) {
                Timber.w(e, "[Reels] Failed to fetch reply count for $messageGuid")
                0
            }

            // Convert reaction messages to ReactionUiModel
            val reactions = reactionMessages.mapNotNull { reaction ->
                val tapback = parseReactionType(reaction.associatedMessageType)
                Timber.d("[Reels] Parsing reaction type=${reaction.associatedMessageType} -> tapback=$tapback")
                if (tapback != null) {
                    // Look up sender name for the reaction using centralized resolver
                    val reactionHandle = reaction.senderAddress?.let { handleRepository.getHandleByAddressAny(it) }
                    ReactionUiModel(
                        tapback = tapback,
                        isFromMe = reaction.isFromMe,
                        senderName = reactionHandle?.let { resolveDisplayName(it) }
                    )
                } else null
            }
            Timber.d("[Reels] Final reactions count: ${reactions.size}")

            ReelItem.fromCached(
                video = video,
                currentTapback = existingTapback,
                avatarPath = avatarPath,
                displayName = displayName,
                reactions = reactions,
                replyCount = replyCount
            )
        }
    }

    /**
     * Sorts reel items for display based on watched state:
     * - If unwatched items exist: unwatched (newest→oldest) + watched (newest→oldest)
     * - If all watched: watched (newest→oldest)
     *
     * This ensures users see all unwatched content first, then transition to watched content,
     * and the "You're all caught up!" toast appears at the correct boundary.
     */
    private fun sortReelsForDisplay(items: List<ReelItem>): List<ReelItem> {
        val unwatched = items.filter { !it.isViewed }
        val watched = items.filter { it.isViewed }

        return if (unwatched.isNotEmpty()) {
            // Both sections: newest first
            val sortedUnwatched = unwatched.sortedByDescending { it.sentTimestamp }
            val sortedWatched = watched.sortedByDescending { it.sentTimestamp }
            sortedUnwatched + sortedWatched
        } else {
            // All watched: newest first
            watched.sortedByDescending { it.sentTimestamp }
        }
    }

    /**
     * Refreshes the cached videos list, re-sorts, and recalculates unwatched count.
     */
    fun refreshCachedVideos() {
        scope.launch {
            val includeAttachments = featurePreferences.reelsIncludeVideoAttachments.first()
            val cachedVideos = cacheManager.getCachedVideosForChats(allChatGuids)

            // Find pending videos (social media links not yet cached)
            val pendingItems = findPendingVideos(cachedVideos)

            // Load video attachments if setting is enabled
            val attachmentItems = if (includeAttachments) {
                loadVideoAttachments()
            } else {
                emptyList()
            }

            // Recalculate unwatched count for videos received AFTER initial sync
            val unwatchedCount = cachedVideos.count { video ->
                !video.viewedInReels && video.sentTimestamp > initialSyncCompleteTimestamp
            }

            // Combine cached social media and attachment items
            val cachedReelItems = sortAndMapVideos(cachedVideos)
            val allItems = (cachedReelItems + attachmentItems + pendingItems)

            // Sort based on whether there are unwatched items
            val sortedItems = sortReelsForDisplay(allItems)

            _state.value = _state.value.copy(
                cachedVideos = cachedVideos,
                reelItems = sortedItems,
                unwatchedCount = unwatchedCount
            )
        }
    }

    /**
     * Marks a video as viewed in the Reels feed.
     * Called when user spends time on a video.
     * Handles both social media videos (cached) and video attachments.
     *
     * NOTE: We don't refresh/re-sort here to avoid changing the list order mid-viewing.
     * The sort will be applied next time the Reels feed is opened.
     */
    fun markVideoAsViewed(originalUrl: String) {
        Timber.tag("ReelViewed").d("[REEL_DEBUG] markVideoAsViewed called with: $originalUrl")
        // Update the viewed state in-place without re-sorting
        val currentItems = _state.value.reelItems.toMutableList()
        val index = currentItems.indexOfFirst { it.originalUrl == originalUrl || it.attachmentVideo?.guid == originalUrl }
        Timber.tag("ReelViewed").d("[REEL_DEBUG] Found item at index: $index, totalItems=${currentItems.size}")
        if (index < 0) {
            Timber.tag("ReelViewed").w("[REEL_DEBUG] Item not found in reelItems list!")
            return
        }

        val item = currentItems[index]
        Timber.tag("ReelViewed").d("[REEL_DEBUG] Item: isCached=${item.isCached}, isAttachment=${item.isAttachment}, isPending=${item.isPending}, isViewed=${item.isViewed}")

        when {
            // Social media video (cached)
            item.isCached -> {
                Timber.tag("ReelViewed").d("[REEL_DEBUG] Marking cached video as viewed via cacheManager")
                cacheManager.markVideoAsViewed(originalUrl)
                val updatedVideo = item.cachedVideo?.copy(viewedInReels = true)
                if (updatedVideo != null) {
                    currentItems[index] = item.copy(cachedVideo = updatedVideo)
                }
            }
            // Video attachment
            item.isAttachment -> {
                val attachmentGuid = item.attachmentVideo?.guid ?: return
                Timber.tag("ReelViewed").d("[REEL_DEBUG] Marking attachment as viewed in DB: $attachmentGuid")
                scope.launch {
                    attachmentDao.markViewedInReels(attachmentGuid)
                }
                val updatedAttachment = item.attachmentVideo?.copy(viewedInReels = true)
                if (updatedAttachment != null) {
                    currentItems[index] = item.copy(attachmentVideo = updatedAttachment)
                }
            }
            // Pending video (not yet downloaded)
            item.isPending -> {
                Timber.tag("ReelViewed").d("[REEL_DEBUG] Marking pending video as viewed via cacheManager")
                cacheManager.markVideoAsViewed(originalUrl)
                currentItems[index] = item.copy(pendingViewedInReels = true)
            }
        }

        // Recalculate unwatched count (social media only for badge)
        val unwatchedCount = currentItems.count { reel ->
            reel.isCached && !reel.isViewed &&
            reel.sentTimestamp > initialSyncCompleteTimestamp
        }
        Timber.tag("ReelViewed").d("[REEL_DEBUG] Updated unwatchedCount: $unwatchedCount")

        _state.value = _state.value.copy(
            reelItems = currentItems,
            unwatchedCount = unwatchedCount
        )
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
     * Works for both social media videos (by messageGuid+url) and attachments (by messageGuid+attachmentGuid).
     */
    fun updateTapback(
        messageGuid: String,
        url: String,
        tapback: ReelsTapback?,
        attachmentGuid: String? = null
    ) {
        val currentItems = _state.value.reelItems.toMutableList()
        val index = currentItems.indexOfFirst { item ->
            when {
                // For attachments, match by messageGuid and attachmentGuid
                attachmentGuid != null -> item.attachmentVideo?.guid == attachmentGuid
                // For social media videos, match by messageGuid and URL
                else -> item.messageGuid == messageGuid && item.originalUrl == url
            }
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
     * Gets the index of a reel by its original URL or attachment GUID.
     */
    fun getReelIndex(originalUrl: String, attachmentGuid: String? = null): Int {
        return _state.value.reelItems.indexOfFirst { item ->
            when {
                attachmentGuid != null -> item.attachmentVideo?.guid == attachmentGuid
                else -> item.originalUrl == originalUrl
            }
        }
    }

    /**
     * Enables the Reels feature with the given configuration.
     * Sets all relevant preferences and triggers a reload of reels state.
     *
     * @param includeVideoAttachments Include regular video attachments in Reels
     * @param includeTikToks Enable TikTok video downloading
     * @param includeInstagrams Enable Instagram video downloading
     * @param downloadOnCellular Allow downloading over cellular data
     */
    fun enableReels(
        includeVideoAttachments: Boolean,
        includeTikToks: Boolean,
        includeInstagrams: Boolean,
        downloadOnCellular: Boolean
    ) {
        scope.launch {
            // Set all the preferences
            featurePreferences.setReelsFeedEnabled(true)
            featurePreferences.setReelsIncludeVideoAttachments(includeVideoAttachments)
            featurePreferences.setTiktokDownloaderEnabled(includeTikToks)
            featurePreferences.setInstagramDownloaderEnabled(includeInstagrams)
            featurePreferences.setSocialMediaDownloadOnCellularEnabled(downloadOnCellular)

            // If TikTok or Instagram is enabled, also enable background downloading
            if (includeTikToks || includeInstagrams) {
                featurePreferences.setSocialMediaBackgroundDownloadEnabled(true)
            }

            // Reload reels state to reflect new settings
            loadReelsState()
        }
    }

    /**
     * Checks if there are any reel videos (cached or pending) for this chat.
     */
    fun hasReelVideos(): Boolean = _state.value.reelItems.isNotEmpty()

    /**
     * Checks if the Reels feed feature is enabled.
     */
    fun isReelsFeedEnabled(): Boolean = _state.value.isEnabled

    /**
     * Normalize URL by extracting just the base path (without query params or trailing junk).
     * Used for deduplication when comparing cached URLs with social_media_links entries.
     */
    private fun normalizeUrl(url: String): String {
        return try {
            // Strip query params and any trailing quotes/junk
            val cleanUrl = url.replace("\"", "").replace("'", "")
            val uri = android.net.Uri.parse(cleanUrl)
            val pathOnly = uri.path?.trimEnd('/') ?: ""
            "${uri.scheme}://${uri.host}$pathOnly"
        } catch (e: Exception) {
            // Fallback: just strip everything after ?
            url.substringBefore("?").trimEnd('/')
        }
    }

    /**
     * Resolve display name from a handle using centralized DisplayNameResolver.
     * Ensures consistent "Maybe:" prefix for inferred names.
     */
    private fun resolveDisplayName(handle: HandleEntity): String {
        return displayNameResolver.resolveDisplayName(handle, DisplayNameResolver.DisplayMode.FULL)
    }
}

/**
 * State for the Reels feed.
 */
data class ReelsState(
    val isEnabled: Boolean = false,
    val cachedVideos: List<CachedVideo> = emptyList(),
    val reelItems: List<ReelItem> = emptyList(),
    /** Count of unwatched videos received after initial sync (for badge display). */
    val unwatchedCount: Int = 0
)
