package com.bothbubbles.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an auto-share rule for ETA sharing.
 *
 * When navigation to a matching destination is detected, ETA will
 * automatically be shared with the associated recipients.
 */
@Entity(
    tableName = "auto_share_rules",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["destination_name"])
    ]
)
data class AutoShareRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * User-friendly name for the destination (e.g., "Home", "Work").
     * Used for display in the UI.
     */
    @ColumnInfo(name = "destination_name")
    val destinationName: String,

    /**
     * Comma-separated keywords for matching navigation destinations.
     * If any keyword matches the navigation destination, this rule triggers.
     * Example: "Home,123 Main St,My House"
     */
    @ColumnInfo(name = "keywords")
    val keywords: String,

    /**
     * Location type for UI categorization.
     * Values: "home", "work", "custom"
     */
    @ColumnInfo(name = "location_type")
    val locationType: String = "custom",

    /**
     * Whether this rule is currently enabled.
     */
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /**
     * Timestamp when this rule was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Timestamp when this rule was last triggered (for rate limiting).
     */
    @ColumnInfo(name = "last_triggered_at")
    val lastTriggeredAt: Long? = null,

    /**
     * Count of consecutive days this rule has been triggered (for privacy reminder).
     */
    @ColumnInfo(name = "consecutive_trigger_days")
    val consecutiveTriggerDays: Int = 0,

    /**
     * Date (YYYYMMDD) of last trigger, for tracking consecutive days.
     */
    @ColumnInfo(name = "last_trigger_date")
    val lastTriggerDate: Int? = null
)
