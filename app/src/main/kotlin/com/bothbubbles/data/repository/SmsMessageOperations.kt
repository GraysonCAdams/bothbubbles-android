package com.bothbubbles.data.repository

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.TombstoneDao
import com.bothbubbles.services.sms.SmsContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles SMS/MMS message operations such as marking as read, deleting messages,
 * and deleting entire threads.
 */
class SmsMessageOperations(
    private val context: Context,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val tombstoneDao: TombstoneDao,
    private val smsContentProvider: SmsContentProvider
) {

    /**
     * Mark all messages in a thread as read (both SMS and MMS)
     */
    suspend fun markThreadAsRead(
        chatGuid: String,
        getThreadIdForChat: suspend (String) -> Long?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val threadId = getThreadIdForChat(chatGuid)
            if (threadId != null) {
                smsContentProvider.markThreadAsRead(threadId)  // SMS
                smsContentProvider.markMmsAsRead(threadId)     // MMS
            }
            chatDao.updateUnreadCount(chatGuid, 0)
            chatDao.updateUnreadStatus(chatGuid, false)
        }
    }

    /**
     * Delete a message.
     * Records a tombstone to prevent resurrection during sync.
     */
    suspend fun deleteMessage(messageGuid: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Record tombstone to prevent resurrection during sync
            tombstoneDao.recordDeletedMessage(messageGuid)

            // Delete from our database
            messageDao.deleteMessage(messageGuid)

            // Delete from system SMS/MMS database if it's a local message
            when {
                messageGuid.startsWith("sms-") -> {
                    val smsId = messageGuid.removePrefix("sms-").toLongOrNull()
                    if (smsId != null) {
                        context.contentResolver.delete(
                            Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, smsId.toString()),
                            null,
                            null
                        )
                    }
                }
                messageGuid.startsWith("mms-") -> {
                    val mmsId = messageGuid.removePrefix("mms-").toLongOrNull()
                    if (mmsId != null) {
                        context.contentResolver.delete(
                            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, mmsId.toString()),
                            null,
                            null
                        )
                    }
                }
            }
        }
    }

    /**
     * Delete all messages in a thread.
     * Records a tombstone to prevent resurrection during sync.
     */
    suspend fun deleteThread(
        chatGuid: String,
        getThreadIdForChat: suspend (String) -> Long?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Record tombstone to prevent resurrection during sync
            tombstoneDao.recordDeletedChat(chatGuid)

            val threadId = getThreadIdForChat(chatGuid)
            if (threadId != null) {
                // Delete SMS messages
                context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms.THREAD_ID} = ?",
                    arrayOf(threadId.toString())
                )
                // Delete MMS messages
                context.contentResolver.delete(
                    Telephony.Mms.CONTENT_URI,
                    "${Telephony.Mms.THREAD_ID} = ?",
                    arrayOf(threadId.toString())
                )
            }

            // Delete from our database
            messageDao.deleteMessagesForChat(chatGuid)
            chatDao.deleteChatByGuid(chatGuid)
        }
    }
}
