package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for calendar event occurrences displayed in chat.
 *
 * Stores calendar events that have been "logged" into a conversation as system-style
 * indicators (similar to group member changes). Events are stored separately from
 * messages and merged at display time.
 *
 * Uses address-based linking so events appear in both iMessage and SMS conversations
 * for the same contact.
 *
 * @see CalendarEventOccurrenceDao for database operations
 */
@Entity(
    tableName = "calendar_event_occurrences",
    indices = [
        Index(value = ["chat_address"]),
        Index(value = ["event_start_time"]),
        Index(value = ["chat_address", "event_id", "event_start_time"], unique = true)
    ]
)
data class CalendarEventOccurrenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * The normalized address (phone number or email) this event belongs to.
     * Using address-based linking ensures it works across iMessage and SMS handles.
     */
    @ColumnInfo(name = "chat_address")
    val chatAddress: String,

    /**
     * The device calendar event ID from CalendarContract.Events._ID.
     */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /**
     * The event title/name.
     */
    @ColumnInfo(name = "event_title")
    val eventTitle: String,

    /**
     * Event start time in milliseconds since epoch.
     */
    @ColumnInfo(name = "event_start_time")
    val eventStartTime: Long,

    /**
     * Event end time in milliseconds since epoch.
     */
    @ColumnInfo(name = "event_end_time")
    val eventEndTime: Long,

    /**
     * Whether this is an all-day event.
     */
    @ColumnInfo(name = "is_all_day")
    val isAllDay: Boolean = false,

    /**
     * Cached display name of the contact for rendering.
     */
    @ColumnInfo(name = "contact_display_name")
    val contactDisplayName: String,

    /**
     * When this occurrence was recorded in the database.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
