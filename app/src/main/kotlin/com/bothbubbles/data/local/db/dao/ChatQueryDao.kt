package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

/**
 * Basic query operations for chats.
 * Separated from main ChatDao for better organization.
 */
@Dao
interface ChatQueryDao {

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
    """)
    fun getAllChats(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 0 AND is_archived = 0
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getNonGroupChatsPaginated(limit: Int, offset: Int): List<ChatEntity>

    @Query("""
        SELECT COUNT(*) FROM chats
        WHERE date_deleted IS NULL AND is_group = 0 AND is_archived = 0
    """)
    suspend fun getNonGroupChatCount(): Int

    @Query("""
        SELECT COUNT(*) FROM chats
        WHERE date_deleted IS NULL AND is_group = 0 AND is_archived = 0
    """)
    fun observeNonGroupChatCount(): Flow<Int>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_pinned = 1
        ORDER BY pin_index ASC
    """)
    fun getPinnedChats(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_archived = 0
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
    """)
    fun getActiveChats(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_archived = 1
        ORDER BY latest_message_date DESC
    """)
    fun getArchivedChats(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_starred = 1
        ORDER BY latest_message_date DESC
    """)
    fun getStarredChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE guid = :guid")
    suspend fun getChatByGuid(guid: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE guid IN (:guids)")
    suspend fun getChatsByGuids(guids: List<String>): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE guid = :guid")
    fun observeChatByGuid(guid: String): Flow<ChatEntity?>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL
        AND (display_name LIKE '%' || :query || '%'
             OR chat_identifier LIKE '%' || :query || '%')
        ORDER BY latest_message_date DESC
    """)
    fun searchChats(query: String): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL
        AND is_group = 1
        AND (display_name LIKE '%' || :query || '%'
             OR chat_identifier LIKE '%' || :query || '%')
        ORDER BY latest_message_date DESC
        LIMIT 10
    """)
    fun searchGroupChats(query: String): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL
        AND is_group = 1
        ORDER BY latest_message_date DESC
        LIMIT 5
    """)
    fun getRecentGroupChats(): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL")
    suspend fun getChatCount(): Int

    @Query("SELECT guid FROM chats WHERE date_deleted IS NULL ORDER BY latest_message_date DESC")
    suspend fun getAllChatGuids(): List<String>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_archived = 0
        ORDER BY latest_message_date DESC
        LIMIT :limit
    """)
    suspend fun getRecentChats(limit: Int): List<ChatEntity>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND has_unread_message = 1")
    fun getUnreadChatCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND is_archived = 1")
    fun getArchivedChatCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND is_starred = 1")
    fun getStarredChatCount(): Flow<Int>
}
