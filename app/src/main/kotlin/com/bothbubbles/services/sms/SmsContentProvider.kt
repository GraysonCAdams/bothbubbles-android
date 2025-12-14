package com.bothbubbles.services.sms

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsContentProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SmsContentProvider"
    }

    private val contentResolver: ContentResolver = context.contentResolver

    // ===== Threads (Conversations) =====

    /**
     * Get all SMS/MMS threads (conversations)
     */
    suspend fun getThreads(limit: Int = 100, offset: Int = 0): List<SmsThread> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getThreads called (limit=$limit, offset=$offset)")
        val threads = mutableListOf<SmsThread>()

        val projection = arrayOf(
            Telephony.Threads._ID,
            Telephony.Threads.SNIPPET,
            Telephony.Threads.MESSAGE_COUNT,
            Telephony.Threads.DATE,
            Telephony.Threads.READ,
            Telephony.Threads.RECIPIENT_IDS
        )

        Log.d(TAG, "Querying content resolver...")
        val cursor = contentResolver.query(
            Telephony.Threads.CONTENT_URI.buildUpon()
                .appendQueryParameter("simple", "true")
                .build(),
            projection,
            null,
            null,
            "${Telephony.Threads.DATE} DESC LIMIT $limit OFFSET $offset"
        )
        Log.d(TAG, "Query returned, cursor count: ${cursor?.count ?: -1}")

        // First pass: collect thread info without addresses
        data class ThreadInfo(
            val threadId: Long,
            val snippet: String?,
            val messageCount: Int,
            val lastMessageDate: Long,
            val isRead: Boolean
        )
        val threadInfos = mutableListOf<ThreadInfo>()

        cursor?.use {
            while (it.moveToNext()) {
                val threadId = it.getLong(it.getColumnIndexOrThrow(Telephony.Threads._ID))
                threadInfos.add(
                    ThreadInfo(
                        threadId = threadId,
                        snippet = it.getStringOrNull(Telephony.Threads.SNIPPET),
                        messageCount = it.getInt(it.getColumnIndexOrThrow(Telephony.Threads.MESSAGE_COUNT)),
                        lastMessageDate = it.getLong(it.getColumnIndexOrThrow(Telephony.Threads.DATE)),
                        isRead = it.getInt(it.getColumnIndexOrThrow(Telephony.Threads.READ)) == 1
                    )
                )
            }
        }

        Log.d(TAG, "Collected ${threadInfos.size} thread infos, fetching addresses in batch...")

        // Batch lookup addresses for all threads at once (much faster than N+1 queries)
        val threadIds = threadInfos.map { it.threadId }
        val addressMap = contentResolver.getAddressesForThreadsBatch(threadIds)

        Log.d(TAG, "Batch address lookup complete, building thread objects...")

        // Build final thread objects
        for (info in threadInfos) {
            threads.add(
                SmsThread(
                    threadId = info.threadId,
                    recipientAddresses = addressMap[info.threadId] ?: emptyList(),
                    snippet = info.snippet,
                    messageCount = info.messageCount,
                    lastMessageDate = info.lastMessageDate,
                    isRead = info.isRead
                )
            )
        }

        Log.d(TAG, "Returning ${threads.size} threads")
        threads
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

    /**
     * Mark MMS messages as read for a thread
     */
    suspend fun markMmsAsRead(threadId: Long) = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
        }
        contentResolver.update(
            Telephony.Mms.CONTENT_URI,
            values,
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ? AND ${Telephony.Mms.READ} = 0",
            arrayOf(threadId.toString(), Telephony.Mms.MESSAGE_BOX_INBOX.toString())
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
                        textParts = contentResolver.getMmsTextParts(mmsId),
                        imageParts = contentResolver.getMmsAttachments(mmsId),
                        addresses = contentResolver.getMmsAddresses(mmsId)
                    )
                )
            }
        }

        messages
    }
}
