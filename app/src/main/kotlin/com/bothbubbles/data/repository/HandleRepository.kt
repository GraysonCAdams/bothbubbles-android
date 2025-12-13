package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.HandleEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for handle (contact) data operations.
 *
 * Handles represent message participants (phone numbers, emails) and their associated metadata
 * like contact names, avatars, and service types (iMessage vs SMS).
 */
@Singleton
class HandleRepository @Inject constructor(
    private val handleDao: HandleDao
) {
    // ===== Query Operations =====

    /**
     * Get a handle by its ID.
     */
    suspend fun getHandleById(id: Long): HandleEntity? =
        handleDao.getHandleById(id)

    /**
     * Batch fetch handles by their IDs.
     */
    suspend fun getHandlesByIds(ids: List<Long>): List<HandleEntity> =
        handleDao.getHandlesByIds(ids)

    /**
     * Get a handle by address (phone number or email), ignoring service type.
     */
    suspend fun getHandleByAddressAny(address: String): HandleEntity? =
        handleDao.getHandleByAddressAny(address)

    /**
     * Get all handles with a specific address.
     */
    suspend fun getHandlesByAddress(address: String): List<HandleEntity> =
        handleDao.getHandlesByAddress(address)

    /**
     * Get all handles (one-time query).
     */
    suspend fun getAllHandlesOnce(): List<HandleEntity> =
        handleDao.getAllHandlesOnce()

    /**
     * Observe all handles (reactive Flow).
     */
    fun getAllHandles(): Flow<List<HandleEntity>> =
        handleDao.getAllHandles()

    /**
     * Search handles by address or display name.
     */
    fun searchHandles(query: String): Flow<List<HandleEntity>> =
        handleDao.searchHandles(query)

    /**
     * Get handles from recent 1-on-1 conversations.
     * Used for "Recent" section in chat creator.
     */
    fun getRecentContacts(): Flow<List<HandleEntity>> =
        handleDao.getRecentContacts()

    /**
     * Get the total handle count.
     */
    suspend fun getHandleCount(): Int =
        handleDao.getHandleCount()

    // ===== Mutation Operations =====

    /**
     * Update cached contact info (display name and avatar) for a handle.
     */
    suspend fun updateCachedContactInfo(id: Long, displayName: String?, avatarPath: String?) {
        handleDao.updateCachedContactInfo(id, displayName, avatarPath)
    }

    /**
     * Clear inferred name for a handle by address.
     * Used when a contact is removed or renamed.
     */
    suspend fun clearInferredNameByAddress(address: String) {
        handleDao.clearInferredNameByAddress(address)
    }

    /**
     * Update inferred name for a handle.
     */
    suspend fun updateInferredName(id: Long, inferredName: String?) {
        handleDao.updateInferredName(id, inferredName)
    }

    // ===== Spam Operations =====

    /**
     * Increment spam report count for a handle.
     */
    suspend fun incrementSpamReportCount(id: Long) {
        handleDao.incrementSpamReportCount(id)
    }

    /**
     * Increment spam report count by address.
     */
    suspend fun incrementSpamReportCountByAddress(address: String) {
        handleDao.incrementSpamReportCountByAddress(address)
    }

    /**
     * Update whitelist status for a handle by address.
     */
    suspend fun updateWhitelistedByAddress(address: String, isWhitelisted: Boolean) {
        handleDao.updateWhitelistedByAddress(address, isWhitelisted)
    }

    /**
     * Update whitelist status for a handle by ID.
     */
    suspend fun updateWhitelisted(id: Long, isWhitelisted: Boolean) {
        handleDao.updateWhitelisted(id, isWhitelisted)
    }

    /**
     * Reset spam status for a handle.
     */
    suspend fun resetSpamStatus(id: Long) {
        handleDao.resetSpamStatus(id)
    }

    // ===== Delete Operations =====

    /**
     * Delete all handles from the database.
     */
    suspend fun deleteAllHandles() {
        handleDao.deleteAllHandles()
    }

    /**
     * Delete a specific handle by ID.
     */
    suspend fun deleteHandle(id: Long) {
        handleDao.deleteHandle(id)
    }
}
