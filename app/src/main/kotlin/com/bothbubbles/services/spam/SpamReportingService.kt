package com.bothbubbles.services.spam

import android.content.Context
import android.telephony.SmsManager
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for reporting spam to carriers via the 7726 (SPAM) short code.
 *
 * How 7726 reporting works:
 * 1. User forwards spam message to 7726
 * 2. Carrier responds asking for the sender's phone number
 * 3. User replies with the spammer's number
 * 4. Carrier adds the report to their spam database
 *
 * This service automates this flow by:
 * 1. Sending the spam message content to 7726
 * 2. After a short delay, sending the sender's phone number
 *
 * Note: This only works for SMS. iMessage spam cannot be reported this way.
 * Also, not all carriers support 7726. Most major US carriers do (AT&T, Verizon, T-Mobile, etc.)
 */
@Singleton
class SpamReportingService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val unifiedChatDao: UnifiedChatDao
) {
    companion object {
        /** The universal carrier spam reporting short code */
        const val SPAM_SHORT_CODE = "7726"

        /** Delay between sending message content and sender number (in ms) */
        private const val REPLY_DELAY_MS = 3000L
    }

    /**
     * Result of a spam report attempt.
     */
    sealed class ReportResult {
        data object Success : ReportResult()
        data class Error(val message: String) : ReportResult()
        data object NotSmsChat : ReportResult()
        data object NoMessagesFound : ReportResult()
    }

    /**
     * Report a chat as spam to the carrier via 7726.
     *
     * This sends the most recent spam message to 7726, followed by the sender's number.
     * Only works for SMS chats - iMessage chats cannot be reported via 7726.
     *
     * @param chatGuid The GUID of the chat to report
     * @return Result indicating success or failure
     */
    suspend fun reportToCarrier(chatGuid: String): ReportResult {
        // Check if this is an SMS chat
        if (!chatGuid.startsWith("SMS;")) {
            Timber.w("Cannot report non-SMS chat to carrier: $chatGuid")
            return ReportResult.NotSmsChat
        }

        // Get the chat to find the sender's phone number
        val chat = chatDao.getChatByGuid(chatGuid)
        if (chat == null) {
            Timber.w("Chat not found: $chatGuid")
            return ReportResult.Error("Chat not found")
        }

        val senderNumber = chat.chatIdentifier
        if (senderNumber.isNullOrBlank()) {
            Timber.w("No sender number found for chat: $chatGuid")
            return ReportResult.Error("Sender number not found")
        }

        // Get the most recent message from the sender (not from us)
        val messages = messageDao.getMessagesForChat(chatGuid, limit = 10, offset = 0)
        val spamMessage = messages.firstOrNull { msg -> !msg.isFromMe }

        if (spamMessage == null) {
            Timber.w("No messages found from sender in chat: $chatGuid")
            return ReportResult.NoMessagesFound
        }

        val messageContent = spamMessage.text ?: "[No text content]"

        return try {
            // Send the spam message content to 7726
            Timber.i("Reporting spam to carrier: sending message content to 7726")
            sendSms(SPAM_SHORT_CODE, messageContent)

            // Mark the chat as reported to carrier via unified chat
            chat.unifiedChatId?.let { unifiedId ->
                unifiedChatDao.updateSpamReportedToCarrier(unifiedId, true)
            }

            // After a delay, send the sender's phone number
            // This is done asynchronously - the carrier will request the number
            kotlinx.coroutines.delay(REPLY_DELAY_MS)

            Timber.i("Sending sender number to 7726: $senderNumber")
            sendSms(SPAM_SHORT_CODE, senderNumber)

            Timber.i("Successfully reported spam to carrier for chat: $chatGuid")
            ReportResult.Success
        } catch (e: Exception) {
            Timber.e(e, "Failed to report spam to carrier")
            ReportResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Send an SMS message.
     */
    @Suppress("DEPRECATION")
    private fun sendSms(destination: String, message: String) {
        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

        // Split long messages if needed
        val parts = smsManager.divideMessage(message)
        if (parts.size == 1) {
            smsManager.sendTextMessage(destination, null, message, null, null)
        } else {
            smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
        }
    }

    /**
     * Check if a chat has already been reported to the carrier.
     */
    suspend fun isReportedToCarrier(chatGuid: String): Boolean {
        val chat = chatDao.getChatByGuid(chatGuid) ?: return false
        val unifiedId = chat.unifiedChatId ?: return false
        val unifiedChat = unifiedChatDao.getById(unifiedId) ?: return false
        return unifiedChat.spamReportedToCarrier
    }
}
