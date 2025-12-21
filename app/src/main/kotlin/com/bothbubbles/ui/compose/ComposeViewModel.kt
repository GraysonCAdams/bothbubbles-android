package com.bothbubbles.ui.compose

import timber.log.Timber
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.PendingMessageRepository
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.chat.composer.ComposerState
import com.bothbubbles.ui.chat.composer.panels.GifItem
import com.bothbubbles.ui.chat.composer.panels.GifPickerState
import com.bothbubbles.ui.compose.delegates.ComposeComposerDelegate
import com.bothbubbles.ui.compose.delegates.ComposeConversationDelegate
import com.bothbubbles.ui.compose.delegates.RecipientDelegate
import com.bothbubbles.ui.compose.delegates.SuggestionDelegate
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Apple-style compose screen.
 *
 * Orchestrates delegates for:
 * - RecipientDelegate: Chip management, service detection
 * - SuggestionDelegate: Contact/group filtering
 * - ComposeConversationDelegate: Loading conversations based on recipients
 * - ComposeComposerDelegate: Full ChatComposer integration
 */
@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val recipientDelegate: RecipientDelegate,
    private val suggestionDelegate: SuggestionDelegate,
    private val conversationDelegate: ComposeConversationDelegate,
    private val composerDelegate: ComposeComposerDelegate,
    private val chatRepository: ChatRepository,
    private val pendingMessageRepository: PendingMessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    // Expose composer state directly from delegate
    val composerState: StateFlow<ComposerState> get() = composerDelegate.composerState
    val gifPickerState: StateFlow<GifPickerState> get() = composerDelegate.gifPickerState
    val gifSearchQuery: StateFlow<String> get() = composerDelegate.gifSearchQuery

    init {
        initializeDelegates()
        observeDelegateStates()
    }

    private fun initializeDelegates() {
        recipientDelegate.initialize(viewModelScope)
        suggestionDelegate.initialize(viewModelScope)
        conversationDelegate.initialize(viewModelScope)
        composerDelegate.initialize(viewModelScope)
    }

    private fun observeDelegateStates() {
        // Observe chips changes
        viewModelScope.launch {
            recipientDelegate.chips.collect { chips ->
                _uiState.update { it.copy(chips = chips) }
                // Load conversation when chips change
                conversationDelegate.loadConversation(chips)
                // Update composer send mode based on effective service
                updateComposerSendMode(chips)
            }
        }

        // Observe recipient field lock state
        viewModelScope.launch {
            recipientDelegate.isRecipientFieldLocked.collect { isLocked ->
                _uiState.update { it.copy(isRecipientFieldLocked = isLocked) }
            }
        }

        // Observe suggestions
        viewModelScope.launch {
            suggestionDelegate.suggestions.collect { suggestions ->
                _uiState.update { it.copy(suggestions = suggestions) }
            }
        }

        // Observe suggestions visibility
        viewModelScope.launch {
            suggestionDelegate.showSuggestions.collect { show ->
                _uiState.update { it.copy(showSuggestions = show) }
            }
        }

        // Observe conversation state
        viewModelScope.launch {
            conversationDelegate.conversationState.collect { state ->
                _uiState.update { it.copy(conversationState = state) }
            }
        }
    }

    // ===== Recipient Input Actions =====

    /**
     * Called when recipient input text changes.
     */
    fun onRecipientInputChange(text: String) {
        _uiState.update { it.copy(recipientInput = text) }
        suggestionDelegate.updateQuery(text, recipientDelegate.areGroupsAllowed())
    }

    /**
     * Called when Enter is pressed in the recipient field.
     */
    fun onRecipientEnterPressed() {
        val input = _uiState.value.recipientInput.trim()
        if (input.isEmpty()) return

        // Check if there's a selected suggestion
        val selectedSuggestion = suggestionDelegate.getSelectedSuggestion()
        if (selectedSuggestion != null) {
            onSuggestionSelected(selectedSuggestion)
        } else {
            // Add chip from raw text
            recipientDelegate.addChipFromText(input)
            clearRecipientInput()
        }
    }

    /**
     * Called when a suggestion is selected.
     */
    fun onSuggestionSelected(suggestion: RecipientSuggestion) {
        recipientDelegate.addChipFromSuggestion(suggestion)
        clearRecipientInput()
    }

    /**
     * Called when a chip's X button is clicked.
     */
    fun onChipRemove(chip: RecipientChip) {
        recipientDelegate.removeChip(chip)
    }

    /**
     * Set an initial recipient from voice command (Google Assistant, Android Auto).
     * This adds the recipient as a chip and triggers service detection.
     */
    fun setInitialRecipient(address: String) {
        recipientDelegate.addChipFromText(address)
        // Clear any input that might have been set
        _uiState.update { it.copy(recipientInput = "") }
        suggestionDelegate.hideSuggestions()
    }

    /**
     * Move suggestion selection up.
     */
    fun onSuggestionMoveUp() {
        suggestionDelegate.moveSelectionUp()
    }

    /**
     * Move suggestion selection down.
     */
    fun onSuggestionMoveDown() {
        suggestionDelegate.moveSelectionDown()
    }

    // ===== Composer Actions =====

    /**
     * Handle composer events from ChatComposer component.
     */
    fun onComposerEvent(event: ComposerEvent) {
        composerDelegate.onComposerEvent(
            event = event,
            onSend = ::sendMessage
        )
    }

    /**
     * GIF picker actions
     */
    fun onGifSearchQueryChange(query: String) {
        composerDelegate.updateGifSearchQuery(query)
    }

    fun onGifSearch(query: String) {
        composerDelegate.searchGifs(query)
    }

    fun onGifSelected(gif: GifItem) {
        composerDelegate.selectGif(gif)
    }

    /**
     * Send the message.
     */
    fun sendMessage() {
        val state = _uiState.value
        val messageText = composerDelegate.getText().trim()
        val attachments = composerDelegate.getAttachments()

        if (state.chips.isEmpty()) return
        if (messageText.isEmpty() && attachments.isEmpty()) return
        if (state.chips.any { it.service == RecipientService.INVALID }) return

        viewModelScope.launch {
            composerDelegate.setSending(true)
            _uiState.update { it.copy(error = null) }

            try {
                val chatGuid = getOrCreateChatGuid()
                if (chatGuid == null) {
                    composerDelegate.setSending(false)
                    _uiState.update { it.copy(error = "Failed to create chat") }
                    return@launch
                }

                // Queue the message with attachments
                pendingMessageRepository.queueMessage(
                    chatGuid = chatGuid,
                    text = messageText.ifEmpty { null },
                    attachments = attachments
                )

                Timber.d("Message queued for chat: $chatGuid with ${attachments.size} attachments")

                // Clear composer input
                composerDelegate.clearInput()
                composerDelegate.setSending(false)

                // Navigate to the chat screen
                _uiState.update { it.copy(navigateToChatGuid = chatGuid) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send message")
                composerDelegate.setSending(false)
                _uiState.update { it.copy(error = "Failed to send message") }
            }
        }
    }

    /**
     * Reset navigation state after navigating.
     */
    fun onNavigated() {
        _uiState.update { it.copy(navigateToChatGuid = null) }
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ===== Shared Content =====

    /**
     * Set shared content from a share intent.
     * Call this when navigating to ComposeScreen with shared content.
     */
    fun setSharedContent(text: String?, uris: List<android.net.Uri>) {
        if (text != null) {
            composerDelegate.onComposerEvent(ComposerEvent.TextChanged(text), onSend = {})
        }
        if (uris.isNotEmpty()) {
            composerDelegate.onComposerEvent(ComposerEvent.AddAttachments(uris), onSend = {})
        }
    }

    // ===== Private Helpers =====

    /**
     * Update composer send mode based on effective service from chips.
     */
    private fun updateComposerSendMode(chips: kotlinx.collections.immutable.ImmutableList<RecipientChip>) {
        val effectiveService = when {
            chips.isEmpty() -> RecipientService.IMESSAGE
            chips.any { it.service == RecipientService.INVALID } -> RecipientService.IMESSAGE
            chips.any { it.service == RecipientService.SMS } -> RecipientService.SMS
            else -> RecipientService.IMESSAGE
        }
        val sendMode = when (effectiveService) {
            RecipientService.SMS -> ChatSendMode.SMS
            else -> ChatSendMode.IMESSAGE
        }
        composerDelegate.updateSendMode(sendMode)
    }

    private fun clearRecipientInput() {
        _uiState.update { it.copy(recipientInput = "") }
        suggestionDelegate.hideSuggestions()
    }

    private suspend fun getOrCreateChatGuid(): String? {
        val chips = _uiState.value.chips
        if (chips.isEmpty()) return null

        // Check if we already have an existing chat
        val existingGuid = conversationDelegate.foundChatGuid.value
        if (existingGuid != null) {
            return existingGuid
        }

        // For single recipient, create chat
        if (chips.size == 1) {
            val chip = chips[0]
            if (chip.isGroup) {
                return chip.chatGuid
            }

            return createChatForAddress(chip.address, chip.service)
        }

        // For multiple recipients, would need to create group
        // For now, return null (not implemented)
        Timber.w("Group creation not implemented yet")
        return null
    }

    private suspend fun createChatForAddress(address: String, service: RecipientService): String? {
        return try {
            val normalizedAddress = if (address.contains("@")) {
                address.lowercase()
            } else {
                PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
            }

            val servicePrefix = when (service) {
                RecipientService.IMESSAGE -> "iMessage"
                RecipientService.SMS -> "SMS"
                RecipientService.INVALID -> return null
            }

            val newGuid = "$servicePrefix;-;$normalizedAddress"

            // Check if chat exists
            val existingChat = chatRepository.getChat(newGuid)
            if (existingChat != null) {
                return newGuid
            }

            // Create new local chat entry
            val newChat = ChatEntity(
                guid = newGuid,
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

            Timber.d("Created chat: $newGuid")
            newGuid
        } catch (e: Exception) {
            Timber.e(e, "Failed to create chat")
            null
        }
    }
}
