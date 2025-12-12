package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
