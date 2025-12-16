package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a contact to auto-share ETA with when navigation starts.
 *
 * This is a simplified model that replaces the destination-based AutoShareRuleEntity.
 * Since navigation apps don't expose the destination in notifications, we can't do
 * destination-based auto-sharing. Instead, we just maintain a list of contacts (max 5)
 * who will receive ETA updates whenever navigation starts.
 *
 * The minimum ETA threshold is stored as a global setting in SettingsDataStore.
 */
@Entity(
    tableName = "auto_share_contacts",
    indices = [
        Index(value = ["enabled"])
    ]
)
data class AutoShareContactEntity(
    /**
     * The chat GUID to auto-share ETA with.
     * This is the primary key since each contact can only be added once.
     */
    @PrimaryKey
    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    /**
     * Cached display name for the contact (for UI display).
     */
    @ColumnInfo(name = "display_name")
    val displayName: String,

    /**
     * Whether auto-sharing is enabled for this contact.
     */
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /**
     * Timestamp when this contact was added.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
