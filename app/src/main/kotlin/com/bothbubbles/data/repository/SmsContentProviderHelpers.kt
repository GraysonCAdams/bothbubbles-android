package com.bothbubbles.data.repository

import android.content.Context
import android.provider.Telephony
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.services.sms.MmsAddress
import com.bothbubbles.services.sms.MmsMessage
import com.bothbubbles.services.sms.SmsMessage
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils

/**
 * Helper functions for SMS content provider operations and entity conversions.
 */
object SmsContentProviderHelpers {

    /**
     * Determine the correct chat GUID for an SMS message based on its per-message address.
     * SMS messages always have a single recipient in the ADDRESS field.
     *
     * @param smsAddress The address from the SMS message's ADDRESS field
     * @return The chat GUID in format "sms;-;+1234567890" or null if invalid
     */
    fun determineChatGuidForSms(smsAddress: String): String? {
        if (!isValidPhoneAddress(smsAddress)) return null
        val normalizedAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(smsAddress)
        if (normalizedAddress.isBlank()) return null
        return "sms;-;$normalizedAddress"
    }

    /**
     * Determine the correct chat GUID for an MMS message based on its per-message addresses.
     * MMS messages can have multiple recipients (group) or a single recipient (1:1).
     *
     * MMS address types:
     * - 137 = From (sender)
     * - 151 = To (recipient)
     * - 130 = CC
     * - 129 = BCC
     *
     * We only use recipient addresses (type 151) for chat_guid determination.
     *
     * @param mmsAddresses The list of MmsAddress objects from the MMS addr table
     * @return The chat GUID in format "sms;-;+123" for 1:1 or "mms;-;+123,+456" for groups, or null if invalid
     */
    fun determineChatGuidForMms(mmsAddresses: List<MmsAddress>): String? {
        // Extract recipient addresses (type 151 = "to")
        // We exclude the sender (type 137) from chat_guid calculation
        val recipientAddresses = mmsAddresses
            .filter { it.type == 151 } // Only "to" recipients
            .map { it.address }

        // Filter and normalize addresses
        val validAddresses = recipientAddresses
            .filter { isValidPhoneAddress(it) }
            .map { PhoneAndCodeParsingUtils.normalizePhoneNumber(it) }
            .filter { it.isNotBlank() }
            .distinct()

        if (validAddresses.isEmpty()) return null

        return if (validAddresses.size == 1) {
            // 1:1 MMS - use sms format for consistency with SMS to same recipient
            "sms;-;${validAddresses.first()}"
        } else {
            // Group MMS
            "mms;-;${validAddresses.sorted().joinToString(",")}"
        }
    }

    /**
     * Get valid recipient addresses from MMS addresses list.
     * Filters to only "to" recipients and normalizes phone numbers.
     */
    fun getValidMmsRecipients(mmsAddresses: List<MmsAddress>): List<String> {
        return mmsAddresses
            .filter { it.type == 151 } // Only "to" recipients
            .map { it.address }
            .filter { isValidPhoneAddress(it) }
            .map { PhoneAndCodeParsingUtils.normalizePhoneNumber(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * Get thread ID for a chat by querying the SMS content provider.
     * Extracts addresses from chat GUID and looks up the thread ID from system SMS database.
     */
    suspend fun getThreadIdForChat(context: Context, chatGuid: String): Long? {
        // Extract address(es) from chat GUID
        val parts = chatGuid.split(";-;")
        if (parts.size != 2) return null

        val addresses = parts[1].split(",")
        if (addresses.isEmpty()) return null

        // Look up thread ID from the first message with this address
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.ADDRESS} = ?",
            arrayOf(addresses.first()),
            "${Telephony.Sms.DATE} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    /**
     * Convert SmsMessage to MessageEntity for database storage.
     */
    fun SmsMessage.toMessageEntity(chatGuid: String): MessageEntity {
        return MessageEntity(
            guid = "sms-$id",
            chatGuid = chatGuid,
            text = body,
            dateCreated = date,
            isFromMe = isFromMe,
            error = if (isFailed) 1 else 0,
            messageSource = MessageSource.LOCAL_SMS.name,
            smsId = id,
            smsThreadId = threadId,
            smsStatus = when {
                isDraft -> "draft"
                isFailed -> "failed"
                isPending -> "pending"
                else -> "complete"
            },
            simSlot = if (subscriptionId >= 0) subscriptionId else null
        )
    }

    /**
     * Convert MmsMessage to MessageEntity for database storage.
     */
    fun MmsMessage.toMessageEntity(chatGuid: String): MessageEntity {
        return MessageEntity(
            guid = "mms-$id",
            chatGuid = chatGuid,
            text = textParts.joinToString("\n").takeIf { it.isNotBlank() },
            subject = subject,
            dateCreated = date,
            isFromMe = isFromMe,
            hasAttachments = imageParts.isNotEmpty(),
            messageSource = MessageSource.LOCAL_MMS.name,
            smsId = id,
            smsThreadId = threadId,
            smsStatus = if (isDraft) "draft" else "complete"
        )
    }

    /**
     * Check if an address is a valid phone number (not RCS, email, or other non-phone format).
     */
    fun isValidPhoneAddress(address: String): Boolean {
        if (address.isBlank()) return false
        // Filter out RCS addresses
        if (address.contains("@")) return false
        if (address.contains("rcs.google.com")) return false
        if (address.contains("rbm.goog")) return false
        // Filter out "insert-address-token" placeholder
        if (address.contains("insert-address-token")) return false
        // Should have at least some digits to be a phone number
        if (address.count { it.isDigit() } < 3) return false
        return true
    }

    /**
     * Check if two phone numbers match, accounting for different formats.
     * Handles cases like +1 prefix, different country codes, etc.
     */
    fun phonesMatch(phone1: String, phone2: String): Boolean {
        // Empty strings should never match (prevents RCS/email addresses from matching all phones)
        if (phone1.isEmpty() || phone2.isEmpty()) return false
        if (phone1 == phone2) return true
        // Only use endsWith matching if both strings have reasonable length
        if (phone1.length >= 7 && phone2.length >= 7) {
            if (phone1.endsWith(phone2) || phone2.endsWith(phone1)) return true
        }
        // Compare last 10 digits for US numbers
        if (phone1.length >= 10 && phone2.length >= 10 &&
            phone1.takeLast(10) == phone2.takeLast(10)) {
            return true
        }
        return false
    }
}
