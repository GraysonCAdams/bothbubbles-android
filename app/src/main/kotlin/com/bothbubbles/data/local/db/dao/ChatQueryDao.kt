package com.bothbubbles.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

/**
 * Basic query operations for chats (protocol channels).
 *
 * Note: ChatEntity is now a protocol-specific channel. UI state queries (pinned, archived,
 * starred, unread, etc.) should use [UnifiedChatDao] instead. This DAO only handles
 * protocol-level queries.
 */
@Dao
interface ChatQueryDao {

    /**
     * Data class for address -> last message date mapping.
     * Used for contact deduplication - determines which handle was most recently used.
     */
    data class AddressLastMessageDate(
        @ColumnInfo(name = "chat_identifier") val chatIdentifier: String,
        @ColumnInfo(name = "latest_message_date") val latestMessageDate: Long
    )

    /**
     * Service info from a chat that has messages.
     * Used to determine the correct service for an address based on actual conversation history.
     */
    data class ChatServiceInfo(
        @ColumnInfo(name = "service") val service: String,
        @ColumnInfo(name = "latest_message_date") val latestMessageDate: Long,
        @ColumnInfo(name = "message_count") val messageCount: Int
    )

    /**
     * Address to service mapping from active chats.
     * Used for batch loading service info for contact list display.
     */
    data class AddressServiceFromActivity(
        @ColumnInfo(name = "chat_identifier") val chatIdentifier: String,
        @ColumnInfo(name = "service") val service: String,
        @ColumnInfo(name = "latest_message_date") val latestMessageDate: Long
    )

    // ===== Basic Chat Queries =====

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL
        ORDER BY latest_message_date DESC
    """)
    fun getAllChats(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_group = 0 AND latest_message_date IS NOT NULL
        ORDER BY latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getNonGroupChatsPaginated(limit: Int, offset: Int): List<ChatEntity>

    @Query("""
        SELECT COUNT(*) FROM chats
        WHERE date_deleted IS NULL AND is_group = 0 AND latest_message_date IS NOT NULL
    """)
    suspend fun getNonGroupChatCount(): Int

    @Query("""
        SELECT COUNT(*) FROM chats
        WHERE date_deleted IS NULL AND is_group = 0 AND latest_message_date IS NOT NULL
    """)
    fun observeNonGroupChatCount(): Flow<Int>

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
        WHERE date_deleted IS NULL
        ORDER BY latest_message_date DESC
        LIMIT :limit
    """)
    suspend fun getRecentChats(limit: Int): List<ChatEntity>

    // ===== Unified Chat Queries =====

    /**
     * Get all chats belonging to a unified conversation.
     * Used to find all protocol channels for a conversation.
     */
    @Query("SELECT * FROM chats WHERE unified_chat_id = :unifiedChatId AND date_deleted IS NULL")
    suspend fun getChatsForUnifiedChat(unifiedChatId: String): List<ChatEntity>

    /**
     * Get all chat GUIDs belonging to a unified conversation.
     */
    @Query("SELECT guid FROM chats WHERE unified_chat_id = :unifiedChatId AND date_deleted IS NULL")
    suspend fun getChatGuidsForUnifiedChat(unifiedChatId: String): List<String>

    /**
     * Batch result for unified chat to chat GUID mapping.
     * Used to avoid N+1 queries when loading conversations.
     */
    data class UnifiedChatGuid(
        @ColumnInfo(name = "unified_chat_id") val unifiedChatId: String,
        @ColumnInfo(name = "guid") val chatGuid: String
    )

    /**
     * Batch get chat GUIDs for multiple unified chats.
     * Returns pairs of (unifiedChatId, chatGuid) for grouping.
     */
    @Query("""
        SELECT unified_chat_id, guid
        FROM chats
        WHERE unified_chat_id IN (:unifiedChatIds) AND date_deleted IS NULL
    """)
    suspend fun getChatGuidsForUnifiedChats(unifiedChatIds: List<String>): List<UnifiedChatGuid>

    /**
     * Check which chat GUIDs are linked to any unified chat.
     * Used to filter out chats that shouldn't appear as orphans.
     */
    @Query("SELECT guid FROM chats WHERE guid IN (:chatGuids) AND unified_chat_id IS NOT NULL")
    suspend fun getChatsLinkedToUnifiedChats(chatGuids: List<String>): List<String>

    /**
     * Observe all chats for a unified conversation.
     */
    @Query("SELECT * FROM chats WHERE unified_chat_id = :unifiedChatId AND date_deleted IS NULL")
    fun observeChatsForUnifiedChat(unifiedChatId: String): Flow<List<ChatEntity>>

    /**
     * Get the chat with a specific chat_identifier (phone/email).
     */
    @Query("SELECT * FROM chats WHERE chat_identifier = :identifier AND date_deleted IS NULL")
    suspend fun getChatsByIdentifier(identifier: String): List<ChatEntity>

    /**
     * Update the unified_chat_id for a chat.
     */
    @Query("UPDATE chats SET unified_chat_id = :unifiedChatId WHERE guid = :guid")
    suspend fun setUnifiedChatId(guid: String, unifiedChatId: String)

    /**
     * Find chats that need repair sync.
     *
     * These are chats where:
     * - latest_message_date is set (server indicated messages exist)
     * - No sync_ranges exist (messages were never synced)
     * - No messages exist in the local database
     * - Chat is not deleted
     *
     * This detects chats created from server metadata where the actual
     * message sync never occurred (e.g., socket event created the chat
     * but messages weren't pulled).
     */
    @Query("""
        SELECT c.* FROM chats c
        WHERE c.date_deleted IS NULL
        AND c.latest_message_date IS NOT NULL
        AND c.latest_message_date > 0
        AND NOT EXISTS (SELECT 1 FROM sync_ranges sr WHERE sr.chat_guid = c.guid)
        AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.chat_guid = c.guid AND m.date_deleted IS NULL)
        ORDER BY c.latest_message_date DESC
    """)
    suspend fun findChatsNeedingRepair(): List<ChatEntity>

    /**
     * Count of chats needing repair sync.
     * Used for quick detection without fetching full entities.
     */
    @Query("""
        SELECT COUNT(*) FROM chats c
        WHERE c.date_deleted IS NULL
        AND c.latest_message_date IS NOT NULL
        AND c.latest_message_date > 0
        AND NOT EXISTS (SELECT 1 FROM sync_ranges sr WHERE sr.chat_guid = c.guid)
        AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.chat_guid = c.guid AND m.date_deleted IS NULL)
    """)
    suspend fun countChatsNeedingRepair(): Int

    /**
     * Get the most recent message date for each chat identifier (phone/email).
     * Used for contact deduplication - determines which handle was most recently used.
     * Only returns non-group chats with a chat_identifier.
     */
    @Query("""
        SELECT chat_identifier, latest_message_date
        FROM chats
        WHERE date_deleted IS NULL
        AND is_group = 0
        AND chat_identifier IS NOT NULL
        AND latest_message_date IS NOT NULL
    """)
    suspend fun getLastMessageDatePerAddress(): List<AddressLastMessageDate>

    // ===== Activity-Based Service Selection =====

    /**
     * Get service info from the most recently active chat that HAS messages for an address.
     * Returns null if no chat with messages exists for this address.
     *
     * Service classification:
     * - iMessage;-; prefix → "iMessage"
     * - SMS;-;, sms;-;, MMS;-;, mms;-;, RCS;-; prefixes → "SMS"
     *
     * @param address The chat identifier (phone number or email)
     * @return ChatServiceInfo with service type, latest message date, and message count
     */
    @Query("""
        SELECT
            CASE
                WHEN c.guid LIKE 'iMessage;%' THEN 'iMessage'
                ELSE 'SMS'
            END as service,
            c.latest_message_date,
            (SELECT COUNT(*) FROM messages m
             WHERE m.chat_guid = c.guid
             AND m.date_deleted IS NULL
             AND m.is_reaction = 0) as message_count
        FROM chats c
        WHERE c.chat_identifier = :address
        AND c.date_deleted IS NULL
        AND c.is_group = 0
        AND EXISTS (
            SELECT 1 FROM messages m
            WHERE m.chat_guid = c.guid
            AND m.date_deleted IS NULL
            LIMIT 1
        )
        ORDER BY c.latest_message_date DESC
        LIMIT 1
    """)
    suspend fun getMostRecentActiveChatService(address: String): ChatServiceInfo?

    /**
     * Get service from most recent active chat per address (batch query for contact list).
     * Only returns addresses that have at least one chat with messages.
     *
     * For each address, returns the service type from the most recently active chat.
     * Used by ContactLoadDelegate and SuggestionDelegate for intelligent service display.
     */
    @Query("""
        SELECT
            c.chat_identifier,
            CASE
                WHEN c.guid LIKE 'iMessage;%' THEN 'iMessage'
                ELSE 'SMS'
            END as service,
            c.latest_message_date
        FROM chats c
        WHERE c.date_deleted IS NULL
        AND c.is_group = 0
        AND c.chat_identifier IS NOT NULL
        AND c.latest_message_date IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM messages m
            WHERE m.chat_guid = c.guid
            AND m.date_deleted IS NULL
            LIMIT 1
        )
        ORDER BY c.latest_message_date DESC
    """)
    suspend fun getServiceMapFromActiveChats(): List<AddressServiceFromActivity>

    // ===== Stale Data Cleanup =====

    /**
     * Find stale empty chats that should be cleaned up.
     * Returns chats that:
     * - Are not deleted
     * - Are 1:1 (not group)
     * - Were created before the cutoff date
     * - Have NO messages at all
     *
     * These are typically chats created from server sync that were never actually used.
     */
    @Query("""
        SELECT c.* FROM chats c
        WHERE c.date_deleted IS NULL
        AND c.is_group = 0
        AND c.date_created < :cutoffDate
        AND NOT EXISTS (
            SELECT 1 FROM messages m
            WHERE m.chat_guid = c.guid
            AND m.date_deleted IS NULL
            LIMIT 1
        )
    """)
    suspend fun getStaleEmptyChats(cutoffDate: Long): List<ChatEntity>
}
