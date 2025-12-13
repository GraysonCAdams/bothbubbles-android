package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.ScheduledMessageDao
import com.bothbubbles.data.local.db.entity.ScheduledMessageEntity
import com.bothbubbles.data.local.db.entity.ScheduledMessageStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for scheduled message operations.
 *
 * Scheduled messages are messages that will be sent at a future time.
 */
@Singleton
class ScheduledMessageRepository @Inject constructor(
    private val scheduledMessageDao: ScheduledMessageDao
) {
    // ===== Query Operations =====

    /**
     * Get a scheduled message by its ID.
     */
    suspend fun getById(id: Long): ScheduledMessageEntity? =
        scheduledMessageDao.getById(id)

    /**
     * Observe scheduled messages for a specific chat.
     */
    fun observeForChat(chatGuid: String): Flow<List<ScheduledMessageEntity>> =
        scheduledMessageDao.observeForChat(chatGuid)

    /**
     * Get scheduled messages by status.
     */
    suspend fun getByStatus(status: ScheduledMessageStatus): List<ScheduledMessageEntity> =
        scheduledMessageDao.getByStatus(status)

    /**
     * Get all pending scheduled messages.
     */
    suspend fun getPending(): List<ScheduledMessageEntity> =
        scheduledMessageDao.getPending()

    /**
     * Observe the count of pending scheduled messages for a chat.
     */
    fun observePendingCount(chatGuid: String): Flow<Int> =
        scheduledMessageDao.observePendingCount(chatGuid)

    // ===== Mutation Operations =====

    /**
     * Insert a new scheduled message.
     * @return The ID of the inserted message.
     */
    suspend fun insert(scheduledMessage: ScheduledMessageEntity): Long =
        scheduledMessageDao.insert(scheduledMessage)

    /**
     * Update an existing scheduled message.
     */
    suspend fun update(scheduledMessage: ScheduledMessageEntity) {
        scheduledMessageDao.update(scheduledMessage)
    }

    /**
     * Update the work request ID for a scheduled message.
     */
    suspend fun updateWorkRequestId(id: Long, workRequestId: String) {
        scheduledMessageDao.updateWorkRequestId(id, workRequestId)
    }

    /**
     * Update the status of a scheduled message.
     */
    suspend fun updateStatus(id: Long, status: ScheduledMessageStatus) {
        scheduledMessageDao.updateStatus(id, status)
    }

    /**
     * Update the status with an optional error message.
     */
    suspend fun updateStatusWithError(id: Long, status: ScheduledMessageStatus, errorMessage: String?) {
        scheduledMessageDao.updateStatusWithError(id, status, errorMessage)
    }

    // ===== Delete Operations =====

    /**
     * Delete a scheduled message by its ID.
     */
    suspend fun delete(id: Long) {
        scheduledMessageDao.delete(id)
    }

    /**
     * Delete all sent scheduled messages.
     */
    suspend fun deleteSent() {
        scheduledMessageDao.deleteSent()
    }
}
