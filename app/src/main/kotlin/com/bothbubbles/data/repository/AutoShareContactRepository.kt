package com.bothbubbles.data.repository

import android.util.Log
import com.bothbubbles.data.local.db.dao.AutoShareContactDao
import com.bothbubbles.data.local.db.entity.AutoShareContactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain model for an auto-share contact.
 */
data class AutoShareContact(
    val chatGuid: String,
    val displayName: String,
    val enabled: Boolean
)

/**
 * Repository for managing ETA auto-share contacts.
 *
 * This is a simplified model that just maintains a list of contacts (max 5)
 * who will receive ETA updates when navigation starts.
 *
 * The minimum ETA threshold for triggering auto-share is stored as a global
 * setting in SettingsDataStore (autoShareMinimumEtaMinutes).
 */
@Singleton
class AutoShareContactRepository @Inject constructor(
    private val dao: AutoShareContactDao
) {
    companion object {
        private const val TAG = "AutoShareContactRepo"
        const val MAX_CONTACTS = 5
    }

    // ===== Observation =====

    /**
     * Observe all auto-share contacts.
     */
    fun observeAll(): Flow<List<AutoShareContact>> =
        dao.observeAll().map { entities ->
            entities.map { it.toDomainModel() }
        }

    // ===== Getters =====

    /**
     * Get all auto-share contacts (one-shot).
     */
    suspend fun getAll(): List<AutoShareContact> =
        dao.getAll().map { it.toDomainModel() }

    /**
     * Get all enabled auto-share contacts (for triggering).
     */
    suspend fun getEnabled(): List<AutoShareContact> =
        dao.getEnabled().map { it.toDomainModel() }

    /**
     * Get count of contacts.
     */
    suspend fun getCount(): Int = dao.getCount()

    /**
     * Check if a chat is already in the auto-share list.
     */
    suspend fun exists(chatGuid: String): Boolean = dao.exists(chatGuid)

    /**
     * Check if we can add more contacts (max 5).
     */
    suspend fun canAddMore(): Boolean = getCount() < MAX_CONTACTS

    // ===== Mutations =====

    /**
     * Add a contact to the auto-share list.
     *
     * @return true if added successfully, false if already exists or at max limit
     */
    suspend fun add(chatGuid: String, displayName: String): Boolean {
        // Check limit
        if (!canAddMore()) {
            Log.w(TAG, "Cannot add contact: max limit of $MAX_CONTACTS reached")
            return false
        }

        // Check if already exists
        if (exists(chatGuid)) {
            Log.w(TAG, "Cannot add contact: already exists")
            return false
        }

        val entity = AutoShareContactEntity(
            chatGuid = chatGuid,
            displayName = displayName,
            enabled = true
        )

        val result = dao.insert(entity)
        if (result != -1L) {
            Log.d(TAG, "Added auto-share contact: $displayName")
            return true
        }
        return false
    }

    /**
     * Remove a contact from the auto-share list.
     */
    suspend fun remove(chatGuid: String) {
        dao.delete(chatGuid)
        Log.d(TAG, "Removed auto-share contact: $chatGuid")
    }

    /**
     * Toggle enabled status for a contact.
     */
    suspend fun setEnabled(chatGuid: String, enabled: Boolean) {
        dao.updateEnabled(chatGuid, enabled)
        Log.d(TAG, "Set auto-share contact $chatGuid enabled=$enabled")
    }

    /**
     * Update a contact's display name.
     */
    suspend fun updateDisplayName(chatGuid: String, displayName: String) {
        val existing = dao.getByChatGuid(chatGuid) ?: return
        dao.update(existing.copy(displayName = displayName))
    }

    /**
     * Remove all contacts.
     */
    suspend fun removeAll() {
        dao.deleteAll()
        Log.d(TAG, "Removed all auto-share contacts")
    }

    // ===== Mapping =====

    private fun AutoShareContactEntity.toDomainModel(): AutoShareContact {
        return AutoShareContact(
            chatGuid = chatGuid,
            displayName = displayName,
            enabled = enabled
        )
    }
}
