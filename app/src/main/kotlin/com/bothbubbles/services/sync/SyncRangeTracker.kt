package com.bothbubbles.services.sync

import android.util.Log
import com.bothbubbles.data.local.db.dao.SyncRangeDao
import com.bothbubbles.data.local.db.entity.SyncRangeEntity
import com.bothbubbles.data.local.db.entity.SyncSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for tracking which message timestamp ranges have been synced from the server.
 *
 * This enables efficient sparse pagination by:
 * 1. Recording ranges that have been fetched from the server
 * 2. Detecting gaps in synced data when scrolling
 * 3. Only requesting data from the server for unsynced ranges
 *
 * The tracker automatically merges overlapping/adjacent ranges to keep
 * the sync_ranges table minimal and efficient.
 */
@Singleton
class SyncRangeTracker @Inject constructor(
    private val syncRangeDao: SyncRangeDao
) {
    companion object {
        private const val TAG = "SyncRangeTracker"
    }

    /**
     * Record that a timestamp range has been synced from the server.
     * Automatically merges with any overlapping ranges.
     *
     * @param chatGuid The chat this range belongs to
     * @param startTimestamp The oldest message timestamp in the range
     * @param endTimestamp The newest message timestamp in the range
     * @param source Where this sync came from (initial sync, on-demand, etc.)
     */
    suspend fun recordSyncedRange(
        chatGuid: String,
        startTimestamp: Long,
        endTimestamp: Long,
        source: SyncSource = SyncSource.ON_DEMAND
    ) {
        if (startTimestamp > endTimestamp) {
            Log.w(TAG, "Invalid range: start ($startTimestamp) > end ($endTimestamp)")
            return
        }

        Log.d(TAG, "Recording synced range for $chatGuid: $startTimestamp - $endTimestamp (${source.name})")
        syncRangeDao.recordSyncedRange(
            chatGuid = chatGuid,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            syncSource = source.name
        )
    }

    /**
     * Record a synced range for multiple chats (unified chat groups).
     */
    suspend fun recordSyncedRangeForChats(
        chatGuids: List<String>,
        startTimestamp: Long,
        endTimestamp: Long,
        source: SyncSource = SyncSource.ON_DEMAND
    ) {
        chatGuids.forEach { chatGuid ->
            recordSyncedRange(chatGuid, startTimestamp, endTimestamp, source)
        }
    }

    /**
     * Check if a specific timestamp is within a synced range.
     *
     * @return true if the timestamp has already been synced
     */
    suspend fun isTimestampSynced(chatGuid: String, timestamp: Long): Boolean {
        return syncRangeDao.findRangeContaining(chatGuid, timestamp) != null
    }

    /**
     * Check if a timestamp range is fully covered by existing sync ranges.
     *
     * @return true if the entire range has been synced (no gaps)
     */
    suspend fun isRangeFullySynced(
        chatGuid: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): Boolean {
        return syncRangeDao.isRangeSynced(chatGuid, startTimestamp, endTimestamp)
    }

    /**
     * Find gaps in synced ranges for a chat.
     * Returns timestamp ranges that need to be fetched from the server.
     *
     * @param chatGuid The chat to check
     * @param fromTimestamp The oldest timestamp to check (default: 0 = beginning of time)
     * @param toTimestamp The newest timestamp to check (default: now)
     * @return List of (start, end) timestamp pairs representing gaps
     */
    suspend fun findSyncGaps(
        chatGuid: String,
        fromTimestamp: Long = 0,
        toTimestamp: Long = System.currentTimeMillis()
    ): List<Pair<Long, Long>> {
        val ranges = syncRangeDao.getRangesForChat(chatGuid)

        if (ranges.isEmpty()) {
            // No sync ranges - entire span is a gap
            return listOf(fromTimestamp to toTimestamp)
        }

        val sortedRanges = ranges.sortedBy { it.startTimestamp }
        val gaps = mutableListOf<Pair<Long, Long>>()

        // Check gap before first range
        if (sortedRanges.first().startTimestamp > fromTimestamp) {
            gaps.add(fromTimestamp to sortedRanges.first().startTimestamp)
        }

        // Check gaps between ranges
        for (i in 0 until sortedRanges.size - 1) {
            val current = sortedRanges[i]
            val next = sortedRanges[i + 1]

            // Gap exists if there's space between ranges
            if (current.endTimestamp < next.startTimestamp - 1) {
                gaps.add(current.endTimestamp + 1 to next.startTimestamp - 1)
            }
        }

        // Check gap after last range
        if (sortedRanges.last().endTimestamp < toTimestamp) {
            gaps.add(sortedRanges.last().endTimestamp + 1 to toTimestamp)
        }

        return gaps.filter { (start, end) ->
            start >= fromTimestamp && end <= toTimestamp && start <= end
        }
    }

    /**
     * Get the oldest synced timestamp for a chat.
     * Returns null if no sync ranges exist.
     */
    suspend fun getOldestSyncedTimestamp(chatGuid: String): Long? {
        return syncRangeDao.getOldestSyncedTimestamp(chatGuid)
    }

    /**
     * Get the newest synced timestamp for a chat.
     * Returns null if no sync ranges exist.
     */
    suspend fun getNewestSyncedTimestamp(chatGuid: String): Long? {
        return syncRangeDao.getNewestSyncedTimestamp(chatGuid)
    }

    /**
     * Check if a chat has any synced ranges.
     */
    suspend fun hasSyncedData(chatGuid: String): Boolean {
        return syncRangeDao.hasSyncedRanges(chatGuid)
    }

    /**
     * Check if any of the provided chat GUIDs have synced data.
     */
    suspend fun hasSyncedDataForAny(chatGuids: List<String>): Boolean {
        return chatGuids.any { hasSyncedData(it) }
    }

    /**
     * Clear all sync ranges for a chat.
     * Use this when re-syncing a chat from scratch.
     */
    suspend fun clearRangesForChat(chatGuid: String) {
        Log.d(TAG, "Clearing sync ranges for $chatGuid")
        syncRangeDao.deleteRangesForChat(chatGuid)
    }

    /**
     * Clear all sync ranges.
     * Use this when resetting the entire app.
     */
    suspend fun clearAllRanges() {
        Log.d(TAG, "Clearing all sync ranges")
        syncRangeDao.deleteAllRanges()
    }

    /**
     * Get all sync ranges for a chat (for debugging/inspection).
     */
    suspend fun getRangesForChat(chatGuid: String): List<SyncRangeEntity> {
        return syncRangeDao.getRangesForChat(chatGuid)
    }
}
