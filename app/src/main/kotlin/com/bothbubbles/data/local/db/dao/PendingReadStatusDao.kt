package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.core.model.entity.PendingReadStatusEntity

/**
 * DAO for managing pending read status syncs to the BlueBubbles server.
 *
 * Uses REPLACE conflict strategy since the unique constraint on chat_guid
 * ensures we only keep the latest read status change for each chat.
 */
@Dao
interface PendingReadStatusDao {

    /**
     * Insert or replace a pending read status sync.
     * REPLACE strategy deduplicates - if a chat already has a pending sync,
     * this updates it with the new read status.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingReadStatusEntity): Long

    /**
     * Get a pending sync by ID.
     */
    @Query("SELECT * FROM pending_read_status WHERE id = :id")
    suspend fun getById(id: Long): PendingReadStatusEntity?

    /**
     * Get a pending sync by chat GUID.
     */
    @Query("SELECT * FROM pending_read_status WHERE chat_guid = :chatGuid")
    suspend fun getByChatGuid(chatGuid: String): PendingReadStatusEntity?

    /**
     * Get all pending syncs (not yet processed).
     */
    @Query("SELECT * FROM pending_read_status WHERE sync_status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getPending(): List<PendingReadStatusEntity>

    /**
     * Get pending and failed syncs for retry.
     */
    @Query("SELECT * FROM pending_read_status WHERE sync_status IN ('PENDING', 'FAILED') ORDER BY created_at ASC")
    suspend fun getPendingAndFailed(): List<PendingReadStatusEntity>

    /**
     * Get count of pending syncs (for deciding whether to schedule worker).
     */
    @Query("SELECT COUNT(*) FROM pending_read_status WHERE sync_status IN ('PENDING', 'FAILED')")
    suspend fun getPendingCount(): Int

    /**
     * Update sync status.
     */
    @Query("UPDATE pending_read_status SET sync_status = :status, last_attempt_at = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Update sync status with error message.
     */
    @Query("UPDATE pending_read_status SET sync_status = :status, error_message = :error, retry_count = retry_count + 1, last_attempt_at = :timestamp WHERE id = :id")
    suspend fun updateStatusWithError(id: Long, status: String, error: String?, timestamp: Long = System.currentTimeMillis())

    /**
     * Delete a pending sync (after successful sync).
     */
    @Query("DELETE FROM pending_read_status WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Delete pending sync by chat GUID (when chat is deleted).
     */
    @Query("DELETE FROM pending_read_status WHERE chat_guid = :chatGuid")
    suspend fun deleteByChatGuid(chatGuid: String)

    /**
     * Delete successfully synced entries older than the given timestamp.
     * Keeps failed entries for debugging/retry.
     */
    @Query("DELETE FROM pending_read_status WHERE sync_status = 'SYNCED' AND created_at < :olderThan")
    suspend fun deleteOldSynced(olderThan: Long)

    /**
     * Delete all synced entries (cleanup).
     */
    @Query("DELETE FROM pending_read_status WHERE sync_status = 'SYNCED'")
    suspend fun deleteSynced()
}
