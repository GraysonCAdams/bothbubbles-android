package com.bothbubbles.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents an attachment queued with a pending message.
 *
 * Attachments are copied to app-internal storage when queued to ensure they
 * survive app kills and URI permission revocation. The persisted files are
 * cleaned up after successful send.
 */
@Entity(
    tableName = "pending_attachments",
    indices = [
        Index(value = ["pending_message_id"]),
        Index(value = ["local_id"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = PendingMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pending_message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PendingAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Local UUID for tracking this attachment
     */
    @ColumnInfo(name = "local_id")
    val localId: String,

    /**
     * Foreign key to parent pending message
     */
    @ColumnInfo(name = "pending_message_id")
    val pendingMessageId: Long,

    /**
     * Original content URI (for reference/debugging)
     */
    @ColumnInfo(name = "original_uri")
    val originalUri: String,

    /**
     * Path to the persisted file in app-internal storage.
     * This file survives app kills and URI permission revocation.
     */
    @ColumnInfo(name = "persisted_path")
    val persistedPath: String,

    /**
     * Original file name for display and MIME type detection
     */
    @ColumnInfo(name = "file_name")
    val fileName: String,

    /**
     * MIME type of the attachment
     */
    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    /**
     * File size in bytes
     */
    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    /**
     * Upload progress (0.0 to 1.0)
     */
    @ColumnInfo(name = "upload_progress")
    val uploadProgress: Float = 0f,

    /**
     * Error type if upload failed
     */
    @ColumnInfo(name = "error_type")
    val errorType: String? = null,

    /**
     * User-friendly error message
     */
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    /**
     * Order index for maintaining attachment order
     */
    @ColumnInfo(name = "order_index")
    val orderIndex: Int = 0,

    /**
     * Image quality setting for compression.
     * Values: "AUTO", "STANDARD", "HIGH", "ORIGINAL"
     * Default is STANDARD for good balance of quality and file size.
     */
    @ColumnInfo(name = "quality")
    val quality: String = "STANDARD",

    /**
     * Optional caption text to display with the attachment.
     * Shown below the attachment in the message bubble.
     */
    @ColumnInfo(name = "caption")
    val caption: String? = null
)
