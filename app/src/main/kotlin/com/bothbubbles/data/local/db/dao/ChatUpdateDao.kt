package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.ChatEntity

/**
 * Update operations for chats.
 * Separated from main ChatDao for better organization.
 */
@Dao
interface ChatUpdateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatEntity>)

    @Update
    suspend fun updateChat(chat: ChatEntity)

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

    @Query("UPDATE chats SET latest_message_date = :date WHERE guid = :guid")
    suspend fun updateLatestMessageDate(guid: String, date: Long)

    @Query("UPDATE chats SET latest_message_date = :date, last_message_text = :text, last_message_date = :date WHERE guid = :guid")
    suspend fun updateLastMessage(guid: String, date: Long, text: String?)

    @Query("UPDATE chats SET display_name = :displayName WHERE guid = :guid")
    suspend fun updateDisplayName(guid: String, displayName: String?)

    @Query("UPDATE chats SET custom_avatar_path = :avatarPath WHERE guid = :guid")
    suspend fun updateCustomAvatarPath(guid: String, avatarPath: String?)

    @Query("""
        UPDATE chats
        SET server_group_photo_guid = :photoGuid,
            server_group_photo_path = :photoPath
        WHERE guid = :guid
    """)
    suspend fun updateServerGroupPhoto(guid: String, photoGuid: String?, photoPath: String?)

    @Query("UPDATE chats SET text_field_text = :text WHERE guid = :guid")
    suspend fun updateDraftText(guid: String, text: String?)

    @Query("UPDATE chats SET unread_count = :count WHERE guid = :guid")
    suspend fun updateUnreadCount(guid: String, count: Int)

    @Query("UPDATE chats SET unread_count = unread_count + 1 WHERE guid = :guid")
    suspend fun incrementUnreadCount(guid: String)

    @Query("UPDATE chats SET has_unread_message = 0, unread_count = 0 WHERE has_unread_message = 1 OR unread_count > 0")
    suspend fun markAllChatsAsRead(): Int

    @Query("""
        UPDATE chats
        SET display_name = NULL
        WHERE display_name LIKE '%(sms%)%'
           OR display_name LIKE '%(ft%)%'
           OR (LENGTH(display_name) BETWEEN 5 AND 8 AND display_name GLOB '[0-9a-z][0-9a-z]*' AND display_name NOT GLOB '*[A-Z ]*')
    """)
    suspend fun clearInvalidDisplayNames(): Int

    @Query("""
        UPDATE chats
        SET preferred_send_mode = :mode, send_mode_manually_set = :manuallySet
        WHERE guid = :guid
    """)
    suspend fun updatePreferredSendMode(guid: String, mode: String?, manuallySet: Boolean)
}
