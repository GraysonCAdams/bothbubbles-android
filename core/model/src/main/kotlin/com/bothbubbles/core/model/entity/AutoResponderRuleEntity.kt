package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores auto-responder rules with configurable conditions.
 *
 * Rules are evaluated in priority order (lower priority = checked first).
 * Conditions are stored as nullable fields - null means "don't check this condition".
 * When a message arrives, the first rule whose conditions all match is used.
 *
 * ## Condition Types
 * - **Source filtering**: Which Stitches trigger this rule (SMS, iMessage, etc.)
 * - **Sender filtering**: First-time senders only
 * - **Time-based**: Day of week, time range
 * - **System state**: DND mode, driving, on call
 * - **Location**: Inside/outside a geofence
 */
@Entity(
    tableName = "auto_responder_rules",
    indices = [Index(value = ["priority"])]
)
data class AutoResponderRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * User-defined name for the rule (e.g., "Night Mode", "Driving Response")
     */
    val name: String,

    /**
     * The message to send when this rule matches
     */
    val message: String,

    /**
     * Rule evaluation priority. Lower number = higher priority (evaluated first).
     * First matching rule wins.
     */
    val priority: Int,

    /**
     * Whether this rule is active
     */
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    // ===== Source Filtering =====

    /**
     * Comma-separated Stitch IDs (e.g., "sms,bluebubbles").
     * Null = match all sources.
     */
    @ColumnInfo(name = "source_stitch_ids")
    val sourceStitchIds: String? = null,

    /**
     * If true, only match first message ever from this sender.
     * Uses AutoRespondedSenderDao to track who has received responses.
     */
    @ColumnInfo(name = "first_time_from_sender")
    val firstTimeFromSender: Boolean? = null,

    // ===== Time-Based Conditions =====

    /**
     * Comma-separated days: "MON,TUE,WED,THU,FRI,SAT,SUN"
     * Null = match all days.
     */
    @ColumnInfo(name = "days_of_week")
    val daysOfWeek: String? = null,

    /**
     * Start of time range in minutes from midnight (e.g., 540 = 9:00 AM).
     * Used together with timeEndMinutes.
     */
    @ColumnInfo(name = "time_start_minutes")
    val timeStartMinutes: Int? = null,

    /**
     * End of time range in minutes from midnight.
     * If end < start, wraps around midnight (e.g., 22:00-07:00).
     */
    @ColumnInfo(name = "time_end_minutes")
    val timeEndMinutes: Int? = null,

    // ===== System State Conditions =====

    /**
     * Comma-separated DND modes: "PRIORITY_ONLY,ALARMS_ONLY,TOTAL_SILENCE"
     * Null = don't check DND state.
     */
    @ColumnInfo(name = "dnd_modes")
    val dndModes: String? = null,

    /**
     * If true, only match when connected to Android Auto.
     */
    @ColumnInfo(name = "require_driving")
    val requireDriving: Boolean? = null,

    /**
     * If true, only match when on a phone call.
     */
    @ColumnInfo(name = "require_on_call")
    val requireOnCall: Boolean? = null,

    // ===== Location Conditions =====

    /**
     * Human-readable location name for display (e.g., "Home", "Work")
     */
    @ColumnInfo(name = "location_name")
    val locationName: String? = null,

    /**
     * Geofence center latitude
     */
    @ColumnInfo(name = "location_lat")
    val locationLat: Double? = null,

    /**
     * Geofence center longitude
     */
    @ColumnInfo(name = "location_lng")
    val locationLng: Double? = null,

    /**
     * Geofence radius in meters. Default 100m if not specified.
     */
    @ColumnInfo(name = "location_radius_meters")
    val locationRadiusMeters: Int? = null,

    /**
     * If true, match when INSIDE geofence. If false, match when OUTSIDE.
     */
    @ColumnInfo(name = "location_inside")
    val locationInside: Boolean? = null,

    // ===== Metadata =====

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Returns a summary of active conditions for display in rule list.
     * Example: "SMS · First time only · 10PM-7AM"
     */
    fun getConditionSummary(): String {
        val parts = mutableListOf<String>()

        sourceStitchIds?.let { ids ->
            val sources = ids.split(",").map { it.trim().replaceFirstChar { c -> c.uppercase() } }
            parts.add(sources.joinToString(", "))
        }

        if (firstTimeFromSender == true) {
            parts.add("First time only")
        }

        if (timeStartMinutes != null && timeEndMinutes != null) {
            parts.add("${formatTime(timeStartMinutes)}-${formatTime(timeEndMinutes)}")
        }

        if (requireDriving == true) {
            parts.add("Android Auto")
        }

        if (requireOnCall == true) {
            parts.add("On call")
        }

        dndModes?.let {
            parts.add("DND")
        }

        locationName?.let {
            val prefix = if (locationInside == true) "At" else "Away from"
            parts.add("$prefix $it")
        }

        return parts.joinToString(" · ").ifEmpty { "Always" }
    }

    private fun formatTime(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        val period = if (hours < 12) "AM" else "PM"
        val displayHour = when {
            hours == 0 -> 12
            hours > 12 -> hours - 12
            else -> hours
        }
        return if (mins == 0) "$displayHour$period" else "$displayHour:${mins.toString().padStart(2, '0')}$period"
    }
}
