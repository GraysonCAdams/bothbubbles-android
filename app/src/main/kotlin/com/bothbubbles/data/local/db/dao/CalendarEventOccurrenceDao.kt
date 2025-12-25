package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.core.model.entity.CalendarEventOccurrenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for calendar event occurrences displayed in chat.
 *
 * Provides operations for storing and querying calendar events that appear
 * as system-style indicators in conversation timelines.
 */
@Dao
interface CalendarEventOccurrenceDao {

    // ===== Queries =====

    /**
     * Observe all calendar event occurrences for a chat address.
     * Used by the chat UI to display events inline with messages.
     */
    @Query("""
        SELECT * FROM calendar_event_occurrences
        WHERE chat_address = :address
        ORDER BY event_start_time DESC
    """)
    fun observeForAddress(address: String): Flow<List<CalendarEventOccurrenceEntity>>

    /**
     * Get events within a time range for a chat (one-shot).
     * Used for filtering events to relevant message windows.
     */
    @Query("""
        SELECT * FROM calendar_event_occurrences
        WHERE chat_address = :address
        AND event_start_time >= :startTime
        AND event_start_time <= :endTime
        ORDER BY event_start_time DESC
    """)
    suspend fun getForAddressInRange(
        address: String,
        startTime: Long,
        endTime: Long
    ): List<CalendarEventOccurrenceEntity>

    /**
     * Check if an event occurrence already exists (for deduplication).
     * Uses the unique constraint columns: address + eventId + startTime.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM calendar_event_occurrences
            WHERE chat_address = :address
            AND event_id = :eventId
            AND event_start_time = :startTime
        )
    """)
    suspend fun exists(address: String, eventId: Long, startTime: Long): Boolean

    /**
     * Get the most recent event for an address (one-shot).
     * Useful for checking if any events exist.
     */
    @Query("""
        SELECT * FROM calendar_event_occurrences
        WHERE chat_address = :address
        ORDER BY event_start_time DESC
        LIMIT 1
    """)
    suspend fun getMostRecentForAddress(address: String): CalendarEventOccurrenceEntity?

    /**
     * Count events for an address.
     */
    @Query("SELECT COUNT(*) FROM calendar_event_occurrences WHERE chat_address = :address")
    suspend fun countForAddress(address: String): Int

    // ===== Mutations =====

    /**
     * Insert a single event occurrence.
     * Uses IGNORE strategy since we have a unique constraint on
     * (address, event_id, event_start_time) to prevent duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(occurrence: CalendarEventOccurrenceEntity)

    /**
     * Insert multiple event occurrences.
     * Duplicates are ignored due to the unique constraint.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(occurrences: List<CalendarEventOccurrenceEntity>)

    /**
     * Delete all events for a specific address.
     * Called when a calendar association is removed.
     */
    @Query("DELETE FROM calendar_event_occurrences WHERE chat_address = :address")
    suspend fun deleteForAddress(address: String)

    /**
     * Delete old event occurrences before a cutoff time.
     * Called periodically to clean up stale events.
     */
    @Query("DELETE FROM calendar_event_occurrences WHERE event_start_time < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)

    /**
     * Delete all event occurrences.
     */
    @Query("DELETE FROM calendar_event_occurrences")
    suspend fun deleteAll()
}
