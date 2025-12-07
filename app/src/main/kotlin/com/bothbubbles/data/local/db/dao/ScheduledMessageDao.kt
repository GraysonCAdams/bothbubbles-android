package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.ScheduledMessageEntity
import com.bothbubbles.data.local.db.entity.ScheduledMessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(scheduledMessage: ScheduledMessageEntity): Long

    @Update
    suspend fun update(scheduledMessage: ScheduledMessageEntity)

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun getById(id: Long): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages WHERE chat_guid = :chatGuid ORDER BY scheduled_at ASC")
    fun observeForChat(chatGuid: String): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE status = :status ORDER BY scheduled_at ASC")
    suspend fun getByStatus(status: ScheduledMessageStatus): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING' ORDER BY scheduled_at ASC")
    suspend fun getPending(): List<ScheduledMessageEntity>

    @Query("UPDATE scheduled_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ScheduledMessageStatus)

    @Query("UPDATE scheduled_messages SET status = :status, error_message = :errorMessage WHERE id = :id")
    suspend fun updateStatusWithError(id: Long, status: ScheduledMessageStatus, errorMessage: String?)

    @Query("UPDATE scheduled_messages SET work_request_id = :workRequestId WHERE id = :id")
    suspend fun updateWorkRequestId(id: Long, workRequestId: String)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM scheduled_messages WHERE status = 'SENT'")
    suspend fun deleteSent()

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE chat_guid = :chatGuid AND status = 'PENDING'")
    fun observePendingCount(chatGuid: String): Flow<Int>
}
