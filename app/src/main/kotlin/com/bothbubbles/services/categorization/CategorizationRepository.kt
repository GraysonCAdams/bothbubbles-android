package com.bothbubbles.services.categorization

import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for message categorization.
 * Handles evaluating messages and updating chat categories in the database.
 */
@Singleton
class CategorizationRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val messageCategorizer: MessageCategorizer,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "CategorizationRepository"
        private const val MESSAGES_TO_ANALYZE = 10
    }

    /**
     * Evaluate a message and potentially update the chat's category.
     *
     * @param chatGuid The chat GUID
     * @param senderAddress The sender's address (phone number or email)
     * @param messageText The message text
     * @return The category result
     */
    suspend fun evaluateAndCategorize(
        chatGuid: String,
        senderAddress: String,
        messageText: String?
    ): MessageCategorizer.CategoryResult {
        // Check if categorization is enabled
        val enabled = settingsDataStore.categorizationEnabled.first()
        if (!enabled) {
            return MessageCategorizer.CategoryResult(null, 0, listOf("Categorization disabled"))
        }

        // Categorize the message
        val result = messageCategorizer.categorize(messageText, senderAddress, useMlKit = true)

        // Update chat category if we have a confident result
        if (result.category != null && result.confidence >= MessageCategorizer.MEDIUM_CONFIDENCE) {
            chatDao.updateCategory(
                guid = chatGuid,
                category = result.category.name.lowercase(),
                confidence = result.confidence,
                timestamp = System.currentTimeMillis()
            )
            Log.d(TAG, "Categorized chat $chatGuid as ${result.category} (confidence: ${result.confidence})")
        }

        return result
    }

    /**
     * Clear the category for a chat.
     */
    suspend fun clearCategory(chatGuid: String) {
        chatDao.clearCategory(chatGuid)
        Log.d(TAG, "Cleared category for chat $chatGuid")
    }

    /**
     * Categorize all uncategorized chats retroactively.
     * This is used after ML model download or when enabling categorization
     * to categorize existing conversations that were synced before.
     *
     * @param onProgress Callback with (current, total) progress
     * @return Number of chats that were categorized
     */
    suspend fun categorizeAllChats(
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Int {
        val uncategorizedChats = chatDao.getUncategorizedChats()
        if (uncategorizedChats.isEmpty()) {
            Log.d(TAG, "No uncategorized chats found")
            return 0
        }

        Log.d(TAG, "Starting retroactive categorization of ${uncategorizedChats.size} chats")
        var categorizedCount = 0

        uncategorizedChats.forEachIndexed { index, chat ->
            onProgress?.invoke(index, uncategorizedChats.size)

            try {
                // Get recent messages for this chat (non-sent messages for categorization)
                val messages = messageDao.getMessagesForChat(chat.guid, MESSAGES_TO_ANALYZE, 0)
                    .filter { !it.isFromMe && !it.text.isNullOrBlank() }

                if (messages.isEmpty()) {
                    return@forEachIndexed
                }

                // Get participant info for sender address
                val participants = chatDao.getParticipantsForChat(chat.guid)
                val senderAddress = participants.firstOrNull()?.address ?: chat.chatIdentifier ?: ""

                // Try categorizing based on the most recent message first
                val latestMessage = messages.firstOrNull()
                if (latestMessage != null) {
                    val result = messageCategorizer.categorize(
                        latestMessage.text,
                        senderAddress,
                        useMlKit = true
                    )

                    if (result.category != null && result.confidence >= MessageCategorizer.MEDIUM_CONFIDENCE) {
                        chatDao.updateCategory(
                            guid = chat.guid,
                            category = result.category.name.lowercase(),
                            confidence = result.confidence,
                            timestamp = System.currentTimeMillis()
                        )
                        categorizedCount++
                        Log.d(TAG, "Categorized chat ${chat.guid} as ${result.category}")
                        return@forEachIndexed
                    }
                }

                // If single message didn't categorize, try multi-message analysis
                if (messages.size > 1) {
                    val messagePairs = messages.map { it.text to senderAddress }
                    val result = messageCategorizer.categorizeChatFromMessages(messagePairs)

                    if (result.category != null && result.confidence >= MessageCategorizer.MEDIUM_CONFIDENCE) {
                        chatDao.updateCategory(
                            guid = chat.guid,
                            category = result.category.name.lowercase(),
                            confidence = result.confidence,
                            timestamp = System.currentTimeMillis()
                        )
                        categorizedCount++
                        Log.d(TAG, "Categorized chat ${chat.guid} as ${result.category} (multi-message)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error categorizing chat ${chat.guid}", e)
            }
        }

        onProgress?.invoke(uncategorizedChats.size, uncategorizedChats.size)
        Log.d(TAG, "Retroactive categorization complete: $categorizedCount of ${uncategorizedChats.size} chats categorized")
        return categorizedCount
    }
}
