package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

/**
 * Group and unified chat query operations.
 * Separated from main ChatDao for better organization.
 */
@Dao
interface ChatGroupDao {

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 0
        ORDER BY latest_message_date DESC
    """)
    suspend fun getAllNonGroupChats(): List<ChatEntity>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL
        AND is_group = 0
        AND guid NOT LIKE 'sms;%'
        AND guid NOT LIKE 'mms;%'
        ORDER BY latest_message_date DESC
    """)
    suspend fun getAllNonGroupIMessageChats(): List<ChatEntity>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 1 AND is_archived = 0
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
    """)
    fun observeActiveGroupChats(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 1 AND is_archived = 0
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getGroupChatsPaginated(limit: Int, offset: Int): List<ChatEntity>

    @Query("""
        SELECT COUNT(*) FROM chats
        WHERE date_deleted IS NULL AND is_group = 1 AND is_archived = 0
    """)
    suspend fun getGroupChatCount(): Int

    @Query("""
        SELECT COUNT(*) FROM chats
        WHERE date_deleted IS NULL AND is_group = 1 AND is_archived = 0
    """)
    fun observeGroupChatCount(): Flow<Int>

    // ===== Filtered Queries for Select All Feature =====

    /**
     * Get count of group chats matching filter criteria.
     * Used for Gmail-style "Select All" to count all matching conversations.
     * Group chats are always considered "known senders".
     * Includes both pinned and non-pinned group chats.
     */
    @Query("""
        SELECT COUNT(*) FROM chats
        WHERE date_deleted IS NULL
        AND is_group = 1
        AND is_archived = 0
        AND (
            (:includeSpam = 1 AND is_spam = 1)
            OR (:includeSpam = 0 AND is_spam = 0)
        )
        AND (:unreadOnly = 0 OR unread_count > 0)
        AND (:category IS NULL OR category = :category)
    """)
    suspend fun getFilteredGroupChatCount(
        includeSpam: Boolean,
        unreadOnly: Boolean,
        category: String?
    ): Int

    /**
     * Get GUIDs of group chats matching filter criteria (paginated).
     * Used for batch operations in Gmail-style "Select All".
     * Includes both pinned and non-pinned group chats.
     */
    @Query("""
        SELECT guid FROM chats
        WHERE date_deleted IS NULL
        AND is_group = 1
        AND is_archived = 0
        AND (
            (:includeSpam = 1 AND is_spam = 1)
            OR (:includeSpam = 0 AND is_spam = 0)
        )
        AND (:unreadOnly = 0 OR unread_count > 0)
        AND (:category IS NULL OR category = :category)
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFilteredGroupChatGuids(
        includeSpam: Boolean,
        unreadOnly: Boolean,
        category: String?,
        limit: Int,
        offset: Int
    ): List<String>
}
