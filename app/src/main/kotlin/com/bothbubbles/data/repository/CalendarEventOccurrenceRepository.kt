package com.bothbubbles.data.repository

import com.bothbubbles.core.model.entity.CalendarEventOccurrenceEntity
import com.bothbubbles.data.local.db.dao.CalendarEventOccurrenceDao
import com.bothbubbles.data.local.db.dao.ContactCalendarDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.services.calendar.CalendarContentProvider
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing calendar event occurrences displayed in chat.
 *
 * This repository handles:
 * - Syncing calendar events to the database for display in conversations
 * - Retroactive loading of past events when opening a conversation
 * - Cleanup of old event occurrences
 *
 * Events are stored separately from messages and merged at display time.
 */
@Singleton
class CalendarEventOccurrenceRepository @Inject constructor(
    private val dao: CalendarEventOccurrenceDao,
    private val calendarProvider: CalendarContentProvider,
    private val calendarAssociationDao: ContactCalendarDao,
    private val handleDao: HandleDao
) {
    companion object {
        private const val TAG = "CalendarEventOccRepo"
        private const val RETROACTIVE_WINDOW_HOURS = 48
        private const val CLEANUP_DAYS = 7
        private const val UPCOMING_WINDOW_HOURS = 24
    }

    // ===== Query Methods =====

    /**
     * Observe calendar event occurrences for a chat address.
     * Used by the chat UI to display events inline with messages.
     */
    fun observeForAddress(address: String): Flow<List<CalendarEventOccurrenceEntity>> =
        dao.observeForAddress(address)

    /**
     * Get events within a time range for a chat address (one-shot).
     */
    suspend fun getForAddressInRange(
        address: String,
        startTime: Long,
        endTime: Long
    ): List<CalendarEventOccurrenceEntity> =
        dao.getForAddressInRange(address, startTime, endTime)

    // ===== Sync Methods =====

    /**
     * Sync upcoming calendar events for a contact (background sync).
     * Fetches events from now to +24 hours and records occurrences.
     *
     * @param address The normalized contact address
     * @return Number of new events inserted
     */
    suspend fun syncEventsForContact(address: String): Result<Int> = runCatching {
        val association = calendarAssociationDao.getByAddress(address)
        if (association == null) {
            Timber.tag(TAG).d("No calendar association for $address, skipping sync")
            return@runCatching 0
        }

        val displayName = getDisplayNameForAddress(address)
        val now = System.currentTimeMillis()
        val windowEnd = now + (UPCOMING_WINDOW_HOURS * 60 * 60 * 1000L)

        val events = calendarProvider.getEventsInRange(
            calendarId = association.calendarId,
            startTime = now,
            endTime = windowEnd
        )

        var insertedCount = 0
        for (event in events) {
            if (!dao.exists(address, event.id, event.startTime)) {
                dao.insert(
                    CalendarEventOccurrenceEntity(
                        chatAddress = address,
                        eventId = event.id,
                        eventTitle = event.title,
                        eventStartTime = event.startTime,
                        eventEndTime = event.endTime,
                        isAllDay = event.isAllDay,
                        contactDisplayName = displayName
                    )
                )
                insertedCount++
            }
        }

        if (insertedCount > 0) {
            Timber.tag(TAG).d("Synced $insertedCount new events for $address")
        }
        insertedCount
    }

    /**
     * Retroactively fetch events for the past 48 hours.
     * Called when opening a conversation if sync hasn't run recently.
     *
     * @param address The normalized contact address
     * @return Number of new events inserted
     */
    suspend fun retroactiveSyncForContact(address: String): Result<Int> = runCatching {
        val association = calendarAssociationDao.getByAddress(address)
        if (association == null) {
            Timber.tag(TAG).d("No calendar association for $address, skipping retroactive sync")
            return@runCatching 0
        }

        val displayName = getDisplayNameForAddress(address)
        val now = System.currentTimeMillis()
        val windowStart = now - (RETROACTIVE_WINDOW_HOURS * 60 * 60 * 1000L)

        // Query past events using Instances API
        val events = calendarProvider.getEventsInRange(
            calendarId = association.calendarId,
            startTime = windowStart,
            endTime = now
        )

        var insertedCount = 0
        for (event in events) {
            if (!dao.exists(address, event.id, event.startTime)) {
                dao.insert(
                    CalendarEventOccurrenceEntity(
                        chatAddress = address,
                        eventId = event.id,
                        eventTitle = event.title,
                        eventStartTime = event.startTime,
                        eventEndTime = event.endTime,
                        isAllDay = event.isAllDay,
                        contactDisplayName = displayName
                    )
                )
                insertedCount++
            }
        }

        if (insertedCount > 0) {
            Timber.tag(TAG).d("Retroactively synced $insertedCount events for $address")
        }
        insertedCount
    }

    // ===== Cleanup Methods =====

    /**
     * Clean up old event occurrences (older than 7 days).
     * Called periodically by the background worker.
     */
    suspend fun cleanupOldEvents(): Result<Unit> = runCatching {
        val cutoff = System.currentTimeMillis() - (CLEANUP_DAYS * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
        Timber.tag(TAG).d("Cleaned up events older than $CLEANUP_DAYS days")
    }

    /**
     * Delete all events for a specific address.
     * Called when a calendar association is removed.
     */
    suspend fun deleteEventsForAddress(address: String): Result<Unit> = runCatching {
        dao.deleteForAddress(address)
        Timber.tag(TAG).d("Deleted all calendar event occurrences for $address")
    }

    // ===== Helper Methods =====

    /**
     * Get display name for a contact address.
     * Falls back to a truncated address if no handle found.
     */
    private suspend fun getDisplayNameForAddress(address: String): String {
        val handle = handleDao.getHandleByAddressAny(address)
        return handle?.displayName ?: address.take(10)
    }
}
