package com.bothbubbles.services.sms

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import com.bothbubbles.core.model.entity.ChatEntity
import com.bothbubbles.core.model.entity.HandleEntity
import com.bothbubbles.core.model.entity.MessageEntity
import com.bothbubbles.core.model.entity.MessageSource
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Headless service required for the app to be eligible as the default SMS app.
 *
 * This service handles android.intent.action.RESPOND_VIA_MESSAGE, which is triggered
 * when the user chooses to respond to an incoming call via text message ("Reply with message"
 * feature on incoming calls).
 *
 * Without this service declared in the manifest with the proper intent filter and permission,
 * the app cannot be set as the default SMS app.
 *
 * Messages sent through this service are logged to the local database so they appear
 * in the conversation history.
 */
class HeadlessSmsSendService : Service() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface HeadlessSmsSendEntryPoint {
        fun chatDao(): ChatDao
        fun handleDao(): HandleDao
        fun messageDao(): MessageDao
        fun unifiedChatDao(): UnifiedChatDao
        fun androidContactsService(): AndroidContactsService
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var chatDao: ChatDao
    private lateinit var handleDao: HandleDao
    private lateinit var messageDao: MessageDao
    private lateinit var unifiedChatDao: UnifiedChatDao
    private lateinit var androidContactsService: AndroidContactsService

    override fun onCreate() {
        super.onCreate()
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            HeadlessSmsSendEntryPoint::class.java
        )
        chatDao = entryPoint.chatDao()
        handleDao = entryPoint.handleDao()
        messageDao = entryPoint.messageDao()
        unifiedChatDao = entryPoint.unifiedChatDao()
        androidContactsService = entryPoint.androidContactsService()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val action = intent.action
        if (TelephonyManager.ACTION_RESPOND_VIA_MESSAGE == action) {
            handleRespondViaMessage(intent, startId)
        } else {
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun handleRespondViaMessage(intent: Intent, startId: Int) {
        // Get the recipient address from the intent data (sms:+1234567890 or smsto:+1234567890)
        val uri: Uri? = intent.data
        if (uri == null) {
            Timber.e("No URI in RESPOND_VIA_MESSAGE intent")
            stopSelf(startId)
            return
        }

        val recipient = getRecipientFromUri(uri)
        if (recipient.isNullOrBlank()) {
            Timber.e("Could not extract recipient from URI: $uri")
            stopSelf(startId)
            return
        }

        // Get the message text from the intent extra
        val message = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (message.isNullOrBlank()) {
            Timber.e("No message text in RESPOND_VIA_MESSAGE intent")
            stopSelf(startId)
            return
        }

        Timber.d("Sending quick-reply SMS to $recipient")

        // Launch coroutine to handle database operations and SMS sending
        serviceScope.launch {
            val now = System.currentTimeMillis()
            val chatGuid = "sms;-;$recipient"
            val messageGuid = "temp-quick-reply-${UUID.randomUUID()}"

            try {
                // Ensure chat exists
                ensureChatExists(chatGuid, recipient, now, message)

                // Ensure handle exists and get its ID
                val handleId = ensureHandleExists(recipient)

                // Send the SMS first
                val smsManager = getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(message)

                if (parts.size == 1) {
                    smsManager.sendTextMessage(
                        recipient,
                        null, // service center - use default
                        message,
                        null, // sent intent - not tracking for quick reply
                        null  // delivery intent
                    )
                } else {
                    smsManager.sendMultipartTextMessage(
                        recipient,
                        null,
                        parts,
                        null,
                        null
                    )
                }

                // Get unifiedChatId for the message
                val chat = chatDao.getChatByGuid(chatGuid)
                val unifiedChatId = chat?.unifiedChatId

                // Create message entity for the sent message (after successful send)
                val messageEntity = MessageEntity(
                    guid = messageGuid,
                    chatGuid = chatGuid,
                    unifiedChatId = unifiedChatId,
                    handleId = handleId,
                    text = message,
                    isFromMe = true,
                    dateCreated = now,
                    dateDelivered = now,  // For local SMS, sent = delivered
                    messageSource = MessageSource.LOCAL_SMS.name
                )

                // Insert message into database
                messageDao.insertMessage(messageEntity)

                // Update unified chat's latest message
                unifiedChatId?.let { unifiedId ->
                    unifiedChatDao.updateLatestMessageIfNewer(
                        id = unifiedId,
                        date = now,
                        text = message,
                        guid = messageGuid,
                        isFromMe = true,
                        hasAttachments = false,
                        source = MessageSource.LOCAL_SMS.name,
                        dateDelivered = now,
                        dateRead = null,
                        error = 0
                    )
                }

                Timber.d("Quick-reply SMS sent and logged successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to send quick-reply SMS")
            } finally {
                stopSelf(startId)
            }
        }
    }

    private suspend fun ensureChatExists(
        chatGuid: String,
        address: String,
        date: Long,
        lastMessage: String
    ) {
        val existingChat = chatDao.getChatByGuid(chatGuid)
        if (existingChat == null) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = address,
                displayName = null,
                isGroup = false,
                latestMessageDate = date
            )
            chatDao.insertChat(chat)
        }
    }

    private suspend fun ensureHandleExists(address: String): Long? {
        try {
            var handle = handleDao.getHandleByAddressAndService(address, "SMS")
            if (handle == null) {
                // Look up contact info from device contacts
                val contactName = androidContactsService.getContactDisplayName(address)
                val contactPhotoUri = androidContactsService.getContactPhotoUri(address)
                val formattedAddress = PhoneNumberFormatter.format(address)

                handle = HandleEntity(
                    address = address,
                    formattedAddress = formattedAddress,
                    service = "SMS",
                    cachedDisplayName = contactName,
                    cachedAvatarPath = contactPhotoUri
                )
                val insertedId = handleDao.insertHandle(handle)

                // Return the inserted ID
                return insertedId
            }
            return handle.id
        } catch (e: Exception) {
            Timber.e(e, "Failed to ensure handle exists for $address")
            return null
        }
    }

    private fun getRecipientFromUri(uri: Uri): String? {
        // URI format is typically: sms:+1234567890 or smsto:+1234567890
        val schemeSpecificPart = uri.schemeSpecificPart
        if (!schemeSpecificPart.isNullOrBlank()) {
            // Remove any extra parameters (after ?)
            return schemeSpecificPart.split("?")[0]
        }
        return null
    }
}
