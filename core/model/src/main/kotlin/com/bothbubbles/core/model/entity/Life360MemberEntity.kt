package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Life360 circle members.
 *
 * Stores member location data fetched from Life360 API, with optional mapping
 * to BothBubbles contacts (handles). The mapping enables showing Life360 location
 * in conversation details and enables location-aware features.
 *
 * @see Life360Dao for database operations
 */
@Entity(
    tableName = "life360_members",
    foreignKeys = [
        ForeignKey(
            entity = HandleEntity::class,
            parentColumns = ["id"],
            childColumns = ["mapped_handle_id"],
            onDelete = ForeignKey.SET_NULL  // Mapping cleared when handle deleted, member preserved
        )
    ],
    indices = [
        Index(value = ["mapped_handle_id"]),  // Critical for conversation list performance
        Index(value = ["circle_id"]),         // For filtering by circle
        Index(value = ["phone_number"])       // For auto-matching contacts
    ]
)
data class Life360MemberEntity(
    @PrimaryKey
    @ColumnInfo(name = "member_id")
    val memberId: String,

    @ColumnInfo(name = "circle_id")
    val circleId: String,

    @ColumnInfo(name = "first_name")
    val firstName: String,

    @ColumnInfo(name = "last_name")
    val lastName: String? = null,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String? = null,        // For auto-matching contacts

    // Location data (nullable - location may be unavailable)
    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "accuracy_meters")
    val accuracyMeters: Int? = null,        // Already converted from feet

    @ColumnInfo(name = "address")
    val address: String? = null,

    @ColumnInfo(name = "short_address", defaultValue = "NULL")
    val shortAddress: String? = null,   // City, State (for subtitle display)

    @ColumnInfo(name = "place_name")
    val placeName: String? = null,          // e.g., "Home", "Work"

    // Device status
    @ColumnInfo(name = "battery")
    val battery: Int? = null,

    @ColumnInfo(name = "is_driving")
    val isDriving: Boolean? = null,

    @ColumnInfo(name = "is_charging")
    val isCharging: Boolean? = null,

    // Timestamps
    @ColumnInfo(name = "location_timestamp")
    val locationTimestamp: Long? = null,    // When Life360 got location (null if no location)

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long,                  // When we fetched it

    // Location unavailability reason
    @ColumnInfo(name = "no_location_reason")
    val noLocationReason: String? = null,   // If location unavailable, why

    // Contact mapping (nullable FK to HandleEntity)
    @ColumnInfo(name = "mapped_handle_id")
    val mappedHandleId: Long? = null,

    // Auto-linking control: when true, prevents autoMapContacts from re-linking
    // Set to true when user manually unlinks, cleared when user manually links
    @ColumnInfo(name = "auto_link_disabled", defaultValue = "0")
    val autoLinkDisabled: Boolean = false
) {
    /**
     * Combined display name from first and last name.
     */
    val displayName: String
        get() = listOfNotNull(firstName, lastName).joinToString(" ").ifEmpty { "Unknown" }

    /**
     * Whether this member has valid location data.
     */
    val hasLocation: Boolean
        get() = latitude != null && longitude != null
}
