package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tombstone entity for tracking deleted messages and chats.
 *
 * When a user deletes a message or chat locally, we record its GUID here.
 * During sync operations, we check this table to avoid resurrecting deleted items.
 *
 * Tombstones can be pruned after a retention period (e.g., 90 days) since
 * old deleted items are unlikely to appear in sync queries.
 */
@Entity(
    tableName = "tombstones",
    indices = [
        Index(value = ["guid"], unique = true),
        Index(value = ["type"]),
        Index(value = ["deleted_at"])
    ]
)
data class TombstoneEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The GUID of the deleted message or chat */
    @ColumnInfo(name = "guid")
    val guid: String,

    /** Type of deleted item: "message" or "chat" */
    @ColumnInfo(name = "type")
    val type: String,

    /** Timestamp when the item was deleted */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_MESSAGE = "message"
        const val TYPE_CHAT = "chat"
    }
}
