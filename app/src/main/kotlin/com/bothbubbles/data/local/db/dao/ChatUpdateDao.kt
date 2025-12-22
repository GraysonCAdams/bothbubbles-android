package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.ChatEntity

/**
 * Update operations for chats (protocol channels).
 *
 * Note: UI state updates (pinned, archived, starred, unread, mute, etc.) should use
 * [UnifiedChatDao] instead. This DAO only handles protocol-level updates.
 */
@Dao
interface ChatUpdateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatEntity>)

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Query("UPDATE chats SET latest_message_date = :date WHERE guid = :guid")
    suspend fun updateLatestMessageDate(guid: String, date: Long)

    @Query("UPDATE chats SET display_name = :displayName WHERE guid = :guid")
    suspend fun updateDisplayName(guid: String, displayName: String?)

    @Query("""
        UPDATE chats
        SET display_name = NULL
        WHERE display_name LIKE '%(sms%)%'
           OR display_name LIKE '%(ft%)%'
           OR (LENGTH(display_name) BETWEEN 5 AND 8 AND display_name GLOB '[0-9a-z][0-9a-z]*' AND display_name NOT GLOB '*[A-Z ]*')
    """)
    suspend fun clearInvalidDisplayNames(): Int

    @Query("UPDATE chats SET unified_chat_id = :unifiedChatId WHERE guid = :guid")
    suspend fun updateUnifiedChatId(guid: String, unifiedChatId: String)

    @Query("UPDATE chats SET unified_chat_id = :unifiedChatId WHERE guid IN (:guids)")
    suspend fun updateUnifiedChatIdForChats(guids: List<String>, unifiedChatId: String)
}
