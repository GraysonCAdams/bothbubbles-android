package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for querying chat popularity based on message engagement.
 *
 * Popularity is calculated based on message count within a time window.
 * Used by:
 * - Launcher app shortcuts (top 3 popular chats)
 * - Chat composer suggestions
 *
 * Note: These queries filter out archived and deleted chats to ensure
 * only active conversations appear in suggestions.
 */
@Dao
interface PopularChatsDao {

    /**
     * Data class for chat popularity metrics.
     */
    data class ChatPopularity(
        val chatGuid: String,
        val messageCount: Int,
        val latestMessageDate: Long
    )

    /**
     * Get message counts per chat within a time window.
     * Only includes active chats (not archived, not deleted).
     *
     * @param since Timestamp (epoch ms) to count messages from
     * @param limit Maximum number of results
     * @return List of chat GUIDs with their message counts, sorted by count desc
     */
    @Query("""
        SELECT
            m.chat_guid as chatGuid,
            COUNT(m.guid) as messageCount,
            MAX(m.date_created) as latestMessageDate
        FROM messages m
        INNER JOIN chats c ON m.chat_guid = c.guid
        LEFT JOIN unified_chats uc ON c.unified_chat_id = uc.id
        WHERE m.date_deleted IS NULL
        AND m.date_created >= :since
        AND m.is_reaction = 0
        AND c.date_deleted IS NULL
        AND (uc.is_archived IS NULL OR uc.is_archived = 0)
        GROUP BY m.chat_guid
        ORDER BY messageCount DESC, latestMessageDate DESC
        LIMIT :limit
    """)
    suspend fun getPopularChats(since: Long, limit: Int): List<ChatPopularity>

    /**
     * Observe message counts per chat within a time window (reactive).
     * Used for keeping launcher shortcuts up to date.
     */
    @Query("""
        SELECT
            m.chat_guid as chatGuid,
            COUNT(m.guid) as messageCount,
            MAX(m.date_created) as latestMessageDate
        FROM messages m
        INNER JOIN chats c ON m.chat_guid = c.guid
        LEFT JOIN unified_chats uc ON c.unified_chat_id = uc.id
        WHERE m.date_deleted IS NULL
        AND m.date_created >= :since
        AND m.is_reaction = 0
        AND c.date_deleted IS NULL
        AND (uc.is_archived IS NULL OR uc.is_archived = 0)
        GROUP BY m.chat_guid
        ORDER BY messageCount DESC, latestMessageDate DESC
        LIMIT :limit
    """)
    fun observePopularChats(since: Long, limit: Int): Flow<List<ChatPopularity>>

    /**
     * Get message counts for unified chats (1:1 chats merged across iMessage/SMS).
     * Sums message counts across all messages linked to each unified chat.
     *
     * Uses the new architecture where messages have unified_chat_id directly.
     *
     * @param since Timestamp (epoch ms) to count messages from
     * @param limit Maximum number of results
     */
    @Query("""
        SELECT
            uc.source_id as chatGuid,
            COUNT(m.guid) as messageCount,
            MAX(m.date_created) as latestMessageDate
        FROM unified_chats uc
        INNER JOIN messages m ON m.unified_chat_id = uc.id
        WHERE m.date_deleted IS NULL
        AND m.date_created >= :since
        AND m.is_reaction = 0
        AND uc.is_archived = 0
        AND uc.date_deleted IS NULL
        GROUP BY uc.id
        ORDER BY messageCount DESC, latestMessageDate DESC
        LIMIT :limit
    """)
    suspend fun getPopularUnifiedChats(since: Long, limit: Int): List<ChatPopularity>

    /**
     * Observe popular unified chats (reactive).
     */
    @Query("""
        SELECT
            uc.source_id as chatGuid,
            COUNT(m.guid) as messageCount,
            MAX(m.date_created) as latestMessageDate
        FROM unified_chats uc
        INNER JOIN messages m ON m.unified_chat_id = uc.id
        WHERE m.date_deleted IS NULL
        AND m.date_created >= :since
        AND m.is_reaction = 0
        AND uc.is_archived = 0
        AND uc.date_deleted IS NULL
        GROUP BY uc.id
        ORDER BY messageCount DESC, latestMessageDate DESC
        LIMIT :limit
    """)
    fun observePopularUnifiedChats(since: Long, limit: Int): Flow<List<ChatPopularity>>

    /**
     * Get popular group chats (multi-participant chats).
     * Separate from unified groups which are 1:1 chats.
     */
    @Query("""
        SELECT
            c.guid as chatGuid,
            COUNT(m.guid) as messageCount,
            MAX(m.date_created) as latestMessageDate
        FROM chats c
        INNER JOIN messages m ON m.chat_guid = c.guid
        LEFT JOIN unified_chats uc ON c.unified_chat_id = uc.id
        WHERE m.date_deleted IS NULL
        AND m.date_created >= :since
        AND m.is_reaction = 0
        AND c.date_deleted IS NULL
        AND (uc.is_archived IS NULL OR uc.is_archived = 0)
        AND c.is_group = 1
        GROUP BY c.guid
        ORDER BY messageCount DESC, latestMessageDate DESC
        LIMIT :limit
    """)
    suspend fun getPopularGroupChats(since: Long, limit: Int): List<ChatPopularity>

    /**
     * Observe popular group chats (reactive).
     */
    @Query("""
        SELECT
            c.guid as chatGuid,
            COUNT(m.guid) as messageCount,
            MAX(m.date_created) as latestMessageDate
        FROM chats c
        INNER JOIN messages m ON m.chat_guid = c.guid
        LEFT JOIN unified_chats uc ON c.unified_chat_id = uc.id
        WHERE m.date_deleted IS NULL
        AND m.date_created >= :since
        AND m.is_reaction = 0
        AND c.date_deleted IS NULL
        AND (uc.is_archived IS NULL OR uc.is_archived = 0)
        AND c.is_group = 1
        GROUP BY c.guid
        ORDER BY messageCount DESC, latestMessageDate DESC
        LIMIT :limit
    """)
    fun observePopularGroupChats(since: Long, limit: Int): Flow<List<ChatPopularity>>
}
