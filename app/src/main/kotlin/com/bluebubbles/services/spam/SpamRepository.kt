package com.bluebubbles.services.spam

import android.util.Log
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow
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
    private val spamDetector: SpamDetector
) {
    companion object {
        private const val TAG = "SpamRepository"
    }

    /**
     * Get all spam conversations as a Flow.
     */
    fun getSpamChats(): Flow<List<ChatEntity>> = chatDao.getSpamChats()

    /**
     * Get count of spam conversations as a Flow.
     */
    fun getSpamChatCount(): Flow<Int> = chatDao.getSpamChatCount()

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
            chatDao.updateSpamStatus(chatGuid, true, result.score)
            Log.i(TAG, "Marked chat $chatGuid as spam (score: ${result.score})")
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

        // Mark chat as spam with max score
        chatDao.updateSpamStatus(chatGuid, true, 100)

        // Increment spam report count for all participants
        val participants = chatDao.getParticipantsForChat(chatGuid)
        participants.forEach { handle ->
            handleDao.incrementSpamReportCount(handle.id)
            // Remove whitelist status if they were whitelisted
            if (handle.isWhitelisted) {
                handleDao.updateWhitelisted(handle.id, false)
            }
        }

        Log.i(TAG, "Reported chat $chatGuid as spam, updated ${participants.size} participants")
    }

    /**
     * Mark a conversation as safe (not spam).
     * - Clears spam status from the chat
     * - Whitelists the sender so future messages aren't auto-flagged
     */
    suspend fun markAsSafe(chatGuid: String) {
        // Clear spam status
        chatDao.clearSpamStatus(chatGuid)

        // Whitelist all participants
        val participants = chatDao.getParticipantsForChat(chatGuid)
        participants.forEach { handle ->
            handleDao.updateWhitelisted(handle.id, true)
        }

        Log.i(TAG, "Marked chat $chatGuid as safe, whitelisted ${participants.size} participants")
    }

    /**
     * Mark that spam was reported to carrier (7726).
     */
    suspend fun markReportedToCarrier(chatGuid: String) {
        chatDao.markAsReportedToCarrier(chatGuid)
        Log.i(TAG, "Marked chat $chatGuid as reported to carrier")
    }

    /**
     * Check if a chat is marked as spam.
     */
    suspend fun isSpam(chatGuid: String): Boolean {
        return chatDao.getChatByGuid(chatGuid)?.isSpam == true
    }

    /**
     * Check if a sender is whitelisted.
     */
    suspend fun isSenderWhitelisted(address: String): Boolean {
        return handleDao.getHandleByAddressAny(address)?.isWhitelisted == true
    }
}
