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
}
