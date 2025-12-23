package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
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
     * Get count of thread replies for a message.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE thread_originator_guid = :messageGuid AND date_deleted IS NULL
    """)
    suspend fun getReplyCountForMessage(messageGuid: String): Int

    /**
     * Get reactions for a message (one-time suspend version).
     */
    @Query("""
        SELECT * FROM messages
        WHERE is_reaction = 1 AND date_deleted IS NULL
        AND (
            associated_message_guid = :messageGuid
            OR (
                associated_message_guid LIKE 'p:%/%'
                AND SUBSTR(associated_message_guid, INSTR(associated_message_guid, '/') + 1) = :messageGuid
            )
        )
        ORDER BY date_created ASC
    """)
    suspend fun getReactionsForMessageOnce(messageGuid: String): List<MessageEntity>

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

    /**
     * Cursor-based pagination using timestamp instead of OFFSET.
     * O(1) performance regardless of how far back in the conversation.
     * Use this for loading older messages during scroll.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid AND date_deleted IS NULL
        AND date_created < :beforeTimestamp
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    fun observeMessagesBefore(chatGuid: String, beforeTimestamp: Long, limit: Int): Flow<List<MessageEntity>>

    /**
     * Cursor-based pagination for merged chats (iMessage + SMS).
     * O(1) performance regardless of conversation size.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid IN (:chatGuids) AND date_deleted IS NULL
        AND date_created < :beforeTimestamp
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    fun observeMessagesBeforeForChats(chatGuids: List<String>, beforeTimestamp: Long, limit: Int): Flow<List<MessageEntity>>

    /**
     * Full-text search for messages using FTS5 index.
     * Provides O(log n) performance compared to O(n) with LIKE '%query%'.
     * For 100K+ messages, this is 50-100x faster.
     *
     * Note: The query is preprocessed to handle FTS5 special characters and add wildcards.
     * Use searchMessagesWithPreprocessing() for the full implementation.
     */
    @SkipQueryVerification // FTS5 virtual table created in migration, Room can't validate at compile time
    @Query("""
        SELECT m.* FROM messages m
        INNER JOIN message_fts ON message_fts.rowid = m.id
        WHERE message_fts MATCH :query
        AND m.date_deleted IS NULL
        ORDER BY m.date_created DESC
        LIMIT :limit
    """)
    fun searchMessagesFts(query: String, limit: Int = 100): Flow<List<MessageEntity>>

    /**
     * Fallback LIKE-based search for queries that can't be used with FTS5.
     * Used when FTS5 query fails (e.g., special characters, empty query).
     */
    @Query("""
        SELECT * FROM messages
        WHERE date_deleted IS NULL
        AND (text LIKE '%' || :query || '%' OR subject LIKE '%' || :query || '%')
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    fun searchMessagesLike(query: String, limit: Int = 100): Flow<List<MessageEntity>>

    /**
     * Search messages by text content using LIKE-based search.
     * This is the primary search method used by the UI.
     */
    @Query("""
        SELECT * FROM messages
        WHERE date_deleted IS NULL
        AND (text LIKE '%' || :query || '%' OR subject LIKE '%' || :query || '%')
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    fun searchMessages(query: String, limit: Int = 100): Flow<List<MessageEntity>>

    /**
     * Search messages by text content with optional date range filtering.
     * @param query The search query (matched against text and subject)
     * @param startDate Start of date range in milliseconds (null = no lower bound)
     * @param endDate End of date range in milliseconds (null = no upper bound)
     * @param limit Maximum number of results to return
     */
    @Query("""
        SELECT * FROM messages
        WHERE date_deleted IS NULL
        AND (text LIKE '%' || :query || '%' OR subject LIKE '%' || :query || '%')
        AND (:startDate IS NULL OR date_created >= :startDate)
        AND (:endDate IS NULL OR date_created <= :endDate)
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    fun searchMessagesInDateRange(
        query: String,
        startDate: Long?,
        endDate: Long?,
        limit: Int = 100
    ): Flow<List<MessageEntity>>

    /**
     * Get messages within a date range (no text query required).
     * Use this for date-only browsing of messages.
     * @param startDate Start of date range in milliseconds
     * @param endDate End of date range in milliseconds
     * @param limit Maximum number of results to return
     */
    @Query("""
        SELECT * FROM messages
        WHERE date_deleted IS NULL
        AND date_created >= :startDate
        AND date_created <= :endDate
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    fun getMessagesInDateRange(
        startDate: Long,
        endDate: Long,
        limit: Int = 100
    ): Flow<List<MessageEntity>>

    /**
     * Full-text search for messages within specific chats using FTS5 index.
     * Uses FTS5 for O(log n) performance on large conversation histories.
     *
     * @param query The search query (will be matched against text and subject)
     * @param chatGuids List of chat GUIDs to search within
     * @param limit Maximum number of results to return
     */
    @SkipQueryVerification // FTS5 virtual table created in migration
    @Query("""
        SELECT m.* FROM messages m
        INNER JOIN message_fts ON message_fts.rowid = m.id
        WHERE message_fts MATCH :query
        AND m.chat_guid IN (:chatGuids)
        AND m.date_deleted IS NULL
        ORDER BY m.date_created DESC
        LIMIT :limit
    """)
    suspend fun searchMessagesInChatsFts(query: String, chatGuids: List<String>, limit: Int = 100): List<MessageEntity>

    /**
     * LIKE-based search for messages within specific chats.
     * Fallback when FTS5 query fails (special characters, etc.).
     *
     * @param query The search query
     * @param chatGuids List of chat GUIDs to search within
     * @param limit Maximum number of results to return
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid IN (:chatGuids)
        AND date_deleted IS NULL
        AND (text LIKE '%' || :query || '%' OR subject LIKE '%' || :query || '%')
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    suspend fun searchMessagesInChatsLike(query: String, chatGuids: List<String>, limit: Int = 100): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid AND date_deleted IS NULL
        AND (sms_status IS NULL OR sms_status != 'draft')
        ORDER BY date_created DESC
        LIMIT 1
    """)
    suspend fun getLatestMessageForChat(chatGuid: String): MessageEntity?

    /**
     * Batch fetch the latest message for multiple chats in a single query.
     * Uses a correlated subquery to get the most recent message per chat.
     * Much more efficient than calling getLatestMessageForChat N times.
     */
    @Query("""
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT chat_guid, MAX(date_created) as max_date
            FROM messages
            WHERE chat_guid IN (:chatGuids)
            AND date_deleted IS NULL
            AND (sms_status IS NULL OR sms_status != 'draft')
            GROUP BY chat_guid
        ) latest ON m.chat_guid = latest.chat_guid AND m.date_created = latest.max_date
        WHERE m.date_deleted IS NULL
    """)
    suspend fun getLatestMessagesForChats(chatGuids: List<String>): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE chat_guid = :chatGuid AND date_deleted IS NULL")
    suspend fun getMessageCountForChat(chatGuid: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE date_deleted IS NULL")
    suspend fun getTotalMessageCount(): Int

    /**
     * Get recent messages that have text content.
     * Used for scanning messages for social media links to auto-cache.
     * Orders by most recent first.
     */
    @Query("""
        SELECT * FROM messages
        WHERE date_deleted IS NULL
        AND text IS NOT NULL
        AND text != ''
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    suspend fun getRecentMessagesWithText(limit: Int): List<MessageEntity>

    // ===== BitSet Pagination Queries =====
    // Note: These queries EXCLUDE reactions to ensure count/position consistency.
    // Uses the denormalized is_reaction column for efficient filtering.
    // @see ReactionClassifier for the centralized reaction detection logic.

    /**
     * Get total message count for multiple chats (for merged iMessage + SMS conversations).
     * Used to initialize the BitSet size for sparse pagination.
     * EXCLUDES reactions to ensure count matches actual message positions.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE chat_guid IN (:chatGuids)
        AND date_deleted IS NULL
        AND is_reaction = 0
    """)
    suspend fun getMessageCountForChats(chatGuids: List<String>): Int

    /**
     * Observe message count changes for multiple chats.
     * Emits when messages are added/deleted, used to resize BitSet.
     * EXCLUDES reactions to ensure count matches actual message positions.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE chat_guid IN (:chatGuids)
        AND date_deleted IS NULL
        AND is_reaction = 0
    """)
    fun observeMessageCountForChats(chatGuids: List<String>): Flow<Int>

    // ===== Unified Chat Pagination Queries =====
    // These queries use unified_chat_id for native pagination across merged conversations.
    // No stream merging required - database handles sorting across all protocol channels.

    /**
     * Main pagination query for unified chats.
     * Fetches messages across all protocol channels (iMessage + SMS) with a single query.
     * EXCLUDES reactions (displayed as overlays on parent messages).
     *
     * @param unifiedChatId The unified conversation identifier
     * @param limit Dynamic limit that grows as user scrolls up
     */
    @Query("""
        SELECT * FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND date_deleted IS NULL
        AND is_reaction = 0
        ORDER BY date_created DESC, guid DESC
        LIMIT :limit
    """)
    fun observeMessagesForUnifiedChat(unifiedChatId: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * One-shot fetch of messages for a unified chat.
     */
    @Query("""
        SELECT * FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND date_deleted IS NULL
        ORDER BY date_created DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMessagesForUnifiedChat(unifiedChatId: String, limit: Int, offset: Int): List<MessageEntity>

    /**
     * Get message count for a unified conversation.
     * EXCLUDES reactions to match pagination count.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND date_deleted IS NULL
        AND is_reaction = 0
    """)
    suspend fun getMessageCountForUnifiedChat(unifiedChatId: String): Int

    /**
     * Observe message count for a unified conversation.
     * Emits when messages are added/deleted.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND date_deleted IS NULL
        AND is_reaction = 0
    """)
    fun observeMessageCountForUnifiedChat(unifiedChatId: String): Flow<Int>

    /**
     * Cursor-based pagination for unified chats.
     * Fetches messages older than a given timestamp.
     */
    @Query("""
        SELECT * FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND date_deleted IS NULL
        AND is_reaction = 0
        AND date_created < :beforeTimestamp
        ORDER BY date_created DESC, guid DESC
        LIMIT :limit
    """)
    suspend fun getMessagesBeforeForUnifiedChat(
        unifiedChatId: String,
        beforeTimestamp: Long,
        limit: Int
    ): List<MessageEntity>

    /**
     * Get messages after a timestamp for a unified chat (for polling new messages).
     */
    @Query("""
        SELECT * FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND date_deleted IS NULL
        AND date_created > :afterTimestamp
        ORDER BY date_created ASC
    """)
    suspend fun getMessagesAfterForUnifiedChat(unifiedChatId: String, afterTimestamp: Long): List<MessageEntity>

    /**
     * Observe messages within a time window for a unified chat.
     * Used for jump-to-message functionality.
     */
    @Query("""
        SELECT * FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND date_deleted IS NULL
        AND is_reaction = 0
        AND date_created >= :windowStart
        AND date_created <= :windowEnd
        ORDER BY date_created DESC, guid DESC
    """)
    fun observeMessagesInWindowForUnifiedChat(
        unifiedChatId: String,
        windowStart: Long,
        windowEnd: Long
    ): Flow<List<MessageEntity>>

    /**
     * Get the latest message for a unified chat.
     * Used for updating the conversation preview.
     */
    @Query("""
        SELECT * FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND date_deleted IS NULL
        AND (sms_status IS NULL OR sms_status != 'draft')
        ORDER BY date_created DESC
        LIMIT 1
    """)
    suspend fun getLatestMessageForUnifiedChat(unifiedChatId: String): MessageEntity?

    /**
     * Search messages within a unified conversation.
     */
    @Query("""
        SELECT * FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND date_deleted IS NULL
        AND (text LIKE '%' || :query || '%' OR subject LIKE '%' || :query || '%')
        ORDER BY date_created DESC
        LIMIT :limit
    """)
    suspend fun searchMessagesInUnifiedChat(
        unifiedChatId: String,
        query: String,
        limit: Int = 100
    ): List<MessageEntity>

    /**
     * Get reactions for messages in a unified chat.
     */
    @Query("""
        SELECT * FROM messages
        WHERE unified_chat_id = :unifiedChatId
        AND is_reaction = 1
        AND (
            associated_message_guid IN (:messageGuids)
            OR (
                associated_message_guid LIKE 'p:%/%'
                AND SUBSTR(associated_message_guid, INSTR(associated_message_guid, '/') + 1) IN (:messageGuids)
            )
        )
    """)
    suspend fun getReactionsForMessagesInUnifiedChat(
        unifiedChatId: String,
        messageGuids: List<String>
    ): List<MessageEntity>

    /**
     * Update the unified_chat_id for all messages in a chat.
     * Used when linking a chat to a unified conversation.
     */
    @Query("UPDATE messages SET unified_chat_id = :unifiedChatId WHERE chat_guid = :chatGuid")
    suspend fun setUnifiedChatIdForChat(chatGuid: String, unifiedChatId: String)

    // ===== Cursor-Based Pagination Queries =====
    // These queries support the new cursor-based pagination model where Room is the
    // single source of truth. The growing query limit pattern replaces BitSet pagination.

    /**
     * Observe recent messages with a dynamic limit (cursor-based pagination).
     * The main driver for the chat message list.
     *
     * Key features:
     * - ORDER BY date_created DESC, guid DESC for deterministic ordering
     * - Room Flow automatically emits on INSERT/UPDATE
     * - EXCLUDES reactions (displayed as overlays on parent messages)
     *
     * @param chatGuids List of chat GUIDs (supports merged iMessage + SMS)
     * @param limit Dynamic limit that grows as user scrolls up
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid IN (:chatGuids)
        AND date_deleted IS NULL
        AND is_reaction = 0
        ORDER BY date_created DESC, guid DESC
        LIMIT :limit
    """)
    fun observeRecentMessages(chatGuids: List<String>, limit: Int): Flow<List<MessageEntity>>

    /**
     * Observe messages within a time window (for Archive/Jump-to-message mode).
     * Used when user taps a search result or deep link to jump to a specific message.
     *
     * @param chatGuids List of chat GUIDs
     * @param windowStart Start of time window (targetTimestamp - windowMs)
     * @param windowEnd End of time window (targetTimestamp + windowMs)
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid IN (:chatGuids)
        AND date_deleted IS NULL
        AND is_reaction = 0
        AND date_created >= :windowStart
        AND date_created <= :windowEnd
        ORDER BY date_created DESC, guid DESC
    """)
    fun observeMessagesInWindow(
        chatGuids: List<String>,
        windowStart: Long,
        windowEnd: Long
    ): Flow<List<MessageEntity>>

    /**
     * Count messages for cursor pagination (includes reactions in count).
     * Used to determine if more messages are available locally vs need server fetch.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE chat_guid IN (:chatGuids)
        AND date_deleted IS NULL
        AND is_reaction = 0
    """)
    suspend fun countMessagesForCursor(chatGuids: List<String>): Int

    /**
     * Position-based pagination for BitSet sparse loading.
     * Fetches messages at specific positions (used when BitSet detects gaps).
     * Uses OFFSET which is O(n), but only called for sparse gaps, not continuous scroll.
     * EXCLUDES reactions to ensure positions match the count.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid IN (:chatGuids)
        AND date_deleted IS NULL
        AND is_reaction = 0
        ORDER BY date_created DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMessagesByPosition(chatGuids: List<String>, limit: Int, offset: Int): List<MessageEntity>

    /**
     * Get reactions for a list of messages.
     * Used by RoomMessageDataSource to attach reactions to their parent messages.
     *
     * Note: iMessage stores associated_message_guid with a part prefix like "p:0/GUID".
     * This query handles both formats:
     * - Direct GUID match (e.g., "ABC123")
     * - Prefixed GUID match (e.g., "p:0/ABC123" -> extracts "ABC123")
     */
    @Query("""
        SELECT * FROM messages
        WHERE is_reaction = 1
        AND (
            associated_message_guid IN (:messageGuids)
            OR (
                associated_message_guid LIKE 'p:%/%'
                AND SUBSTR(associated_message_guid, INSTR(associated_message_guid, '/') + 1) IN (:messageGuids)
            )
        )
    """)
    suspend fun getReactionsForMessages(messageGuids: List<String>): List<MessageEntity>

    /**
     * Get the position (index) of a specific message within the sorted conversation.
     * Returns count of messages newer than the target, which equals its position.
     * Used for jump-to-message (search results, deep links).
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE chat_guid IN (:chatGuids) AND date_deleted IS NULL
        AND date_created > (SELECT date_created FROM messages WHERE guid = :targetGuid)
    """)
    suspend fun getMessagePosition(chatGuids: List<String>, targetGuid: String): Int

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
     * Get messages that contain URLs for a specific chat with pagination.
     * Uses pattern matching for http/https links and www prefixes.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid
        AND date_deleted IS NULL
        AND (text LIKE '%http://%' OR text LIKE '%https://%' OR text LIKE '%www.%')
        ORDER BY date_created DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMessagesWithUrlsForChatPaged(
        chatGuid: String,
        limit: Int,
        offset: Int
    ): List<MessageEntity>

    /**
     * Count messages that contain URLs for a specific chat.
     * Used for pagination to know total available.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE chat_guid = :chatGuid
        AND date_deleted IS NULL
        AND (text LIKE '%http://%' OR text LIKE '%https://%' OR text LIKE '%www.%')
    """)
    suspend fun countMessagesWithUrlsForChat(chatGuid: String): Int

    /**
     * Find messages containing social media URLs (Instagram Reels/posts, TikTok).
     * Used for recovering metadata for orphaned cached videos.
     */
    @Query("""
        SELECT * FROM messages
        WHERE date_deleted IS NULL
        AND (
            text LIKE '%instagram.com/reel/%'
            OR text LIKE '%instagram.com/p/%'
            OR text LIKE '%instagram.com/reels/%'
            OR text LIKE '%tiktok.com/%/video/%'
            OR text LIKE '%vm.tiktok.com/%'
        )
        ORDER BY date_created DESC
    """)
    suspend fun getMessagesWithSocialMediaUrls(): List<MessageEntity>

    /**
     * Find messages containing social media URLs for a specific chat.
     * Used for showing pending (not-yet-downloaded) videos in the Reels feed.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid
        AND date_deleted IS NULL
        AND (
            text LIKE '%instagram.com/reel/%'
            OR text LIKE '%instagram.com/p/%'
            OR text LIKE '%instagram.com/reels/%'
            OR text LIKE '%instagram.com/share/reel/%'
            OR text LIKE '%instagram.com/share/p/%'
            OR text LIKE '%tiktok.com/%/video/%'
            OR text LIKE '%vm.tiktok.com/%'
            OR text LIKE '%vt.tiktok.com/%'
        )
        ORDER BY date_created DESC
    """)
    suspend fun getMessagesWithSocialMediaUrlsForChat(chatGuid: String): List<MessageEntity>

    /**
     * Find a matching message by content and timestamp within a tolerance window.
     * Used to detect duplicate SMS/MMS messages that may have different GUIDs.
     * Handles NULL text properly (for attachment-only messages).
     * Excludes the message with the given excludeGuid (if provided).
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_guid = :chatGuid
        AND date_deleted IS NULL
        AND (text = :text OR (text IS NULL AND :text IS NULL))
        AND is_from_me = :isFromMe
        AND ABS(date_created - :dateCreated) <= :toleranceMs
        AND (:excludeGuid IS NULL OR guid != :excludeGuid)
        LIMIT 1
    """)
    suspend fun findMatchingMessage(
        chatGuid: String,
        text: String?,
        isFromMe: Boolean,
        dateCreated: Long,
        toleranceMs: Long = 5000,
        excludeGuid: String? = null
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

    // Replace temp GUID with server GUID (internal use only - use replaceGuidSafe instead)
    @Query("""
        UPDATE messages
        SET guid = :newGuid, error = 0
        WHERE guid = :tempGuid
    """)
    suspend fun replaceGuidDirect(tempGuid: String, newGuid: String)

    /**
     * Safely replace a temp GUID with the server GUID.
     * Handles the race condition where the server message arrives via socket
     * before the HTTP response, which would cause a UNIQUE constraint violation.
     */
    @Transaction
    suspend fun replaceGuid(tempGuid: String, newGuid: String) {
        // Check if a message with the new GUID already exists (race condition - socket arrived first)
        val existingMessage = getMessageByGuid(newGuid)
        if (existingMessage != null) {
            // Socket event already inserted the real message, just delete the temp
            deleteMessage(tempGuid)
        } else {
            // Normal case - update the temp message with the real GUID
            try {
                replaceGuidDirect(tempGuid, newGuid)
            } catch (e: Exception) {
                // Race condition hit: Socket inserted message while we were checking.
                // The UNIQUE constraint failed, so we know the server message exists.
                // Safe to delete temp.
                deleteMessage(tempGuid)
            }
        }
    }

    /**
     * Find orphaned temp messages that are older than a threshold.
     * Used for cleanup of messages that weren't properly replaced.
     */
    @Query("""
        SELECT * FROM messages
        WHERE guid LIKE 'temp-%'
        AND is_from_me = 1
        AND date_created < :beforeTimestamp
    """)
    suspend fun getOrphanedTempMessages(beforeTimestamp: Long): List<MessageEntity>

    // ===== Deletes =====

    @Query("UPDATE messages SET date_deleted = :timestamp WHERE guid = :guid")
    suspend fun softDeleteMessage(guid: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE messages SET date_deleted = :timestamp WHERE guid IN (:guids)")
    suspend fun softDeleteMessages(guids: List<String>, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM messages WHERE guid = :guid")
    suspend fun deleteMessage(guid: String)

    @Query("DELETE FROM messages WHERE chat_guid = :chatGuid")
    suspend fun deleteMessagesForChat(chatGuid: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    /**
     * Check if the user has sent any iMessage to a specific address.
     * Used by auto-responder to avoid sending if user already replied via iMessage.
     *
     * @param address The recipient's phone number or email
     * @return true if user has sent at least one iMessage to this address
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM messages
            WHERE chat_guid LIKE 'iMessage;-;%' || :address || '%'
            AND is_from_me = 1
            AND date_deleted IS NULL
            LIMIT 1
        )
    """)
    suspend fun hasOutboundIMessageToAddress(address: String): Boolean

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

    @Transaction
    suspend fun insertMessagesAndRecordSync(
        messages: List<MessageEntity>,
        chatGuid: String,
        syncedFromTimestamp: Long,
        syncedToTimestamp: Long,
        syncSource: String,
        syncRangeDao: SyncRangeDao
    ) {
        // Insert all messages
        insertMessages(messages)

        // Record that this range is synced
        syncRangeDao.recordSyncedRange(
            chatGuid = chatGuid,
            startTimestamp = syncedFromTimestamp,
            endTimestamp = syncedToTimestamp,
            syncSource = syncSource
        )
    }
}
