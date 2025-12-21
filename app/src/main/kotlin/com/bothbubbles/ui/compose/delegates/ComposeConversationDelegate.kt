package com.bothbubbles.ui.compose.delegates

import timber.log.Timber
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.ui.chat.MessageTransformationUtils.toUiModel
import com.bothbubbles.ui.compose.ComposeConversationState
import com.bothbubbles.ui.compose.RecipientChip
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate managing conversation loading based on selected recipients.
 *
 * Handles:
 * - Looking up existing 1:1 conversations by address
 * - Looking up existing group conversations by GUID
 * - Loading message previews for existing conversations
 * - Detecting new conversation scenarios
 */
class ComposeConversationDelegate @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val attachmentRepository: AttachmentRepository
) {
    private val _conversationState = MutableStateFlow<ComposeConversationState>(ComposeConversationState.Empty)
    val conversationState: StateFlow<ComposeConversationState> = _conversationState.asStateFlow()

    private val _foundChatGuid = MutableStateFlow<String?>(null)
    val foundChatGuid: StateFlow<String?> = _foundChatGuid.asStateFlow()

    private var scope: CoroutineScope? = null

    /**
     * Initialize the delegate with a coroutine scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Load conversation based on current chips.
     * Call this whenever chips change.
     */
    fun loadConversation(chips: ImmutableList<RecipientChip>) {
        scope?.launch {
            loadConversationInternal(chips)
        }
    }

    private suspend fun loadConversationInternal(chips: ImmutableList<RecipientChip>) {
        when {
            chips.isEmpty() -> {
                _conversationState.value = ComposeConversationState.Empty
                _foundChatGuid.value = null
            }
            chips.size == 1 && chips[0].isGroup -> {
                // Single group chip - load that group's conversation
                loadGroupConversation(chips[0].chatGuid!!)
            }
            chips.size == 1 -> {
                // Single contact - look up 1:1 conversation
                loadSingleRecipientConversation(chips[0])
            }
            else -> {
                // Multiple recipients - look up existing group with exact participants
                // For now, show as new conversation (group lookup by participants is complex)
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = null
            }
        }
    }

    private suspend fun loadSingleRecipientConversation(chip: RecipientChip) {
        _conversationState.value = ComposeConversationState.Loading

        try {
            // If chip has a chatGuid from unified group selection, use it directly
            if (chip.chatGuid != null) {
                loadMessagesForChat(chip.chatGuid)
                return
            }

            val address = chip.address
            val normalizedAddress = if (address.contains("@")) {
                address.lowercase()
            } else {
                PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
            }

            // Check all possible GUID variants
            val possibleGuids = listOf(
                "iMessage;-;$normalizedAddress",
                "SMS;-;$normalizedAddress",
                "RCS;-;$normalizedAddress",
                "MMS;-;$normalizedAddress",
                "sms;-;$normalizedAddress"
            )

            // Find existing chat with messages
            var bestChat: ChatEntity? = null
            for (guid in possibleGuids) {
                val chat = chatRepository.getChat(guid)
                if (chat != null) {
                    if (chat.lastMessageText != null) {
                        bestChat = chat
                        break
                    }
                    if (bestChat == null) {
                        bestChat = chat
                    }
                }
            }

            if (bestChat != null && bestChat.lastMessageText != null) {
                // Found existing conversation with messages
                loadMessagesForChat(bestChat.guid)
            } else if (bestChat != null) {
                // Found empty chat entry
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = bestChat.guid
            } else {
                // No existing conversation
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load single recipient conversation")
            _conversationState.value = ComposeConversationState.NewConversation
            _foundChatGuid.value = null
        }
    }

    private suspend fun loadGroupConversation(chatGuid: String) {
        _conversationState.value = ComposeConversationState.Loading

        try {
            val chat = chatRepository.getChat(chatGuid)
            if (chat != null && chat.lastMessageText != null) {
                loadMessagesForChat(chatGuid)
            } else {
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = chatGuid
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load group conversation")
            _conversationState.value = ComposeConversationState.NewConversation
            _foundChatGuid.value = chatGuid
        }
    }

    private suspend fun loadMessagesForChat(chatGuid: String) {
        try {
            val messages = messageRepository.getRecentMessagesForPreview(chatGuid, limit = 20)

            if (messages.isEmpty()) {
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = chatGuid
                return
            }

            // Load attachments for all messages in batch
            val messageGuids = messages.map { it.guid }
            val allAttachments = attachmentRepository.getAttachmentsForMessages(messageGuids)
            val attachmentsByMessage = allAttachments.groupBy { it.messageGuid }

            // Convert to MessageUiModel for proper styling
            val uiModels = messages.map { message ->
                val attachments = attachmentsByMessage[message.guid] ?: emptyList()
                message.toUiModel(
                    reactions = emptyList(), // Don't need reactions for preview
                    attachments = attachments,
                    handleIdToName = emptyMap(),
                    addressToName = emptyMap(),
                    addressToAvatarPath = emptyMap(),
                    replyPreview = null
                )
            }.sortedBy { it.dateCreated } // Sort oldest first for display

            _conversationState.value = ComposeConversationState.Existing(
                chatGuid = chatGuid,
                messages = uiModels.toImmutableList()
            )
            _foundChatGuid.value = chatGuid
        } catch (e: Exception) {
            Timber.e(e, "Failed to load messages for chat")
            _conversationState.value = ComposeConversationState.NewConversation
            _foundChatGuid.value = chatGuid
        }
    }

    /**
     * Reset to empty state.
     */
    fun reset() {
        _conversationState.value = ComposeConversationState.Empty
        _foundChatGuid.value = null
    }
}
