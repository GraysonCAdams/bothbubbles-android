package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for calendar-contact associations.
 *
 * Stores which device calendar is associated with a contact's address.
 * Uses address-based linking (like Life360MemberEntity) so the association
 * works across both iMessage and SMS handles for the same contact.
 *
 * @see ContactCalendarDao for database operations
 */
@Entity(
    tableName = "contact_calendar_associations",
    indices = [
        Index(value = ["calendar_id"])  // For reverse lookups
    ]
)
data class ContactCalendarAssociationEntity(
    /**
     * The normalized address (phone number or email) of the linked contact.
     * Using address-based linking ensures it works across iMessage and SMS handles.
     */
    @PrimaryKey
    @ColumnInfo(name = "linked_address")
    val linkedAddress: String,

    /**
     * The device calendar ID from CalendarContract.Calendars._ID.
     */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long,

    /**
     * Cached calendar display name for UI display without re-querying.
     */
    @ColumnInfo(name = "calendar_display_name")
    val calendarDisplayName: String,

    /**
     * Cached calendar color for UI display.
     */
    @ColumnInfo(name = "calendar_color")
    val calendarColor: Int? = null,

    /**
     * Cached account name the calendar belongs to.
     */
    @ColumnInfo(name = "account_name")
    val accountName: String? = null,

    /**
     * When this association was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * When this association was last updated.
     */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
