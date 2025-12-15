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
    val isRecent: Boolean = false  // Whether this contact has recent conversations
)

/**
 * Main UI state for the ChatCreator screen
 */
data class ChatCreatorUiState(
    val mode: ChatCreatorMode = ChatCreatorMode.SINGLE,
    val searchQuery: String = "",
    val recentContacts: List<ContactUiModel> = emptyList(),  // Recent conversations (up to 4)
    val groupedContacts: Map<String, List<ContactUiModel>> = emptyMap(),
    val favoriteContacts: List<ContactUiModel> = emptyList(),
    val groupChats: List<GroupChatUiModel> = emptyList(),
    val selectedRecipients: List<SelectedRecipient> = emptyList(),
    val isLoading: Boolean = false,
    val isCheckingAvailability: Boolean = false,
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
