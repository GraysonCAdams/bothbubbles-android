package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["guid"], unique = true),
        Index(value = ["chat_guid"]),
        Index(value = ["date_created"]),
        Index(value = ["handle_id"]),
        Index(value = ["associated_message_guid"]),
        Index(value = ["thread_originator_guid"]),
        Index(value = ["message_source"]),
        Index(value = ["chat_guid", "date_deleted"]),
        // Covering index for pagination queries - enables O(1) position-based lookups
        // Includes guid as tie-breaker for deterministic ordering when timestamps are equal
        Index(value = ["chat_guid", "date_created", "date_deleted", "guid"]),
        // Index for efficient filtering by reaction status in BitSet pagination
        Index(value = ["is_reaction"]),
        // Index for cursor-based pagination: chat_guid + is_reaction + date_created + guid
        // This supports the ORDER BY date_created DESC, guid DESC LIMIT :limit pattern
        Index(value = ["chat_guid", "is_reaction", "date_created", "guid"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["guid"],
            childColumns = ["chat_guid"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "guid")
    val guid: String,

    @ColumnInfo(name = "chat_guid")
    val chatGuid: String,

    @ColumnInfo(name = "handle_id")
    val handleId: Long? = null,

    // Sender's address (phone number or email) for incoming messages
    // Used for group chats to identify who sent each message
    @ColumnInfo(name = "sender_address")
    val senderAddress: String? = null,

    @ColumnInfo(name = "text")
    val text: String? = null,

    @ColumnInfo(name = "subject")
    val subject: String? = null,

    @ColumnInfo(name = "date_created")
    val dateCreated: Long,

    @ColumnInfo(name = "date_read")
    val dateRead: Long? = null,

    @ColumnInfo(name = "date_delivered")
    val dateDelivered: Long? = null,

    @ColumnInfo(name = "date_edited")
    val dateEdited: Long? = null,

    @ColumnInfo(name = "date_played")
    val datePlayed: Long? = null,

    @ColumnInfo(name = "date_deleted")
    val dateDeleted: Long? = null,

    @ColumnInfo(name = "is_from_me")
    val isFromMe: Boolean,

    @ColumnInfo(name = "error")
    val error: Int = 0,

    @ColumnInfo(name = "item_type")
    val itemType: Int = 0,

    @ColumnInfo(name = "group_title")
    val groupTitle: String? = null,

    @ColumnInfo(name = "group_action_type")
    val groupActionType: Int = 0,

    @ColumnInfo(name = "balloon_bundle_id")
    val balloonBundleId: String? = null,

    @ColumnInfo(name = "associated_message_guid")
    val associatedMessageGuid: String? = null,

    @ColumnInfo(name = "associated_message_part")
    val associatedMessagePart: Int? = null,

    @ColumnInfo(name = "associated_message_type")
    val associatedMessageType: String? = null,

    @ColumnInfo(name = "expressive_send_style_id")
    val expressiveSendStyleId: String? = null,

    @ColumnInfo(name = "thread_originator_guid")
    val threadOriginatorGuid: String? = null,

    @ColumnInfo(name = "thread_originator_part")
    val threadOriginatorPart: String? = null,

    @ColumnInfo(name = "has_attachments")
    val hasAttachments: Boolean = false,

    @ColumnInfo(name = "has_reactions")
    val hasReactions: Boolean = false,

    @ColumnInfo(name = "big_emoji")
    val bigEmoji: Boolean = false,

    @ColumnInfo(name = "was_delivered_quietly")
    val wasDeliveredQuietly: Boolean = false,

    @ColumnInfo(name = "did_notify_recipient")
    val didNotifyRecipient: Boolean = false,

    @ColumnInfo(name = "is_bookmarked")
    val isBookmarked: Boolean = false,

    @ColumnInfo(name = "has_dd_results")
    val hasDdResults: Boolean = false,

    // SMS/MMS specific fields stored as JSON or separate columns
    @ColumnInfo(name = "message_source")
    val messageSource: String = MessageSource.IMESSAGE.name, // IMESSAGE, SERVER_SMS, LOCAL_SMS, LOCAL_MMS

    @ColumnInfo(name = "sms_id")
    val smsId: Long? = null,

    @ColumnInfo(name = "sms_thread_id")
    val smsThreadId: Long? = null,

    @ColumnInfo(name = "sms_status")
    val smsStatus: String? = null,

    @ColumnInfo(name = "sim_slot")
    val simSlot: Int? = null,

    @ColumnInfo(name = "sms_error_message")
    val smsErrorMessage: String? = null,

    // Metadata stored as JSON
    @ColumnInfo(name = "metadata")
    val metadata: String? = null,

    /**
     * Denormalized column indicating if this message is a reaction/tapback.
     * Computed at insert time using [ReactionClassifier.isReaction].
     * This enables efficient filtering in SQL queries without complex pattern matching.
     */
    @ColumnInfo(name = "is_reaction", defaultValue = "0")
    val isReactionDb: Boolean = false,

    /**
     * Groups related messages that were composed together (e.g., text + attachments).
     *
     * When a user sends a message with both text and attachments, they are split into
     * separate messages (like native iMessage). This ID links them for:
     * - Visual grouping in the UI (tighter spacing, connected bubbles)
     * - Understanding which messages were sent as a unit
     *
     * Format: "batch-{UUID}" or null for single messages.
     * Only set on outgoing messages created locally.
     */
    @ColumnInfo(name = "split_batch_id")
    val splitBatchId: String? = null
) {
    /**
     * Whether this message has been sent (no error, not pending)
     */
    val isSent: Boolean
        get() = error == 0 && !guid.startsWith("temp-")

    /**
     * Whether this message is a reaction/tapback.
     * Uses the denormalized [isReactionDb] column for efficiency.
     * Falls back to computed detection for backwards compatibility with pre-migration data.
     *
     * @see ReactionClassifier for the centralized detection logic
     */
    val isReaction: Boolean
        get() = isReactionDb || ReactionClassifier.isReaction(associatedMessageGuid, associatedMessageType)

    /**
     * Whether this message is a group event (participant added/removed, name change)
     */
    val isGroupEvent: Boolean
        get() = itemType != 0

    /**
     * Whether this message is a reply in a thread
     */
    val isReply: Boolean
        get() = threadOriginatorGuid != null

    /**
     * Full text combining subject and body
     */
    val fullText: String?
        get() = when {
            subject != null && text != null -> "$subject\n$text"
            subject != null -> subject
            else -> text
        }
}

enum class MessageSource {
    IMESSAGE,
    SERVER_SMS,
    LOCAL_SMS,
    LOCAL_MMS
}
