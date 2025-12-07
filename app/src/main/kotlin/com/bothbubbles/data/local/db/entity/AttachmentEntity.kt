package com.bothbubbles.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    indices = [
        Index(value = ["guid"], unique = true),
        Index(value = ["message_guid"])
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
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "guid")
    val guid: String,

    @ColumnInfo(name = "message_guid")
    val messageGuid: String,

    @ColumnInfo(name = "original_row_id")
    val originalRowId: Int? = null,

    @ColumnInfo(name = "uti")
    val uti: String? = null,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "is_outgoing")
    val isOutgoing: Boolean = false,

    @ColumnInfo(name = "transfer_name")
    val transferName: String? = null,

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long? = null,

    @ColumnInfo(name = "height")
    val height: Int? = null,

    @ColumnInfo(name = "width")
    val width: Int? = null,

    @ColumnInfo(name = "web_url")
    val webUrl: String? = null,

    @ColumnInfo(name = "has_live_photo")
    val hasLivePhoto: Boolean = false,

    @ColumnInfo(name = "hide_attachment")
    val hideAttachment: Boolean = false,

    @ColumnInfo(name = "is_sticker")
    val isSticker: Boolean = false,

    @ColumnInfo(name = "local_path")
    val localPath: String? = null,

    @ColumnInfo(name = "blurhash")
    val blurhash: String? = null,

    // Metadata stored as JSON
    @ColumnInfo(name = "metadata")
    val metadata: String? = null
) {
    /**
     * MIME type category (image, video, audio, etc.)
     */
    val mimeCategory: String?
        get() = mimeType?.split("/")?.firstOrNull()

    /**
     * Whether this is an image attachment
     */
    val isImage: Boolean
        get() = mimeCategory == "image"

    /**
     * Whether this is a video attachment
     */
    val isVideo: Boolean
        get() = mimeCategory == "video"

    /**
     * Whether this is an audio attachment
     */
    val isAudio: Boolean
        get() = mimeCategory == "audio"

    /**
     * Whether attachment has been downloaded locally
     */
    val isDownloaded: Boolean
        get() = localPath != null

    /**
     * Whether this attachment has valid dimensions
     */
    val hasValidSize: Boolean
        get() = (width ?: 0) > 0 && (height ?: 0) > 0

    /**
     * Aspect ratio for layout calculations
     */
    val aspectRatio: Float
        get() = if (hasValidSize) width!!.toFloat() / height!!.toFloat() else 1f

    /**
     * File extension from transfer name
     */
    val fileExtension: String?
        get() = transferName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }

    /**
     * Friendly file size string
     */
    val friendlySize: String
        get() = when {
            totalBytes == null -> "Unknown"
            totalBytes < 1024 -> "$totalBytes B"
            totalBytes < 1024 * 1024 -> "${totalBytes / 1024} KB"
            totalBytes < 1024 * 1024 * 1024 -> "${totalBytes / (1024 * 1024)} MB"
            else -> "${totalBytes / (1024 * 1024 * 1024)} GB"
        }
}
