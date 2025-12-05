package com.bluebubbles.services.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage as AndroidSmsMessage
import android.util.Log
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.MessageEntity
import com.bluebubbles.data.local.db.entity.MessageSource
import com.bluebubbles.services.notifications.NotificationService
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
    lateinit var messageDao: MessageDao

    @Inject
    lateinit var notificationService: NotificationService

    @Inject
    lateinit var smsContentProvider: SmsContentProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> handleSmsReceived(intent)
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> handleSmsDeliver(intent)
            "android.provider.Telephony.WAP_PUSH_DELIVER" -> handleMmsReceived(intent)
        }
    }

    private fun handleSmsReceived(intent: Intent) {
        // This is only for monitoring, not for default SMS app
        Log.d(TAG, "SMS_RECEIVED_ACTION - monitoring only")
    }

    private fun handleSmsDeliver(intent: Intent) {
        // This is called when we are the default SMS app
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        Log.d(TAG, "SMS_DELIVER_ACTION - received ${messages.size} message parts")

        scope.launch {
            try {
                processIncomingSms(messages)
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

    private suspend fun processIncomingSms(messages: Array<AndroidSmsMessage>) {
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

            // Create message entity
            val message = MessageEntity(
                guid = "sms-incoming-${System.currentTimeMillis()}-${address.hashCode()}",
                chatGuid = chatGuid,
                text = fullBody,
                dateCreated = timestamp,
                isFromMe = false,
                messageSource = MessageSource.LOCAL_SMS.name
            )
            messageDao.insertMessage(message)

            // Show notification
            notificationService.showMessageNotification(
                chatGuid = chatGuid,
                chatTitle = chat.displayName ?: address,
                messageText = fullBody,
                messageGuid = message.guid,
                senderName = null
            )

            Log.d(TAG, "Saved incoming SMS from $address: ${fullBody.take(50)}...")
        }
    }
}
