package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.bothbubbles.data.local.db.entity.SyncRangeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for sync range tracking.
 *
 * Used to track which message timestamp ranges have been synced from the server,
 * enabling efficient sparse pagination without redundant API calls.
 */
@Dao
interface SyncRangeDao {

    // ===== Queries =====

    /**
     * Get all sync ranges for a chat, ordered by timestamp.
     */
    @Query("""
        SELECT * FROM sync_ranges
        WHERE chat_guid = :chatGuid
        ORDER BY start_timestamp DESC
    """)
    suspend fun getRangesForChat(chatGuid: String): List<SyncRangeEntity>

    /**
     * Observe sync ranges for a chat.
     */
    @Query("""
        SELECT * FROM sync_ranges
        WHERE chat_guid = :chatGuid
        ORDER BY start_timestamp DESC
    """)
    fun observeRangesForChat(chatGuid: String): Flow<List<SyncRangeEntity>>

    /**
     * Check if a specific timestamp is within a synced range.
     * Returns the range if found, null otherwise.
     */
    @Query("""
        SELECT * FROM sync_ranges
        WHERE chat_guid = :chatGuid
        AND start_timestamp <= :timestamp
        AND end_timestamp >= :timestamp
        LIMIT 1
    """)
    suspend fun findRangeContaining(chatGuid: String, timestamp: Long): SyncRangeEntity?

    /**
     * Find ranges that overlap with a given timestamp range.
     * Used for merging adjacent ranges.
     */
    @Query("""
        SELECT * FROM sync_ranges
        WHERE chat_guid = :chatGuid
        AND NOT (end_timestamp < :startTimestamp OR start_timestamp > :endTimestamp)
        ORDER BY start_timestamp ASC
    """)
    suspend fun findOverlappingRanges(
        chatGuid: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): List<SyncRangeEntity>

    /**
     * Get the oldest synced timestamp for a chat.
     * Returns null if no ranges exist.
     */
    @Query("""
        SELECT MIN(start_timestamp) FROM sync_ranges
        WHERE chat_guid = :chatGuid
    """)
    suspend fun getOldestSyncedTimestamp(chatGuid: String): Long?

    /**
     * Get the newest synced timestamp for a chat.
     * Returns null if no ranges exist.
     */
    @Query("""
        SELECT MAX(end_timestamp) FROM sync_ranges
        WHERE chat_guid = :chatGuid
    """)
    suspend fun getNewestSyncedTimestamp(chatGuid: String): Long?

    /**
     * Check if a chat has any synced ranges.
     */
    @Query("""
        SELECT EXISTS(SELECT 1 FROM sync_ranges WHERE chat_guid = :chatGuid LIMIT 1)
    """)
    suspend fun hasSyncedRanges(chatGuid: String): Boolean

    // ===== Inserts/Updates =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRange(range: SyncRangeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRanges(ranges: List<SyncRangeEntity>)

    // ===== Deletes =====

    @Query("DELETE FROM sync_ranges WHERE id = :id")
    suspend fun deleteRange(id: Long)

    @Query("DELETE FROM sync_ranges WHERE id IN (:ids)")
    suspend fun deleteRanges(ids: List<Long>)

    @Query("DELETE FROM sync_ranges WHERE chat_guid = :chatGuid")
    suspend fun deleteRangesForChat(chatGuid: String)

    @Query("DELETE FROM sync_ranges")
    suspend fun deleteAllRanges()

    // ===== Transactions =====

    /**
     * Record a new synced range, merging with any overlapping ranges.
     * This keeps the sync_ranges table minimal and prevents fragmentation.
     */
    @Transaction
    suspend fun recordSyncedRange(
        chatGuid: String,
        startTimestamp: Long,
        endTimestamp: Long,
        syncSource: String
    ) {
        // Find any overlapping ranges
        val overlapping = findOverlappingRanges(chatGuid, startTimestamp, endTimestamp)

        if (overlapping.isEmpty()) {
            // No overlap - insert new range
            insertRange(
                SyncRangeEntity(
                    chatGuid = chatGuid,
                    startTimestamp = startTimestamp,
                    endTimestamp = endTimestamp,
                    syncSource = syncSource
                )
            )
        } else {
            // Merge with overlapping ranges
            val minStart = minOf(startTimestamp, overlapping.minOf { it.startTimestamp })
            val maxEnd = maxOf(endTimestamp, overlapping.maxOf { it.endTimestamp })

            // Delete old overlapping ranges
            deleteRanges(overlapping.map { it.id })

            // Insert merged range
            insertRange(
                SyncRangeEntity(
                    chatGuid = chatGuid,
                    startTimestamp = minStart,
                    endTimestamp = maxEnd,
                    syncSource = syncSource
                )
            )
        }
    }

    /**
     * Check if a timestamp range is fully covered by existing sync ranges.
     * Returns true if fully covered, false if any gaps exist.
     */
    @Transaction
    suspend fun isRangeSynced(
        chatGuid: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): Boolean {
        val overlapping = findOverlappingRanges(chatGuid, startTimestamp, endTimestamp)
        if (overlapping.isEmpty()) return false

        // Check if the ranges fully cover the requested range
        val sortedRanges = overlapping.sortedBy { it.startTimestamp }

        // First range must start at or before our start
        if (sortedRanges.first().startTimestamp > startTimestamp) return false

        // Last range must end at or after our end
        if (sortedRanges.last().endTimestamp < endTimestamp) return false

        // Check for gaps between ranges
        for (i in 0 until sortedRanges.size - 1) {
            val current = sortedRanges[i]
            val next = sortedRanges[i + 1]
            // If there's a gap between ranges, it's not fully synced
            if (current.endTimestamp < next.startTimestamp - 1) {
                return false
            }
        }

        return true
    }
}
