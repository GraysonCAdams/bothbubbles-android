package com.bothbubbles.ui.components.message

import com.bothbubbles.core.model.entity.CalendarEventOccurrenceEntity

/**
 * UI model for a calendar event occurrence displayed in chat.
 *
 * This represents a calendar event that has been "logged" into a conversation,
 * displayed as a system-style indicator (like group member changes).
 *
 * @property id The database ID for the event occurrence
 * @property contactName The display name of the contact (cached from sync time)
 * @property eventTitle The event title/name
 * @property eventStartTime Event start time in milliseconds since epoch
 * @property eventEndTime Event end time in milliseconds since epoch
 * @property isAllDay Whether this is an all-day event
 */
data class CalendarEventItem(
    val id: Long,
    val contactName: String,
    val eventTitle: String,
    val eventStartTime: Long,
    val eventEndTime: Long,
    val isAllDay: Boolean
) {
    /**
     * Formatted display text for the chat timeline.
     *
     * Format examples:
     * - "10:30 AM · WFH - All Day"
     * - "2:30 PM · Coffee with Jessica (started 5m ago)"
     * - "9:00 AM · train home (ended 2h ago)"
     *
     * @param formattedStartTime The event start time formatted according to system 12h/24h setting
     * @param currentTime Current time for calculating relative timing
     */
    fun getDisplayText(
        formattedStartTime: String,
        currentTime: Long = System.currentTimeMillis()
    ): String {
        val timingInfo = getTimingInfo(currentTime)
        return "$formattedStartTime · $eventTitle$timingInfo"
    }

    /**
     * Get timing information for the event.
     * Returns " - All Day" for all-day events, or "(started/ended X ago)" for timed events.
     */
    private fun getTimingInfo(currentTime: Long): String {
        if (isAllDay) return " - All Day"

        return when {
            currentTime < eventStartTime -> {
                // Event hasn't started yet
                ""
            }
            currentTime < eventEndTime -> {
                // Event is currently happening
                val elapsed = currentTime - eventStartTime
                " (started ${formatDuration(elapsed)} ago)"
            }
            else -> {
                // Event has ended
                val elapsed = currentTime - eventEndTime
                " (ended ${formatDuration(elapsed)} ago)"
            }
        }
    }

    /**
     * Format a duration in milliseconds to a human-readable string.
     * Examples: "5m", "2h", "1d"
     */
    private fun formatDuration(durationMs: Long): String {
        val minutes = durationMs / (60 * 1000)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "just now"
        }
    }

    companion object {
        /**
         * Create a CalendarEventItem from a database entity.
         */
        fun fromEntity(entity: CalendarEventOccurrenceEntity): CalendarEventItem {
            return CalendarEventItem(
                id = entity.id,
                contactName = entity.contactDisplayName,
                eventTitle = entity.eventTitle,
                eventStartTime = entity.eventStartTime,
                eventEndTime = entity.eventEndTime,
                isAllDay = entity.isAllDay
            )
        }
    }
}
