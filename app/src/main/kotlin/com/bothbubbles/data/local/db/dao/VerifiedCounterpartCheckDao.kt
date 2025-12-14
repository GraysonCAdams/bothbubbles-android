package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.VerifiedCounterpartCheckEntity

/**
 * Data Access Object for counterpart chat verification records.
 *
 * Used by [com.bothbubbles.services.sync.CounterpartSyncService] to:
 * - Check if we've already verified an address (avoid redundant API calls)
 * - Record verification results (positive or negative)
 * - Invalidate stale checks after a TTL period
 */
@Dao
interface VerifiedCounterpartCheckDao {

    /**
     * Get verification record for an address.
     * Returns null if we haven't checked this address yet.
     */
    @Query("SELECT * FROM verified_counterpart_checks WHERE normalized_address = :address LIMIT 1")
    suspend fun get(address: String): VerifiedCounterpartCheckEntity?

    /**
     * Check if an address has been verified (regardless of result).
     * More efficient than fetching the full entity when we just need existence check.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM verified_counterpart_checks WHERE normalized_address = :address)")
    suspend fun isVerified(address: String): Boolean

    /**
     * Check if an address was verified as having a counterpart.
     * Returns false if not verified or verified as no-counterpart.
     */
    @Query("""
        SELECT COALESCE(
            (SELECT has_counterpart FROM verified_counterpart_checks WHERE normalized_address = :address),
            0
        )
    """)
    suspend fun hasCounterpart(address: String): Boolean

    /**
     * Insert or update a verification record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VerifiedCounterpartCheckEntity)

    /**
     * Delete verification records older than the given timestamp.
     * Used to invalidate stale checks (e.g., contact may have switched phones).
     */
    @Query("DELETE FROM verified_counterpart_checks WHERE verified_at < :before")
    suspend fun deleteOlderThan(before: Long)

    /**
     * Delete a specific verification record by address.
     * Used when user manually triggers a re-check.
     */
    @Query("DELETE FROM verified_counterpart_checks WHERE normalized_address = :address")
    suspend fun delete(address: String)

    /**
     * Delete all verification records.
     * Used for full sync reset.
     */
    @Query("DELETE FROM verified_counterpart_checks")
    suspend fun deleteAll()

    /**
     * Count total verified addresses (for diagnostics/settings UI).
     */
    @Query("SELECT COUNT(*) FROM verified_counterpart_checks")
    suspend fun count(): Int

    /**
     * Count addresses verified as having no counterpart (Android contacts).
     */
    @Query("SELECT COUNT(*) FROM verified_counterpart_checks WHERE has_counterpart = 0")
    suspend fun countNoCounterpart(): Int
}
