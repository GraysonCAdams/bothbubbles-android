package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.PendingMessageEntity
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pendingMessage: PendingMessageEntity): Long

    @Update
    suspend fun update(pendingMessage: PendingMessageEntity)

    @Query("SELECT * FROM pending_messages WHERE id = :id")
    suspend fun getById(id: Long): PendingMessageEntity?

    @Query("SELECT * FROM pending_messages WHERE local_id = :localId")
    suspend fun getByLocalId(localId: String): PendingMessageEntity?

    /**
     * Observe pending messages for a specific chat.
     * Used by ChatViewModel to display queued messages in the UI.
     */
    @Query("SELECT * FROM pending_messages WHERE chat_guid = :chatGuid ORDER BY created_at ASC")
    fun observeForChat(chatGuid: String): Flow<List<PendingMessageEntity>>

    /**
     * Observe pending messages for a specific chat (non-reactive query).
     */
    @Query("SELECT * FROM pending_messages WHERE chat_guid = :chatGuid ORDER BY created_at ASC")
    suspend fun getForChat(chatGuid: String): List<PendingMessageEntity>

    /**
     * Get all pending and failed messages for retry at startup.
     */
    @Query("SELECT * FROM pending_messages WHERE sync_status IN ('PENDING', 'FAILED') ORDER BY created_at ASC")
    suspend fun getPendingAndFailed(): List<PendingMessageEntity>

    /**
     * Get messages by status.
     */
    @Query("SELECT * FROM pending_messages WHERE sync_status = :status ORDER BY created_at ASC")
    suspend fun getByStatus(status: String): List<PendingMessageEntity>

    /**
     * Update sync status only.
     */
    @Query("UPDATE pending_messages SET sync_status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /**
     * Update sync status with timestamp.
     */
    @Query("UPDATE pending_messages SET sync_status = :status, last_attempt_at = :timestamp WHERE id = :id")
    suspend fun updateStatusWithTimestamp(id: Long, status: String, timestamp: Long)

    /**
     * Update sync status with error info after a failed send attempt.
     */
    @Query("""
        UPDATE pending_messages
        SET sync_status = :status,
            error_message = :errorMessage,
            retry_count = retry_count + 1,
            last_attempt_at = :timestamp
        WHERE id = :id
    """)
    suspend fun updateStatusWithError(id: Long, status: String, errorMessage: String?, timestamp: Long)

    /**
     * Mark message as successfully sent with server GUID.
     */
    @Query("UPDATE pending_messages SET server_guid = :serverGuid, sync_status = 'SENT' WHERE id = :id")
    suspend fun markAsSent(id: Long, serverGuid: String)

    /**
     * Store the server GUID immediately after API success, BEFORE confirmation wait.
     * This ensures retries can detect "API already succeeded" state even if worker
     * dies during the 2-minute confirmation wait.
     *
     * Does NOT change sync_status - that remains SENDING until confirmation completes.
     */
    @Query("UPDATE pending_messages SET server_guid = :serverGuid WHERE id = :id")
    suspend fun updateServerGuid(id: Long, serverGuid: String)

    /**
     * Store WorkManager request ID for cancellation.
     */
    @Query("UPDATE pending_messages SET work_request_id = :workRequestId WHERE id = :id")
    suspend fun updateWorkRequestId(id: Long, workRequestId: String)

    /**
     * Delete a pending message by ID.
     */
    @Query("DELETE FROM pending_messages WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Delete a pending message by local ID.
     */
    @Query("DELETE FROM pending_messages WHERE local_id = :localId")
    suspend fun deleteByLocalId(localId: String)

    /**
     * Mark message as successfully sent with server GUID.
     * Also updates the chat's last message info if needed.
     */
    @Transaction
    suspend fun markMessageSentAndUpdateChat(
        tempGuid: String,
        finalGuid: String,
        chatDao: ChatDao,
        chatGuid: String
    ) {
        val pending = getByLocalId(tempGuid) ?: return
        markAsSent(pending.id, finalGuid)

        // Note: ChatEntity does not have last_message_guid column, so we cannot update it.
        // If schema changes in future, uncomment this:
        // chatDao.updateLastMessageGuid(chatGuid, finalGuid)
    }

    /**
     * Clean up sent messages (run periodically).
     */
    @Query("DELETE FROM pending_messages WHERE sync_status = 'SENT'")
    suspend fun deleteSent()

    /**
     * Observe count of pending messages for a chat (for badges/indicators).
     */
    @Query("SELECT COUNT(*) FROM pending_messages WHERE chat_guid = :chatGuid AND sync_status IN ('PENDING', 'SENDING')")
    fun observePendingCount(chatGuid: String): Flow<Int>

    /**
     * Check if there are any unsent messages (PENDING or FAILED).
     */
    @Query("SELECT COUNT(*) FROM pending_messages WHERE sync_status IN ('PENDING', 'SENDING', 'FAILED')")
    suspend fun getUnsentCount(): Int

    /**
     * Get messages stuck in SENDING status older than the given timestamp.
     * Used at startup to reset stale SENDING messages that got interrupted.
     */
    @Query("SELECT * FROM pending_messages WHERE sync_status = 'SENDING' AND last_attempt_at < :olderThan")
    suspend fun getStaleSending(olderThan: Long): List<PendingMessageEntity>

    // ============================================================================
    // MESSAGE ORDERING / DEPENDENCY QUERIES
    // ============================================================================

    /**
     * Get the latest pending or sending message in a chat.
     * Used when queueing a new message to set its depends_on_local_id.
     * Returns null if no pending/sending messages exist in the chat.
     */
    @Query("""
        SELECT * FROM pending_messages
        WHERE chat_guid = :chatGuid
        AND sync_status IN ('PENDING', 'SENDING')
        ORDER BY created_at DESC
        LIMIT 1
    """)
    suspend fun getLatestPendingOrSending(chatGuid: String): PendingMessageEntity?

    /**
     * Get all messages that directly depend on a given message.
     * Used for cascade failure when a message fails.
     */
    @Query("SELECT * FROM pending_messages WHERE depends_on_local_id = :localId")
    suspend fun getDependentMessages(localId: String): List<PendingMessageEntity>

    /**
     * Get all messages in a dependency chain starting from a given localId.
     * This recursively finds all messages that depend on the given message,
     * plus all messages that depend on those, etc.
     *
     * Note: SQLite doesn't support true recursive CTEs in all versions,
     * so we'll handle this iteratively in the repository.
     */
    @Query("""
        SELECT * FROM pending_messages
        WHERE depends_on_local_id = :localId
        AND sync_status IN ('PENDING', 'SENDING')
        ORDER BY created_at ASC
    """)
    suspend fun getDirectDependents(localId: String): List<PendingMessageEntity>

    /**
     * Update dependency reference when marking a message as failed.
     * Clears the depends_on_local_id for dependent messages.
     */
    @Query("UPDATE pending_messages SET depends_on_local_id = NULL WHERE depends_on_local_id = :localId")
    suspend fun clearDependency(localId: String)

    /**
     * Count messages that depend on a given localId.
     * Used to determine if cascade failure notification should be grouped.
     */
    @Query("SELECT COUNT(*) FROM pending_messages WHERE depends_on_local_id = :localId AND sync_status IN ('PENDING', 'SENDING')")
    suspend fun countDependentMessages(localId: String): Int

    /**
     * Get all pending/sending messages in a chat, ordered by creation time.
     * Used for verifying dependency chain integrity.
     */
    @Query("""
        SELECT * FROM pending_messages
        WHERE chat_guid = :chatGuid
        AND sync_status IN ('PENDING', 'SENDING')
        ORDER BY created_at ASC
    """)
    suspend fun getPendingOrSendingForChat(chatGuid: String): List<PendingMessageEntity>
}
