package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a protocol-specific chat channel (iMessage, SMS, MMS, RCS).
 *
 * This is a "channel" within a [UnifiedChatEntity] conversation. Multiple ChatEntity
 * records may share the same [unifiedChatId] when they represent the same contact
 * across different protocols (e.g., iMessage + SMS for the same phone number).
 *
 * UI state (pinned, archived, unread, etc.) is stored in [UnifiedChatEntity].
 * This entity only stores protocol-specific data and server-synced metadata.
 */
@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["guid"], unique = true),
        Index(value = ["unified_chat_id"]),
        Index(value = ["latest_message_date"])
    ]
)
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Protocol-specific chat identifier.
     * Format examples:
     * - iMessage: "iMessage;-;+1234567890" or "iMessage;+;chat123456"
     * - SMS: "sms;-;+1234567890"
     * - MMS: "mms;-;+1234567890"
     * - Text Forwarding: "SMS;-;+1234567890"
     * - RCS: "RCS;-;+1234567890"
     */
    @ColumnInfo(name = "guid")
    val guid: String,

    /**
     * References the unified conversation this chat belongs to.
     * All chats with the same unifiedChatId are part of one logical conversation.
     */
    @ColumnInfo(name = "unified_chat_id")
    val unifiedChatId: String? = null,

    /**
     * Chat identifier from the server (phone number, email, or group identifier).
     */
    @ColumnInfo(name = "chat_identifier")
    val chatIdentifier: String? = null,

    /**
     * Display name from the server (for group chats).
     * For 1:1 chats, this is typically null and display name comes from contacts.
     */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /**
     * iMessage style (0 = 1:1, 43 = group, 45 = group with name).
     */
    @ColumnInfo(name = "style")
    val style: Int? = null,

    /**
     * Whether this is a group chat.
     */
    @ColumnInfo(name = "is_group")
    val isGroup: Boolean = false,

    /**
     * Latest message date for this specific protocol channel.
     * Used for determining which channel was most recently active.
     */
    @ColumnInfo(name = "latest_message_date")
    val latestMessageDate: Long? = null,

    /**
     * When this chat was created.
     */
    @ColumnInfo(name = "date_created")
    val dateCreated: Long = System.currentTimeMillis(),

    /**
     * When this chat was soft-deleted.
     */
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
}
