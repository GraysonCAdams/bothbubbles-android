package com.bothbubbles.services.calendar

import android.content.ContentResolver
import android.content.Context
import android.provider.CalendarContract
import com.bothbubbles.util.PermissionStateMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for querying device calendars via Android Calendar Provider.
 *
 * Requires READ_CALENDAR permission - always check before calling methods.
 * Methods return empty results if permission is not granted rather than throwing.
 */
@Singleton
class CalendarContentProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionStateMonitor: PermissionStateMonitor
) {
    private val contentResolver: ContentResolver = context.contentResolver

    companion object {
        private const val TAG = "CalendarContentProvider"
    }

    // ===== Calendar List =====

    /**
     * Get all visible calendars on the device.
     * Returns empty list if permission not granted.
     */
    suspend fun getCalendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!permissionStateMonitor.hasCalendarPermission()) {
            Timber.tag(TAG).d("Calendar permission not granted, returning empty list")
            return@withContext emptyList()
        }

        val calendars = mutableListOf<DeviceCalendar>()

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE
        )

        try {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    calendars.add(
                        DeviceCalendar(
                            id = cursor.getLong(0),
                            displayName = cursor.getString(1) ?: "Unknown",
                            color = cursor.getInt(2).takeIf { !cursor.isNull(2) },
                            accountName = cursor.getString(3),
                            accountType = cursor.getString(4)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to query calendars")
        }

        calendars
    }

    // ===== Event Queries =====

    /**
     * Get current and upcoming events for a calendar within a time window.
     *
     * @param calendarId The calendar to query
     * @param windowHours How far ahead to look (default 4 hours)
     * @return List of events starting from now, sorted by start time
     */
    suspend fun getUpcomingEvents(
        calendarId: Long,
        windowHours: Int = 4
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!permissionStateMonitor.hasCalendarPermission()) {
            return@withContext emptyList()
        }

        val now = System.currentTimeMillis()
        val windowEnd = now + (windowHours * 60 * 60 * 1000L)
        val events = mutableListOf<CalendarEvent>()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_COLOR
        )

        // Query events that:
        // 1. Belong to the specified calendar
        // 2. Start before window end AND end after now (overlapping or upcoming)
        val selection = """
            ${CalendarContract.Events.CALENDAR_ID} = ?
            AND ${CalendarContract.Events.DTSTART} < ?
            AND ${CalendarContract.Events.DTEND} > ?
            AND ${CalendarContract.Events.DELETED} = 0
        """.trimIndent()

        try {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                arrayOf(calendarId.toString(), windowEnd.toString(), now.toString()),
                "${CalendarContract.Events.DTSTART} ASC LIMIT 5"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    events.add(
                        CalendarEvent(
                            id = cursor.getLong(0),
                            title = cursor.getString(1) ?: "No title",
                            startTime = cursor.getLong(2),
                            endTime = cursor.getLong(3),
                            isAllDay = cursor.getInt(4) == 1,
                            location = cursor.getString(5),
                            color = cursor.getInt(6).takeIf { !cursor.isNull(6) }
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to query events for calendar $calendarId")
        }

        events
    }

    /**
     * Get the current or next event for a calendar.
     * Efficient single-event query for chat header display.
     *
     * @return CurrentEventState indicating current event, upcoming event, or nothing
     */
    suspend fun getCurrentOrNextEvent(
        calendarId: Long,
        lookaheadHours: Int = 4
    ): CurrentEventState = withContext(Dispatchers.IO) {
        if (!permissionStateMonitor.hasCalendarPermission()) {
            return@withContext CurrentEventState.NoPermission
        }

        val now = System.currentTimeMillis()
        val events = getUpcomingEvents(calendarId, lookaheadHours)

        if (events.isEmpty()) {
            return@withContext CurrentEventState.NoUpcomingEvents
        }

        val currentEvent = events.firstOrNull { event ->
            event.startTime <= now && event.endTime > now
        }

        if (currentEvent != null) {
            return@withContext CurrentEventState.InEvent(currentEvent)
        }

        val nextEvent = events.firstOrNull { it.startTime > now }
        if (nextEvent != null) {
            return@withContext CurrentEventState.UpcomingEvent(nextEvent)
        }

        CurrentEventState.NoUpcomingEvents
    }
}

// ===== Data Classes =====

/**
 * Represents a device calendar.
 */
data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val color: Int?,
    val accountName: String?,
    val accountType: String?
)

/**
 * Represents a calendar event.
 */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val isAllDay: Boolean,
    val location: String?,
    val color: Int?
) {
    /**
     * Duration in milliseconds.
     */
    val durationMs: Long
        get() = endTime - startTime

    /**
     * Whether the event is currently happening.
     */
    fun isCurrentlyActive(now: Long = System.currentTimeMillis()): Boolean =
        startTime <= now && endTime > now

    /**
     * Time until event starts (negative if already started).
     */
    fun timeUntilStart(now: Long = System.currentTimeMillis()): Long =
        startTime - now

    /**
     * Time until event ends.
     */
    fun timeUntilEnd(now: Long = System.currentTimeMillis()): Long =
        endTime - now
}

/**
 * Represents the current event state for a calendar.
 */
sealed class CurrentEventState {
    /** Calendar permission not granted */
    data object NoPermission : CurrentEventState()

    /** No events within the lookahead window */
    data object NoUpcomingEvents : CurrentEventState()

    /** Currently in an event */
    data class InEvent(val event: CalendarEvent) : CurrentEventState()

    /** Event upcoming within lookahead window */
    data class UpcomingEvent(val event: CalendarEvent) : CurrentEventState()
}
