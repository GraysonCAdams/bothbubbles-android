package com.bothbubbles.services.autoresponder

import timber.log.Timber
import com.bothbubbles.core.model.entity.AutoRespondedSenderEntity
import com.bothbubbles.data.local.db.dao.AutoRespondedSenderDao
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.seam.hems.autoresponder.AutoResponderRuleEngine
import com.bothbubbles.seam.hems.autoresponder.MessageContext
import com.bothbubbles.seam.stitches.StitchRegistry
import com.bothbubbles.services.messaging.MessageSendingService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service that handles automatic responses based on user-configured rules.
 *
 * Auto-responses are triggered based on configurable rules with various conditions:
 * - Source Stitch (SMS, iMessage)
 * - First-time sender detection
 * - Time and day conditions
 * - System state (DND, driving, on call)
 * - Location (geofencing)
 *
 * The first matching rule's message is sent as the auto-response.
 *
 * Core safety checks are always applied:
 * - Feature must be enabled globally
 * - Initial sync must be complete (avoid historical messages)
 * - Message must not be from self
 * - Must not be a group chat
 * - Must not have already responded to this sender
 * - Rate limit must not be exceeded
 */
@Singleton
class AutoResponderService @Inject constructor(
    private val autoRespondedSenderDao: AutoRespondedSenderDao,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val messageSendingService: MessageSendingService,
    private val ruleEngine: AutoResponderRuleEngine,
    private val stitchRegistry: StitchRegistry,
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
            Timber.tag(TAG).d("Auto-responder disabled")
            return false
        }

        // 2. Skip if initial sync not complete (avoid responding to historical messages)
        if (!settingsDataStore.initialSyncComplete.first()) {
            Timber.tag(TAG).d("Initial sync not complete, skipping auto-response")
            return false
        }

        // 3. Skip messages from self
        if (isFromMe) {
            Timber.tag(TAG).d("Message is from self, skipping")
            return false
        }

        // 4. Skip group chats
        val chat = chatDao.getChatByGuid(chatGuid)
        if (chat?.isGroup == true) {
            Timber.tag(TAG).d("Chat is group chat, skipping")
            return false
        }

        // 5. Already responded to this SENDER? (persists even if chat deleted)
        if (autoRespondedSenderDao.get(senderAddress) != null) {
            Timber.tag(TAG).d("Already auto-responded to sender: $senderAddress")
            return false
        }

        // 6. Check rate limit (default 10/hour)
        val limit = settingsDataStore.autoResponderRateLimit.first()
        val oneHourAgo = System.currentTimeMillis() - 3600_000
        val recentCount = autoRespondedSenderDao.countSince(oneHourAgo)
        if (recentCount >= limit) {
            Timber.tag(TAG).w("Rate limit exceeded ($recentCount/$limit per hour)")
            return false
        }

        // 7. Determine the Stitch ID for this chat
        val stitchId = stitchRegistry.getStitchForChat(chatGuid)?.id ?: "unknown"

        // 8. Check if this is the first message from this sender
        val isFirstFromSender = autoRespondedSenderDao.get(senderAddress) == null

        // 9. Build message context and find matching rule
        val context = MessageContext(
            senderAddress = senderAddress,
            chatGuid = chatGuid,
            stitchId = stitchId,
            isFirstFromSender = isFirstFromSender
        )

        val matchingRule = ruleEngine.findMatchingRule(context)
        if (matchingRule == null) {
            Timber.tag(TAG).d("No matching rule found for sender: $senderAddress")
            return false
        }

        // 10. Send the auto-response using the rule's message
        Timber.tag(TAG).d("Sending auto-response to $senderAddress using rule '${matchingRule.name}'")

        return try {
            val sendResult = messageSendingService.sendMessage(chatGuid, matchingRule.message)
            if (sendResult.isSuccess) {
                // Track by SENDER ADDRESS so it persists even if chat is deleted
                autoRespondedSenderDao.insert(AutoRespondedSenderEntity(senderAddress = senderAddress))
                Timber.tag(TAG).i("Auto-response sent successfully to $senderAddress via rule '${matchingRule.name}'")
                true
            } else {
                Timber.tag(TAG).e("Failed to send auto-response: ${sendResult.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception sending auto-response")
            false
        }
    }
}
