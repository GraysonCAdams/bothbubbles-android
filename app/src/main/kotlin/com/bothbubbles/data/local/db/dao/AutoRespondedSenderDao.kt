package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.AutoRespondedSenderEntity

/**
 * Data Access Object for auto-responded senders tracking.
 */
@Dao
interface AutoRespondedSenderDao {

    /**
     * Get an auto-responded sender record by address.
     * Returns null if we haven't auto-responded to this sender yet.
     */
    @Query("SELECT * FROM auto_responded_senders WHERE sender_address = :address LIMIT 1")
    suspend fun get(address: String): AutoRespondedSenderEntity?

    /**
     * Insert or replace an auto-responded sender record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AutoRespondedSenderEntity)

    /**
     * Count auto-responses sent since the given timestamp.
     * Used for rate limiting.
     */
    @Query("SELECT COUNT(*) FROM auto_responded_senders WHERE responded_at > :since")
    suspend fun countSince(since: Long): Int

    /**
     * Delete all auto-responded sender records.
     * Useful for resetting the feature.
     */
    @Query("DELETE FROM auto_responded_senders")
    suspend fun deleteAll()
}
