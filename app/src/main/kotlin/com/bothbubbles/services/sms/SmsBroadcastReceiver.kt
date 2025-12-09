package com.bothbubbles.services.sms

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
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.services.nameinference.NameInferenceService
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.categorization.CategorizationRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.sound.SoundManager
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.ui.components.PhoneAndCodeParsingUtils
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

    @Inject
    lateinit var spamRepository: SpamRepository

    @Inject
    lateinit var categorizationRepository: CategorizationRepository

    @Inject
    lateinit var androidContactsService: AndroidContactsService

    @Inject
    lateinit var activeConversationManager: ActiveConversationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> handleSmsReceived(context, intent)
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> handleSmsDeliver(context, intent)
            "android.provider.Telephony.WAP_PUSH_DELIVER" -> handleMmsReceived(intent)
        }
    }

    private fun handleSmsReceived(context: Context, intent: Intent) {
        if (!smsPermissionHelper.isDefaultSmsApp()) {
            Log.d(TAG, "SMS_RECEIVED_ACTION - ignoring because we are not the default SMS app")
            return
        }

        // If we are the default app, handleSmsDeliver will be called, so we still ignore this one
        Log.d(TAG, "SMS_RECEIVED_ACTION - ignoring because we are default app (waiting for DELIVER)")
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

        messagesByAddress.forEach { (rawAddress, parts) ->
            if (rawAddress.isBlank()) return@forEach

            // Skip non-phone addresses (RCS, email, etc.) - rare for SMS but check anyway
            if (!isValidPhoneAddress(rawAddress)) {
                Log.d(TAG, "Skipping SMS from non-phone address: $rawAddress")
                return@forEach
            }

            // Normalize address to prevent duplicate conversations for same phone number
            // e.g., "+16505551229", "6505551229", "(650) 555-1229" all become "+16505551229"
            val address = PhoneAndCodeParsingUtils.normalizePhoneNumber(rawAddress)

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

            // Use raw address for system provider compatibility
            val providerResult = maybeInsertIntoSystemProvider(context, rawAddress, fullBody, timestamp)

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

            // Ensure handle exists, link to chat, and try to infer sender name
            ensureHandleAndInferName(chatGuid, address, fullBody)

            // Check if notifications are disabled for this chat
            if (!chat.notificationsEnabled) {
                Log.i(TAG, "Notifications disabled for chat $chatGuid, skipping SMS notification")
                return@forEach
            }

            // Check if chat is snoozed
            if (chat.isSnoozed) {
                Log.i(TAG, "Chat $chatGuid is snoozed, skipping SMS notification")
                return@forEach
            }

            // Check if user is currently viewing this conversation
            if (activeConversationManager.isConversationActive(chatGuid)) {
                Log.i(TAG, "Chat $chatGuid is currently active, playing in-app sound and skipping notification")
                soundManager.playReceiveSound(chatGuid)
                return@forEach
            }

            // Check for spam - if spam, skip notification
            val spamResult = spamRepository.evaluateAndMarkSpam(chatGuid, address, fullBody)
            if (spamResult.isSpam) {
                Log.i(TAG, "SMS from $address detected as spam (score: ${spamResult.score}), skipping notification")
                return@forEach
            }

            // Categorize the message for filtering purposes
            categorizationRepository.evaluateAndCategorize(chatGuid, address, fullBody)

            // Resolve sender name and avatar from contacts
            val senderName = androidContactsService.getContactDisplayName(address)
            val senderAvatarUri = androidContactsService.getContactPhotoUri(address)

            // Show notification (only for non-spam, non-snoozed, notifications-enabled chats)
            // Notification will play its own sound for inactive conversations
            notificationService.showMessageNotification(
                chatGuid = chatGuid,
                chatTitle = chat.displayName ?: senderName ?: address,
                messageText = fullBody,
                messageGuid = message.guid,
                senderName = senderName,
                senderAddress = address,
                avatarUri = senderAvatarUri
            )

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
     * Ensure handle exists for this address, link it to the chat, and try to infer sender name.
     * This is necessary for the conversation list to show "Maybe: [Name]" for inferred names.
     */
    private suspend fun ensureHandleAndInferName(chatGuid: String, address: String, messageText: String) {
        try {
            // Get or create handle for this address
            var handle = handleDao.getHandleByAddressAndService(address, "SMS")
            if (handle == null) {
                // Look up contact info from device contacts
                val contactName = androidContactsService.getContactDisplayName(address)
                val contactPhotoUri = androidContactsService.getContactPhotoUri(address)

                val handleId = handleDao.insertHandle(
                    HandleEntity(
                        address = address,
                        service = "SMS",
                        cachedDisplayName = contactName,
                        cachedAvatarPath = contactPhotoUri
                    )
                )
                handle = handleDao.getHandleById(handleId)
            }

            handle?.let {
                // Link handle to chat (required for conversation list to use handle's displayName)
                chatDao.insertChatHandleCrossRef(ChatHandleCrossRef(chatGuid, it.id))

                // Try to infer sender name from message
                nameInferenceService.processIncomingMessage(it.id, messageText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring handle and inferring sender name", e)
        }
    }

    /**
     * Check if an address is a valid phone number (not RCS, email, or other non-phone format)
     */
    private fun isValidPhoneAddress(address: String): Boolean {
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
}
