package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.core.model.entity.SocialMediaLinkEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for social media link tracking.
 *
 * This replaces regex-based message lookups for the Reels feature.
 * Links are inserted when messages are received and marked as downloaded
 * when the video is cached.
 */
@Dao
interface SocialMediaLinkDao {

    // ===== Queries =====

    /**
     * Get all social media links for a chat, ordered by sent timestamp (newest first).
     * Used by ChatReelsDelegate to build the Reels feed.
     */
    @Query("""
        SELECT * FROM social_media_links
        WHERE chat_guid = :chatGuid
        ORDER BY sent_timestamp DESC
    """)
    suspend fun getLinksForChat(chatGuid: String): List<SocialMediaLinkEntity>

    /**
     * Get all social media links for multiple chats (for merged conversations).
     */
    @Query("""
        SELECT * FROM social_media_links
        WHERE chat_guid IN (:chatGuids)
        ORDER BY sent_timestamp DESC
    """)
    suspend fun getLinksForChats(chatGuids: List<String>): List<SocialMediaLinkEntity>

    /**
     * Observe social media links for a chat (reactive).
     */
    @Query("""
        SELECT * FROM social_media_links
        WHERE chat_guid = :chatGuid
        ORDER BY sent_timestamp DESC
    """)
    fun observeLinksForChat(chatGuid: String): Flow<List<SocialMediaLinkEntity>>

    /**
     * Get pending (not downloaded) links for a chat.
     * Used to show pending videos in the Reels feed.
     */
    @Query("""
        SELECT * FROM social_media_links
        WHERE chat_guid = :chatGuid
        AND is_downloaded = 0
        ORDER BY sent_timestamp DESC
    """)
    suspend fun getPendingLinksForChat(chatGuid: String): List<SocialMediaLinkEntity>

    /**
     * Get pending (not downloaded) links for multiple chats (merged chats).
     * Used to show pending videos in the Reels feed for unified chat views.
     */
    @Query("""
        SELECT * FROM social_media_links
        WHERE chat_guid IN (:chatGuids)
        AND is_downloaded = 0
        ORDER BY sent_timestamp DESC
    """)
    suspend fun getPendingLinksForChats(chatGuids: List<String>): List<SocialMediaLinkEntity>

    /**
     * Get pending links across all chats for auto-caching.
     * Limited to avoid overwhelming the download queue.
     */
    @Query("""
        SELECT * FROM social_media_links
        WHERE is_downloaded = 0
        ORDER BY sent_timestamp DESC
        LIMIT :limit
    """)
    suspend fun getPendingLinks(limit: Int = 50): List<SocialMediaLinkEntity>

    /**
     * Get a link by URL hash.
     */
    @Query("SELECT * FROM social_media_links WHERE url_hash = :urlHash")
    suspend fun getByUrlHash(urlHash: String): SocialMediaLinkEntity?

    /**
     * Get a link by original URL.
     */
    @Query("SELECT * FROM social_media_links WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): SocialMediaLinkEntity?

    /**
     * Get a link by message GUID.
     * A message can contain multiple URLs, so this returns a list.
     */
    @Query("SELECT * FROM social_media_links WHERE message_guid = :messageGuid")
    suspend fun getByMessageGuid(messageGuid: String): List<SocialMediaLinkEntity>

    /**
     * Check if a URL hash exists.
     */
    @Query("SELECT COUNT(*) > 0 FROM social_media_links WHERE url_hash = :urlHash")
    suspend fun exists(urlHash: String): Boolean

    /**
     * Get the correct sender info for a URL.
     * Used to repair cached video metadata.
     */
    @Query("""
        SELECT sender_address, is_from_me
        FROM social_media_links
        WHERE url_hash = :urlHash
    """)
    suspend fun getSenderInfoByUrlHash(urlHash: String): SenderInfo?

    /**
     * Data class for sender info query result.
     */
    data class SenderInfo(
        val sender_address: String?,
        val is_from_me: Boolean
    )

    /**
     * Get count of links for a chat.
     */
    @Query("SELECT COUNT(*) FROM social_media_links WHERE chat_guid = :chatGuid")
    suspend fun getCountForChat(chatGuid: String): Int

    /**
     * Get count of pending links for a chat (for badge display).
     */
    @Query("""
        SELECT COUNT(*) FROM social_media_links
        WHERE chat_guid = :chatGuid
        AND is_downloaded = 0
    """)
    suspend fun getPendingCountForChat(chatGuid: String): Int

    // ===== Inserts =====

    /**
     * Insert a social media link.
     * Uses IGNORE to skip duplicates (same URL hash).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: SocialMediaLinkEntity): Long

    /**
     * Insert multiple social media links.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<SocialMediaLinkEntity>)

    // ===== Updates =====

    /**
     * Mark a link as downloaded.
     */
    @Query("UPDATE social_media_links SET is_downloaded = 1 WHERE url_hash = :urlHash")
    suspend fun markAsDownloaded(urlHash: String)

    /**
     * Mark multiple links as downloaded.
     */
    @Query("UPDATE social_media_links SET is_downloaded = 1 WHERE url_hash IN (:urlHashes)")
    suspend fun markAsDownloaded(urlHashes: List<String>)

    /**
     * Mark a link as not downloaded (e.g., if cache was cleared).
     */
    @Query("UPDATE social_media_links SET is_downloaded = 0 WHERE url_hash = :urlHash")
    suspend fun markAsNotDownloaded(urlHash: String)

    // ===== Deletes =====

    /**
     * Delete a link by URL hash.
     */
    @Query("DELETE FROM social_media_links WHERE url_hash = :urlHash")
    suspend fun deleteByUrlHash(urlHash: String)

    /**
     * Delete links for a message (when message is deleted).
     */
    @Query("DELETE FROM social_media_links WHERE message_guid = :messageGuid")
    suspend fun deleteByMessageGuid(messageGuid: String)

    /**
     * Delete links for a chat (when chat is deleted).
     */
    @Query("DELETE FROM social_media_links WHERE chat_guid = :chatGuid")
    suspend fun deleteByChatGuid(chatGuid: String)

    /**
     * Delete all links (clear all data).
     */
    @Query("DELETE FROM social_media_links")
    suspend fun deleteAll()

    /**
     * Delete Instagram post URLs (those containing /p/ or /share/p/).
     * Used by migration to clean up non-reel URLs since we only support reels now.
     * @return Number of rows deleted
     */
    @Query("DELETE FROM social_media_links WHERE platform = 'INSTAGRAM' AND (url LIKE '%/p/%' OR url LIKE '%/share/p/%')")
    suspend fun deleteInstagramPosts(): Int

    /**
     * Get all Instagram links (for migration/cleanup purposes).
     */
    @Query("SELECT * FROM social_media_links WHERE platform = 'INSTAGRAM'")
    suspend fun getAllInstagramLinks(): List<SocialMediaLinkEntity>
}
