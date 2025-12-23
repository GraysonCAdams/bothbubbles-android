package com.bothbubbles.ui.chatcreator.delegates

import kotlinx.coroutines.flow.first
import timber.log.Timber
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.ui.chatcreator.ConversationPreviewState
import com.bothbubbles.ui.chatcreator.SelectedRecipient
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.normalizeAddress
import com.bothbubbles.ui.components.message.toUiModel
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
 * - Loading recent messages with full details (reactions, attachments, contact info)
 * - Determining if this will be a new conversation or existing one
 *
 * Uses the same MessageUiModel transformation as the main chat for consistent rendering.
 */
class ConversationPreviewDelegate @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val attachmentRepository: AttachmentRepository,
    private val handleRepository: HandleRepository
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
                        // Get chat info to determine if it's a group
                        val chat = chatRepository.getChat(chatGuid)
                        val isGroup = chat?.isGroup ?: false

                        // Transform to full MessageUiModel using same logic as main chat
                        val uiModels = transformToUiModels(chatGuid, messages, isGroup)

                        _conversationPreview.value = ConversationPreviewState.Existing(
                            chatGuid = chatGuid,
                            messages = uiModels,
                            isGroup = isGroup
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
     * Load conversation preview for a specific chat GUID.
     * Used by ShareSheet when navigating to an existing conversation.
     *
     * @param chatGuid The chat GUID to load preview for
     * @param scope CoroutineScope to launch the loading operation
     */
    fun loadPreviewForChat(chatGuid: String, scope: CoroutineScope) {
        // Cancel any pending load
        loadJob?.cancel()

        // Set loading state
        _conversationPreview.value = ConversationPreviewState.Loading

        loadJob = scope.launch {
            try {
                // Load recent messages for the conversation
                val messages = messageRepository.getRecentMessagesForPreview(chatGuid, MAX_PREVIEW_MESSAGES)

                if (messages.isNotEmpty()) {
                    // Get chat info to determine if it's a group
                    val chat = chatRepository.getChat(chatGuid)
                    val isGroup = chat?.isGroup ?: false

                    // Transform to full MessageUiModel
                    val uiModels = transformToUiModels(chatGuid, messages, isGroup)

                    _conversationPreview.value = ConversationPreviewState.Existing(
                        chatGuid = chatGuid,
                        messages = uiModels,
                        isGroup = isGroup
                    )
                } else {
                    // Chat exists but has no messages yet
                    _conversationPreview.value = ConversationPreviewState.NewConversation
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load conversation preview for chat $chatGuid")
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
     * Transform message entities to UI models with full details.
     * Uses the same transformation logic as the main chat.
     */
    private suspend fun transformToUiModels(
        chatGuid: String,
        messages: List<com.bothbubbles.data.local.db.entity.MessageEntity>,
        isGroup: Boolean
    ): List<MessageUiModel> {
        if (messages.isEmpty()) return emptyList()

        // Load participants for contact resolution
        val participants = chatRepository.observeParticipantsForChats(listOf(chatGuid)).first()
        val handleIdToName: Map<Long, String> = participants.associate { it.id to it.displayName }
        val addressToName: Map<String, String> = participants.associate { normalizeAddress(it.address) to it.displayName }
        val addressToAvatarPath: Map<String, String?> = participants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }

        // Fetch reactions for all messages
        val messageGuids = messages.map { it.guid }
        val reactions = messageRepository.getReactionsForMessages(messageGuids)
        val reactionsByMessage = reactions.groupBy { reaction ->
            reaction.associatedMessageGuid?.let { guid ->
                if (guid.contains("/")) guid.substringAfter("/") else guid
            }
        }

        // Batch load attachments
        val allAttachments = attachmentRepository.getAttachmentsForMessages(messageGuids)
            .groupBy { it.messageGuid }

        // Transform each message
        return messages.mapNotNull { entity ->
            // Skip reaction messages
            if (entity.associatedMessageType?.contains("reaction") == true) {
                return@mapNotNull null
            }

            val entityReactions = entity.guid.let { guid ->
                reactionsByMessage[guid] ?: emptyList()
            }
            val entityAttachments = allAttachments[entity.guid] ?: emptyList()

            entity.toUiModel(
                reactions = entityReactions,
                attachments = entityAttachments,
                handleIdToName = handleIdToName,
                addressToName = addressToName,
                addressToAvatarPath = addressToAvatarPath
            )
        }
    }

    /**
     * Find the chat GUID for a single recipient.
     */
    private suspend fun findChatForSingleRecipient(recipient: SelectedRecipient): String? {
        val normalizedAddress = normalizeAddressForLookup(recipient.address)
        return chatRepository.findChatGuidByAddress(normalizedAddress)
    }

    /**
     * Normalize an address for lookup.
     */
    private fun normalizeAddressForLookup(address: String): String {
        return if (address.contains("@")) {
            // Email - just lowercase
            address.lowercase()
        } else {
            // Phone number - normalize using PhoneNumberFormatter
            PhoneNumberFormatter.normalize(address) ?: address
        }
    }
}
