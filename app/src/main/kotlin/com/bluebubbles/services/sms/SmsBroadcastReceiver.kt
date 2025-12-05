package com.bluebubbles.services.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsMessage as AndroidSmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.HandleEntity
import com.bluebubbles.services.nameinference.NameInferenceService
import com.bluebubbles.data.local.db.entity.MessageEntity
import com.bluebubbles.data.local.db.entity.MessageSource
import com.bluebubbles.services.notifications.NotificationService
import com.bluebubbles.services.sound.SoundManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver for incoming SMS messages.
 * Only active when app is set as default SMS app.
 */
@AndroidEntryPoint
class SmsBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsBroadcastReceiver"
    }

    @Inject
    lateinit var chatDao: ChatDao

    @Inject
    lateinit var handleDao: HandleDao

    @Inject
    lateinit var messageDao: MessageDao

    @Inject
    lateinit var notificationService: NotificationService

    @Inject
    lateinit var nameInferenceService: NameInferenceService

    @Inject
    lateinit var smsContentProvider: SmsContentProvider

    @Inject
    lateinit var smsPermissionHelper: SmsPermissionHelper

    @Inject
    lateinit var soundManager: SoundManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> handleSmsReceived(context, intent)
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> handleSmsDeliver(context, intent)
            "android.provider.Telephony.WAP_PUSH_DELIVER" -> handleMmsReceived(intent)
        }
    }

    private fun handleSmsReceived(context: Context, intent: Intent) {
        // If we are the default app, handleSmsDeliver will be called, so we ignore this one.
        if (smsPermissionHelper.isDefaultSmsApp()) {
            Log.d(TAG, "SMS_RECEIVED_ACTION - ignoring because we are default app (waiting for DELIVER)")
            return
        }

        Log.d(TAG, "SMS_RECEIVED_ACTION - processing as non-default app")
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        scope.launch {
            try {
                processIncomingSms(context, messages)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS", e)
            }
        }
    }

    private fun handleSmsDeliver(context: Context, intent: Intent) {
        // This is called when we are the default SMS app
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        Log.d(TAG, "SMS_DELIVER_ACTION - received ${messages.size} message parts")

        scope.launch {
            try {
                processIncomingSms(context, messages)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS", e)
            }
        }
    }

    private fun handleMmsReceived(intent: Intent) {
        Log.d(TAG, "MMS received")
        // MMS handling is more complex and requires content observer
        // For now, we'll rely on content observer to pick up MMS
    }

    private suspend fun processIncomingSms(
        context: Context,
        messages: Array<AndroidSmsMessage>
    ) {
        // Group message parts by originating address (for multipart SMS)
        val messagesByAddress = messages.groupBy { it.originatingAddress ?: "" }

        messagesByAddress.forEach { (address, parts) ->
            if (address.isBlank()) return@forEach

            // Combine message body from parts
            val fullBody = parts.sortedBy { it.timestampMillis }
                .joinToString("") { it.messageBody ?: "" }

            val timestamp = parts.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()

            // Find or create chat for this address
            val chatGuid = "sms;-;$address"
            var chat = chatDao.getChatByGuid(chatGuid)

            if (chat == null) {
                // Create new chat for this SMS conversation
                chat = ChatEntity(
                    guid = chatGuid,
                    chatIdentifier = address,
                    displayName = null, // Will be resolved from contacts later
                    isGroup = false,
                    lastMessageDate = timestamp,
                    lastMessageText = fullBody,
                    unreadCount = 1
                )
                chatDao.insertChat(chat)
            } else {
                // Update existing chat
                chatDao.updateLastMessage(chatGuid, timestamp, fullBody)
                chatDao.updateUnreadCount(chatGuid, chat.unreadCount + 1)
            }

            val providerResult = maybeInsertIntoSystemProvider(context, address, fullBody, timestamp)

            val messageGuid = providerResult?.let { "sms-${it.id}" }
                ?: "sms-incoming-${System.currentTimeMillis()}-${address.hashCode()}"

            // Create message entity
            val message = MessageEntity(
                guid = messageGuid,
                chatGuid = chatGuid,
                text = fullBody,
                dateCreated = timestamp,
                isFromMe = false,
                messageSource = MessageSource.LOCAL_SMS.name,
                smsId = providerResult?.id,
                smsThreadId = providerResult?.threadId
            )
            messageDao.insertMessage(message)

            // Try to infer sender name from self-introduction patterns (e.g., "Hey it's John")
            tryInferSenderName(address, fullBody)

            // Show notification
            notificationService.showMessageNotification(
                chatGuid = chatGuid,
                chatTitle = chat.displayName ?: address,
                messageText = fullBody,
                messageGuid = message.guid,
                senderName = null
            )

            // Play receive sound
            soundManager.playReceiveSound()

            Log.d(TAG, "Saved incoming SMS from $address: ${fullBody.take(50)}...")
        }
    }

    private fun maybeInsertIntoSystemProvider(
        context: Context,
        address: String,
        body: String,
        timestamp: Long
    ): ProviderInsertResult? {
        // Must be the default SMS app to write to the SMS provider
        if (!smsPermissionHelper.isDefaultSmsApp()) return null

        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
            }

            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) ?: return null

            context.contentResolver.query(
                uri,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
                    val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
                    ProviderInsertResult(id, threadId)
                } else {
                    null
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to insert SMS into system provider", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error inserting SMS into system provider", e)
            null
        }
    }

    private data class ProviderInsertResult(
        val id: Long,
        val threadId: Long
    )

    /**
     * Try to infer sender name from message content.
     * Gets or creates a handle for the address and processes for name inference.
     */
    private suspend fun tryInferSenderName(address: String, messageText: String) {
        try {
            // Get or create handle for this address
            var handle = handleDao.getHandleByAddressAndService(address, "SMS")
            if (handle == null) {
                val handleId = handleDao.insertHandle(
                    HandleEntity(address = address, service = "SMS")
                )
                handle = handleDao.getHandleById(handleId)
            }

            handle?.let {
                nameInferenceService.processIncomingMessage(it.id, messageText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inferring sender name", e)
        }
    }
}
