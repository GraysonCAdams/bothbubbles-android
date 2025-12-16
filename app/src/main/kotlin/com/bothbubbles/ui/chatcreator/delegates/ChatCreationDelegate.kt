package com.bothbubbles.ui.chatcreator.delegates

import timber.log.Timber
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.dto.CreateChatRequest
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.ui.chatcreator.ContactUiModel
import com.bothbubbles.ui.chatcreator.GroupChatUiModel
import com.bothbubbles.ui.chatcreator.GroupSetupNavigation
import com.bothbubbles.ui.chatcreator.SelectedRecipient
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Delegate responsible for creating chats.
 *
 * This delegate handles:
 * - Creating direct chats (single recipient)
 * - Creating SMS chats locally
 * - Creating iMessage chats via BlueBubbles API
 * - Preparing group chat setup navigation
 * - Selecting existing group chats
 */
class ChatCreationDelegate @Inject constructor(
    private val api: BothBubblesApi,
    private val chatRepository: ChatRepository
) {
    /**
     * Result of a chat creation operation
     */
    sealed class ChatCreationResult {
        data class Success(val chatGuid: String) : ChatCreationResult()
        data class Error(val message: String) : ChatCreationResult()
        data class NavigateToGroupSetup(val navigation: GroupSetupNavigation) : ChatCreationResult()
    }

    private val _createdChatGuid = MutableStateFlow<String?>(null)
    val createdChatGuid: StateFlow<String?> = _createdChatGuid.asStateFlow()

    private val _navigateToGroupSetup = MutableStateFlow<GroupSetupNavigation?>(null)
    val navigateToGroupSetup: StateFlow<GroupSetupNavigation?> = _navigateToGroupSetup.asStateFlow()

    /**
     * Select a contact and create a direct chat
     */
    suspend fun selectContact(contact: ContactUiModel): ChatCreationResult {
        return try {
            // Create a new chat with the selected contact
            val response = api.createChat(
                CreateChatRequest(
                    addresses = listOf(contact.address),
                    service = contact.service
                )
            )

            val body = response.body()
            val chatData = body?.data
            if (response.isSuccessful && chatData != null) {
                val chatGuid = chatData.guid
                _createdChatGuid.value = chatGuid
                ChatCreationResult.Success(chatGuid)
            } else {
                ChatCreationResult.Error(body?.message ?: "Failed to create chat")
            }
        } catch (e: Exception) {
            ChatCreationResult.Error(e.message ?: "Failed to create chat")
        }
    }

    /**
     * Start a conversation with a manually entered address (phone number or email).
     */
    suspend fun startConversationWithAddress(address: String, service: String): ChatCreationResult {
        Timber.d("startConversationWithAddress: address=$address, service=$service")

        return try {
            // For SMS mode or when iMessage is not available, create a local SMS chat
            if (service == "SMS") {
                // Normalize address to prevent duplicate conversations
                val normalizedAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
                // Create local SMS chat GUID
                val chatGuid = "sms;-;$normalizedAddress"

                // Try to find or create the chat in the local database
                val existingChat = chatRepository.getChatByGuid(chatGuid)
                if (existingChat == null) {
                    // Create a minimal chat entry for local SMS
                    val newChat = ChatEntity(
                        guid = chatGuid,
                        chatIdentifier = normalizedAddress,
                        displayName = null,
                        isArchived = false,
                        isPinned = false,
                        isGroup = false,
                        hasUnreadMessage = false,
                        unreadCount = 0,
                        lastMessageDate = System.currentTimeMillis(),
                        lastMessageText = null
                    )
                    chatRepository.insertChat(newChat)
                }

                Timber.d("SMS chat created/found: $chatGuid")
                _createdChatGuid.value = chatGuid
                ChatCreationResult.Success(chatGuid)
            } else {
                // Use BlueBubbles server to create iMessage chat
                Timber.d("Creating iMessage chat via server API")
                val response = api.createChat(
                    CreateChatRequest(
                        addresses = listOf(address),
                        service = service
                    )
                )

                val body = response.body()
                val chatData = body?.data
                Timber.d("createChat response: code=${response.code()}, message=${body?.message}, data=$chatData")
                if (response.isSuccessful && chatData != null) {
                    val chatGuid = chatData.guid
                    Timber.d("iMessage chat created: $chatGuid")
                    _createdChatGuid.value = chatGuid
                    ChatCreationResult.Success(chatGuid)
                } else {
                    val errorMsg = body?.message ?: "Failed to create chat"
                    Timber.e("Failed to create iMessage chat: $errorMsg")
                    ChatCreationResult.Error(errorMsg)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start conversation")
            ChatCreationResult.Error(e.message ?: "Failed to create chat")
        }
    }

    /**
     * Handle continue action with selected recipients
     */
    fun handleContinue(recipients: List<SelectedRecipient>): ChatCreationResult {
        Timber.d("handleContinue called with ${recipients.size} recipients: ${recipients.map { "${it.displayName} (${it.service})" }}")
        return when {
            recipients.isEmpty() -> {
                ChatCreationResult.Error("Please add at least one recipient")
            }
            recipients.size == 1 -> {
                // Single recipient - caller should create direct chat
                ChatCreationResult.Error("Use startConversationWithAddress for single recipient")
            }
            else -> {
                // Multiple recipients - navigate to group setup
                val participantsJson = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        com.bothbubbles.ui.chatcreator.GroupParticipant.serializer()
                    ),
                    recipients.map { recipient ->
                        com.bothbubbles.ui.chatcreator.GroupParticipant(
                            address = recipient.address,
                            displayName = recipient.displayName,
                            service = recipient.service,
                            avatarPath = recipient.avatarPath,
                            isManualEntry = recipient.isManualEntry
                        )
                    }
                )
                // Determine group service type
                val allIMessage = recipients.all { it.service.equals("iMessage", ignoreCase = true) }
                val groupService = if (allIMessage) "IMESSAGE" else "MMS"

                val navigation = GroupSetupNavigation(
                    participantsJson = participantsJson,
                    groupService = groupService
                )
                _navigateToGroupSetup.value = navigation
                ChatCreationResult.NavigateToGroupSetup(navigation)
            }
        }
    }

    /**
     * Select an existing group chat
     */
    fun selectGroupChat(groupChat: GroupChatUiModel) {
        _createdChatGuid.value = groupChat.guid
    }

    /**
     * Reset the created chat GUID
     */
    fun resetCreatedChatGuid() {
        _createdChatGuid.value = null
    }

    /**
     * Reset the group setup navigation state
     */
    fun resetGroupSetupNavigation() {
        _navigateToGroupSetup.value = null
    }
}
