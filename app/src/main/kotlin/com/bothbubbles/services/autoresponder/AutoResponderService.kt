package com.bothbubbles.services.autoresponder

import android.util.Log
import com.bothbubbles.data.local.db.dao.AutoRespondedSenderDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.AutoRespondedSenderEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.imessage.IMessageAvailabilityService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service that handles automatic responses to SMS senders who are iMessage-capable.
 *
 * When someone sends you an SMS but is also registered on iMessage, this sends
 * them an iMessage explaining how to add your email to their contacts so future
 * messages go through iMessage instead of SMS.
 *
 * The auto-responder only triggers if:
 * - The incoming message is via SMS (not iMessage)
 * - The user has NOT already replied to this sender via iMessage
 *
 * This ensures we don't send the auto-response to people the user is already
 * actively messaging via iMessage from their phone.
 */
@Singleton
class AutoResponderService @Inject constructor(
    private val autoRespondedSenderDao: AutoRespondedSenderDao,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val messageSendingService: MessageSendingService,
    private val iMessageAvailabilityService: IMessageAvailabilityService,
    private val androidContactsService: AndroidContactsService,
    private val smsRepository: SmsRepository,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "AutoResponderService"
    }

    /**
     * Check if we should auto-respond to this message and send response if appropriate.
     *
     * @param chatGuid The chat GUID where the message was received
     * @param senderAddress The sender's email or phone number
     * @param isFromMe Whether this message is from the current user
     * @return true if auto-response was sent, false otherwise
     */
    suspend fun maybeAutoRespond(
        chatGuid: String,
        senderAddress: String,
        isFromMe: Boolean
    ): Boolean {
        // 1. Check feature enabled
        if (!settingsDataStore.autoResponderEnabled.first()) {
            Log.d(TAG, "Auto-responder disabled")
            return false
        }

        // 2. Skip if initial sync not complete (avoid responding to historical messages)
        if (!settingsDataStore.initialSyncComplete.first()) {
            Log.d(TAG, "Initial sync not complete, skipping auto-response")
            return false
        }

        // 3. Skip messages from self
        if (isFromMe) {
            Log.d(TAG, "Message is from self, skipping")
            return false
        }

        // 4. Skip group chats
        val chat = chatDao.getChatByGuid(chatGuid)
        if (chat?.isGroup == true) {
            Log.d(TAG, "Chat is group chat, skipping")
            return false
        }

        // 5. Only respond to SMS messages (not iMessage)
        if (chat?.isSmsChat != true) {
            Log.d(TAG, "Chat is not SMS, skipping auto-responder")
            return false
        }

        // 6. Skip if user has already replied via iMessage to this sender
        if (messageDao.hasOutboundIMessageToAddress(senderAddress)) {
            Log.d(TAG, "User already replied via iMessage to $senderAddress, skipping")
            return false
        }

        // 7. Already responded to this SENDER? (persists even if chat deleted)
        if (autoRespondedSenderDao.get(senderAddress) != null) {
            Log.d(TAG, "Already auto-responded to sender: $senderAddress")
            return false
        }

        // 8. Check iMessage availability (only respond if sender can receive iMessage)
        val availabilityResult = iMessageAvailabilityService.checkAvailability(senderAddress)
        if (availabilityResult.isFailure || availabilityResult.getOrNull() != true) {
            Log.d(TAG, "Sender not iMessage registered: $senderAddress")
            return false
        }

        // 9. Check filter mode
        val filter = settingsDataStore.autoResponderFilter.first()
        val passesFilter = when (filter) {
            "everyone" -> true
            "known_senders" -> androidContactsService.isContactSaved(senderAddress)
            "favorites" -> androidContactsService.isContactStarred(senderAddress)
            else -> false
        }
        if (!passesFilter) {
            Log.d(TAG, "Sender doesn't pass filter ($filter): $senderAddress")
            return false
        }

        // 10. Check rate limit (default 10/hour)
        val limit = settingsDataStore.autoResponderRateLimit.first()
        val oneHourAgo = System.currentTimeMillis() - 3600_000
        val recentCount = autoRespondedSenderDao.countSince(oneHourAgo)
        if (recentCount >= limit) {
            Log.w(TAG, "Rate limit exceeded ($recentCount/$limit per hour)")
            return false
        }

        // 11. Build and send message (using stored alias preference)
        val recommendedAlias = settingsDataStore.autoResponderRecommendedAlias.first()
            .takeIf { it.isNotBlank() }
        val message = buildMessage(recommendedAlias)
        Log.d(TAG, "Sending auto-response to $senderAddress: $message")

        return try {
            val sendResult = messageSendingService.sendMessage(chatGuid, message)
            if (sendResult.isSuccess) {
                // Track by SENDER ADDRESS so it persists even if chat is deleted
                autoRespondedSenderDao.insert(AutoRespondedSenderEntity(senderAddress = senderAddress))
                Log.i(TAG, "Auto-response sent successfully to $senderAddress")
                true
            } else {
                Log.e(TAG, "Failed to send auto-response: ${sendResult.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending auto-response", e)
            false
        }
    }

    /**
     * Build the auto-response message.
     *
     * @param recommendedAlias Optional alias (phone/email) to recommend adding to contacts.
     *                         If null, uses a generic message.
     */
    fun buildMessage(recommendedAlias: String? = null): String {
        val phone = smsRepository.getAvailableSims().firstOrNull()?.number
            ?.takeIf { it.isNotBlank() }
            ?.let { formatPhone(it) }

        val baseMessage = if (recommendedAlias != null) {
            "Hello, I am on BlueBubbles which lets me use iMessage on my Android. " +
                "Please add $recommendedAlias to my contact card so future messages " +
                "go through iMessage."
        } else {
            "Hello, I am on BlueBubbles which lets me use iMessage on my Android. " +
                "Please add my iMessage address to my contact card so future messages " +
                "go through iMessage."
        }

        return if (phone != null && recommendedAlias != phone) {
            "$baseMessage You can still reach me via SMS at $phone."
        } else {
            baseMessage
        }
    }

    /**
     * Format phone number as (xxx) xxx-xxxx for US numbers.
     * Returns original format for international numbers.
     */
    private fun formatPhone(phone: String): String {
        // Extract digits only
        val digits = phone.replace(Regex("[^0-9]"), "")
        return when {
            // US 10-digit: (555) 123-4567
            digits.length == 10 -> "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
            // US 11-digit with country code: (555) 123-4567
            digits.length == 11 && digits.startsWith("1") ->
                "(${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7)}"
            // Other formats: return as-is
            else -> phone
        }
    }
}
