package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.AutoShareContactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for auto-share contacts.
 *
 * Provides CRUD operations for managing the list of contacts (max 5)
 * who receive automatic ETA updates when navigation starts.
 */
@Dao
interface AutoShareContactDao {

    /**
     * Observe all auto-share contacts.
     */
    @Query("SELECT * FROM auto_share_contacts ORDER BY created_at ASC")
    fun observeAll(): Flow<List<AutoShareContactEntity>>

    /**
     * Get all auto-share contacts (one-shot).
     */
    @Query("SELECT * FROM auto_share_contacts ORDER BY created_at ASC")
    suspend fun getAll(): List<AutoShareContactEntity>

    /**
     * Get all enabled auto-share contacts.
     */
    @Query("SELECT * FROM auto_share_contacts WHERE enabled = 1 ORDER BY created_at ASC")
    suspend fun getEnabled(): List<AutoShareContactEntity>

    /**
     * Get count of auto-share contacts.
     */
    @Query("SELECT COUNT(*) FROM auto_share_contacts")
    suspend fun getCount(): Int

    /**
     * Get a contact by chat GUID.
     */
    @Query("SELECT * FROM auto_share_contacts WHERE chat_guid = :chatGuid")
    suspend fun getByChatGuid(chatGuid: String): AutoShareContactEntity?

    /**
     * Check if a chat is already in auto-share list.
     */
    @Query("SELECT COUNT(*) > 0 FROM auto_share_contacts WHERE chat_guid = :chatGuid")
    suspend fun exists(chatGuid: String): Boolean

    /**
     * Insert a new auto-share contact.
     * Returns -1 if insert failed (e.g., conflict).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(contact: AutoShareContactEntity): Long

    /**
     * Update an existing contact.
     */
    @Update
    suspend fun update(contact: AutoShareContactEntity)

    /**
     * Update enabled status for a contact.
     */
    @Query("UPDATE auto_share_contacts SET enabled = :enabled WHERE chat_guid = :chatGuid")
    suspend fun updateEnabled(chatGuid: String, enabled: Boolean)

    /**
     * Delete a contact by chat GUID.
     */
    @Query("DELETE FROM auto_share_contacts WHERE chat_guid = :chatGuid")
    suspend fun delete(chatGuid: String)

    /**
     * Delete a contact entity.
     */
    @Delete
    suspend fun delete(contact: AutoShareContactEntity)

    /**
     * Delete all auto-share contacts.
     */
    @Query("DELETE FROM auto_share_contacts")
    suspend fun deleteAll()
}
