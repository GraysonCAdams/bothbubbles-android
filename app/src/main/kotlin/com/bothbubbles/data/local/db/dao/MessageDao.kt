package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    // ===== Queries =====

    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid AND date_deleted IS NULL
        ORDER BY date_created DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMessagesForChat(chatGuid: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid AND date_deleted IS NULL
        ORDER BY date_created DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observeMessagesForChat(chatGuid: String, limit: Int, offset: Int): Flow<List<MessageEntity>>

    /**
     * Observe messages from multiple chats (for merged iMessage + SMS conversations).
     * Results are sorted by date_created DESC across all chats.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid IN (:chatGuids) AND date_deleted IS NULL
        ORDER BY date_created DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observeMessagesForChats(chatGuids: List<String>, limit: Int, offset: Int): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid AND date_deleted IS NULL
        ORDER BY date_created DESC
    """)
    fun observeAllMessagesForChat(chatGuid: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE guid = :guid")
    suspend fun getMessageByGuid(guid: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE guid = :guid")
    fun observeMessageByGuid(guid: String): Flow<MessageEntity?>

    @Query("""
        SELECT * FROM messages
        WHERE associated_message_guid = :messageGuid AND date_deleted IS NULL
        ORDER BY date_created ASC
    """)
    fun getReactionsForMessage(messageGuid: String): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages
        WHERE thread_originator_guid = :messageGuid AND date_deleted IS NULL
        ORDER BY date_created ASC
    """)
    fun getRepliesForMessage(messageGuid: String): Flow<List<MessageEntity>>

    /**
     * Batch fetch messages by their GUIDs.
     * Used for efficiently loading reply preview data.
     */
    @Query("""
        SELECT * FROM messages
        WHERE guid IN (:guids) AND date_deleted IS NULL
    """)
    suspend fun getMessagesByGuids(guids: List<String>): List<MessageEntity>

    /**
     * Get all messages in a thread (the original message + all replies to it).
     * Used for displaying the thread overlay when user taps a reply indicator.
     */
    @Query("""
        SELECT * FROM messages
        WHERE (guid = :originGuid OR thread_originator_guid = :originGuid)
        AND date_deleted IS NULL
        ORDER BY date_created ASC
    """)
    suspend fun getThreadMessages(originGuid: String): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid AND date_deleted IS NULL
        AND date_created > :afterTimestamp
        ORDER BY date_created ASC
    """)
    suspend fun getMessagesAfter(chatGuid: String, afterTimestamp: Long): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid AND date_deleted IS NULL
        AND date_created < :beforeTimestamp
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    suspend fun getMessagesBefore(chatGuid: String, beforeTimestamp: Long, limit: Int): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE date_deleted IS NULL
        AND (text LIKE '%' || :query || '%' OR subject LIKE '%' || :query || '%')
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    fun searchMessages(query: String, limit: Int = 100): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid AND date_deleted IS NULL
        AND (sms_status IS NULL OR sms_status != 'draft')
        ORDER BY date_created DESC
        LIMIT 1
    """)
    suspend fun getLatestMessageForChat(chatGuid: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE chat_guid = :chatGuid AND date_deleted IS NULL")
    suspend fun getMessageCountForChat(chatGuid: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE date_deleted IS NULL")
    suspend fun getTotalMessageCount(): Int

    /**
     * Get messages that contain URLs for a specific chat.
     * Uses pattern matching for http/https links and www prefixes.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid
        AND date_deleted IS NULL
        AND (text LIKE '%http://%' OR text LIKE '%https://%' OR text LIKE '%www.%')
        ORDER BY date_created DESC
    """)
    fun getMessagesWithUrlsForChat(chatGuid: String): Flow<List<MessageEntity>>

    /**
     * Find a matching message by content and timestamp within a tolerance window.
     * Used to detect duplicate SMS/MMS messages that may have different GUIDs.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid
        AND date_deleted IS NULL
        AND text = :text
        AND is_from_me = :isFromMe
        AND ABS(date_created - :dateCreated) <= :toleranceMs
        LIMIT 1
    """)
    suspend fun findMatchingMessage(
        chatGuid: String,
        text: String?,
        isFromMe: Boolean,
        dateCreated: Long,
        toleranceMs: Long = 5000
    ): MessageEntity?

    // ===== Inserts/Updates =====

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET date_read = :dateRead WHERE guid = :guid")
    suspend fun updateReadStatus(guid: String, dateRead: Long)

    @Query("UPDATE messages SET date_delivered = :dateDelivered WHERE guid = :guid")
    suspend fun updateDeliveryStatus(guid: String, dateDelivered: Long)

    @Query("UPDATE messages SET error = :error WHERE guid = :guid")
    suspend fun updateErrorStatus(guid: String, error: Int)

    @Query("UPDATE messages SET error = :error, sms_error_message = :errorMessage WHERE guid = :guid")
    suspend fun updateMessageError(guid: String, error: Int, errorMessage: String?)

    @Query("UPDATE messages SET text = :text, date_edited = :dateEdited WHERE guid = :guid")
    suspend fun updateMessageText(guid: String, text: String, dateEdited: Long)

    @Query("UPDATE messages SET has_reactions = :hasReactions WHERE guid = :guid")
    suspend fun updateReactionStatus(guid: String, hasReactions: Boolean)

    @Query("UPDATE messages SET message_source = :messageSource WHERE guid = :guid")
    suspend fun updateMessageSource(guid: String, messageSource: String)

    @Query("UPDATE messages SET date_played = :datePlayed WHERE guid = :guid")
    suspend fun updateDatePlayed(guid: String, datePlayed: Long)

    @Query("UPDATE messages SET sms_status = :smsStatus WHERE guid = :guid")
    suspend fun updateSmsStatus(guid: String, smsStatus: String)

    /**
     * Get all local MMS messages that don't have sms_status set (for draft detection migration)
     */
    @Query("""
        SELECT * FROM messages
        WHERE message_source = 'LOCAL_MMS'
        AND sms_status IS NULL
    """)
    suspend fun getLocalMmsWithoutStatus(): List<MessageEntity>

    // Replace temp GUID with server GUID
    @Query("""
        UPDATE messages
        SET guid = :newGuid, error = 0
        WHERE guid = :tempGuid
    """)
    suspend fun replaceGuid(tempGuid: String, newGuid: String)

    // ===== Deletes =====

    @Query("UPDATE messages SET date_deleted = :timestamp WHERE guid = :guid")
    suspend fun softDeleteMessage(guid: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM messages WHERE guid = :guid")
    suspend fun deleteMessage(guid: String)

    @Query("DELETE FROM messages WHERE guid = :guid")
    suspend fun deleteMessageByGuid(guid: String)

    @Query("DELETE FROM messages WHERE chat_guid = :chatGuid")
    suspend fun deleteMessagesForChat(chatGuid: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    /**
     * Delete all messages from BlueBubbles server (iMessage).
     * Keeps local SMS/MMS messages intact.
     * Use this when disconnecting from the server.
     */
    @Query("""
        DELETE FROM messages
        WHERE message_source = 'IMESSAGE'
        OR (message_source = 'UNKNOWN' AND chat_guid LIKE 'iMessage%')
    """)
    suspend fun deleteServerMessages()

    // ===== Transactions =====

    @Transaction
    suspend fun insertOrUpdateMessage(message: MessageEntity) {
        val existing = getMessageByGuid(message.guid)
        if (existing != null) {
            updateMessage(message.copy(id = existing.id))
        } else {
            insertMessage(message)
        }
    }
}
