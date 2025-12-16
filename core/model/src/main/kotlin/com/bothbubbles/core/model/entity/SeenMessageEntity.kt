package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for tracking messages that have been seen/processed to prevent duplicate notifications.
 * Used by MessageDeduplicator to persist seen messages across app restarts.
 *
 * Old entries are automatically cleaned up to prevent unbounded growth.
 */
@Entity(
    tableName = "seen_messages",
    indices = [
        Index(value = ["message_guid"], unique = true),
        Index(value = ["seen_at"])
    ]
)
data class SeenMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The message GUID that was seen/processed */
    @ColumnInfo(name = "message_guid")
    val messageGuid: String,

    /** Timestamp when the message was seen */
    @ColumnInfo(name = "seen_at")
    val seenAt: Long = System.currentTimeMillis()
)
