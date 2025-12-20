package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["guid"], unique = true),
        Index(value = ["is_pinned"]),
        Index(value = ["is_starred"]),
        Index(value = ["latest_message_date"]),
        Index(value = ["is_spam"]),
        Index(value = ["category"]),
        Index(value = ["is_archived"])
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

    @ColumnInfo(name = "is_starred")
    val isStarred: Boolean = false,

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

    // App-level notification mute toggle (independent of Android channel settings)
    @ColumnInfo(name = "notifications_enabled", defaultValue = "1")
    val notificationsEnabled: Boolean = true,

    // Snooze notifications until timestamp (-1 = indefinite, null = not snoozed)
    @ColumnInfo(name = "snooze_until")
    val snoozeUntil: Long? = null,

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
    val dateDeleted: Long? = null,

    @ColumnInfo(name = "is_sms_fallback", defaultValue = "0")
    val isSmsFallback: Boolean = false,

    @ColumnInfo(name = "fallback_reason")
    val fallbackReason: String? = null,

    @ColumnInfo(name = "fallback_updated_at")
    val fallbackUpdatedAt: Long? = null,

    // Spam detection
    @ColumnInfo(name = "is_spam", defaultValue = "0")
    val isSpam: Boolean = false,

    @ColumnInfo(name = "spam_score", defaultValue = "0")
    val spamScore: Int = 0,

    @ColumnInfo(name = "spam_reported_to_carrier", defaultValue = "0")
    val spamReportedToCarrier: Boolean = false,

    // Message categorization (ML)
    @ColumnInfo(name = "category")
    val category: String? = null, // "transactions", "deliveries", "promotions", "reminders"

    @ColumnInfo(name = "category_confidence", defaultValue = "0")
    val categoryConfidence: Int = 0,

    @ColumnInfo(name = "category_last_updated")
    val categoryLastUpdated: Long? = null,

    // Per-chat send mode preference (iMessage vs SMS toggle)
    @ColumnInfo(name = "preferred_send_mode")
    val preferredSendMode: String? = null, // "imessage", "sms", or null (automatic)

    @ColumnInfo(name = "send_mode_manually_set", defaultValue = "0")
    val sendModeManuallySet: Boolean = false
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
     * Whether this is an imported RCS chat.
     * RCS chats are imported from the device's RCS app but can only deliver SMS.
     */
    val isRcs: Boolean
        get() = guid.startsWith("RCS;")

    /**
     * Whether this is any SMS chat (local, server text forwarding, or imported RCS).
     * Use this for UI styling decisions (green bubbles, SMS placeholder, etc.)
     */
    val isSmsChat: Boolean
        get() = isLocalSms || isTextForwarding || isRcs

    /**
     * Whether this is an iMessage chat
     */
    val isIMessage: Boolean
        get() = !isTextForwarding && !isLocalSms && !isRcs

    /**
     * Whether this chat is currently snoozed.
     * Returns true if snoozeUntil is -1 (indefinite) or a future timestamp.
     */
    val isSnoozed: Boolean
        get() = snoozeUntil != null && (snoozeUntil == -1L || snoozeUntil > System.currentTimeMillis())
}
