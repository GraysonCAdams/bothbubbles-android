package com.bothbubbles.services.categorization

import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
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
    private val messageCategorizer: MessageCategorizer,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "CategorizationRepository"
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
}
