package com.bothbubbles.services.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.ChatHandleCrossRef
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.ui.components.PhoneAndCodeParsingUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives MMS messages when app is the default SMS handler.
 *
 * MMS delivery flow:
 * 1. WAP Push notification received (this receiver)
 * 2. Parse PDU to extract sender and content location
 * 3. Check spam/blocking - reject if blocked
 * 4. Write to MMS provider to trigger system download
 * 5. Show early notification to user
 * 6. SmsContentObserver picks up full message after download completes
 */
@AndroidEntryPoint
class MmsBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsBroadcastReceiver"

        // MMS provider columns
        private const val MMS_CONTENT_LOCATION = "ct_l"
        private const val MMS_EXPIRY = "exp"
        private const val MMS_MESSAGE_SIZE = "m_size"
        private const val MMS_MESSAGE_TYPE = "m_type"
        private const val MMS_SUBJECT = "sub"
        private const val MMS_TRANSACTION_ID = "tr_id"
        private const val MMS_STATUS = "st"
        private const val MMS_MESSAGE_BOX = "msg_box"
        private const val MMS_READ = "read"
        private const val MMS_SEEN = "seen"
        private const val MMS_DATE = "date"

        // Message type for notification indication
        private const val MESSAGE_TYPE_NOTIFICATION_IND = 130

        // Message box type for inbox
        private const val MESSAGE_BOX_INBOX = 1

        // MMS address types (from PduHeaders)
        private const val PDU_ADDR_TYPE_FROM = 137
        private const val PDU_ADDR_TYPE_TO = 151

        // Character set for UTF-8 (IANA MIBenum)
        private const val CHARSET_UTF8 = 106
    }

    @Inject
    lateinit var smsPermissionHelper: SmsPermissionHelper

    @Inject
    lateinit var chatDao: ChatDao

    @Inject
    lateinit var handleDao: HandleDao

    @Inject
    lateinit var notificationService: NotificationService

    @Inject
    lateinit var spamRepository: SpamRepository

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        // Only handle MMS when we are the default SMS app
        if (!smsPermissionHelper.isDefaultSmsApp()) {
            Log.d(TAG, "Ignoring MMS - not default SMS app")
            return
        }

        when (intent.action) {
            Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION -> {
                Log.d(TAG, "WAP_PUSH_RECEIVED_ACTION - waiting for DELIVER")
                // When we're the default app, we wait for DELIVER action
            }

            Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION -> {
                Log.d(TAG, "WAP_PUSH_DELIVER_ACTION - processing MMS")
                handleMmsDeliver(context, intent)
            }
        }
    }

    private fun handleMmsDeliver(context: Context, intent: Intent) {
        val pdu = intent.getByteArrayExtra("data")
        val subscriptionId = intent.getIntExtra(
            "subscription",
            SmsManager.getDefaultSmsSubscriptionId()
        )

        if (pdu == null) {
            Log.w(TAG, "No PDU data in MMS intent")
            return
        }

        // Parse the MMS notification PDU
        val notification = MmsPduParser.parseNotificationInd(pdu)
        if (notification == null || !notification.isValid) {
            Log.w(TAG, "Failed to parse MMS notification PDU")
            return
        }

        Log.d(TAG, "MMS notification from: ${notification.from}, subject: ${notification.subject}, size: ${notification.messageSize}")

        // Get pending result to allow async processing
        val pendingResult = goAsync()

        scope.launch {
            try {
                processMmsNotification(context, notification, pdu, subscriptionId)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing MMS notification", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processMmsNotification(
        context: Context,
        notification: MmsPduParser.MmsNotificationInfo,
        pdu: ByteArray,
        subscriptionId: Int
    ) {
        val rawAddress = notification.from
        if (rawAddress.isNullOrBlank()) {
            Log.w(TAG, "No sender address in MMS notification")
            // Still write to provider to trigger download - we'll get address later
            writeMmsToProvider(context, notification, subscriptionId)
            return
        }

        // Normalize address
        val address = PhoneAndCodeParsingUtils.normalizePhoneNumber(rawAddress)

        // Check if sender is blocked
        val isBlocked = spamRepository.isBlocked(address)
        if (isBlocked) {
            Log.i(TAG, "MMS from $address is blocked - rejecting")
            // Don't write to provider, effectively blocking the MMS
            return
        }

        // Check unknown number blocking setting
        val blockUnknown = settingsDataStore.blockUnknownSenders.first()
        if (blockUnknown) {
            val isKnownContact = isKnownContact(context, address)
            if (!isKnownContact) {
                Log.i(TAG, "MMS from unknown sender $address - blocking per settings")
                return
            }
        }

        // Write to MMS provider to trigger download
        val mmsId = writeMmsToProvider(context, notification, subscriptionId)

        if (mmsId != null) {
            // Ensure chat exists for this sender
            val chatGuid = "sms;-;$address"
            ensureChatExists(chatGuid, address)

            // Ensure handle exists
            ensureHandleExists(chatGuid, address)

            // Show early notification
            val chat = chatDao.getChatByGuid(chatGuid)
            val notificationText = notification.subject?.takeIf { it.isNotBlank() }
                ?: "Incoming MMS"

            notificationService.showMessageNotification(
                chatGuid = chatGuid,
                chatTitle = chat?.displayName ?: address,
                messageText = notificationText,
                messageGuid = "mms-pending-$mmsId",
                senderName = null,
                senderAddress = address
            )

            Log.i(TAG, "MMS from $address written to provider (ID: $mmsId), awaiting download")
        }
    }

    /**
     * Write MMS notification to the system MMS provider.
     * This triggers the system to download the actual MMS content.
     */
    private fun writeMmsToProvider(
        context: Context,
        notification: MmsPduParser.MmsNotificationInfo,
        subscriptionId: Int
    ): Long? {
        return try {
            val values = ContentValues().apply {
                // MMS metadata
                put(MMS_MESSAGE_TYPE, MESSAGE_TYPE_NOTIFICATION_IND)
                put(MMS_TRANSACTION_ID, notification.transactionId)
                put(MMS_CONTENT_LOCATION, notification.contentLocation)
                put(MMS_MESSAGE_BOX, MESSAGE_BOX_INBOX)
                put(MMS_READ, 0)
                put(MMS_SEEN, 0)
                put(MMS_DATE, System.currentTimeMillis() / 1000)

                notification.subject?.let { put(MMS_SUBJECT, it) }
                notification.messageSize?.let { put(MMS_MESSAGE_SIZE, it) }
                notification.expiry?.let { put(MMS_EXPIRY, it) }

                // Subscription ID for dual-SIM
                if (subscriptionId != -1) {
                    put(Telephony.Mms.SUBSCRIPTION_ID, subscriptionId)
                }
            }

            // Android 14+ has stricter restrictions on SUBSCRIPTION_ID
            Android14Utils.sanitizeMmsContentValues(values)

            val uri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
            val mmsId = uri?.lastPathSegment?.toLongOrNull()

            if (mmsId != null && notification.from != null) {
                // Write the sender address to the MMS address table
                writeMmsAddress(context, mmsId, notification.from)
            }

            mmsId
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception writing MMS to provider - not default app?", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error writing MMS to provider", e)
            null
        }
    }

    /**
     * Write sender address to MMS address table
     */
    private fun writeMmsAddress(context: Context, mmsId: Long, address: String) {
        try {
            val values = ContentValues().apply {
                put("address", address)
                put("type", PDU_ADDR_TYPE_FROM)
                put("charset", CHARSET_UTF8)
            }

            val uri = Uri.parse("content://mms/$mmsId/addr")
            context.contentResolver.insert(uri, values)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing MMS address", e)
        }
    }

    /**
     * Check if a phone number belongs to a known contact
     */
    private fun isKnownContact(context: Context, phoneNumber: String): Boolean {
        return try {
            val uri = Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.count > 0
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if contact is known", e)
            false
        }
    }

    private suspend fun ensureChatExists(chatGuid: String, address: String) {
        val existingChat = chatDao.getChatByGuid(chatGuid)
        if (existingChat == null) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = address,
                displayName = null,
                isGroup = false,
                lastMessageDate = System.currentTimeMillis(),
                lastMessageText = "Incoming MMS...",
                unreadCount = 1
            )
            chatDao.insertChat(chat)
        } else {
            chatDao.updateUnreadCount(chatGuid, existingChat.unreadCount + 1)
        }
    }

    private suspend fun ensureHandleExists(chatGuid: String, address: String) {
        try {
            var handle = handleDao.getHandleByAddressAndService(address, "SMS")
            if (handle == null) {
                val handleId = handleDao.insertHandle(
                    HandleEntity(address = address, service = "SMS")
                )
                handle = handleDao.getHandleById(handleId)
            }
            handle?.let {
                chatDao.insertChatHandleCrossRef(ChatHandleCrossRef(chatGuid, it.id))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring handle exists", e)
        }
    }
}
