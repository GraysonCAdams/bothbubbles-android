package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Platform types for social media videos.
 */
enum class SocialMediaPlatform {
    INSTAGRAM,
    TIKTOK
}

/**
 * Entity for tracking social media links detected in messages.
 *
 * This is the source of truth for which messages contain downloadable social media videos.
 * Unlike regex-based lookups on message text, this table:
 * - Only contains actual messages (not tapback reactions that quote the URL)
 * - Captures correct sender info at detection time
 * - Enables efficient queries for the Reels UI without message text parsing
 *
 * Populated by:
 * - IncomingMessageHandler when new messages arrive
 * - One-time migration for existing messages
 *
 * Used by:
 * - ChatReelsDelegate for displaying pending videos
 * - SocialMediaDownloadService for auto-caching
 * - SocialMediaCacheManager for linking cached videos to messages
 */
@Entity(
    tableName = "social_media_links",
    indices = [
        Index(value = ["message_guid"]),
        Index(value = ["chat_guid"]),
        Index(value = ["is_downloaded"]),
        Index(value = ["chat_guid", "is_downloaded"]),
        Index(value = ["sent_timestamp"])
    ]
)
data class SocialMediaLinkEntity(
    /**
     * SHA-256 hash of the normalized URL for deduplication.
     * Primary key to ensure each unique URL is only stored once per message.
     */
    @PrimaryKey
    @ColumnInfo(name = "url_hash")
    val urlHash: String,

    /**
     * The original URL as it appeared in the message.
     */
    @ColumnInfo(name = "url")
    val url: String,

    /**
     * GUID of the original message containing this URL.
     * This is always the actual message, never a tapback/reaction.
     */
    @ColumnInfo(name = "message_guid")
    val messageGuid: String,

    /**
     * Chat GUID for filtering by conversation.
     */
    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    /**
     * Platform (INSTAGRAM or TIKTOK).
     */
    @ColumnInfo(name = "platform")
    val platform: String,

    /**
     * Sender's address (phone number or email).
     * NULL if is_from_me is true.
     */
    @ColumnInfo(name = "sender_address")
    val senderAddress: String?,

    /**
     * Whether the message was sent by the current user.
     */
    @ColumnInfo(name = "is_from_me")
    val isFromMe: Boolean,

    /**
     * Timestamp when the message was sent.
     * Used for sorting in the Reels UI.
     */
    @ColumnInfo(name = "sent_timestamp")
    val sentTimestamp: Long,

    /**
     * Whether the video has been downloaded/cached.
     * Updated by SocialMediaCacheManager when download completes.
     */
    @ColumnInfo(name = "is_downloaded")
    val isDownloaded: Boolean = false,

    /**
     * Timestamp when the entry was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Get the platform as an enum.
     */
    val platformEnum: SocialMediaPlatform
        get() = try {
            SocialMediaPlatform.valueOf(platform)
        } catch (e: IllegalArgumentException) {
            SocialMediaPlatform.INSTAGRAM // Default fallback
        }
}
