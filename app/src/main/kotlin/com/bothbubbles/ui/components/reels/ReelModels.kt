package com.bothbubbles.ui.components.reels

import com.bothbubbles.core.model.entity.AttachmentEntity
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
 * Represents a video attachment from a message (not social media).
 * Used for regular video attachments sent in the chat.
 */
data class AttachmentVideoData(
    /** Unique identifier (attachment guid) */
    val guid: String,
    /** Message GUID the attachment belongs to */
    val messageGuid: String,
    /** Chat GUID for the message */
    val chatGuid: String? = null,
    /** Local file path to the video */
    val localPath: String,
    /** Thumbnail path for preview */
    val thumbnailPath: String? = null,
    /** Blurhash for placeholder */
    val blurhash: String? = null,
    /** Original filename */
    val transferName: String? = null,
    /** File size in bytes */
    val totalBytes: Long? = null,
    /** Video width in pixels */
    val width: Int? = null,
    /** Video height in pixels */
    val height: Int? = null,
    /** Sender name */
    val senderName: String? = null,
    /** Sender address (phone/email) */
    val senderAddress: String? = null,
    /** Timestamp when the message was sent */
    val sentTimestamp: Long = 0L,
    /** Whether the video was sent by the user */
    val isFromMe: Boolean = false,
    /** Whether this has been viewed in Reels */
    val viewedInReels: Boolean = false
)

/**
 * Represents an item in the Reels feed.
 *
 * Can be:
 * - A cached social media video (ready to play)
 * - A pending social media video (needs downloading)
 * - A video attachment from a message (ready to play)
 */
data class ReelItem(
    /** The cached social media video data, null if video is pending or an attachment */
    val cachedVideo: CachedVideo? = null,

    /** Video attachment data, null if this is a social media video */
    val attachmentVideo: AttachmentVideoData? = null,

    // === Pending video fields (used when cachedVideo and attachmentVideo are null) ===
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
    /** Whether this pending video has been viewed in Reels */
    val pendingViewedInReels: Boolean = false,

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
    /** Whether this is a cached social media video (ready to play) */
    val isCached: Boolean get() = cachedVideo != null

    /** Whether this is a video attachment (ready to play) */
    val isAttachment: Boolean get() = attachmentVideo != null

    /** Whether this is a pending (needs download) video */
    val isPending: Boolean get() = cachedVideo == null && attachmentVideo == null && pendingUrl != null

    /** Whether this item is ready to play (either cached or attachment) */
    val isReadyToPlay: Boolean get() = isCached || isAttachment

    /** The original URL for social media, or local path for attachments */
    val originalUrl: String get() = cachedVideo?.originalUrl ?: pendingUrl ?: ""

    /** Local file path for playback */
    val localPath: String? get() = cachedVideo?.localPath ?: attachmentVideo?.localPath

    /** The message GUID, from either cached, attachment, or pending */
    val messageGuid: String get() = cachedVideo?.messageGuid ?: attachmentVideo?.messageGuid ?: pendingMessageGuid ?: ""

    /** The platform (null for attachments) */
    val platform: SocialMediaPlatform? get() = cachedVideo?.platform ?: pendingPlatform

    /** Sender name, from either source */
    val senderName: String? get() = cachedVideo?.senderName ?: attachmentVideo?.senderName ?: pendingSenderName

    /** Sender address, from either source */
    val senderAddress: String? get() = cachedVideo?.senderAddress ?: attachmentVideo?.senderAddress ?: pendingSenderAddress

    /** Sent timestamp, from either source */
    val sentTimestamp: Long get() = cachedVideo?.sentTimestamp ?: attachmentVideo?.sentTimestamp ?: pendingSentTimestamp

    /** Whether this was sent by the current user */
    val isFromMe: Boolean get() = when {
        attachmentVideo != null -> attachmentVideo.isFromMe
        // For social media videos, check if sender is "You" (set in IncomingMessageHandler/IMessageSenderStrategy)
        cachedVideo != null -> cachedVideo.senderName == "You"
        pendingSenderName != null -> pendingSenderName == "You"
        else -> false
    }

    /**
     * Whether this video has been viewed in the Reels feed.
     * Sent items (isFromMe) are always considered viewed since you know what you sent.
     */
    val isViewed: Boolean get() = isFromMe || cachedVideo?.viewedInReels ?: attachmentVideo?.viewedInReels ?: pendingViewedInReels

    /** Thumbnail path for preview (attachments only) */
    val thumbnailPath: String? get() = attachmentVideo?.thumbnailPath

    /** Blurhash for placeholder (attachments only) */
    val blurhash: String? get() = attachmentVideo?.blurhash

    /** Display name for the video (filename for attachments, platform for social media) */
    val displayTitle: String? get() = when {
        attachmentVideo != null -> attachmentVideo.transferName
        cachedVideo != null -> cachedVideo.platform.name
        pendingPlatform != null -> pendingPlatform.name
        else -> null
    }

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
         * Creates a ReelItem from an AttachmentVideoData.
         */
        fun fromAttachment(
            video: AttachmentVideoData,
            currentTapback: ReelsTapback? = null,
            avatarPath: String? = null,
            displayName: String? = null,
            reactions: List<ReactionUiModel> = emptyList(),
            replyCount: Int = 0
        ): ReelItem {
            return ReelItem(
                attachmentVideo = if (displayName != null) {
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
            sentTimestamp: Long = 0L,
            avatarPath: String? = null,
            viewedInReels: Boolean = false
        ): ReelItem {
            return ReelItem(
                pendingUrl = url,
                pendingPlatform = platform,
                pendingMessageGuid = messageGuid,
                pendingChatGuid = chatGuid,
                pendingSenderName = senderName,
                pendingSenderAddress = senderAddress,
                pendingSentTimestamp = sentTimestamp,
                pendingViewedInReels = viewedInReels,
                avatarPath = avatarPath
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
