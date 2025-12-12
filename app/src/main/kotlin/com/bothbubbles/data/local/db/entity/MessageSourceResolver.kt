package com.bothbubbles.data.local.db.entity

/**
 * Centralized logic for determining message source (iMessage vs SMS/MMS).
 *
 * Message source is determined by:
 * 1. Chat GUID prefix (sms;-;, mms;-;, RCS;-;) for local SMS/MMS
 * 2. Handle service type for server-synced messages
 * 3. Default to iMessage for iMessage;-; prefixed chats
 *
 * This object provides a single source of truth to avoid scattered logic
 * across MessageRepository, SmsSendService, and ChatViewModel.
 */
object MessageSourceResolver {

    /**
     * Chat GUID prefixes indicating local SMS/MMS messaging.
     */
    private val LOCAL_SMS_PREFIXES = listOf("sms;-;", "SMS;-;", "mms;-;", "MMS;-;", "RCS;-;", "rcs;-;")

    /**
     * Chat GUID prefixes indicating server-synced SMS (via BlueBubbles server).
     */
    private val SERVER_SMS_PREFIXES = LOCAL_SMS_PREFIXES

    /**
     * Determine the message source from a chat GUID (for locally-initiated messages).
     *
     * @param chatGuid The chat's GUID
     * @return The appropriate MessageSource
     */
    fun fromChatGuid(chatGuid: String): MessageSource {
        return when {
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.LOCAL_SMS
            chatGuid.startsWith("mms;-;", ignoreCase = true) -> MessageSource.LOCAL_MMS
            chatGuid.startsWith("RCS;-;", ignoreCase = true) -> MessageSource.LOCAL_SMS
            else -> MessageSource.IMESSAGE
        }
    }

    /**
     * Determine the message source from server data (for received messages).
     *
     * @param chatGuid The chat's GUID
     * @param handleService The handle's service type (e.g., "SMS", "iMessage")
     * @return The appropriate MessageSource
     */
    fun fromServerData(chatGuid: String, handleService: String?): MessageSource {
        return when {
            handleService?.equals("SMS", ignoreCase = true) == true -> MessageSource.SERVER_SMS
            handleService?.equals("RCS", ignoreCase = true) == true -> MessageSource.SERVER_SMS
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS
            chatGuid.startsWith("SMS;-;") -> MessageSource.SERVER_SMS
            chatGuid.startsWith("RCS;-;", ignoreCase = true) -> MessageSource.SERVER_SMS
            chatGuid.startsWith("mms;-;", ignoreCase = true) -> MessageSource.SERVER_SMS
            else -> MessageSource.IMESSAGE
        }
    }

    /**
     * Check if a chat is for local SMS/MMS (not via BlueBubbles server).
     *
     * @param chatGuid The chat's GUID
     * @return true if this is a local SMS/MMS chat
     */
    fun isLocalSmsChat(chatGuid: String): Boolean {
        return LOCAL_SMS_PREFIXES.any { chatGuid.startsWith(it, ignoreCase = true) }
    }

    /**
     * Check if a chat is an iMessage chat.
     *
     * @param chatGuid The chat's GUID
     * @return true if this is an iMessage chat
     */
    fun isIMessageChat(chatGuid: String): Boolean {
        return chatGuid.startsWith("iMessage;-;", ignoreCase = true) ||
                !isLocalSmsChat(chatGuid)
    }

    /**
     * Extract the phone number or email address from a chat GUID.
     *
     * @param chatGuid The chat's GUID (e.g., "sms;-;+15551234567")
     * @return The address portion, or null if not parseable
     */
    fun extractAddress(chatGuid: String): String? {
        val parts = chatGuid.split(";-;")
        return if (parts.size == 2) parts[1] else null
    }

    /**
     * Get the service type string for a chat (for display purposes).
     *
     * @param chatGuid The chat's GUID
     * @return "iMessage", "SMS", "MMS", or "RCS"
     */
    fun getServiceType(chatGuid: String): String {
        return when {
            chatGuid.startsWith("sms;-;", ignoreCase = true) -> "SMS"
            chatGuid.startsWith("mms;-;", ignoreCase = true) -> "MMS"
            chatGuid.startsWith("RCS;-;", ignoreCase = true) -> "RCS"
            else -> "iMessage"
        }
    }
}
