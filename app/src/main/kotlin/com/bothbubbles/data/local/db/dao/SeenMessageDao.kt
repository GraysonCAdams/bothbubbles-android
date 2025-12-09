package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.SeenMessageEntity

/**
 * DAO for tracking seen messages to prevent duplicate notifications.
 */
@Dao
interface SeenMessageDao {

    /**
     * Check if a message GUID exists in the seen messages table.
     * @return true if message was already seen, false otherwise
     */
    @Query("SELECT EXISTS(SELECT 1 FROM seen_messages WHERE message_guid = :messageGuid)")
    suspend fun exists(messageGuid: String): Boolean

    /**
     * Insert a seen message record.
     * Uses IGNORE strategy to silently skip if GUID already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(seenMessage: SeenMessageEntity)

    /**
     * Insert a message GUID as seen.
     * Convenience method that creates the entity internally.
     */
    @Query("INSERT OR IGNORE INTO seen_messages (message_guid, seen_at) VALUES (:messageGuid, :seenAt)")
    suspend fun markAsSeen(messageGuid: String, seenAt: Long = System.currentTimeMillis())

    /**
     * Delete old seen message records to prevent unbounded table growth.
     * Keeps entries from the last [keepDurationMs] milliseconds.
     */
    @Query("DELETE FROM seen_messages WHERE seen_at < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)

    /**
     * Get count of tracked messages (for debugging).
     */
    @Query("SELECT COUNT(*) FROM seen_messages")
    suspend fun count(): Int

    /**
     * Clear all seen messages (useful for testing or reset scenarios).
     */
    @Query("DELETE FROM seen_messages")
    suspend fun clear()
}
