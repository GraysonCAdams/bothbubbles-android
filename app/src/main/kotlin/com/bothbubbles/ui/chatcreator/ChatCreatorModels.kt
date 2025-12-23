package com.bothbubbles.ui.chatcreator

/**
 * Selection mode for the chat creator screen.
 * Determines whether the user is creating a 1:1 chat or a group chat.
 */
enum class ChatCreatorMode {
    /** Default mode - tapping a contact opens a 1:1 chat directly */
    SINGLE,
    /** Group selection mode - contacts show checkboxes, multiple can be selected */
    GROUP
}

/**
 * UI model for displaying a contact in the list
 */
data class ContactUiModel(
    val address: String,
    val normalizedAddress: String,  // For de-duplication
    val formattedAddress: String,
    val displayName: String,
    val service: String,
    val avatarPath: String? = null,
    val isFavorite: Boolean = false,
    val isRecent: Boolean = false,  // Whether this contact has recent conversations
    val isPopular: Boolean = false,  // Whether this contact has high message engagement
    val contactId: Long? = null,  // Android contact ID for grouping multiple handles
    val lastMessageDate: Long? = null  // Last message timestamp for this handle
)

/**
 * Main UI state for the ChatCreator screen
 */
data class ChatCreatorUiState(
    val mode: ChatCreatorMode = ChatCreatorMode.SINGLE,
    val searchQuery: String = "",
    val popularChats: List<PopularChatUiModel> = emptyList(),  // Top 5 popular chats (1:1 + groups)
    val groupedContacts: Map<String, List<ContactUiModel>> = emptyMap(),
    val favoriteContacts: List<ContactUiModel> = emptyList(),
    val groupChats: List<GroupChatUiModel> = emptyList(),
    val selectedRecipients: List<SelectedRecipient> = emptyList(),
    val conversationPreview: ConversationPreviewState? = null,  // Preview of existing conversation
    val isLoading: Boolean = false,
    val isCheckingAvailability: Boolean = false,
    val hasContactsPermission: Boolean = true,  // Assume granted until checked
    val manualAddressEntry: ManualAddressEntry? = null,
    val error: String? = null,
    val createdChatGuid: String? = null,
    val navigateToGroupSetup: GroupSetupNavigation? = null
)

/**
 * Data for navigating to group setup screen
 */
data class GroupSetupNavigation(
    val participantsJson: String,
    val groupService: String
)

/**
 * Represents a selected recipient in the To bar
 */
data class SelectedRecipient(
    val address: String,
    val displayName: String,
    val service: String,
    val avatarPath: String? = null,
    val isManualEntry: Boolean = false
)

/**
 * Represents a manually entered address (phone number or email)
 * that is not in the contacts list
 */
data class ManualAddressEntry(
    val address: String,
    val isIMessageAvailable: Boolean,
    val service: String // "iMessage" or "SMS"
)

/**
 * UI model for displaying a group chat in the search results
 */
data class GroupChatUiModel(
    val guid: String,
    val displayName: String,
    val lastMessage: String?,
    val lastMessageTime: String?,
    val avatarPath: String? = null,
    val participantCount: Int = 0
)

/**
 * UI model for displaying a popular chat (1:1 or group) in the Popular section
 */
data class PopularChatUiModel(
    val chatGuid: String,
    val displayName: String,
    val isGroup: Boolean,
    val avatarPath: String?,
    val service: String,  // "iMessage" or "SMS" for 1:1, empty for groups
    val identifier: String?  // Phone/email for 1:1, null for groups
)

/**
 * Represents the state of the conversation preview section.
 * Uses full MessageUiModel for consistent rendering with the main chat.
 */
sealed class ConversationPreviewState {
    /** Loading the conversation preview */
    data object Loading : ConversationPreviewState()

    /** No existing conversation - will be a new chat */
    data object NewConversation : ConversationPreviewState()

    /** Existing conversation with full message models */
    data class Existing(
        val chatGuid: String,
        val messages: List<com.bothbubbles.ui.components.message.MessageUiModel>,
        val isGroup: Boolean = false
    ) : ConversationPreviewState()
}

/**
 * Serializable model for group chat participants (used for navigation)
 */
@kotlinx.serialization.Serializable
data class GroupParticipant(
    val address: String,
    val displayName: String,
    val service: String,
    val avatarPath: String? = null,
    val isManualEntry: Boolean = false
)
