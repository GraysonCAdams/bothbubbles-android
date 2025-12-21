package com.bothbubbles.ui.chatcreator.delegates

import timber.log.Timber
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.ui.chatcreator.ConversationPreviewState
import com.bothbubbles.ui.chatcreator.MessagePreview
import com.bothbubbles.ui.chatcreator.SelectedRecipient
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for loading conversation previews based on selected recipients.
 *
 * This delegate handles:
 * - Finding existing conversations for selected recipients
 * - Loading recent messages for preview display
 * - Determining if this will be a new conversation or existing one
 */
class ConversationPreviewDelegate @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository
) {
    companion object {
        /** Maximum number of messages to show in preview */
        private const val MAX_PREVIEW_MESSAGES = 10
    }

    private val _conversationPreview = MutableStateFlow<ConversationPreviewState?>(null)
    val conversationPreview: StateFlow<ConversationPreviewState?> = _conversationPreview.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Load conversation preview for the given recipients.
     * Call this whenever the selected recipients change.
     *
     * @param recipients Currently selected recipients
     * @param scope CoroutineScope to launch the loading operation
     */
    fun loadPreviewForRecipients(recipients: List<SelectedRecipient>, scope: CoroutineScope) {
        // Cancel any pending load
        loadJob?.cancel()

        // No recipients = no preview
        if (recipients.isEmpty()) {
            _conversationPreview.value = null
            return
        }

        // Set loading state
        _conversationPreview.value = ConversationPreviewState.Loading

        loadJob = scope.launch {
            try {
                val chatGuid = when (recipients.size) {
                    1 -> findChatForSingleRecipient(recipients.first())
                    else -> null  // For 2+ recipients, show new conversation (finding exact group match is complex)
                }

                if (chatGuid != null) {
                    // Load recent messages for existing conversation
                    val messages = messageRepository.getRecentMessagesForPreview(chatGuid, MAX_PREVIEW_MESSAGES)

                    if (messages.isNotEmpty()) {
                        val previews = messages.map { message ->
                            MessagePreview(
                                guid = message.guid,
                                text = message.text,
                                isFromMe = message.isFromMe,
                                timestamp = message.dateCreated ?: System.currentTimeMillis(),
                                hasAttachments = message.hasAttachments,
                                attachmentPreviewText = if (message.hasAttachments && message.text.isNullOrBlank()) {
                                    getAttachmentPreviewText(message.associatedMessageType)
                                } else null
                            )
                        }
                        _conversationPreview.value = ConversationPreviewState.Existing(
                            chatGuid = chatGuid,
                            messages = previews
                        )
                    } else {
                        // Chat exists but has no messages yet
                        _conversationPreview.value = ConversationPreviewState.NewConversation
                    }
                } else {
                    // No existing conversation found
                    _conversationPreview.value = ConversationPreviewState.NewConversation
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load conversation preview")
                _conversationPreview.value = ConversationPreviewState.NewConversation
            }
        }
    }

    /**
     * Clear the conversation preview.
     */
    fun clearPreview() {
        loadJob?.cancel()
        _conversationPreview.value = null
    }

    /**
     * Find the chat GUID for a single recipient.
     */
    private suspend fun findChatForSingleRecipient(recipient: SelectedRecipient): String? {
        val normalizedAddress = normalizeAddress(recipient.address)
        return chatRepository.findChatGuidByAddress(normalizedAddress)
    }

    /**
     * Normalize an address for lookup.
     */
    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            // Email - just lowercase
            address.lowercase()
        } else {
            // Phone number - normalize using PhoneNumberFormatter
            PhoneNumberFormatter.normalize(address) ?: address
        }
    }

    /**
     * Get a user-friendly description for attachment-only messages.
     */
    private fun getAttachmentPreviewText(associatedMessageType: String?): String {
        return when {
            associatedMessageType?.contains("image", ignoreCase = true) == true -> "Photo"
            associatedMessageType?.contains("video", ignoreCase = true) == true -> "Video"
            associatedMessageType?.contains("audio", ignoreCase = true) == true -> "Audio"
            else -> "Attachment"
        }
    }
}
