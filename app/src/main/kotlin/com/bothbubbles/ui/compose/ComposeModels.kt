package com.bothbubbles.ui.compose

import com.bothbubbles.ui.components.message.MessageUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Service type for a recipient, determines chip color.
 */
enum class RecipientService {
    /** iMessage recipient - blue chip */
    IMESSAGE,
    /** SMS recipient - green chip */
    SMS,
    /** Invalid format (won't work for iMessage OR SMS) - red chip */
    INVALID
}

/**
 * Represents a selected recipient chip in the compose screen.
 */
data class RecipientChip(
    /** Unique ID for this chip */
    val id: String,
    /** Phone number or email address */
    val address: String,
    /** Contact name or formatted address */
    val displayName: String,
    /** Service type (determines chip color) */
    val service: RecipientService,
    /** True if this represents a group chat */
    val isGroup: Boolean = false,
    /** Chat GUID if this is an existing conversation */
    val chatGuid: String? = null,
    /** Avatar image path */
    val avatarPath: String? = null
)

/**
 * Suggestion item in the recipient dropdown.
 */
sealed class RecipientSuggestion {
    /** Contact suggestion with phone/email */
    data class Contact(
        val contactId: Long?,
        val displayName: String,
        val address: String,
        val formattedAddress: String,
        val service: String,
        val avatarPath: String?,
        /** Chat GUID if this comes from an existing unified chat group */
        val chatGuid: String? = null
    ) : RecipientSuggestion()

    /** Group chat suggestion */
    data class Group(
        val chatGuid: String,
        val displayName: String,
        /** Preview of member names: "Alice, Bob, Carol..." */
        val memberPreview: String,
        val avatarPath: String?
    ) : RecipientSuggestion()
}

/**
 * Simple message preview for the compose screen conversation area.
 */
data class ComposeMessagePreview(
    val guid: String,
    val text: String?,
    val isFromMe: Boolean,
    val timestamp: Long,
    val hasAttachments: Boolean = false,
    /** e.g., "Photo", "Video", "Audio" */
    val attachmentPreviewText: String? = null,
    val senderName: String? = null
)

/**
 * State of the conversation area in the compose screen.
 */
sealed class ComposeConversationState {
    /** No recipients selected - show empty placeholder */
    data object Empty : ComposeConversationState()

    /** Loading conversation for selected recipients */
    data object Loading : ComposeConversationState()

    /** No existing conversation - will create new */
    data object NewConversation : ComposeConversationState()

    /** Existing conversation found with real message models */
    data class Existing(
        val chatGuid: String,
        val messages: ImmutableList<MessageUiModel>
    ) : ComposeConversationState()
}

/**
 * Main UI state for the ComposeScreen.
 */
data class ComposeUiState(
    /** Selected recipient chips */
    val chips: ImmutableList<RecipientChip> = persistentListOf(),
    /** Current text in the recipient input field */
    val recipientInput: String = "",
    /** Whether suggestion dropdown is visible */
    val showSuggestions: Boolean = false,
    /** Filtered suggestions based on input */
    val suggestions: ImmutableList<RecipientSuggestion> = persistentListOf(),
    /** Whether the recipient field is locked (group selected) */
    val isRecipientFieldLocked: Boolean = false,
    /** State of the conversation area */
    val conversationState: ComposeConversationState = ComposeConversationState.Empty,
    /** Message text in the composer */
    val messageText: String = "",
    /** Whether a send operation is in progress */
    val isSending: Boolean = false,
    /** Error message to display */
    val error: String? = null,
    /** Chat GUID to navigate to after successful send */
    val navigateToChatGuid: String? = null,
    /** Merged GUIDs for unified conversation navigation */
    val navigateToMergedGuids: List<String>? = null
) {
    /**
     * Get the effective service for all chips (for display color).
     * Red if any invalid, green if any SMS, blue if all iMessage.
     */
    val effectiveService: RecipientService
        get() = when {
            chips.any { it.service == RecipientService.INVALID } -> RecipientService.INVALID
            chips.any { it.service == RecipientService.SMS } -> RecipientService.SMS
            else -> RecipientService.IMESSAGE
        }

    /** Whether send button should be enabled */
    val canSend: Boolean
        get() = chips.isNotEmpty() &&
            chips.none { it.service == RecipientService.INVALID } &&
            (messageText.isNotBlank() || conversationState is ComposeConversationState.Existing)
}
