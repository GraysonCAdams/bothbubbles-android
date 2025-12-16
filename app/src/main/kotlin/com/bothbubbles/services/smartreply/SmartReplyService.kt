package com.bothbubbles.services.smartreply

import timber.log.Timber
import com.bothbubbles.ui.components.message.MessageUiModel
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for generating smart reply suggestions using Google ML Kit.
 *
 * Features:
 * - On-device ML processing (no network required)
 * - Analyzes conversation context to suggest relevant replies
 * - English language only
 * - Returns up to 3 contextual suggestions
 */
@Singleton
class SmartReplyService @Inject constructor() {
    companion object {
        private const val TAG = "SmartReplyService"
        private const val MAX_CONVERSATION_HISTORY = 10
    }

    private val smartReplyGenerator by lazy { SmartReply.getClient() }

    /**
     * Generate smart reply suggestions based on conversation history.
     *
     * @param messages Recent messages in the conversation (newest last)
     * @param maxSuggestions Maximum number of suggestions to return (default 3)
     * @return List of suggested reply strings, or empty list if unavailable
     */
    suspend fun getSuggestions(
        messages: List<MessageUiModel>,
        maxSuggestions: Int = 3
    ): List<String> = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) {
            return@withContext emptyList()
        }

        // Filter to text messages only and take recent history
        val textMessages = messages
            .filter { !it.text.isNullOrBlank() && !it.isReaction }
            .takeLast(MAX_CONVERSATION_HISTORY)

        if (textMessages.isEmpty()) {
            return@withContext emptyList()
        }

        // Don't suggest replies if the last message is from the user
        val lastMessage = textMessages.lastOrNull()
        if (lastMessage?.isFromMe == true) {
            return@withContext emptyList()
        }

        // Convert to ML Kit TextMessage format
        val conversation = textMessages.mapNotNull { msg ->
            val text = msg.text ?: return@mapNotNull null
            try {
                if (msg.isFromMe) {
                    TextMessage.createForLocalUser(text, msg.dateCreated)
                } else {
                    TextMessage.createForRemoteUser(
                        text,
                        msg.dateCreated,
                        msg.senderName ?: "Unknown"
                    )
                }
            } catch (e: Exception) {
                Timber.w("Failed to create TextMessage: ${e.message}")
                null
            }
        }

        if (conversation.isEmpty()) {
            return@withContext emptyList()
        }

        try {
            val result = smartReplyGenerator.suggestReplies(conversation).await()
            when (result.status) {
                SmartReplySuggestionResult.STATUS_SUCCESS -> {
                    result.suggestions
                        .take(maxSuggestions)
                        .map { it.text }
                        .also { Timber.d("Generated ${it.size} suggestions") }
                }
                SmartReplySuggestionResult.STATUS_NOT_SUPPORTED_LANGUAGE -> {
                    Timber.d("Language not supported for smart replies")
                    emptyList()
                }
                SmartReplySuggestionResult.STATUS_NO_REPLY -> {
                    Timber.d("No smart reply suggestions available")
                    emptyList()
                }
                else -> {
                    Timber.w("Smart reply failed with status: ${result.status}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error generating smart replies")
            emptyList()
        }
    }

    /**
     * Release resources when no longer needed.
     */
    fun close() {
        smartReplyGenerator.close()
    }
}
