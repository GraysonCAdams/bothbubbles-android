package com.bothbubbles.services.spam

import timber.log.Timber
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for spam-related data operations.
 *
 * Handles:
 * - Marking conversations as spam/not spam
 * - Whitelisting senders
 * - Tracking spam reports
 * - Carrier reporting status
 */
@Singleton
class SpamRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val spamDetector: SpamDetector
) {
    /**
     * Get all spam conversations as a Flow.
     * Returns unified chats marked as spam.
     */
    fun getSpamChats(): Flow<List<UnifiedChatEntity>> =
        unifiedChatDao.observeAllChats().map { chats ->
            chats.filter { it.isSpam }
        }

    /**
     * Get count of spam conversations as a Flow.
     */
    fun getSpamChatCount(): Flow<Int> =
        unifiedChatDao.observeAllChats().map { chats ->
            chats.count { it.isSpam }
        }

    /**
     * Evaluate a new message for spam and update the chat status if needed.
     * Returns the spam result.
     */
    suspend fun evaluateAndMarkSpam(
        chatGuid: String,
        senderAddress: String,
        messageText: String?
    ): SpamDetector.SpamResult {
        val result = spamDetector.evaluate(senderAddress, messageText, chatGuid)

        if (result.isSpam) {
            // Update unified chat spam status
            chatDao.getChatByGuid(chatGuid)?.unifiedChatId?.let { unifiedId ->
                unifiedChatDao.updateSpamStatus(unifiedId, true, result.score)
            }
            Timber.i("Marked chat $chatGuid as spam (score: ${result.score})")
        }

        return result
    }

    /**
     * Report a conversation as spam.
     * - Marks the chat as spam
     * - Increments the sender's spam report count
     */
    suspend fun reportAsSpam(chatGuid: String) {
        val chat = chatDao.getChatByGuid(chatGuid) ?: return

        // Mark unified chat as spam with max score
        chat.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateSpamStatus(unifiedId, true, 100)
        }

        // Increment spam report count for all participants
        val participants = chatDao.getParticipantsForChat(chatGuid)
        participants.forEach { handle ->
            handleDao.incrementSpamReportCount(handle.id)
            // Remove whitelist status if they were whitelisted
            if (handle.isWhitelisted) {
                handleDao.updateWhitelisted(handle.id, false)
            }
        }

        Timber.i("Reported chat $chatGuid as spam, updated ${participants.size} participants")
    }

    /**
     * Mark a conversation as safe (not spam).
     * - Clears spam status from the chat
     * - Whitelists the sender so future messages aren't auto-flagged
     */
    suspend fun markAsSafe(chatGuid: String) {
        // Clear spam status on unified chat
        chatDao.getChatByGuid(chatGuid)?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateSpamStatus(unifiedId, false, 0)
        }

        // Whitelist all participants
        val participants = chatDao.getParticipantsForChat(chatGuid)
        participants.forEach { handle ->
            handleDao.updateWhitelisted(handle.id, true)
        }

        Timber.i("Marked chat $chatGuid as safe, whitelisted ${participants.size} participants")
    }

    /**
     * Mark that spam was reported to carrier (7726).
     */
    suspend fun markReportedToCarrier(chatGuid: String) {
        chatDao.getChatByGuid(chatGuid)?.unifiedChatId?.let { unifiedId ->
            unifiedChatDao.updateSpamReportedToCarrier(unifiedId, true)
            Timber.i("Marked chat $chatGuid (unified: $unifiedId) as reported to carrier")
        }
    }

    /**
     * Check if a chat is marked as spam.
     */
    suspend fun isSpam(chatGuid: String): Boolean {
        val chat = chatDao.getChatByGuid(chatGuid) ?: return false
        val unifiedChat = chat.unifiedChatId?.let { unifiedChatDao.getById(it) }
        return unifiedChat?.isSpam == true
    }

    /**
     * Check if a sender is whitelisted.
     */
    suspend fun isSenderWhitelisted(address: String): Boolean {
        return handleDao.getHandleByAddressAny(address)?.isWhitelisted == true
    }

    /**
     * Check if a sender is blocked (has been reported as spam and isn't whitelisted).
     * A sender is considered blocked if they have spam reports and aren't whitelisted.
     */
    suspend fun isBlocked(address: String): Boolean {
        val handle = handleDao.getHandleByAddressAny(address) ?: return false

        // If whitelisted, not blocked
        if (handle.isWhitelisted) return false

        // If they have spam reports, they're blocked
        return handle.spamReportCount > 0
    }
}
