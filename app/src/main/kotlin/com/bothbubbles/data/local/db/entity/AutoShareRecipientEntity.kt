package com.bothbubbles.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a recipient for an auto-share rule.
 *
 * Each rule can have multiple recipients who will receive ETA updates
 * when the rule is triggered.
 */
@Entity(
    tableName = "auto_share_recipients",
    foreignKeys = [
        ForeignKey(
            entity = AutoShareRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["rule_id"]),
        Index(value = ["chat_guid"])
    ]
)
data class AutoShareRecipientEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * The ID of the parent auto-share rule.
     */
    @ColumnInfo(name = "rule_id")
    val ruleId: Long,

    /**
     * The chat GUID to share ETA with.
     */
    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    /**
     * Cached display name for the recipient (for UI).
     */
    @ColumnInfo(name = "display_name")
    val displayName: String,

    /**
     * Timestamp when this recipient was added.
     */
    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
)
