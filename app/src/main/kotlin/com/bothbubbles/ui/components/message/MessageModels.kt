package com.bothbubbles.ui.components.message

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.ui.components.message.ReactionUiModel
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.ui.util.StableList
import com.bothbubbles.ui.util.toStable

/**
 * Result of analyzing text for emoji-only content.
 * Public so it can be pre-computed in ViewModel and cached in MessageUiModel.
 */
@Immutable
data class EmojiAnalysis(
    val isEmojiOnly: Boolean,
    val emojiCount: Int
)

/**
 * Position of a message within a consecutive group from the same sender.
 * Used to determine bubble shape and spacing for visual grouping.
 */
enum class MessageGroupPosition {
    /** Standalone message - not grouped with adjacent messages */
    SINGLE,
    /** First message in a group - rounded top, tight bottom corners */
    FIRST,
    /** Middle message in a group - tight corners on both ends */
    MIDDLE,
    /** Last message in a group - tight top corners, tail at bottom */
    LAST
}

/**
 * Preview data for the message being replied to.
 * Shown as a quote box above reply messages.
 */
@Immutable
data class ReplyPreviewData(
    val originalGuid: String,
    val previewText: String?,        // Truncated to ~50 chars
    val senderName: String?,         // "You" or contact name
    val isFromMe: Boolean,
    val hasAttachment: Boolean,
    val isNotLoaded: Boolean = false // Original not found within recent messages
)

/**
 * Represents a thread of messages: the original message and all replies to it.
 * Used for the thread overlay view.
 */
@Stable
data class ThreadChain(
    val originMessage: MessageUiModel?,      // The root message being replied to (null if deleted/not found)
    val replies: StableList<MessageUiModel>        // All replies in chronological order
)

/**
 * UI model for a message bubble
 */
@Stable
data class MessageUiModel(
    val guid: String,
    val text: String?,
    val subject: String? = null,
    val dateCreated: Long,
    val formattedTime: String,
    val isFromMe: Boolean,
    val isSent: Boolean,
    val isDelivered: Boolean,
    val isRead: Boolean,
    val hasError: Boolean,
    val isReaction: Boolean,
    val attachments: StableList<AttachmentUiModel>,
    val senderName: String?,
    val senderAvatarPath: String? = null,
    val messageSource: String,
    val reactions: StableList<ReactionUiModel> = emptyList<ReactionUiModel>().toStable(),
    val myReactions: Set<Tapback> = emptySet(),
    val expressiveSendStyleId: String? = null,
    val effectPlayed: Boolean = false,
    val associatedMessageGuid: String? = null,
    // Reply indicator fields
    val threadOriginatorGuid: String? = null,
    val replyPreview: ReplyPreviewData? = null,
    // Pre-computed emoji analysis to avoid recalculating on every composition
    val emojiAnalysis: EmojiAnalysis? = null
) {
    /** True if this is a sticker that was placed on another message */
    val isPlacedSticker: Boolean
        get() = associatedMessageGuid != null && attachments.any { it.isSticker }

    /** True if this message is a reply to another message */
    val isReply: Boolean
        get() = threadOriginatorGuid != null

    /** True if this message came from the BlueBubbles server (iMessage or server-forwarded SMS).
     * These messages can have tapback reactions sent via the server API. */
    val isServerOrigin: Boolean
        get() = messageSource == MessageSource.IMESSAGE.name ||
                messageSource == MessageSource.SERVER_SMS.name
}

@Stable
data class AttachmentUiModel(
    val guid: String,
    val mimeType: String?,
    val localPath: String?,
    val webUrl: String?,
    val width: Int?,
    val height: Int?,
    val transferName: String? = null,
    val totalBytes: Long? = null,
    val isSticker: Boolean = false,
    val blurhash: String? = null,
    val thumbnailPath: String? = null,
    // Transfer state fields for snappy rendering
    val transferState: String = "DOWNLOADED",
    val transferProgress: Float = 0f,
    val isOutgoing: Boolean = false,
    // Error state fields for clear error display with retry
    val errorType: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    // Caption text displayed below the attachment
    val caption: String? = null
) {
    /** True if the attachment needs to be downloaded (inbound, no local file available) */
    val needsDownload: Boolean
        get() = !isOutgoing && localPath == null && transferState != "DOWNLOADED"

    /** True if this attachment is currently uploading */
    val isUploading: Boolean
        get() = transferState == "UPLOADING"

    /** True if this attachment is currently downloading */
    val isDownloading: Boolean
        get() = transferState == "DOWNLOADING"

    /** True if this attachment is in any transfer state */
    val isTransferring: Boolean
        get() = isUploading || isDownloading

    /** True if transfer has failed */
    val hasFailed: Boolean
        get() = transferState == "FAILED"

    /** True if the error is retryable (user can tap to retry) */
    val isRetryable: Boolean
        get() = when (errorType) {
            "FILE_TOO_LARGE", "FORMAT_UNSUPPORTED" -> false
            else -> hasFailed
        }

    /** True if this attachment has an error that should be displayed */
    val hasError: Boolean
        get() = hasFailed && errorType != null

    /** True if this attachment can be displayed (has local path or is outgoing with local file) */
    val canDisplay: Boolean
        get() = localPath != null || (isOutgoing && transferState != "FAILED")

    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true

    val isVideo: Boolean
        get() = mimeType?.startsWith("video/") == true

    val isAudio: Boolean
        get() = mimeType?.startsWith("audio/") == true

    val isGif: Boolean
        get() = mimeType == "image/gif"

    val isVCard: Boolean
        get() = mimeType == "text/vcard" || mimeType == "text/x-vcard" ||
                transferName?.lowercase()?.endsWith(".vcf") == true

    val friendlySize: String
        get() = when {
            totalBytes == null -> ""
            totalBytes < 1024 -> "$totalBytes B"
            totalBytes < 1024 * 1024 -> "${totalBytes / 1024} KB"
            totalBytes < 1024 * 1024 * 1024 -> "${totalBytes / (1024 * 1024)} MB"
            else -> "${totalBytes / (1024 * 1024 * 1024)} GB"
        }

    val fileExtension: String?
        get() = transferName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }

    /** True if this image format supports transparency (PNG, GIF, WebP, APNG) */
    val mayHaveTransparency: Boolean
        get() = isSticker || mimeType?.lowercase() in listOf(
            "image/png", "image/gif", "image/webp", "image/apng"
        )
}
