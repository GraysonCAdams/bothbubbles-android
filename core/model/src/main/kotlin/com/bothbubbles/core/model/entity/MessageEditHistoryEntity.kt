package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing message edit history.
 * When a message is edited, the previous text is saved here before updating the message.
 * This allows users to view the edit history like iOS does.
 *
 * Each row represents a previous version of the message text.
 */
@Entity(
    tableName = "message_edit_history",
    indices = [
        Index(value = ["message_guid"]),
        Index(value = ["edited_at"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["guid"],
            childColumns = ["message_guid"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEditHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The GUID of the message this history entry belongs to */
    @ColumnInfo(name = "message_guid")
    val messageGuid: String,

    /** The message text before the edit */
    @ColumnInfo(name = "previous_text")
    val previousText: String?,

    /** Timestamp when this version was superseded by a new edit */
    @ColumnInfo(name = "edited_at")
    val editedAt: Long
)
