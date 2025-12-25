package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

/**
 * Group and protocol-based chat query operations.
 *
 * Note: UI state queries (pinned, archived, starred, unread, spam, category) should use
 * [UnifiedChatDao]. This DAO only handles protocol-level group queries.
 */
@Dao
interface ChatGroupDao {

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 1
        ORDER BY latest_message_date DESC
    """)
    suspend fun getAllGroupChats(): List<ChatEntity>

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
        WHERE date_deleted IS NULL
        AND (guid LIKE 'sms;%' OR guid LIKE 'mms;%' OR guid LIKE 'SMS;%' OR guid LIKE 'MMS;%' OR guid LIKE 'RCS;%' OR guid LIKE 'rcs;%')
        ORDER BY latest_message_date DESC
    """)
    suspend fun getAllSmsChats(): List<ChatEntity>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 1 AND unified_chat_id IS NULL
            AND latest_message_date IS NOT NULL
        ORDER BY latest_message_date DESC
    """)
    fun observeGroupChats(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 1 AND unified_chat_id IS NULL
            AND latest_message_date IS NOT NULL
        ORDER BY latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getGroupChatsPaginated(limit: Int, offset: Int): List<ChatEntity>

    @Query("""
        SELECT COUNT(*) FROM chats
        WHERE date_deleted IS NULL AND is_group = 1 AND unified_chat_id IS NULL
            AND latest_message_date IS NOT NULL
    """)
    suspend fun getGroupChatCount(): Int

    @Query("""
        SELECT COUNT(*) FROM chats
        WHERE date_deleted IS NULL AND is_group = 1 AND unified_chat_id IS NULL
            AND latest_message_date IS NOT NULL
    """)
    fun observeGroupChatCount(): Flow<Int>
}
