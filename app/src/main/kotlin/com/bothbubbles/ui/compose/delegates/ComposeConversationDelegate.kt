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

    // Merged GUIDs for unified conversations (iMessage + SMS combined)
    private val _foundMergedGuids = MutableStateFlow<List<String>?>(null)
    val foundMergedGuids: StateFlow<List<String>?> = _foundMergedGuids.asStateFlow()

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
                _foundMergedGuids.value = null
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
                loadMultiRecipientConversation(chips)
            }
        }
    }

    /**
     * Load conversation for multiple recipients by finding existing group with exact participants.
     * Prefers iMessage groups over SMS groups.
     */
    private suspend fun loadMultiRecipientConversation(chips: ImmutableList<RecipientChip>) {
        _conversationState.value = ComposeConversationState.Loading

        try {
            // Get normalized addresses from chips for comparison
            val chipAddresses = chips.map { chip ->
                normalizeAddress(chip.address)
            }.toSet()

            Timber.d("Looking for group with ${chipAddresses.size} participants: $chipAddresses")

            // Get recent group chats
            val groupChats = chatRepository.getRecentGroupChats().first()
            Timber.d("Found ${groupChats.size} group chats to check")

            if (groupChats.isEmpty()) {
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = null
                _foundMergedGuids.value = null
                return
            }

            // Batch fetch participants for all group chats
            val participantsByChat = chatRepository.getParticipantsGroupedByChat(
                groupChats.map { it.guid }
            )

            // Find groups with exact participant match
            val matchingGroups = groupChats.filter { chat ->
                val participants = participantsByChat[chat.guid] ?: emptyList()
                val participantAddresses = participants.map { normalizeAddress(it.address) }.toSet()

                val matches = participantAddresses == chipAddresses
                if (matches) {
                    Timber.d("Found matching group: ${chat.displayName ?: chat.guid} (${chat.guid})")
                }
                matches
            }

            if (matchingGroups.isEmpty()) {
                Timber.d("No matching group found for participants")
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = null
                _foundMergedGuids.value = null
                return
            }

            // Prefer iMessage groups over SMS groups, then most recent
            val bestGroup = matchingGroups
                .sortedWith(compareByDescending<ChatEntity> { it.guid.startsWith("iMessage", ignoreCase = true) }
                    .thenByDescending { it.latestMessageDate ?: 0L })
                .first()

            Timber.d("Selected group: ${bestGroup.displayName ?: bestGroup.guid}")
            loadMessagesForChat(bestGroup.guid)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load multi-recipient conversation")
            _conversationState.value = ComposeConversationState.NewConversation
            _foundChatGuid.value = null
            _foundMergedGuids.value = null
        }
    }

    /**
     * Normalize an address for comparison (phone numbers and emails).
     */
    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            address.lowercase()
        } else {
            PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
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

            val normalizedAddress = normalizeAddress(chip.address)

            // Check all possible GUID variants
            val possibleGuids = listOf(
                "iMessage;-;$normalizedAddress",
                "SMS;-;$normalizedAddress",
                "RCS;-;$normalizedAddress",
                "MMS;-;$normalizedAddress",
                "sms;-;$normalizedAddress"
            )

            // Find existing chat with messages (check latestMessageDate as proxy for having messages)
            var bestChat: ChatEntity? = null
            for (guid in possibleGuids) {
                val chat = chatRepository.getChat(guid)
                if (chat != null) {
                    if (chat.latestMessageDate != null) {
                        bestChat = chat
                        break
                    }
                    if (bestChat == null) {
                        bestChat = chat
                    }
                }
            }

            if (bestChat != null && bestChat.latestMessageDate != null) {
                // Found existing conversation with messages
                loadMessagesForChat(bestChat.guid)
            } else if (bestChat != null) {
                // Found empty chat entry - still resolve merged GUIDs for consistent navigation
                val mergedGuids = messageRepository.resolveUnifiedChatGuids(bestChat.guid)
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = bestChat.guid
                _foundMergedGuids.value = if (mergedGuids.size > 1) mergedGuids else null
            } else {
                // No existing conversation
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = null
                _foundMergedGuids.value = null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load single recipient conversation")
            _conversationState.value = ComposeConversationState.NewConversation
            _foundChatGuid.value = null
            _foundMergedGuids.value = null
        }
    }

    private suspend fun loadGroupConversation(chatGuid: String) {
        _conversationState.value = ComposeConversationState.Loading

        try {
            val chat = chatRepository.getChat(chatGuid)
            if (chat != null && chat.latestMessageDate != null) {
                loadMessagesForChat(chatGuid)
            } else {
                // Group chat with no messages - resolve merged GUIDs for consistent navigation
                val mergedGuids = messageRepository.resolveUnifiedChatGuids(chatGuid)
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = chatGuid
                _foundMergedGuids.value = if (mergedGuids.size > 1) mergedGuids else null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load group conversation")
            _conversationState.value = ComposeConversationState.NewConversation
            _foundChatGuid.value = chatGuid
            _foundMergedGuids.value = null
        }
    }

    private suspend fun loadMessagesForChat(chatGuid: String) {
        try {
            // Resolve merged GUIDs for unified conversation navigation
            val mergedGuids = messageRepository.resolveUnifiedChatGuids(chatGuid)

            val messages = messageRepository.getRecentMessagesForPreview(chatGuid, limit = 20)

            if (messages.isEmpty()) {
                _conversationState.value = ComposeConversationState.NewConversation
                _foundChatGuid.value = chatGuid
                _foundMergedGuids.value = if (mergedGuids.size > 1) mergedGuids else null
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
            _foundMergedGuids.value = if (mergedGuids.size > 1) mergedGuids else null
        } catch (e: Exception) {
            Timber.e(e, "Failed to load messages for chat")
            _conversationState.value = ComposeConversationState.NewConversation
            _foundChatGuid.value = chatGuid
            _foundMergedGuids.value = null
        }
    }

    /**
     * Reset to empty state.
     */
    fun reset() {
        _conversationState.value = ComposeConversationState.Empty
        _foundChatGuid.value = null
        _foundMergedGuids.value = null
    }
}
