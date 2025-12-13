package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.PendingAttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingAttachmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: PendingAttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<PendingAttachmentEntity>)

    /**
     * Get all attachments for a pending message.
     */
    @Query("SELECT * FROM pending_attachments WHERE pending_message_id = :messageId ORDER BY order_index")
    suspend fun getForMessage(messageId: Long): List<PendingAttachmentEntity>

    /**
     * Observe attachments for a pending message (for progress updates).
     */
    @Query("SELECT * FROM pending_attachments WHERE pending_message_id = :messageId ORDER BY order_index")
    fun observeForMessage(messageId: Long): Flow<List<PendingAttachmentEntity>>

    /**
     * Update upload progress for an attachment.
     */
    @Query("UPDATE pending_attachments SET upload_progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float)

    /**
     * Update error state for an attachment.
     */
    @Query("UPDATE pending_attachments SET error_type = :errorType, error_message = :errorMessage WHERE id = :id")
    suspend fun updateError(id: Long, errorType: String?, errorMessage: String?)

    /**
     * Delete all attachments for a message.
     * Note: This is also handled by CASCADE delete on foreign key.
     */
    @Query("DELETE FROM pending_attachments WHERE pending_message_id = :messageId")
    suspend fun deleteForMessage(messageId: Long)

    /**
     * Get all persisted paths for cleanup.
     */
    @Query("SELECT persisted_path FROM pending_attachments")
    suspend fun getAllPersistedPaths(): List<String>
}
