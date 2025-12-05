package com.bluebubbles.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["guid"], unique = true),
        Index(value = ["is_pinned"]),
        Index(value = ["latest_message_date"])
    ]
)
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "guid")
    val guid: String,

    @ColumnInfo(name = "chat_identifier")
    val chatIdentifier: String? = null,

    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "pin_index")
    val pinIndex: Int? = null,

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "mute_type")
    val muteType: String? = null,

    @ColumnInfo(name = "mute_args")
    val muteArgs: String? = null,

    @ColumnInfo(name = "has_unread_message")
    val hasUnreadMessage: Boolean = false,

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,

    @ColumnInfo(name = "style")
    val style: Int? = null,

    @ColumnInfo(name = "is_group")
    val isGroup: Boolean = false,

    @ColumnInfo(name = "last_message_text")
    val lastMessageText: String? = null,

    @ColumnInfo(name = "last_message_date")
    val lastMessageDate: Long? = null,

    @ColumnInfo(name = "custom_avatar_path")
    val customAvatarPath: String? = null,

    @ColumnInfo(name = "custom_notification_sound")
    val customNotificationSound: String? = null,

    @ColumnInfo(name = "auto_send_read_receipts")
    val autoSendReadReceipts: Boolean? = null,

    @ColumnInfo(name = "auto_send_typing_indicators")
    val autoSendTypingIndicators: Boolean? = null,

    @ColumnInfo(name = "text_field_text")
    val textFieldText: String? = null,

    @ColumnInfo(name = "lock_chat_name")
    val lockChatName: Boolean = false,

    @ColumnInfo(name = "lock_chat_icon")
    val lockChatIcon: Boolean = false,

    @ColumnInfo(name = "latest_message_date")
    val latestMessageDate: Long? = null,

    @ColumnInfo(name = "date_created")
    val dateCreated: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "date_deleted")
    val dateDeleted: Long? = null
) {
    /**
     * Whether this chat uses SMS text forwarding
     */
    val isTextForwarding: Boolean
        get() = guid.startsWith("SMS;")

    /**
     * Whether this is a local SMS chat
     */
    val isLocalSms: Boolean
        get() = guid.startsWith("sms;") || guid.startsWith("mms;")

    /**
     * Whether this is an iMessage chat
     */
    val isIMessage: Boolean
        get() = !isTextForwarding && !isLocalSms
}
