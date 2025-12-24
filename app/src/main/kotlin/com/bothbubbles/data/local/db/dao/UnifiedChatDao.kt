package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for unified chats.
 *
 * Unlike the old UnifiedChatGroupDao, this doesn't need junction table operations
 * because chats and messages directly reference unified chats via [unifiedChatId].
 */
@Dao
interface UnifiedChatDao {

    // ==================== Observe Queries ====================

    @Query("""
        SELECT * FROM unified_chats
        WHERE is_archived = 0 AND date_deleted IS NULL
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
    """)
    fun observeActiveChats(): Flow<List<UnifiedChatEntity>>

    @Query("""
        SELECT * FROM unified_chats
        WHERE is_archived = 1 AND date_deleted IS NULL
        ORDER BY latest_message_date DESC
    """)
    fun observeArchivedChats(): Flow<List<UnifiedChatEntity>>

    @Query("""
        SELECT * FROM unified_chats
        WHERE is_starred = 1 AND date_deleted IS NULL
        ORDER BY latest_message_date DESC
    """)
    fun observeStarredChats(): Flow<List<UnifiedChatEntity>>

    @Query("""
        SELECT * FROM unified_chats
        WHERE date_deleted IS NULL
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
    """)
    fun observeAllChats(): Flow<List<UnifiedChatEntity>>

    @Query("SELECT * FROM unified_chats WHERE id = :id")
    fun observeById(id: String): Flow<UnifiedChatEntity?>

    @Query("SELECT * FROM unified_chats WHERE source_id = :sourceId LIMIT 1")
    fun observeBySourceId(sourceId: String): Flow<UnifiedChatEntity?>

    // ==================== Get Queries ====================

    @Query("""
        SELECT * FROM unified_chats
        WHERE is_archived = 0 AND date_deleted IS NULL
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getActiveChats(limit: Int, offset: Int): List<UnifiedChatEntity>

    @Query("""
        SELECT * FROM unified_chats
        WHERE is_archived = 1 AND date_deleted IS NULL
        ORDER BY latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getArchivedChats(limit: Int, offset: Int): List<UnifiedChatEntity>

    @Query("SELECT * FROM unified_chats WHERE date_deleted IS NULL")
    suspend fun getAllChats(): List<UnifiedChatEntity>

    @Query("SELECT * FROM unified_chats WHERE id = :id")
    suspend fun getById(id: String): UnifiedChatEntity?

    @Query("SELECT * FROM unified_chats WHERE normalized_address = :address LIMIT 1")
    suspend fun getByNormalizedAddress(address: String): UnifiedChatEntity?

    @Query("SELECT * FROM unified_chats WHERE source_id = :sourceId LIMIT 1")
    suspend fun getBySourceId(sourceId: String): UnifiedChatEntity?

    /**
     * Search unified chats by display name, normalized address, or handle nickname.
     * Used by compose screen to find existing conversations.
     */
    @Query("""
        SELECT DISTINCT uc.* FROM unified_chats uc
        LEFT JOIN handles h ON uc.normalized_address = h.address
        WHERE uc.is_archived = 0 AND uc.date_deleted IS NULL
        AND (
            uc.display_name LIKE '%' || :query || '%' COLLATE NOCASE
            OR uc.normalized_address LIKE '%' || :query || '%' COLLATE NOCASE
            OR h.cached_display_name LIKE '%' || :query || '%' COLLATE NOCASE
        )
        ORDER BY uc.latest_message_date DESC
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 10): List<UnifiedChatEntity>

    // ==================== Counts ====================

    @Query("SELECT COUNT(*) FROM unified_chats WHERE is_archived = 0 AND date_deleted IS NULL")
    suspend fun getActiveCount(): Int

    @Query("SELECT COUNT(*) FROM unified_chats WHERE is_archived = 0 AND date_deleted IS NULL")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM unified_chats WHERE is_archived = 1 AND date_deleted IS NULL")
    fun observeArchivedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM unified_chats WHERE is_starred = 1 AND date_deleted IS NULL")
    fun observeStarredCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(unread_count), 0) FROM unified_chats WHERE is_archived = 0 AND date_deleted IS NULL")
    fun observeTotalUnreadCount(): Flow<Int>

    // ==================== Insert/Update ====================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(chat: UnifiedChatEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: UnifiedChatEntity)

    @Update
    suspend fun update(chat: UnifiedChatEntity)

    /**
     * Get or create a unified chat for the given normalized address.
     * Thread-safe: uses transaction to prevent race conditions.
     */
    @Transaction
    suspend fun getOrCreate(chat: UnifiedChatEntity): UnifiedChatEntity {
        val existing = getByNormalizedAddress(chat.normalizedAddress)
        if (existing != null) {
            return existing
        }

        // Insert returns -1 if IGNORE triggered (another thread won)
        val result = insertIfNotExists(chat)
        if (result != -1L) {
            return chat
        }

        // Another thread inserted first, return that one
        return getByNormalizedAddress(chat.normalizedAddress)
            ?: throw IllegalStateException("Failed to get or create unified chat for ${chat.normalizedAddress}")
    }

    // ==================== Latest Message Updates ====================

    @Query("""
        UPDATE unified_chats
        SET latest_message_date = :date,
            latest_message_text = :text,
            latest_message_guid = :guid,
            latest_message_is_from_me = :isFromMe,
            latest_message_has_attachments = :hasAttachments,
            latest_message_source = :source,
            latest_message_date_delivered = :dateDelivered,
            latest_message_date_read = :dateRead,
            latest_message_error = :error
        WHERE id = :id
    """)
    suspend fun updateLatestMessage(
        id: String,
        date: Long,
        text: String?,
        guid: String?,
        isFromMe: Boolean,
        hasAttachments: Boolean,
        source: String?,
        dateDelivered: Long?,
        dateRead: Long?,
        error: Int
    )

    /**
     * Update latest message if the provided date is newer.
     * Returns true if update was performed.
     */
    @Transaction
    suspend fun updateLatestMessageIfNewer(
        id: String,
        date: Long,
        text: String?,
        guid: String?,
        isFromMe: Boolean,
        hasAttachments: Boolean,
        source: String?,
        dateDelivered: Long?,
        dateRead: Long?,
        error: Int
    ): Boolean {
        val current = getById(id) ?: return false
        val currentDate = current.latestMessageDate ?: 0L
        if (date >= currentDate) {
            updateLatestMessage(
                id, date, text, guid, isFromMe, hasAttachments,
                source, dateDelivered, dateRead, error
            )
            return true
        }
        return false
    }

    // ==================== Unread Count ====================

    @Query("UPDATE unified_chats SET unread_count = :count, has_unread_message = :count > 0 WHERE id = :id")
    suspend fun updateUnreadCount(id: String, count: Int)

    @Query("UPDATE unified_chats SET unread_count = unread_count + 1, has_unread_message = 1 WHERE id = :id")
    suspend fun incrementUnreadCount(id: String)

    @Query("UPDATE unified_chats SET unread_count = 0, has_unread_message = 0 WHERE id = :id")
    suspend fun markAsRead(id: String)

    @Query("UPDATE unified_chats SET unread_count = 0, has_unread_message = 0 WHERE unread_count > 0")
    suspend fun markAllAsRead(): Int

    // ==================== State Updates ====================

    @Query("UPDATE unified_chats SET display_name = :displayName WHERE id = :id")
    suspend fun updateDisplayName(id: String, displayName: String?)

    @Query("UPDATE unified_chats SET is_pinned = :isPinned, pin_index = :pinIndex WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean, pinIndex: Int?)

    @Query("UPDATE unified_chats SET is_archived = :isArchived WHERE id = :id")
    suspend fun updateArchiveStatus(id: String, isArchived: Boolean)

    @Query("UPDATE unified_chats SET is_starred = :isStarred WHERE id = :id")
    suspend fun updateStarredStatus(id: String, isStarred: Boolean)

    @Query("UPDATE unified_chats SET mute_type = :muteType, mute_args = :muteArgs WHERE id = :id")
    suspend fun updateMuteStatus(id: String, muteType: String?, muteArgs: String? = null)

    @Query("UPDATE unified_chats SET snooze_until = :snoozeUntil WHERE id = :id")
    suspend fun updateSnoozeUntil(id: String, snoozeUntil: Long?)

    @Query("UPDATE unified_chats SET notifications_enabled = :enabled WHERE id = :id")
    suspend fun updateNotificationsEnabled(id: String, enabled: Boolean)

    @Query("UPDATE unified_chats SET source_id = :sourceId WHERE id = :id")
    suspend fun updateSourceId(id: String, sourceId: String)

    @Query("UPDATE unified_chats SET text_field_text = :text WHERE id = :id")
    suspend fun updateTextFieldText(id: String, text: String?)

    @Query("UPDATE unified_chats SET preferred_send_mode = :mode, send_mode_manually_set = :manuallySet WHERE id = :id")
    suspend fun updatePreferredSendMode(id: String, mode: String?, manuallySet: Boolean)

    @Query("UPDATE unified_chats SET is_sms_fallback = :isFallback, fallback_reason = :reason, fallback_updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSmsFallbackStatus(id: String, isFallback: Boolean, reason: String?, updatedAt: Long?)

    /**
     * Get unified chats that are in SMS fallback mode.
     * Returns source_id (chatGuid), fallback_reason, and fallback_updated_at for restoration.
     */
    @Query("""
        SELECT source_id, fallback_reason, fallback_updated_at
        FROM unified_chats
        WHERE is_sms_fallback = 1 AND date_deleted IS NULL
    """)
    suspend fun getChatsInFallback(): List<FallbackProjection>

    @Query("UPDATE unified_chats SET is_spam = :isSpam, spam_score = :spamScore WHERE id = :id")
    suspend fun updateSpamStatus(id: String, isSpam: Boolean, spamScore: Int)

    @Query("UPDATE unified_chats SET spam_reported_to_carrier = :reported WHERE id = :id")
    suspend fun updateSpamReportedToCarrier(id: String, reported: Boolean)

    @Query("UPDATE unified_chats SET category = :category, category_confidence = :confidence, category_last_updated = :lastUpdated WHERE id = :id")
    suspend fun updateCategory(id: String, category: String?, confidence: Int, lastUpdated: Long)

    @Query("UPDATE unified_chats SET custom_avatar_path = :path WHERE id = :id")
    suspend fun updateCustomAvatarPath(id: String, path: String?)

    @Query("UPDATE unified_chats SET server_group_photo_path = :path, server_group_photo_guid = :guid WHERE id = :id")
    suspend fun updateServerGroupPhoto(id: String, path: String?, guid: String?)

    @Query("UPDATE unified_chats SET auto_send_read_receipts = :enabled WHERE id = :id")
    suspend fun updateAutoSendReadReceipts(id: String, enabled: Boolean?)

    @Query("UPDATE unified_chats SET auto_send_typing_indicators = :enabled WHERE id = :id")
    suspend fun updateAutoSendTypingIndicators(id: String, enabled: Boolean?)

    @Query("UPDATE unified_chats SET lock_chat_name = :locked WHERE id = :id")
    suspend fun updateLockChatName(id: String, locked: Boolean)

    @Query("UPDATE unified_chats SET lock_chat_icon = :locked WHERE id = :id")
    suspend fun updateLockChatIcon(id: String, locked: Boolean)

    // ==================== Delete ====================

    @Query("UPDATE unified_chats SET date_deleted = :timestamp WHERE id = :id")
    suspend fun softDelete(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM unified_chats WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM unified_chats WHERE normalized_address = :address")
    suspend fun deleteByNormalizedAddress(address: String)

    @Query("DELETE FROM unified_chats")
    suspend fun deleteAll()

    // ==================== Filtered Queries ====================

    @Query("""
        SELECT COUNT(*) FROM unified_chats
        WHERE is_archived = 0 AND date_deleted IS NULL
        AND (
            (:includeSpam = 1 AND is_spam = 1)
            OR (:includeSpam = 0 AND is_spam = 0)
        )
        AND (:unreadOnly = 0 OR unread_count > 0)
        AND (:category IS NULL OR category = :category)
    """)
    suspend fun getFilteredCount(
        includeSpam: Boolean,
        unreadOnly: Boolean,
        category: String?
    ): Int

    @Query("""
        SELECT id FROM unified_chats
        WHERE is_archived = 0 AND date_deleted IS NULL
        AND (
            (:includeSpam = 1 AND is_spam = 1)
            OR (:includeSpam = 0 AND is_spam = 0)
        )
        AND (:unreadOnly = 0 OR unread_count > 0)
        AND (:category IS NULL OR category = :category)
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFilteredIds(
        includeSpam: Boolean,
        unreadOnly: Boolean,
        category: String?,
        limit: Int,
        offset: Int
    ): List<String>

    // ==================== Batch Operations ====================

    @Query("SELECT * FROM unified_chats WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<UnifiedChatEntity>

    @Query("UPDATE unified_chats SET is_archived = :isArchived WHERE id IN (:ids)")
    suspend fun batchUpdateArchiveStatus(ids: List<String>, isArchived: Boolean)

    @Query("UPDATE unified_chats SET unread_count = 0, has_unread_message = 0 WHERE id IN (:ids)")
    suspend fun batchMarkAsRead(ids: List<String>)

    @Query("UPDATE unified_chats SET is_spam = :isSpam WHERE id IN (:ids)")
    suspend fun batchUpdateSpamStatus(ids: List<String>, isSpam: Boolean)

    @Query("UPDATE unified_chats SET date_deleted = :timestamp WHERE id IN (:ids)")
    suspend fun batchSoftDelete(ids: List<String>, timestamp: Long = System.currentTimeMillis())

    // ==================== Clean Invalid Data ====================

    /**
     * Clear display names that contain service suffixes.
     * When display_name is null, the app falls back to formatted phone numbers.
     */
    @Query("""
        UPDATE unified_chats
        SET display_name = NULL
        WHERE display_name LIKE '%(sms%)%'
           OR display_name LIKE '%(ft%)%'
    """)
    suspend fun clearInvalidDisplayNames(): Int
}

/**
 * Projection for restoring fallback state.
 * Maps source_id to chatGuid for compatibility with ChatFallbackTracker.
 */
data class FallbackProjection(
    @androidx.room.ColumnInfo(name = "source_id") val chatGuid: String,
    @androidx.room.ColumnInfo(name = "fallback_reason") val reason: String?,
    @androidx.room.ColumnInfo(name = "fallback_updated_at") val updatedAt: Long?
)
