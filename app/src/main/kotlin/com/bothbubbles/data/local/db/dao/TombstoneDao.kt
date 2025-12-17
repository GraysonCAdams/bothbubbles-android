package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.core.model.entity.TombstoneEntity

/**
 * DAO for tracking deleted messages and chats.
 *
 * When items are hard-deleted, their GUIDs are recorded here to prevent
 * them from being resurrected during sync operations.
 */
@Dao
interface TombstoneDao {

    /**
     * Check if a GUID exists in the tombstones table.
     * @return true if item was deleted, false otherwise
     */
    @Query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE guid = :guid)")
    suspend fun exists(guid: String): Boolean

    /**
     * Check if any of the provided GUIDs exist in the tombstones table.
     * @return Set of GUIDs that are tombstoned
     */
    @Query("SELECT guid FROM tombstones WHERE guid IN (:guids)")
    suspend fun findTombstoned(guids: List<String>): List<String>

    /**
     * Insert a tombstone record.
     * Uses IGNORE strategy to silently skip if GUID already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tombstone: TombstoneEntity)

    /**
     * Record a deleted message.
     */
    @Query("INSERT OR IGNORE INTO tombstones (guid, type, deleted_at) VALUES (:guid, 'message', :deletedAt)")
    suspend fun recordDeletedMessage(guid: String, deletedAt: Long = System.currentTimeMillis())

    /**
     * Record a deleted chat.
     */
    @Query("INSERT OR IGNORE INTO tombstones (guid, type, deleted_at) VALUES (:guid, 'chat', :deletedAt)")
    suspend fun recordDeletedChat(guid: String, deletedAt: Long = System.currentTimeMillis())

    /**
     * Delete old tombstone records to prevent unbounded table growth.
     * Keeps entries from the last [cutoffTime] timestamp.
     * Recommended: 90 days (older deleted items are unlikely to sync back).
     */
    @Query("DELETE FROM tombstones WHERE deleted_at < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)

    /**
     * Get count of tombstones by type (for debugging).
     */
    @Query("SELECT COUNT(*) FROM tombstones WHERE type = :type")
    suspend fun countByType(type: String): Int

    /**
     * Get total count of tombstones (for debugging).
     */
    @Query("SELECT COUNT(*) FROM tombstones")
    suspend fun count(): Int

    /**
     * Clear all tombstones (useful for testing or reset scenarios).
     */
    @Query("DELETE FROM tombstones")
    suspend fun clear()
}
