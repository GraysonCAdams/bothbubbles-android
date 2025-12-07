package com.bothbubbles.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Cross-reference table for many-to-many relationship between chats and handles (participants)
 */
@Entity(
    tableName = "chat_handle_cross_ref",
    primaryKeys = ["chat_guid", "handle_id"],
    indices = [
        Index(value = ["chat_guid"]),
        Index(value = ["handle_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["guid"],
            childColumns = ["chat_guid"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = HandleEntity::class,
            parentColumns = ["id"],
            childColumns = ["handle_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatHandleCrossRef(
    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    @ColumnInfo(name = "handle_id")
    val handleId: Long
)
