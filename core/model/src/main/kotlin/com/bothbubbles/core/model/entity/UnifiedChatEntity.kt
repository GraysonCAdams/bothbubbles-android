package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a unified conversation that may combine chats from different protocols.
 *
 * This is the single source of truth for conversation-level state (pinned, archived, unread, etc.).
 * Individual chats (iMessage, SMS, MMS) reference this entity via [unifiedChatId] and serve as
 * protocol-specific "channels" for message routing.
 *
 * For 1:1 conversations:
 * - Multiple chats (e.g., iMessage + SMS for the same contact) share one UnifiedChatEntity
 * - Messages are queried directly by [id], enabling native pagination without stream merging
 *
 * For group chats:
 * - One UnifiedChatEntity per group chat (no merging)
 * - The [sourceId] points to the single group chat
 *
 * ID format: Discord-style snowflake (e.g., "17032896543214827")
 */
@Entity(
    tableName = "unified_chats",
    indices = [
        Index(value = ["normalized_address"], unique = true),
        Index(value = ["latest_message_date"]),
        Index(value = ["is_pinned", "pin_index"]),
        Index(value = ["is_archived"]),
        Index(value = ["is_spam"]),
        Index(value = ["category"])
    ]
)
data class UnifiedChatEntity(
    /**
     * Discord-style unique identifier (e.g., "17032896543214827").
     * Generated using [UnifiedChatIdGenerator].
     */
    @PrimaryKey
    val id: String,

    /**
     * Normalized phone number or email that identifies this contact/group.
     * Used to match chats across different protocols for 1:1 conversations.
     * For group chats, this is a hash of participants or the group guid.
     */
    @ColumnInfo(name = "normalized_address")
    val normalizedAddress: String,

    /**
     * The source chat ID for this unified conversation.
     * For 1:1: Preferred channel for sending (iMessage preferred over SMS).
     * For groups: The single group chat guid.
     * Format examples: "iMessage;-;+1234567890", "sms;-;+1234567890"
     */
    @ColumnInfo(name = "source_id")
    val sourceId: String,

    // ==================== Display ====================

    /**
     * Display name for this conversation.
     * For 1:1: Contact name or phone number.
     * For groups: Group name or participant list.
     */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /**
     * User-set custom avatar path (takes priority over other avatars).
     */
    @ColumnInfo(name = "custom_avatar_path")
    val customAvatarPath: String? = null,

    /**
     * Server-provided group photo path (from iMessage group settings).
     */
    @ColumnInfo(name = "server_group_photo_path")
    val serverGroupPhotoPath: String? = null,

    /**
     * GUID of server group photo for change detection.
     */
    @ColumnInfo(name = "server_group_photo_guid")
    val serverGroupPhotoGuid: String? = null,

    // ==================== Cached Latest Message ====================

    /**
     * Timestamp of the latest message across all linked chats.
     * Used for sorting the conversation list.
     */
    @ColumnInfo(name = "latest_message_date")
    val latestMessageDate: Long? = null,

    /**
     * Preview text of the latest message.
     */
    @ColumnInfo(name = "latest_message_text")
    val latestMessageText: String? = null,

    /**
     * GUID of the latest message (for matching/updates).
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
     * Source/protocol of the latest message (IMESSAGE, LOCAL_SMS, etc.).
     */
    @ColumnInfo(name = "latest_message_source")
    val latestMessageSource: String? = null,

    /**
     * Delivery timestamp of the latest message.
     */
    @ColumnInfo(name = "latest_message_date_delivered")
    val latestMessageDateDelivered: Long? = null,

    /**
     * Read timestamp of the latest message.
     */
    @ColumnInfo(name = "latest_message_date_read")
    val latestMessageDateRead: Long? = null,

    /**
     * Error code of the latest message (0 = no error).
     */
    @ColumnInfo(name = "latest_message_error")
    val latestMessageError: Int = 0,

    // ==================== State ====================

    /**
     * Total unread message count.
     */
    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,

    /**
     * Whether this conversation has unread messages.
     */
    @ColumnInfo(name = "has_unread_message")
    val hasUnreadMessage: Boolean = false,

    /**
     * Whether this conversation is pinned.
     */
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,

    /**
     * Pin order for sorting pinned conversations.
     */
    @ColumnInfo(name = "pin_index")
    val pinIndex: Int? = null,

    /**
     * Whether this conversation is archived.
     */
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    /**
     * Whether this conversation is starred/favorited.
     */
    @ColumnInfo(name = "is_starred")
    val isStarred: Boolean = false,

    /**
     * Mute type for notification suppression (e.g., "1_hour", "8_hours", "forever").
     */
    @ColumnInfo(name = "mute_type")
    val muteType: String? = null,

    /**
     * Mute arguments (reserved for future use).
     */
    @ColumnInfo(name = "mute_args")
    val muteArgs: String? = null,

    /**
     * Snooze notifications until this timestamp.
     * -1 = indefinite, null = not snoozed.
     */
    @ColumnInfo(name = "snooze_until")
    val snoozeUntil: Long? = null,

    /**
     * App-level notification toggle (independent of Android channel settings).
     */
    @ColumnInfo(name = "notifications_enabled", defaultValue = "1")
    val notificationsEnabled: Boolean = true,

    // ==================== Spam & Categorization ====================

    /**
     * Whether this conversation is marked as spam.
     */
    @ColumnInfo(name = "is_spam", defaultValue = "0")
    val isSpam: Boolean = false,

    /**
     * Spam detection score (0-100).
     */
    @ColumnInfo(name = "spam_score", defaultValue = "0")
    val spamScore: Int = 0,

    /**
     * Whether spam was reported to carrier.
     */
    @ColumnInfo(name = "spam_reported_to_carrier", defaultValue = "0")
    val spamReportedToCarrier: Boolean = false,

    /**
     * Message category (ML-detected).
     * Values: "transactions", "deliveries", "promotions", "reminders", null
     */
    @ColumnInfo(name = "category")
    val category: String? = null,

    /**
     * Confidence score for category detection (0-100).
     */
    @ColumnInfo(name = "category_confidence", defaultValue = "0")
    val categoryConfidence: Int = 0,

    /**
     * When category was last updated.
     */
    @ColumnInfo(name = "category_last_updated")
    val categoryLastUpdated: Long? = null,

    // ==================== Chat Settings ====================

    /**
     * Whether this is a group chat.
     */
    @ColumnInfo(name = "is_group")
    val isGroup: Boolean = false,

    /**
     * Auto-send read receipts setting for this chat.
     * null = use global setting.
     */
    @ColumnInfo(name = "auto_send_read_receipts")
    val autoSendReadReceipts: Boolean? = null,

    /**
     * Auto-send typing indicators setting for this chat.
     * null = use global setting.
     */
    @ColumnInfo(name = "auto_send_typing_indicators")
    val autoSendTypingIndicators: Boolean? = null,

    /**
     * Saved text field content (draft message).
     */
    @ColumnInfo(name = "text_field_text")
    val textFieldText: String? = null,

    /**
     * Lock chat name from being updated by server/contacts.
     */
    @ColumnInfo(name = "lock_chat_name")
    val lockChatName: Boolean = false,

    /**
     * Lock chat icon from being updated by server/contacts.
     */
    @ColumnInfo(name = "lock_chat_icon")
    val lockChatIcon: Boolean = false,

    /**
     * Preferred send mode for this chat (overrides automatic detection).
     * Values: "imessage", "sms", null (automatic)
     */
    @ColumnInfo(name = "preferred_send_mode")
    val preferredSendMode: String? = null,

    /**
     * Whether send mode was manually set by user.
     */
    @ColumnInfo(name = "send_mode_manually_set", defaultValue = "0")
    val sendModeManuallySet: Boolean = false,

    // ==================== Fallback State ====================

    /**
     * Whether this chat is in SMS fallback mode.
     */
    @ColumnInfo(name = "is_sms_fallback", defaultValue = "0")
    val isSmsFallback: Boolean = false,

    /**
     * Reason for SMS fallback.
     */
    @ColumnInfo(name = "fallback_reason")
    val fallbackReason: String? = null,

    /**
     * When fallback state was last updated.
     */
    @ColumnInfo(name = "fallback_updated_at")
    val fallbackUpdatedAt: Long? = null,

    // ==================== Metadata ====================

    /**
     * When this unified chat was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * When this unified chat was soft-deleted.
     */
    @ColumnInfo(name = "date_deleted")
    val dateDeleted: Long? = null
) {
    /**
     * Whether this conversation is currently snoozed.
     */
    val isSnoozed: Boolean
        get() = snoozeUntil != null && (snoozeUntil == -1L || snoozeUntil > System.currentTimeMillis())

    /**
     * Effective avatar path with priority:
     * 1. customAvatarPath (user-set)
     * 2. serverGroupPhotoPath (from iMessage)
     * 3. null (use participant collage)
     */
    val effectiveAvatarPath: String?
        get() = customAvatarPath ?: serverGroupPhotoPath

    /**
     * Whether the source chat uses SMS (local or text forwarding).
     */
    val isSourceSms: Boolean
        get() = sourceId.startsWith("sms;") ||
                sourceId.startsWith("mms;") ||
                sourceId.startsWith("SMS;") ||
                sourceId.startsWith("RCS;")

    /**
     * Whether the source chat is iMessage.
     */
    val isSourceIMessage: Boolean
        get() = !isSourceSms
}
