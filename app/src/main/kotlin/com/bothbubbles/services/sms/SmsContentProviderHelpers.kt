package com.bothbubbles.services.sms

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource

/**
 * Check if an address is a valid phone number (not RCS, email, or other non-phone format)
 */
internal fun isValidPhoneAddress(address: String): Boolean {
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
 * Get addresses for a thread.
 * Returns the other participants' phone numbers (excluding user's own number for received messages).
 */
internal fun ContentResolver.getAddressesForThread(threadId: Long): List<String> {
    val addresses = mutableSetOf<String>()

    // Get from SMS messages in the thread
    query(
        Telephony.Sms.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS),
        "${Telephony.Sms.THREAD_ID} = ?",
        arrayOf(threadId.toString()),
        "${Telephony.Sms.DATE} DESC LIMIT 1"
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getStringOrNull(Telephony.Sms.ADDRESS)?.let { addr ->
                if (isValidPhoneAddress(addr)) addresses.add(addr)
            }
        }
    }

    // If no SMS, check MMS messages in the thread
    if (addresses.isEmpty()) {
        // First get MMS IDs in this thread
        val mmsIds = mutableListOf<Long>()
        query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Mms.DATE} DESC LIMIT 5"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                mmsIds.add(cursor.getLong(0))
            }
        }

        // For each MMS, get addresses (excluding user's own number which is typically TO)
        for (mmsId in mmsIds) {
            query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address", "type"),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val addr = cursor.getString(0)
                    val type = cursor.getInt(1)
                    // type 137 = FROM (sender), type 130 = BCC (other recipients in group)
                    // type 151 = TO (usually the user for incoming messages)
                    // For determining thread participants, we want FROM and BCC addresses
                    if (addr != null && isValidPhoneAddress(addr) && (type == 137 || type == 130)) {
                        addresses.add(addr)
                    }
                }
            }
            // If we found addresses, no need to check more MMS messages
            if (addresses.isNotEmpty()) break
        }
    }

    return addresses.toList()
}

/**
 * Batch get addresses for multiple threads.
 * Returns a map of threadId -> list of addresses.
 * This is much more efficient than calling getAddressesForThread individually.
 */
internal fun ContentResolver.getAddressesForThreadsBatch(threadIds: List<Long>): Map<Long, List<String>> {
    if (threadIds.isEmpty()) return emptyMap()

    val result = mutableMapOf<Long, MutableSet<String>>()
    threadIds.forEach { result[it] = mutableSetOf() }

    // Batch size to avoid SQL query limits
    val batchSize = 500

    threadIds.chunked(batchSize).forEach { batch ->
        val placeholders = batch.joinToString(",") { "?" }
        val selectionArgs = batch.map { it.toString() }.toTypedArray()

        // Get SMS addresses for all threads in batch with a single query
        // Uses GROUP BY to get one address per thread (the most recent)
        query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS),
            "${Telephony.Sms.THREAD_ID} IN ($placeholders)",
            selectionArgs,
            "${Telephony.Sms.THREAD_ID}, ${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            var lastThreadId: Long? = null
            while (cursor.moveToNext()) {
                val threadId = cursor.getLong(0)
                // Only take first (most recent) address per thread
                if (threadId != lastThreadId) {
                    lastThreadId = threadId
                    cursor.getStringOrNull(Telephony.Sms.ADDRESS)?.let { addr ->
                        if (isValidPhoneAddress(addr)) {
                            result[threadId]?.add(addr)
                        }
                    }
                }
            }
        }

        // For threads without SMS addresses, try MMS
        val threadsWithoutAddresses = batch.filter { result[it]?.isEmpty() == true }
        if (threadsWithoutAddresses.isNotEmpty()) {
            val mmsPlaceholders = threadsWithoutAddresses.joinToString(",") { "?" }
            val mmsSelectionArgs = threadsWithoutAddresses.map { it.toString() }.toTypedArray()

            // Get MMS IDs for threads without addresses
            val mmsIdToThreadId = mutableMapOf<Long, Long>()
            query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID),
                "${Telephony.Mms.THREAD_ID} IN ($mmsPlaceholders)",
                mmsSelectionArgs,
                "${Telephony.Mms.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(0)
                    val threadId = cursor.getLong(1)
                    // Only keep first MMS per thread
                    if (!mmsIdToThreadId.values.contains(threadId)) {
                        mmsIdToThreadId[mmsId] = threadId
                    }
                }
            }

            // Get addresses for MMS messages (unfortunately can't batch across MMS IDs efficiently)
            for ((mmsId, threadId) in mmsIdToThreadId) {
                if (result[threadId]?.isNotEmpty() == true) continue

                query(
                    Uri.parse("content://mms/$mmsId/addr"),
                    arrayOf("address", "type"),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val addr = cursor.getString(0)
                        val type = cursor.getInt(1)
                        if (addr != null && isValidPhoneAddress(addr) && (type == 137 || type == 130)) {
                            result[threadId]?.add(addr)
                        }
                    }
                }
            }
        }
    }

    return result.mapValues { it.value.toList() }
}

/**
 * Get MMS text parts for a message
 */
internal fun ContentResolver.getMmsTextParts(mmsId: Long): List<String> {
    val parts = mutableListOf<String>()

    query(
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

/**
 * Get MMS attachments for a message
 */
internal fun ContentResolver.getMmsAttachments(mmsId: Long): List<MmsAttachment> {
    val attachments = mutableListOf<MmsAttachment>()

    query(
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

/**
 * Get MMS addresses for a message
 */
internal fun ContentResolver.getMmsAddresses(mmsId: Long): List<MmsAddress> {
    val addresses = mutableListOf<MmsAddress>()

    query(
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
            isDraft -> "draft"
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
        smsThreadId = threadId,
        smsStatus = if (isDraft) "draft" else "complete"
    )
}

// ===== Cursor Extensions =====

internal fun Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

internal fun Cursor.getIntOrDefault(column: String, default: Int): Int {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getInt(index) else default
}
