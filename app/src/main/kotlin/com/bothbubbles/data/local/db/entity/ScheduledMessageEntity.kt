package com.bothbubbles.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_messages",
    indices = [
        Index(value = ["chat_guid"]),
        Index(value = ["scheduled_at"]),
        Index(value = ["status"])
    ]
)
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    @ColumnInfo(name = "text")
    val text: String?,

    @ColumnInfo(name = "attachment_uris")
    val attachmentUris: String? = null, // JSON array of URI strings

    @ColumnInfo(name = "scheduled_at")
    val scheduledAt: Long, // Timestamp when message should be sent

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "status")
    val status: ScheduledMessageStatus = ScheduledMessageStatus.PENDING,

    @ColumnInfo(name = "work_request_id")
    val workRequestId: String? = null, // WorkManager request ID for cancellation

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)

enum class ScheduledMessageStatus {
    PENDING,    // Waiting to be sent
    SENDING,    // Currently being sent
    SENT,       // Successfully sent
    FAILED,     // Failed to send
    CANCELLED   // Cancelled by user
}
