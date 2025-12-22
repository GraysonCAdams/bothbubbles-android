package com.bothbubbles.services.categorization

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
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
    private val unifiedChatDao: UnifiedChatDao,
    private val messageDao: MessageDao,
    private val messageCategorizer: MessageCategorizer,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "CategorizationRepository"
        private const val MESSAGES_TO_ANALYZE = 10
        /** Marker for chats that were attempted but couldn't be confidently categorized */
        const val CATEGORY_UNCATEGORIZED = "uncategorized"
    }

    /**
     * Evaluate a message and potentially update the chat's category.
     *
     * @param chatGuid The chat GUID (protocol-specific)
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

        // Update unified chat category if we have a confident result
        if (result.category != null && result.confidence >= MessageCategorizer.MEDIUM_CONFIDENCE) {
            val chat = chatDao.getChatByGuid(chatGuid)
            val unifiedChatId = chat?.unifiedChatId
            if (unifiedChatId != null) {
                unifiedChatDao.updateCategory(
                    id = unifiedChatId,
                    category = result.category.name.lowercase(),
                    confidence = result.confidence,
                    lastUpdated = System.currentTimeMillis()
                )
                Timber.d("Categorized unified chat $unifiedChatId as ${result.category} (confidence: ${result.confidence})")
            }
        }

        return result
    }

    /**
     * Clear the category for a unified chat.
     */
    suspend fun clearCategory(unifiedChatId: String) {
        unifiedChatDao.updateCategory(
            id = unifiedChatId,
            category = null,
            confidence = 0,
            lastUpdated = System.currentTimeMillis()
        )
        Timber.d("Cleared category for unified chat $unifiedChatId")
    }

    /**
     * Mark a unified chat as "uncategorized" - attempted but couldn't be confidently categorized.
     * This prevents the chat from being re-processed on every app launch.
     */
    private suspend fun markAsUncategorized(unifiedChatId: String) {
        unifiedChatDao.updateCategory(
            id = unifiedChatId,
            category = CATEGORY_UNCATEGORIZED,
            confidence = 0,
            lastUpdated = System.currentTimeMillis()
        )
        Timber.d("Marked unified chat $unifiedChatId as uncategorized (no confident match)")
    }

    /**
     * Categorize all uncategorized unified chats retroactively.
     * This is used after ML model download or when enabling categorization
     * to categorize existing conversations that were synced before.
     *
     * @param onProgress Callback with (current, total) progress
     * @return Number of chats that were categorized
     */
    suspend fun categorizeAllChats(
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Int {
        // Get unified chats with no category set
        val allChats = unifiedChatDao.getAllChats()
        val uncategorizedChats = allChats.filter { it.category == null }

        if (uncategorizedChats.isEmpty()) {
            Timber.d("No uncategorized chats found")
            return 0
        }

        Timber.d("Starting retroactive categorization of ${uncategorizedChats.size} chats")
        var categorizedCount = 0

        uncategorizedChats.forEachIndexed { index, unifiedChat ->
            onProgress?.invoke(index, uncategorizedChats.size)

            try {
                // Get recent messages for this chat using sourceId (non-sent messages for categorization)
                val messages = messageDao.getMessagesForChat(unifiedChat.sourceId, MESSAGES_TO_ANALYZE, 0)
                    .filter { !it.isFromMe && !it.text.isNullOrBlank() }

                if (messages.isEmpty()) {
                    // No messages to analyze - mark as uncategorized so we don't retry
                    markAsUncategorized(unifiedChat.id)
                    return@forEachIndexed
                }

                // Get participant info for sender address
                val participants = chatDao.getParticipantsForChat(unifiedChat.sourceId)
                val senderAddress = participants.firstOrNull()?.address ?: unifiedChat.normalizedAddress

                // Try categorizing based on the most recent message first
                val latestMessage = messages.firstOrNull()
                if (latestMessage != null) {
                    val result = messageCategorizer.categorize(
                        latestMessage.text,
                        senderAddress,
                        useMlKit = true
                    )

                    if (result.category != null && result.confidence >= MessageCategorizer.MEDIUM_CONFIDENCE) {
                        unifiedChatDao.updateCategory(
                            id = unifiedChat.id,
                            category = result.category.name.lowercase(),
                            confidence = result.confidence,
                            lastUpdated = System.currentTimeMillis()
                        )
                        categorizedCount++
                        Timber.d("Categorized unified chat ${unifiedChat.id} as ${result.category}")
                        return@forEachIndexed
                    }
                }

                // If single message didn't categorize, try multi-message analysis
                if (messages.size > 1) {
                    val messagePairs = messages.map { it.text to senderAddress }
                    val result = messageCategorizer.categorizeChatFromMessages(messagePairs)

                    if (result.category != null && result.confidence >= MessageCategorizer.MEDIUM_CONFIDENCE) {
                        unifiedChatDao.updateCategory(
                            id = unifiedChat.id,
                            category = result.category.name.lowercase(),
                            confidence = result.confidence,
                            lastUpdated = System.currentTimeMillis()
                        )
                        categorizedCount++
                        Timber.d("Categorized unified chat ${unifiedChat.id} as ${result.category} (multi-message)")
                        return@forEachIndexed
                    }
                }

                // Couldn't categorize with confidence - mark as uncategorized so we don't retry
                markAsUncategorized(unifiedChat.id)
            } catch (e: Exception) {
                Timber.e(e, "Error categorizing unified chat ${unifiedChat.id}")
                // Mark as uncategorized on error so we don't retry indefinitely
                markAsUncategorized(unifiedChat.id)
            }
        }

        onProgress?.invoke(uncategorizedChats.size, uncategorizedChats.size)
        Timber.d("Retroactive categorization complete: $categorizedCount of ${uncategorizedChats.size} chats categorized")
        return categorizedCount
    }
}
