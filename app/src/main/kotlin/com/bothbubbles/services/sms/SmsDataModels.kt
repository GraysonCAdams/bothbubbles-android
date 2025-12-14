package com.bothbubbles.services.sms

import android.net.Uri
import android.provider.Telephony

/**
 * Data class representing an SMS conversation (thread)
 */
data class SmsThread(
    val threadId: Long,
    val recipientAddresses: List<String>,
    val snippet: String?,
    val messageCount: Int,
    val lastMessageDate: Long,
    val isRead: Boolean
)

/**
 * Data class representing an SMS message
 */
data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String?,
    val date: Long,
    val dateSent: Long,
    val type: Int, // 1=inbox, 2=sent, 3=draft, 4=outbox, 5=failed, 6=queued
    val isRead: Boolean,
    val status: Int, // -1=none, 0=complete, 32=pending, 64=failed
    val subscriptionId: Int
) {
    val isFromMe: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_SENT ||
            type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
            type == Telephony.Sms.MESSAGE_TYPE_QUEUED

    val isFailed: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_FAILED ||
            status == Telephony.Sms.STATUS_FAILED

    val isPending: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
            type == Telephony.Sms.MESSAGE_TYPE_QUEUED ||
            status == Telephony.Sms.STATUS_PENDING

    val isDraft: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_DRAFT
}

/**
 * Data class representing an MMS message
 */
data class MmsMessage(
    val id: Long,
    val threadId: Long,
    val date: Long,
    val dateSent: Long,
    val messageBox: Int, // 1=inbox, 2=sent, 3=drafts, 4=outbox
    val isRead: Boolean,
    val subject: String?,
    val textParts: List<String>,
    val imageParts: List<MmsAttachment>,
    val addresses: List<MmsAddress>
) {
    val isFromMe: Boolean get() = messageBox == Telephony.Mms.MESSAGE_BOX_SENT ||
            messageBox == Telephony.Mms.MESSAGE_BOX_OUTBOX

    val isDraft: Boolean get() = messageBox == Telephony.Mms.MESSAGE_BOX_DRAFTS
}

data class MmsAttachment(
    val partId: Long,
    val contentType: String,
    val fileName: String?,
    val dataUri: Uri
)

data class MmsAddress(
    val address: String,
    val type: Int // 137=from, 151=to, 130=cc, 129=bcc
)
