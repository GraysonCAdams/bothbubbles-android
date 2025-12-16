package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a unified chat group that links related chats together.
 *
 * A unified group combines chats from different protocols (iMessage, SMS, MMS)
 * for the same contact, allowing the UI to display them as a single conversation.
 *
 * For example, if a user has both an iMessage chat and an SMS chat with the same
 * phone number, they will be linked together in a single unified group.
 *
 * Group chats (multi-participant) are NOT unified - they remain separate.
 */
@Entity(
    tableName = "unified_chat_groups",
    indices = [
        Index(value = ["identifier"], unique = true),
        Index(value = ["latest_message_date"])
    ]
)
data class UnifiedChatGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Normalized phone number or email that identifies this contact.
     * Used to match chats across different protocols.
     */
    @ColumnInfo(name = "identifier")
    val identifier: String,

    /**
     * The primary chat GUID for this unified group.
     * Usually the iMessage chat if available, otherwise the first SMS chat.
     * Used for sending new messages and determining default behavior.
     */
    @ColumnInfo(name = "primary_chat_guid")
    val primaryChatGuid: String,

    /**
     * Display name for this unified conversation.
     * Inherited from the primary chat or resolved from contacts.
     */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /**
     * Cached latest message date across all linked chats.
     * Used for sorting the conversation list.
     */
    @ColumnInfo(name = "latest_message_date")
    val latestMessageDate: Long? = null,

    /**
     * Cached latest message text across all linked chats.
     * Used for preview in the conversation list.
     */
    @ColumnInfo(name = "latest_message_text")
    val latestMessageText: String? = null,

    /**
     * GUID of the latest message for matching attachments/reactions.
     */
    @ColumnInfo(name = "latest_message_guid")
    val latestMessageGuid: String? = null,

    /**
     * Whether the latest message was sent by the user.
     */
    @ColumnInfo(name = "latest_message_is_from_me")
    val latestMessageIsFromMe: Boolean = false,

    /**
     * Whether the latest message has attachments.
     */
    @ColumnInfo(name = "latest_message_has_attachments")
    val latestMessageHasAttachments: Boolean = false,

    /**
     * Source of the latest message (IMESSAGE, SERVER_SMS, LOCAL_SMS, LOCAL_MMS).
     */
    @ColumnInfo(name = "latest_message_source")
    val latestMessageSource: String? = null,

    /**
     * Delivery timestamp of the latest message (for status display).
     */
    @ColumnInfo(name = "latest_message_date_delivered")
    val latestMessageDateDelivered: Long? = null,

    /**
     * Read timestamp of the latest message (for status display).
     */
    @ColumnInfo(name = "latest_message_date_read")
    val latestMessageDateRead: Long? = null,

    /**
     * Error code of the latest message (0 = no error).
     */
    @ColumnInfo(name = "latest_message_error")
    val latestMessageError: Int = 0,

    /**
     * Total unread count across all linked chats.
     */
    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,

    /**
     * Whether this unified group is pinned.
     */
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,

    /**
     * Pin order for sorting pinned conversations.
     */
    @ColumnInfo(name = "pin_index")
    val pinIndex: Int? = null,

    /**
     * Whether this unified group is archived.
     */
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    /**
     * Whether this unified group is starred.
     */
    @ColumnInfo(name = "is_starred")
    val isStarred: Boolean = false,

    /**
     * Mute type for notification suppression.
     */
    @ColumnInfo(name = "mute_type")
    val muteType: String? = null,

    /**
     * Snooze notifications until this timestamp.
     * -1 = indefinite, null = not snoozed.
     */
    @ColumnInfo(name = "snooze_until")
    val snoozeUntil: Long? = null,

    /**
     * When this unified group was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Whether this unified group is currently snoozed.
     */
    val isSnoozed: Boolean
        get() = snoozeUntil != null && (snoozeUntil == -1L || snoozeUntil > System.currentTimeMillis())
}

/**
 * Junction table linking individual chats to unified groups.
 *
 * Each unified group can contain multiple chats (e.g., one iMessage chat and one SMS chat).
 * Each chat can only belong to one unified group.
 */
@Entity(
    tableName = "unified_chat_members",
    primaryKeys = ["group_id", "chat_guid"],
    indices = [
        Index(value = ["group_id"]),
        Index(value = ["chat_guid"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = UnifiedChatGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class UnifiedChatMember(
    @ColumnInfo(name = "group_id")
    val groupId: Long,

    @ColumnInfo(name = "chat_guid")
    val chatGuid: String
)
