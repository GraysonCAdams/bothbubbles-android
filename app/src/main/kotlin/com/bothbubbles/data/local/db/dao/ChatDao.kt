package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
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

    @Query("SELECT guid FROM chats WHERE date_deleted IS NULL ORDER BY latest_message_date DESC")
    suspend fun getAllChatGuids(): List<String>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND has_unread_message = 1")
    fun getUnreadChatCount(): Flow<Int>

    // ===== Unified Chat Queries =====

    /**
     * Get all non-group chats (for unified chat matching).
     * Excludes deleted chats.
     */
    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 0
        ORDER BY latest_message_date DESC
    """)
    suspend fun getAllNonGroupChats(): List<ChatEntity>

    /**
     * Get all non-group iMessage chats (excludes SMS/MMS).
     * Used for finding existing iMessage chats to link with SMS.
     */
    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL
        AND is_group = 0
        AND guid NOT LIKE 'sms;%'
        AND guid NOT LIKE 'mms;%'
        ORDER BY latest_message_date DESC
    """)
    suspend fun getAllNonGroupIMessageChats(): List<ChatEntity>

    /**
     * Get all group chats (for conversation list that excludes unified groups).
     */
    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 1 AND is_archived = 0
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
    """)
    fun observeActiveGroupChats(): Flow<List<ChatEntity>>

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

    @Query("""
        SELECT guid, fallback_reason AS reason, fallback_updated_at AS updatedAt
        FROM chats
        WHERE is_sms_fallback = 1
    """)
    suspend fun getChatsInFallback(): List<ChatFallbackProjection>

    @Query("""
        UPDATE chats
        SET is_sms_fallback = :isFallback,
            fallback_reason = :reason,
            fallback_updated_at = :updatedAt
        WHERE guid = :guid
    """)
    suspend fun updateFallbackState(
        guid: String,
        isFallback: Boolean,
        reason: String?,
        updatedAt: Long?
    )

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

    @Query("UPDATE chats SET snooze_until = :snoozeUntil WHERE guid = :guid")
    suspend fun updateSnoozeUntil(guid: String, snoozeUntil: Long?)

    @Query("UPDATE chats SET latest_message_date = :date WHERE guid = :guid")
    suspend fun updateLatestMessageDate(guid: String, date: Long)

    @Query("UPDATE chats SET latest_message_date = :date, last_message_text = :text, last_message_date = :date WHERE guid = :guid")
    suspend fun updateLastMessage(guid: String, date: Long, text: String?)

    @Query("UPDATE chats SET display_name = :displayName WHERE guid = :guid")
    suspend fun updateDisplayName(guid: String, displayName: String?)

    @Query("UPDATE chats SET custom_avatar_path = :avatarPath WHERE guid = :guid")
    suspend fun updateCustomAvatarPath(guid: String, avatarPath: String?)

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

    /**
     * Delete all cross-references for chat-handle relationships.
     * Used during purge/reimport.
     */
    @Query("DELETE FROM chat_handle_cross_ref")
    suspend fun deleteAllChatHandleCrossRefs()

    /**
     * Delete all chats from BlueBubbles server (iMessage).
     * Keeps local SMS/MMS chats intact.
     * Use this when disconnecting from the server.
     */
    @Query("""
        DELETE FROM chats
        WHERE guid LIKE 'iMessage%'
        OR (guid NOT LIKE 'sms:%' AND guid NOT LIKE 'mms:%')
    """)
    suspend fun deleteServerChats()

    /**
     * Delete cross-refs for server chats when disconnecting.
     */
    @Query("""
        DELETE FROM chat_handle_cross_ref
        WHERE chat_guid LIKE 'iMessage%'
        OR (chat_guid NOT LIKE 'sms:%' AND chat_guid NOT LIKE 'mms:%')
    """)
    suspend fun deleteServerChatCrossRefs()

    @Query("UPDATE chats SET has_unread_message = 0, unread_count = 0 WHERE has_unread_message = 1")
    suspend fun markAllChatsAsRead(): Int

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND is_archived = 1")
    fun getArchivedChatCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND is_starred = 1")
    fun getStarredChatCount(): Flow<Int>

    // ===== Spam =====

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_spam = 1
        ORDER BY latest_message_date DESC
    """)
    fun getSpamChats(): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND is_spam = 1")
    fun getSpamChatCount(): Flow<Int>

    @Query("UPDATE chats SET is_spam = :isSpam, spam_score = :score WHERE guid = :guid")
    suspend fun updateSpamStatus(guid: String, isSpam: Boolean, score: Int)

    @Query("UPDATE chats SET spam_reported_to_carrier = 1 WHERE guid = :guid")
    suspend fun markAsReportedToCarrier(guid: String)

    @Query("UPDATE chats SET is_spam = 0, spam_score = 0 WHERE guid = :guid")
    suspend fun clearSpamStatus(guid: String)

    // ===== Message Categorization =====

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND category = :category
        ORDER BY latest_message_date DESC
    """)
    fun getChatsByCategory(category: String): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND category = :category")
    fun getChatCountByCategory(category: String): Flow<Int>

    @Query("""
        UPDATE chats
        SET category = :category,
            category_confidence = :confidence,
            category_last_updated = :timestamp
        WHERE guid = :guid
    """)
    suspend fun updateCategory(guid: String, category: String?, confidence: Int, timestamp: Long)

    @Query("UPDATE chats SET category = NULL, category_confidence = 0, category_last_updated = NULL WHERE guid = :guid")
    suspend fun clearCategory(guid: String)

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

data class ChatFallbackProjection(
    val guid: String,
    val reason: String?,
    val updatedAt: Long?
)
