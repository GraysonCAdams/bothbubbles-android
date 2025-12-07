package com.bothbubbles.services.sms

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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
}

/**
 * Data class representing an MMS message
 */
data class MmsMessage(
    val id: Long,
    val threadId: Long,
    val date: Long,
    val dateSent: Long,
    val messageBox: Int, // 1=inbox, 2=sent
    val isRead: Boolean,
    val subject: String?,
    val textParts: List<String>,
    val imageParts: List<MmsAttachment>,
    val addresses: List<MmsAddress>
) {
    val isFromMe: Boolean get() = messageBox == Telephony.Mms.MESSAGE_BOX_SENT ||
            messageBox == Telephony.Mms.MESSAGE_BOX_OUTBOX
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

@Singleton
class SmsContentProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver: ContentResolver = context.contentResolver

    // ===== Threads (Conversations) =====

    /**
     * Get all SMS/MMS threads (conversations)
     */
    suspend fun getThreads(limit: Int = 100, offset: Int = 0): List<SmsThread> = withContext(Dispatchers.IO) {
        val threads = mutableListOf<SmsThread>()

        val projection = arrayOf(
            Telephony.Threads._ID,
            Telephony.Threads.SNIPPET,
            Telephony.Threads.MESSAGE_COUNT,
            Telephony.Threads.DATE,
            Telephony.Threads.READ,
            Telephony.Threads.RECIPIENT_IDS
        )

        contentResolver.query(
            Telephony.Threads.CONTENT_URI.buildUpon()
                .appendQueryParameter("simple", "true")
                .build(),
            projection,
            null,
            null,
            "${Telephony.Threads.DATE} DESC LIMIT $limit OFFSET $offset"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Threads._ID))
                val recipientIds = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Threads.RECIPIENT_IDS))

                threads.add(
                    SmsThread(
                        threadId = threadId,
                        recipientAddresses = getAddressesForThread(threadId),
                        snippet = cursor.getStringOrNull(Telephony.Threads.SNIPPET),
                        messageCount = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Threads.MESSAGE_COUNT)),
                        lastMessageDate = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Threads.DATE)),
                        isRead = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Threads.READ)) == 1
                    )
                )
            }
        }

        threads
    }

    /**
     * Get addresses for a thread
     */
    private fun getAddressesForThread(threadId: Long): List<String> {
        val addresses = mutableListOf<String>()

        // Get from SMS messages in the thread
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getStringOrNull(Telephony.Sms.ADDRESS)?.let { addresses.add(it) }
            }
        }

        // Also check MMS for group conversations
        contentResolver.query(
            Uri.parse("content://mms/${threadId}/addr"),
            arrayOf("address", "type"),
            "type = 151", // TO type
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { addr ->
                    if (addr !in addresses) addresses.add(addr)
                }
            }
        }

        return addresses.distinct()
    }

    // ===== SMS Messages =====

    /**
     * Get SMS messages for a thread
     */
    suspend fun getSmsMessages(
        threadId: Long,
        limit: Int = 50,
        afterDate: Long? = null,
        beforeDate: Long? = null
    ): List<SmsMessage> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<SmsMessage>()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.STATUS,
            Telephony.Sms.SUBSCRIPTION_ID
        )

        val selection = buildString {
            append("${Telephony.Sms.THREAD_ID} = ?")
            afterDate?.let { append(" AND ${Telephony.Sms.DATE} > $it") }
            beforeDate?.let { append(" AND ${Telephony.Sms.DATE} < $it") }
        }

        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                messages.add(
                    SmsMessage(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
                        threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
                        address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
                        body = cursor.getStringOrNull(Telephony.Sms.BODY),
                        date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                        dateSent = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)),
                        type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)),
                        isRead = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1,
                        status = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)),
                        subscriptionId = cursor.getIntOrDefault(Telephony.Sms.SUBSCRIPTION_ID, -1)
                    )
                )
            }
        }

        messages
    }

    /**
     * Get a single SMS message by ID
     */
    suspend fun getSmsMessage(id: Long): SmsMessage? = withContext(Dispatchers.IO) {
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            null,
            "${Telephony.Sms._ID} = ?",
            arrayOf(id.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                SmsMessage(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
                    threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
                    address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
                    body = cursor.getStringOrNull(Telephony.Sms.BODY),
                    date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                    dateSent = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)),
                    type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)),
                    isRead = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1,
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)),
                    subscriptionId = cursor.getIntOrDefault(Telephony.Sms.SUBSCRIPTION_ID, -1)
                )
            } else null
        }
    }

    /**
     * Mark SMS messages as read
     */
    suspend fun markThreadAsRead(threadId: Long) = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.READ, 1)
        }
        contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString())
        )
    }

    // ===== MMS Messages =====

    /**
     * Get MMS messages for a thread
     */
    suspend fun getMmsMessages(
        threadId: Long,
        limit: Int = 50
    ): List<MmsMessage> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<MmsMessage>()

        contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            null,
            "${Telephony.Mms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Mms.DATE} DESC LIMIT $limit"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val mmsId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms._ID))

                messages.add(
                    MmsMessage(
                        id = mmsId,
                        threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)),
                        date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)) * 1000, // MMS dates are in seconds
                        dateSent = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE_SENT)) * 1000,
                        messageBox = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)),
                        isRead = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.READ)) == 1,
                        subject = cursor.getStringOrNull(Telephony.Mms.SUBJECT),
                        textParts = getMmsTextParts(mmsId),
                        imageParts = getMmsAttachments(mmsId),
                        addresses = getMmsAddresses(mmsId)
                    )
                )
            }
        }

        messages
    }

    private fun getMmsTextParts(mmsId: Long): List<String> {
        val parts = mutableListOf<String>()

        contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf("_id", "ct", "text"),
            "ct = 'text/plain'",
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(2)?.let { parts.add(it) }
            }
        }

        return parts
    }

    private fun getMmsAttachments(mmsId: Long): List<MmsAttachment> {
        val attachments = mutableListOf<MmsAttachment>()

        contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf("_id", "ct", "name", "_data"),
            "ct LIKE 'image/%' OR ct LIKE 'video/%' OR ct LIKE 'audio/%'",
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val partId = cursor.getLong(0)
                attachments.add(
                    MmsAttachment(
                        partId = partId,
                        contentType = cursor.getString(1) ?: "application/octet-stream",
                        fileName = cursor.getString(2),
                        dataUri = Uri.parse("content://mms/part/$partId")
                    )
                )
            }
        }

        return attachments
    }

    private fun getMmsAddresses(mmsId: Long): List<MmsAddress> {
        val addresses = mutableListOf<MmsAddress>()

        contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)
                if (!address.isNullOrBlank() && !address.contains("insert-address-token")) {
                    addresses.add(
                        MmsAddress(
                            address = address,
                            type = cursor.getInt(1)
                        )
                    )
                }
            }
        }

        return addresses
    }

    // ===== Conversion Helpers =====

    /**
     * Convert SMS message to our MessageEntity format
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
                isFailed -> "failed"
                isPending -> "pending"
                else -> "complete"
            },
            simSlot = if (subscriptionId >= 0) subscriptionId else null
        )
    }

    /**
     * Convert MMS message to our MessageEntity format
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
            smsThreadId = threadId
        )
    }

    // ===== Cursor Extensions =====

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.getIntOrDefault(column: String, default: Int): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else default
    }
}
