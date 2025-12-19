package com.bothbubbles.ui.components.message

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.ui.util.StableList
import com.bothbubbles.ui.util.toStable

// ===== Cursor-Based Pagination Models =====

/**
 * View mode for the chat message list.
 *
 * **Recent mode**: Standard view anchored to NOW - new messages appear automatically.
 * **Archive mode**: History view anchored to a specific message - used for jump-to-message
 *                   from search results or deep links. New messages don't appear automatically.
 */
@Stable
sealed class ChatViewMode {
    /** Standard view anchored to the most recent messages */
    data object Recent : ChatViewMode()

    /**
     * Archive/History view anchored to a specific message.
     * Used when jumping to a search result or deep link.
     *
     * @param targetGuid The message GUID that triggered the jump
     * @param targetTimestamp The timestamp of the target message (center of window)
     * @param windowMs Time window in milliseconds (±12 hours by default)
     */
    data class Archive(
        val targetGuid: String,
        val targetTimestamp: Long,
        val windowMs: Long = 12 * 60 * 60 * 1000L // ±12 hours
    ) : ChatViewMode()
}

/**
 * Sealed interface for items in the chat message list.
 * Supports messages and date separators with stable keys for Compose diffing.
 *
 * Each item provides:
 * - [key]: Stable identifier for LazyColumn key-based diffing (GUID for messages, date string for headers)
 * - [contentType]: Integer type for Compose item prefetching optimization
 */
@Stable
sealed interface ChatListItem {
    val key: String
    val contentType: Int

    /**
     * A message bubble in the chat.
     */
    @Stable
    data class Message(val message: MessageUiModel) : ChatListItem {
        override val key: String = message.guid
        override val contentType: Int = CONTENT_TYPE_MESSAGE
    }

    /**
     * A date separator header (e.g., "Today", "Yesterday", "December 15").
     *
     * @param dateKey ISO date key for stable identity (e.g., "2024-12-15")
     * @param displayText Formatted display text (e.g., "Today", "Yesterday", "December 15")
     */
    @Stable
    data class DateSeparator(
        val dateKey: String,
        val displayText: String
    ) : ChatListItem {
        override val key: String = "date_$dateKey"
        override val contentType: Int = CONTENT_TYPE_DATE_SEPARATOR
    }

    /**
     * Typing indicator shown at the bottom of the list.
     * Ephemeral - not stored in Room, combined from memory-only flow.
     *
     * @param senderName Display name of person typing (null for 1:1 chats)
     * @param senderAddress Address/handle of person typing
     */
    @Stable
    data class TypingIndicator(
        val senderName: String?,
        val senderAddress: String
    ) : ChatListItem {
        override val key: String = "typing_$senderAddress"
        override val contentType: Int = CONTENT_TYPE_TYPING
    }

    companion object {
        const val CONTENT_TYPE_MESSAGE = 1
        const val CONTENT_TYPE_DATE_SEPARATOR = 2
        const val CONTENT_TYPE_TYPING = 3
    }
}

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
    val thumbnailUri: String? = null, // Thumbnail path for attachment preview
    val quoteDepth: Int = 1,         // Nesting level (1 = direct reply, 2+ = reply to reply)
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
    /** Numeric error code for failed messages (e.g., 22 = not registered with iMessage) */
    val errorCode: Int = 0,
    /** Raw error message from server for additional context */
    val errorMessage: String? = null,
    val isReaction: Boolean,
    val attachments: StableList<AttachmentUiModel>,
    val senderName: String?,
    val senderAvatarPath: String? = null,
    /** Sender's address (phone or email) for group chat avatar clicks */
    val senderAddress: String? = null,
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
    val emojiAnalysis: EmojiAnalysis? = null,
    // Group event fields (participant changes, name changes, icon changes)
    /** True if this is a group event message (participant added/removed, name/icon change) */
    val isGroupEvent: Boolean = false,
    /** Display text for group events (e.g., "Group photo changed", "John joined the group") */
    val groupEventText: String? = null,
    // Mentions (from attributedBody)
    /** Parsed mentions from the message's attributedBody (if any) */
    val mentions: StableList<MentionUiModel> = emptyList<MentionUiModel>().toStable()
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

/**
 * UI model for a mention within a message.
 * Used for rendering mentions with special styling and click handling.
 */
@Stable
data class MentionUiModel(
    /** Starting character index of the mention in the message text */
    val startIndex: Int,
    /** Length of the mention text */
    val length: Int,
    /** Address (phone or email) of the mentioned person */
    val mentionedAddress: String,
    /** Display name of the mentioned person (resolved at display time) */
    val displayName: String? = null
) {
    /** End index of the mention (exclusive) */
    val endIndex: Int get() = startIndex + length
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
    /**
     * True if the attachment needs to be downloaded (inbound, no local file available).
     * Note: We don't check transferState because legacy data may have DOWNLOADED state
     * without an actual local file (before transfer state tracking was implemented).
     */
    val needsDownload: Boolean
        get() = !isOutgoing && localPath == null

    /**
     * The URL to display for this attachment.
     * - If localPath exists, use it
     * - If outgoing (my message), can fall back to webUrl (server may allow unauthenticated access)
     * - If inbound, return null (Coil doesn't have auth headers, must wait for download)
     */
    val displayUrl: String?
        get() = localPath ?: if (isOutgoing) webUrl else null

    /**
     * True if this attachment is waiting for auto-download to complete.
     * Used to show a loading indicator instead of an error state.
     */
    val isAwaitingDownload: Boolean
        get() = displayUrl == null && !isOutgoing

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
        get() = (mimeType == "text/vcard" || mimeType == "text/x-vcard" ||
                transferName?.lowercase()?.endsWith(".vcf") == true) && !isVLocation

    /**
     * True if this is an Apple vLocation attachment (native iMessage location format).
     * Identified by MIME type text/x-vlocation or .loc.vcf extension.
     */
    val isVLocation: Boolean
        get() = mimeType == "text/x-vlocation" ||
                transferName?.lowercase()?.endsWith(".loc.vcf") == true

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
