package com.bluebubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.ChatHandleCrossRef
import com.bluebubbles.data.local.db.entity.HandleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // ===== Queries =====

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
    """)
    fun getAllChats(): Flow<List<ChatEntity>>

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

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND has_unread_message = 1")
    fun getUnreadChatCount(): Flow<Int>

    // ===== Participants =====

    @Query("""
        SELECT h.* FROM handles h
        INNER JOIN chat_handle_cross_ref chr ON h.id = chr.handle_id
        WHERE chr.chat_guid = :chatGuid
    """)
    suspend fun getParticipantsForChat(chatGuid: String): List<HandleEntity>

    @Query("""
        SELECT h.* FROM handles h
        INNER JOIN chat_handle_cross_ref chr ON h.id = chr.handle_id
        WHERE chr.chat_guid = :chatGuid
    """)
    fun observeParticipantsForChat(chatGuid: String): Flow<List<HandleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatHandleCrossRef(crossRef: ChatHandleCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatHandleCrossRefs(crossRefs: List<ChatHandleCrossRef>)

    @Query("DELETE FROM chat_handle_cross_ref WHERE chat_guid = :chatGuid")
    suspend fun deleteParticipantsForChat(chatGuid: String)

    // ===== Inserts/Updates =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatEntity>)

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Query("UPDATE chats SET is_pinned = :isPinned, pin_index = :pinIndex WHERE guid = :guid")
    suspend fun updatePinStatus(guid: String, isPinned: Boolean, pinIndex: Int?)

    @Query("UPDATE chats SET is_archived = :isArchived WHERE guid = :guid")
    suspend fun updateArchiveStatus(guid: String, isArchived: Boolean)

    @Query("UPDATE chats SET is_starred = :isStarred WHERE guid = :guid")
    suspend fun updateStarredStatus(guid: String, isStarred: Boolean)

    @Query("UPDATE chats SET has_unread_message = :hasUnread WHERE guid = :guid")
    suspend fun updateUnreadStatus(guid: String, hasUnread: Boolean)

    @Query("UPDATE chats SET mute_type = :muteType, mute_args = :muteArgs WHERE guid = :guid")
    suspend fun updateMuteStatus(guid: String, muteType: String?, muteArgs: String?)

    // ===== Notification Settings =====

    @Query("UPDATE chats SET notifications_enabled = :enabled WHERE guid = :guid")
    suspend fun updateNotificationsEnabled(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET notification_priority = :priority WHERE guid = :guid")
    suspend fun updateNotificationPriority(guid: String, priority: String)

    @Query("UPDATE chats SET bubble_enabled = :enabled WHERE guid = :guid")
    suspend fun updateBubbleEnabled(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET pop_on_screen = :enabled WHERE guid = :guid")
    suspend fun updatePopOnScreen(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET custom_notification_sound = :sound WHERE guid = :guid")
    suspend fun updateNotificationSound(guid: String, sound: String?)

    @Query("UPDATE chats SET lock_screen_visibility = :visibility WHERE guid = :guid")
    suspend fun updateLockScreenVisibility(guid: String, visibility: String)

    @Query("UPDATE chats SET show_notification_dot = :enabled WHERE guid = :guid")
    suspend fun updateShowNotificationDot(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET vibration_enabled = :enabled WHERE guid = :guid")
    suspend fun updateVibrationEnabled(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET latest_message_date = :date WHERE guid = :guid")
    suspend fun updateLatestMessageDate(guid: String, date: Long)

    @Query("UPDATE chats SET latest_message_date = :date, last_message_text = :text, last_message_date = :date WHERE guid = :guid")
    suspend fun updateLastMessage(guid: String, date: Long, text: String?)

    @Query("UPDATE chats SET display_name = :displayName WHERE guid = :guid")
    suspend fun updateDisplayName(guid: String, displayName: String?)

    @Query("UPDATE chats SET text_field_text = :text WHERE guid = :guid")
    suspend fun updateDraftText(guid: String, text: String?)

    // ===== Deletes =====

    @Query("UPDATE chats SET date_deleted = :timestamp WHERE guid = :guid")
    suspend fun softDeleteChat(guid: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET date_deleted = NULL WHERE guid = :guid")
    suspend fun restoreChat(guid: String)

    @Query("DELETE FROM chats WHERE guid = :guid")
    suspend fun deleteChat(guid: String)

    @Query("DELETE FROM chats WHERE guid = :guid")
    suspend fun deleteChatByGuid(guid: String)

    @Query("UPDATE chats SET unread_count = :count WHERE guid = :guid")
    suspend fun updateUnreadCount(guid: String, count: Int)

    @Query("DELETE FROM chats")
    suspend fun deleteAllChats()

    @Query("UPDATE chats SET has_unread_message = 0, unread_count = 0 WHERE has_unread_message = 1")
    suspend fun markAllChatsAsRead(): Int

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND is_archived = 1")
    fun getArchivedChatCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND is_starred = 1")
    fun getStarredChatCount(): Flow<Int>

    // ===== Transactions =====

    @Transaction
    suspend fun insertChatWithParticipants(chat: ChatEntity, participantIds: List<Long>) {
        insertChat(chat)
        val crossRefs = participantIds.map { ChatHandleCrossRef(chat.guid, it) }
        insertChatHandleCrossRefs(crossRefs)
    }

    @Transaction
    suspend fun updateChatParticipants(chatGuid: String, participantIds: List<Long>) {
        deleteParticipantsForChat(chatGuid)
        val crossRefs = participantIds.map { ChatHandleCrossRef(chatGuid, it) }
        insertChatHandleCrossRefs(crossRefs)
    }
}
