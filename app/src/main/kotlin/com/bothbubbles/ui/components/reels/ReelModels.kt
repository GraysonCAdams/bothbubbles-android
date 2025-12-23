package com.bothbubbles.ui.components.reels

import com.bothbubbles.services.socialmedia.CachedVideo
import com.bothbubbles.services.socialmedia.DownloadProgress
import com.bothbubbles.services.socialmedia.SocialMediaPlatform
import com.bothbubbles.ui.components.message.ReactionUiModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Tapback reaction types available in the Reels feed.
 * Maps to iMessage tapback types for sending reactions.
 */
enum class ReelsTapback(val emoji: String, val label: String, val reactionType: String) {
    LIKE("👍", "Like", "like"),
    LAUGH("😂", "Laugh", "laugh"),
    LOVE("❤️", "Love", "love"),
    DISLIKE("👎", "Dislike", "dislike")
}

/**
 * Represents an item in the Reels feed.
 *
 * Can be either:
 * - A cached video (ready to play)
 * - A pending video (needs downloading)
 */
data class ReelItem(
    /** The cached video data, null if video is still pending download */
    val cachedVideo: CachedVideo? = null,

    // === Pending video fields (used when cachedVideo is null) ===
    /** Original URL of the video (for pending items) */
    val pendingUrl: String? = null,
    /** Platform of the pending video */
    val pendingPlatform: SocialMediaPlatform? = null,
    /** Message GUID the pending video is from */
    val pendingMessageGuid: String? = null,
    /** Chat GUID for the pending video */
    val pendingChatGuid: String? = null,
    /** Sender name for the pending video */
    val pendingSenderName: String? = null,
    /** Sender address for the pending video */
    val pendingSenderAddress: String? = null,
    /** Timestamp when the message was sent */
    val pendingSentTimestamp: Long = 0L,

    // === Common fields ===
    /** Current tapback reaction on this reel, if any */
    val currentTapback: ReelsTapback? = null,
    /** Avatar path for the sender (contact photo) */
    val avatarPath: String? = null,
    /** Reactions from others on the original message */
    val reactions: List<ReactionUiModel> = emptyList(),
    /** Number of thread replies to this message */
    val replyCount: Int = 0,

    // === Download state (for pending items) ===
    /** Whether this item is currently being downloaded */
    val isDownloading: Boolean = false,
    /** Download progress flow (null if not downloading) */
    val downloadProgress: StateFlow<DownloadProgress>? = null,
    /** Error message if download failed */
    val downloadError: String? = null
) {
    /** Whether this is a cached (ready to play) video */
    val isCached: Boolean get() = cachedVideo != null

    /** Whether this is a pending (needs download) video */
    val isPending: Boolean get() = cachedVideo == null && pendingUrl != null

    /** The original URL, from either cached or pending */
    val originalUrl: String get() = cachedVideo?.originalUrl ?: pendingUrl ?: ""

    /** The message GUID, from either cached or pending */
    val messageGuid: String get() = cachedVideo?.messageGuid ?: pendingMessageGuid ?: ""

    /** The platform, from either cached or pending */
    val platform: SocialMediaPlatform get() = cachedVideo?.platform ?: pendingPlatform ?: SocialMediaPlatform.TIKTOK

    /** Sender name, from either cached or pending */
    val senderName: String? get() = cachedVideo?.senderName ?: pendingSenderName

    /** Sender address, from either cached or pending */
    val senderAddress: String? get() = cachedVideo?.senderAddress ?: pendingSenderAddress

    /** Sent timestamp, from either cached or pending */
    val sentTimestamp: Long get() = cachedVideo?.sentTimestamp ?: pendingSentTimestamp

    /** Whether this video has been viewed in the Reels feed */
    val isViewed: Boolean get() = cachedVideo?.viewedInReels ?: false

    companion object {
        /**
         * Creates a ReelItem from a CachedVideo.
         */
        fun fromCached(
            video: CachedVideo,
            currentTapback: ReelsTapback? = null,
            avatarPath: String? = null,
            displayName: String? = null,
            reactions: List<ReactionUiModel> = emptyList(),
            replyCount: Int = 0
        ): ReelItem {
            return ReelItem(
                cachedVideo = if (displayName != null) {
                    video.copy(senderName = displayName)
                } else {
                    video
                },
                currentTapback = currentTapback,
                avatarPath = avatarPath,
                reactions = reactions,
                replyCount = replyCount
            )
        }

        /**
         * Creates a pending ReelItem that needs to be downloaded.
         */
        fun pending(
            url: String,
            platform: SocialMediaPlatform,
            messageGuid: String,
            chatGuid: String? = null,
            senderName: String? = null,
            senderAddress: String? = null,
            sentTimestamp: Long = 0L
        ): ReelItem {
            return ReelItem(
                pendingUrl = url,
                pendingPlatform = platform,
                pendingMessageGuid = messageGuid,
                pendingChatGuid = chatGuid,
                pendingSenderName = senderName,
                pendingSenderAddress = senderAddress,
                pendingSentTimestamp = sentTimestamp
            )
        }
    }
}

/**
 * Represents a social media link found in a message that may or may not be cached.
 */
data class SocialMediaLink(
    val url: String,
    val platform: SocialMediaPlatform,
    val messageGuid: String,
    val chatGuid: String?,
    val senderName: String?,
    val senderAddress: String?,
    val sentTimestamp: Long,
    /** Whether this link has been cached/downloaded */
    val isCached: Boolean
)
