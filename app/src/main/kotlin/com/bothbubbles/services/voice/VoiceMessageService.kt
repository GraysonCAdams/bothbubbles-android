package com.bothbubbles.services.voice

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.telephony.TelephonyManager
import com.bothbubbles.core.model.entity.ChatEntity
import com.bothbubbles.core.model.entity.HandleEntity
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.PhoneContact
import com.bothbubbles.services.imessage.IMessageAvailabilityService
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Headless service for handling voice-initiated messages from Google Assistant and Android Auto.
 *
 * This service processes ACTION_SENDTO intents with SMS/MMS schemes and sends messages
 * without showing any UI. It supports:
 *
 * - **Contact name resolution**: "Send message to John" -> finds John's phone number
 * - **Group name resolution**: "Send message to Family" -> finds the group chat
 * - **iMessage detection**: Uses existing IMessageAvailabilityService to check availability
 * - **Unified routing**: Uses MessageSendingService.sendUnified() with AUTO mode
 *
 * The service reuses the existing iMessage/SMS routing logic to ensure consistent behavior
 * with the rest of the app.
 */
class VoiceMessageService : Service() {

    companion object {
        /**
         * Maximum time to wait for delivery confirmation.
         * Android Auto voice interactions typically expect responses within 30 seconds.
         * We use 25 seconds to allow some buffer for the response to reach the assistant.
         */
        private const val DELIVERY_TIMEOUT_MS = 25_000L

        /**
         * Time to wait for iMessage delivery before attempting SMS fallback.
         * This gives iMessage a fair chance while leaving time for SMS attempt.
         */
        private const val IMESSAGE_TIMEOUT_BEFORE_SMS_FALLBACK_MS = 15_000L

        /**
         * How often to poll for delivery status.
         * Socket events should update dateDelivered, but we poll as a backup.
         */
        private const val DELIVERY_POLL_INTERVAL_MS = 500L
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VoiceMessageEntryPoint {
        fun chatRepository(): ChatRepository
        fun chatDao(): ChatDao
        fun handleDao(): HandleDao
        fun messageDao(): MessageDao
        fun androidContactsService(): AndroidContactsService
        fun iMessageAvailabilityService(): IMessageAvailabilityService
        fun messageSendingService(): MessageSendingService
        fun settingsDataStore(): SettingsDataStore
        fun smsPermissionHelper(): SmsPermissionHelper
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var chatRepository: ChatRepository
    private lateinit var chatDao: ChatDao
    private lateinit var handleDao: HandleDao
    private lateinit var messageDao: MessageDao
    private lateinit var androidContactsService: AndroidContactsService
    private lateinit var iMessageAvailabilityService: IMessageAvailabilityService
    private lateinit var messageSendingService: MessageSendingService
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var smsPermissionHelper: SmsPermissionHelper

    override fun onCreate() {
        super.onCreate()
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            VoiceMessageEntryPoint::class.java
        )
        chatRepository = entryPoint.chatRepository()
        chatDao = entryPoint.chatDao()
        handleDao = entryPoint.handleDao()
        messageDao = entryPoint.messageDao()
        androidContactsService = entryPoint.androidContactsService()
        iMessageAvailabilityService = entryPoint.iMessageAvailabilityService()
        messageSendingService = entryPoint.messageSendingService()
        settingsDataStore = entryPoint.settingsDataStore()
        smsPermissionHelper = entryPoint.smsPermissionHelper()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent.action) {
            Intent.ACTION_SENDTO, TelephonyManager.ACTION_RESPOND_VIA_MESSAGE -> {
                handleVoiceMessage(intent, startId)
            }
            else -> {
                Timber.w("VoiceMessageService received unknown action: ${intent.action}")
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun handleVoiceMessage(intent: Intent, startId: Int) {
        val uri = intent.data
        if (uri == null) {
            Timber.e("No URI in voice message intent")
            stopSelf(startId)
            return
        }

        // Extract recipient from URI (could be phone number or contact name)
        val recipientRaw = extractRecipientFromUri(uri)
        if (recipientRaw.isNullOrBlank()) {
            Timber.e("Could not extract recipient from URI: $uri")
            stopSelf(startId)
            return
        }

        // Extract message body
        val messageBody = uri.getQueryParameter("body")
            ?: intent.getStringExtra("sms_body")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)

        if (messageBody.isNullOrBlank()) {
            Timber.e("No message body in voice message intent")
            stopSelf(startId)
            return
        }

        Timber.d("Voice message: recipient='$recipientRaw', body='${messageBody.take(30)}...'")

        serviceScope.launch {
            try {
                // Resolve recipient to a chat GUID
                val chatGuid = resolveRecipientToChatGuid(recipientRaw)

                if (chatGuid == null) {
                    Timber.e("Could not resolve recipient: $recipientRaw")
                    stopSelf(startId)
                    return@launch
                }

                Timber.d("Resolved recipient to chatGuid: $chatGuid")

                // Send message using unified routing (AUTO mode handles iMessage vs SMS)
                val result = messageSendingService.sendUnified(
                    chatGuid = chatGuid,
                    text = messageBody,
                    deliveryMode = MessageDeliveryMode.AUTO
                )

                if (result.isFailure) {
                    // Server rejected immediately - this is a real failure
                    Timber.e(result.exceptionOrNull(), "Voice message failed to send")
                    stopSelf(startId)
                    return@launch
                }

                val messageEntity = result.getOrNull()
                if (messageEntity == null) {
                    Timber.w("Voice message sent but no entity returned")
                    stopSelf(startId)
                    return@launch
                }

                Timber.d("Voice message accepted by server, waiting for delivery confirmation...")

                // Wait for delivery confirmation with SMS fallback support
                val deliveryConfirmed = waitForDeliveryWithSmsFallback(
                    messageGuid = messageEntity.guid,
                    chatGuid = chatGuid,
                    messageText = messageBody,
                    isIMessage = chatGuid.startsWith("iMessage", ignoreCase = true)
                )

                if (deliveryConfirmed) {
                    Timber.i("Voice message delivered successfully to $chatGuid")
                } else {
                    // Timeout waiting for delivery - message is still pending
                    // Let normal background flow handle eventual delivery or failure
                    Timber.w("Voice message delivery not confirmed within timeout (message still pending)")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing voice message")
            } finally {
                stopSelf(startId)
            }
        }
    }

    /**
     * Wait for delivery confirmation with SMS fallback support.
     *
     * For iMessage sends, if delivery isn't confirmed within IMESSAGE_TIMEOUT_BEFORE_SMS_FALLBACK_MS
     * and auto-switch is enabled, we'll attempt to send via SMS as a fallback.
     *
     * @param messageGuid The GUID of the message to monitor
     * @param chatGuid The chat GUID (used for SMS fallback)
     * @param messageText The message text (needed for SMS resend)
     * @param isIMessage Whether this was originally sent as iMessage
     * @return true if delivered (via iMessage or SMS fallback), false if timeout reached
     */
    private suspend fun waitForDeliveryWithSmsFallback(
        messageGuid: String,
        chatGuid: String,
        messageText: String,
        isIMessage: Boolean
    ): Boolean {
        // First, wait for iMessage delivery (shorter timeout if SMS fallback is possible)
        val shouldAttemptSmsFallback = isIMessage &&
            settingsDataStore.autoSwitchSendMode.first() &&
            canFallbackToSms(chatGuid)

        val iMessageTimeout = if (shouldAttemptSmsFallback) {
            IMESSAGE_TIMEOUT_BEFORE_SMS_FALLBACK_MS
        } else {
            DELIVERY_TIMEOUT_MS
        }

        val iMessageDelivered = waitForDeliveryConfirmation(messageGuid, iMessageTimeout)

        if (iMessageDelivered) {
            return true
        }

        // If iMessage didn't deliver and SMS fallback is possible, try SMS
        if (shouldAttemptSmsFallback) {
            Timber.i("iMessage not delivered within ${iMessageTimeout}ms, attempting SMS fallback...")

            // Soft-delete the pending iMessage message
            messageDao.softDeleteMessage(messageGuid)

            // Send via SMS
            val smsResult = messageSendingService.sendUnified(
                chatGuid = chatGuid.replace("iMessage;-;", "sms;-;"),
                text = messageText,
                deliveryMode = MessageDeliveryMode.LOCAL_SMS
            )

            if (smsResult.isFailure) {
                Timber.e(smsResult.exceptionOrNull(), "SMS fallback also failed")
                return false
            }

            val smsEntity = smsResult.getOrNull()
            if (smsEntity == null) {
                Timber.w("SMS sent but no entity returned")
                return false
            }

            Timber.d("SMS fallback sent, waiting for confirmation...")

            // Wait remaining time for SMS delivery
            val remainingTime = DELIVERY_TIMEOUT_MS - iMessageTimeout
            return waitForDeliveryConfirmation(smsEntity.guid, remainingTime)
        }

        return false
    }

    /**
     * Check if SMS fallback is possible for this chat.
     * Returns true if:
     * - The chat has a phone number (not email)
     * - It's not a group chat
     * - We have SMS permissions
     */
    private suspend fun canFallbackToSms(chatGuid: String): Boolean {
        // Extract address from chat GUID
        val address = chatGuid.split(";-;").getOrNull(1) ?: return false

        // Must be a phone number, not an email
        if (address.contains("@")) {
            return false
        }

        // Must not be a group chat
        val chat = chatDao.getChatByGuid(chatGuid)
        if (chat?.isGroup == true) {
            return false
        }

        // Must have SMS capability
        val smsCapability = smsPermissionHelper.getSmsCapabilityStatus()
        return smsCapability.isDefaultSmsApp && smsCapability.canSendSms
    }

    /**
     * Wait for delivery confirmation by polling the message's dateDelivered field.
     * Returns true if delivered within timeout, false if timeout reached.
     *
     * This does NOT change the message state - it only determines what we report
     * back to the voice assistant. The message continues its normal send flow
     * regardless of this timeout.
     */
    private suspend fun waitForDeliveryConfirmation(messageGuid: String, timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val message = messageDao.getMessageByGuid(messageGuid)

                // Check if message was delivered
                val dateDelivered = message?.dateDelivered
                if (dateDelivered != null && dateDelivered > 0) {
                    return@withTimeoutOrNull true
                }

                // Check if message failed (error > 0 means failure)
                if (message?.error != null && message.error > 0) {
                    Timber.w("Message failed during delivery wait: error=${message.error}")
                    return@withTimeoutOrNull false
                }

                // Check if GUID was replaced (temp -> server GUID)
                // For SMS, delivery is essentially immediate once sent
                if (message != null && !message.guid.startsWith("temp-")) {
                    // For local SMS, consider it delivered once it has a non-temp GUID
                    if (message.messageSource == "LOCAL_SMS" || message.messageSource == "LOCAL_MMS") {
                        return@withTimeoutOrNull true
                    }
                }

                delay(DELIVERY_POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    /**
     * Resolve a recipient (phone number, contact name, or group name) to a chat GUID.
     *
     * Resolution order:
     * 1. If it looks like a phone number, use it directly
     * 2. Search for a group with matching display name
     * 3. Search for a contact with matching name and use their phone number
     */
    private suspend fun resolveRecipientToChatGuid(recipient: String): String? {
        // 1. Check if it's already a phone number
        if (looksLikePhoneNumber(recipient)) {
            return resolvePhoneNumberToChatGuid(recipient)
        }

        // 2. Try to find a group with this name
        val groupChat = findGroupByName(recipient)
        if (groupChat != null) {
            Timber.d("Found group '${groupChat.displayName}' -> ${groupChat.guid}")
            return groupChat.guid
        }

        // 3. Try to find a contact with this name
        val contact = findContactByName(recipient)
        if (contact != null) {
            val phoneNumber = contact.phoneNumbers.firstOrNull()
            if (phoneNumber != null) {
                Timber.d("Found contact '${contact.displayName}' -> $phoneNumber")
                return resolvePhoneNumberToChatGuid(phoneNumber)
            }
        }

        Timber.w("Could not resolve recipient: $recipient")
        return null
    }

    /**
     * Resolve a phone number to a chat GUID, creating the chat if necessary.
     */
    private suspend fun resolvePhoneNumberToChatGuid(phoneNumber: String): String? {
        val normalizedNumber = normalizeAddress(phoneNumber)

        // Check if we have an existing chat for this address
        val existingChatGuid = chatRepository.findChatGuidByAddress(normalizedNumber)
        if (existingChatGuid != null) {
            Timber.d("Found existing chat for $normalizedNumber: $existingChatGuid")
            return existingChatGuid
        }

        // Check iMessage availability using existing service
        val isIMessageAvailable = try {
            val result = iMessageAvailabilityService.checkAvailability(normalizedNumber)
            result.getOrNull() ?: false
        } catch (e: Exception) {
            Timber.w(e, "iMessage availability check failed, defaulting to SMS")
            false
        }

        // Determine chat GUID format based on service
        val chatGuid = if (isIMessageAvailable) {
            "iMessage;-;$normalizedNumber"
        } else {
            "sms;-;$normalizedNumber"
        }

        // Ensure chat exists in database
        ensureChatExists(chatGuid, normalizedNumber)

        return chatGuid
    }

    /**
     * Find a group chat by its display name (case-insensitive).
     */
    private suspend fun findGroupByName(name: String): ChatEntity? {
        val searchResults = chatRepository.searchGroupChats(name).first()

        // First try exact match (case-insensitive)
        val exactMatch = searchResults.find {
            it.displayName?.equals(name, ignoreCase = true) == true
        }
        if (exactMatch != null) {
            return exactMatch
        }

        // Fall back to first result if only one match
        if (searchResults.size == 1) {
            return searchResults.first()
        }

        return null
    }

    /**
     * Find a contact by name (case-insensitive).
     */
    private suspend fun findContactByName(name: String): PhoneContact? {
        val contacts = androidContactsService.getAllContacts()

        // First try exact match
        val exactMatch = contacts.find {
            it.displayName.equals(name, ignoreCase = true)
        }
        if (exactMatch != null) {
            return exactMatch
        }

        // Try partial match (name contains the search term)
        val partialMatches = contacts.filter {
            it.displayName.contains(name, ignoreCase = true)
        }

        // Return single match, or null if ambiguous
        return if (partialMatches.size == 1) partialMatches.first() else null
    }

    /**
     * Ensure a chat entity exists in the database.
     */
    private suspend fun ensureChatExists(chatGuid: String, address: String) {
        val existingChat = chatDao.getChatByGuid(chatGuid)
        if (existingChat == null) {
            val chat = ChatEntity(
                guid = chatGuid,
                chatIdentifier = address,
                displayName = null,
                isGroup = false,
                lastMessageDate = System.currentTimeMillis(),
                lastMessageText = null,
                unreadCount = 0
            )
            chatDao.insertChat(chat)
            Timber.d("Created new chat: $chatGuid")

            // Also ensure handle exists
            ensureHandleExists(address, chatGuid)
        }
    }

    /**
     * Ensure a handle entity exists for the address.
     */
    private suspend fun ensureHandleExists(address: String, chatGuid: String) {
        val service = if (chatGuid.startsWith("iMessage", ignoreCase = true)) "iMessage" else "SMS"
        val existingHandle = handleDao.getHandleByAddressAndService(address, service)

        if (existingHandle == null) {
            val contactName = androidContactsService.getContactDisplayName(address)
            val contactPhoto = androidContactsService.getContactPhotoUri(address)
            val formattedAddress = PhoneNumberFormatter.format(address)

            val handle = HandleEntity(
                address = address,
                formattedAddress = formattedAddress,
                service = service,
                cachedDisplayName = contactName,
                cachedAvatarPath = contactPhoto
            )
            handleDao.insertHandle(handle)
            Timber.d("Created new handle for $address ($service)")
        }
    }

    private fun extractRecipientFromUri(uri: Uri): String? {
        val schemeSpecificPart = uri.schemeSpecificPart
        if (!schemeSpecificPart.isNullOrBlank()) {
            // Remove query parameters
            return schemeSpecificPart.split("?")[0].trim()
        }
        return null
    }

    private fun looksLikePhoneNumber(text: String): Boolean {
        // Contains mostly digits with optional + at start
        val digitsOnly = text.replace(Regex("[^0-9+]"), "")
        return digitsOnly.length >= 7 &&
               (digitsOnly.startsWith("+") || digitsOnly.all { it.isDigit() })
    }

    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            address.lowercase()
        } else {
            address.replace(Regex("[^0-9+]"), "")
        }
    }
}
