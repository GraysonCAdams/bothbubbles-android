package com.bothbubbles.data.local.db.entity

import androidx.compose.runtime.Stable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bothbubbles.util.error.AttachmentErrorState

@Stable
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

    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String? = null,

    // Metadata stored as JSON
    @ColumnInfo(name = "metadata")
    val metadata: String? = null,

    /**
     * Transfer state for tracking upload/download lifecycle.
     * Enables snappy UI where outbound attachments show immediately while uploading,
     * and inbound attachments show blurhash placeholders while downloading.
     */
    @ColumnInfo(name = "transfer_state")
    val transferState: String = TransferState.DOWNLOADED.name,

    /**
     * Transfer progress from 0.0 to 1.0.
     * Used for showing upload/download progress in UI.
     */
    @ColumnInfo(name = "transfer_progress")
    val transferProgress: Float = 0f,

    /**
     * Error type when transfer failed.
     * Maps to AttachmentErrorState sealed class types.
     */
    @ColumnInfo(name = "error_type")
    val errorType: String? = null,

    /**
     * User-friendly error message when transfer failed.
     */
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    /**
     * Number of retry attempts for this transfer.
     * Used for exponential backoff in AttachmentDownloadQueue.
     */
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0
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
     * Whether attachment has been downloaded locally.
     * Uses transfer state if available, falls back to localPath check for backwards compatibility.
     */
    val isDownloaded: Boolean
        get() = transferState == TransferState.DOWNLOADED.name || localPath != null

    /**
     * Whether this attachment is currently uploading.
     */
    val isUploading: Boolean
        get() = transferState == TransferState.UPLOADING.name

    /**
     * Whether this attachment is currently downloading.
     */
    val isDownloading: Boolean
        get() = transferState == TransferState.DOWNLOADING.name

    /**
     * Whether this attachment is in any transfer state (uploading or downloading).
     */
    val isTransferring: Boolean
        get() = isUploading || isDownloading

    /**
     * Whether this attachment transfer has failed.
     */
    val hasFailed: Boolean
        get() = transferState == TransferState.FAILED.name

    /**
     * Whether this attachment needs to be downloaded (inbound, not yet downloaded).
     */
    val needsDownload: Boolean
        get() = !isOutgoing && transferState == TransferState.PENDING.name && localPath == null

    /**
     * Parsed transfer state enum.
     */
    val transferStateEnum: TransferState
        get() = TransferState.fromString(transferState)

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

    /**
     * Parsed error state from stored error type and message.
     * Returns null if no error is stored.
     */
    val parsedErrorState: AttachmentErrorState?
        get() = AttachmentErrorState.fromString(errorType, errorMessage)

    /**
     * Whether this attachment has exceeded the maximum retry count.
     */
    val hasExceededMaxRetries: Boolean
        get() = retryCount >= MAX_RETRY_COUNT

    companion object {
        /**
         * Maximum number of retry attempts before marking as permanently failed.
         */
        const val MAX_RETRY_COUNT = 3
    }
}
