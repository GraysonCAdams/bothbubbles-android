package com.bothbubbles.data.local.db.entity

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
        Index(value = ["chat_guid", "date_deleted"])
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
    val metadata: String? = null
) {
    /**
     * Whether this message has been sent (no error, not pending)
     */
    val isSent: Boolean
        get() = error == 0 && !guid.startsWith("temp-")

    /**
     * Whether this message is a reaction/tapback
     */
    val isReaction: Boolean
        get() = associatedMessageType?.contains("reaction") == true ||
                associatedMessageType?.contains("tapback") == true

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
