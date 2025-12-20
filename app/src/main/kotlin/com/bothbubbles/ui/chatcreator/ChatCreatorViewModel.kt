package com.bothbubbles.ui.chatcreator

import timber.log.Timber
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.ui.chatcreator.delegates.ChatCreationDelegate
import com.bothbubbles.ui.chatcreator.delegates.ContactLoadDelegate
import com.bothbubbles.ui.chatcreator.delegates.ContactSearchDelegate
import com.bothbubbles.ui.chatcreator.delegates.RecipientSelectionDelegate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the ChatCreator screen.
 *
 * This ViewModel orchestrates multiple delegates to handle different aspects of chat creation:
 * - ContactLoadDelegate: Loading and organizing contacts
 * - ContactSearchDelegate: Search functionality and address validation
 * - RecipientSelectionDelegate: Managing selected recipients
 * - ChatCreationDelegate: Creating chats and handling navigation
 */
@HiltViewModel
class ChatCreatorViewModel @Inject constructor(
    private val contactLoadDelegate: ContactLoadDelegate,
    private val contactSearchDelegate: ContactSearchDelegate,
    private val recipientSelectionDelegate: RecipientSelectionDelegate,
    private val chatCreationDelegate: ChatCreationDelegate
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatCreatorUiState())
    val uiState: StateFlow<ChatCreatorUiState> = _uiState.asStateFlow()

    init {
        loadContacts()
        observeSearchQueryForAddressDetection()
        observeDelegateStates()
    }

    /**
     * Load contacts and observe search query changes
     */
    private fun loadContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            contactLoadDelegate.observeContacts(
                searchQueryFlow = contactSearchDelegate.searchQuery,
                scope = viewModelScope
            ).collect { contactsData ->
                _uiState.update { currentState ->
                    currentState.copy(
                        searchQuery = contactsData.query,
                        recentContacts = contactsData.recent,
                        groupedContacts = contactsData.grouped,
                        favoriteContacts = contactsData.favorites,
                        groupChats = contactsData.groupChats,
                        hasContactsPermission = contactsData.hasContactsPermission,
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Observe search query for automatic address detection
     */
    private fun observeSearchQueryForAddressDetection() {
        viewModelScope.launch {
            contactSearchDelegate.observeSearchQueryForAddressDetection(viewModelScope)
                .collect { result ->
                    _uiState.update {
                        it.copy(
                            isCheckingAvailability = result.isValidating,
                            manualAddressEntry = result.manualEntry
                        )
                    }
                }
        }
    }

    /**
     * Observe delegate states and sync them to UI state
     */
    private fun observeDelegateStates() {
        viewModelScope.launch {
            // Observe selected recipients
            recipientSelectionDelegate.selectedRecipients.collect { recipients ->
                _uiState.update { it.copy(selectedRecipients = recipients) }
            }
        }

        viewModelScope.launch {
            // Observe created chat GUID
            chatCreationDelegate.createdChatGuid.collect { chatGuid ->
                _uiState.update { it.copy(createdChatGuid = chatGuid) }
            }
        }

        viewModelScope.launch {
            // Observe group setup navigation
            chatCreationDelegate.navigateToGroupSetup.collect { navigation ->
                _uiState.update { it.copy(navigateToGroupSetup = navigation) }
            }
        }
    }

    /**
     * Update the search query
     */
    fun updateSearchQuery(query: String) {
        contactSearchDelegate.updateSearchQuery(query)
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * Select a contact and create a direct chat
     */
    fun selectContact(contact: ContactUiModel) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            when (val result = chatCreationDelegate.selectContact(contact)) {
                is ChatCreationDelegate.ChatCreationResult.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is ChatCreationDelegate.ChatCreationResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
                is ChatCreationDelegate.ChatCreationResult.NavigateToGroupSetup -> {
                    // Should not happen for single contact selection
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /**
     * Start a conversation with a manually entered address (phone number or email)
     */
    fun startConversationWithAddress(address: String, service: String) {
        viewModelScope.launch {
            Timber.d("startConversationWithAddress: service=$service")
            _uiState.update { it.copy(isLoading = true) }

            when (val result = chatCreationDelegate.startConversationWithAddress(address, service)) {
                is ChatCreationDelegate.ChatCreationResult.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is ChatCreationDelegate.ChatCreationResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
                is ChatCreationDelegate.ChatCreationResult.NavigateToGroupSetup -> {
                    // Should not happen for single address
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /**
     * Toggle a recipient from contact selection
     */
    fun toggleRecipient(contact: ContactUiModel) {
        recipientSelectionDelegate.toggleRecipient(contact, viewModelScope)
    }

    /**
     * Add a recipient from contact selection
     */
    fun addRecipient(contact: ContactUiModel) {
        recipientSelectionDelegate.addRecipient(contact)
    }

    /**
     * Add a recipient from manual address entry
     */
    fun addManualRecipient(address: String, service: String) {
        recipientSelectionDelegate.addManualRecipient(address, service)
        // Clear search query and manual entry after adding
        contactSearchDelegate.updateSearchQuery("")
        _uiState.update {
            it.copy(
                searchQuery = "",
                manualAddressEntry = null
            )
        }
    }

    /**
     * Called when user presses Done on keyboard. Adds the current query as a recipient
     * if it's a valid phone number or email.
     */
    fun onDonePressed() {
        // First check if we already have a validated manual entry
        val manualEntry = _uiState.value.manualAddressEntry
        if (manualEntry != null) {
            addManualRecipient(manualEntry.address, manualEntry.service)
            return
        }

        // Otherwise check if the current query looks like a valid address
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) return

        if (contactSearchDelegate.isCurrentQueryValidAddress()) {
            viewModelScope.launch {
                val service = contactSearchDelegate.getServiceForManualAddress(query)
                addManualRecipient(query, service)
            }
        }
    }

    /**
     * Remove a recipient by address
     */
    fun removeRecipient(address: String) {
        recipientSelectionDelegate.removeRecipient(address)
    }

    /**
     * Remove the last recipient (for backspace handling when input is empty)
     */
    fun removeLastRecipient() {
        recipientSelectionDelegate.removeLastRecipient()
    }

    /**
     * Continue action - handles 1 recipient vs multiple recipients
     *
     * For group chats, waits for pending iMessage availability checks to complete
     * before determining the group service type (iMessage vs MMS).
     */
    fun onContinue() {
        val recipients = _uiState.value.selectedRecipients
        Timber.d("onContinue called with ${recipients.size} recipients")

        when {
            recipients.isEmpty() -> {
                _uiState.update { it.copy(error = "Please add at least one recipient") }
            }
            recipients.size == 1 -> {
                // Single recipient - create direct chat (chat screen handles service detection)
                val recipient = recipients.first()
                Timber.d("Starting conversation (${recipient.service})")
                startConversationWithAddress(recipient.address, recipient.service)
            }
            else -> {
                // Multiple recipients - wait for pending iMessage checks before creating group
                viewModelScope.launch {
                    if (recipientSelectionDelegate.hasPendingChecks()) {
                        _uiState.update { it.copy(isCheckingAvailability = true) }
                        recipientSelectionDelegate.awaitPendingChecks()
                        _uiState.update { it.copy(isCheckingAvailability = false) }
                    }

                    // Get updated recipients after checks complete
                    val updatedRecipients = _uiState.value.selectedRecipients

                    when (val result = chatCreationDelegate.handleContinue(updatedRecipients)) {
                        is ChatCreationDelegate.ChatCreationResult.NavigateToGroupSetup -> {
                            // Navigation state is already updated by delegate
                            Timber.d("Navigating to group setup")
                        }
                        is ChatCreationDelegate.ChatCreationResult.Error -> {
                            _uiState.update { it.copy(error = result.message) }
                        }
                        is ChatCreationDelegate.ChatCreationResult.Success -> {
                            // Should not happen for multiple recipients
                        }
                    }
                }
            }
        }
    }

    /**
     * Select an existing group chat
     */
    fun selectGroupChat(groupChat: GroupChatUiModel) {
        chatCreationDelegate.selectGroupChat(groupChat)
    }

    /**
     * Reset the created chat GUID
     */
    fun resetCreatedChatGuid() {
        chatCreationDelegate.resetCreatedChatGuid()
    }

    /**
     * Reset the group setup navigation state
     */
    fun resetGroupSetupNavigation() {
        chatCreationDelegate.resetGroupSetupNavigation()
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Enter group selection mode. Shows checkboxes on contacts.
     */
    fun enterGroupMode() {
        _uiState.update { it.copy(mode = ChatCreatorMode.GROUP) }
    }

    /**
     * Exit group mode and return to single chat mode.
     * Clears any selected recipients.
     */
    fun exitGroupMode() {
        recipientSelectionDelegate.clearRecipients()
        _uiState.update { it.copy(mode = ChatCreatorMode.SINGLE) }
    }

    /**
     * Check if we're in group selection mode
     */
    val isGroupMode: Boolean
        get() = _uiState.value.mode == ChatCreatorMode.GROUP

    /**
     * Refresh contacts permission status and reload contacts if permission was granted.
     * Called when user returns from system settings.
     */
    fun refreshContactsPermission() {
        val hasPermission = contactLoadDelegate.hasContactsPermission()
        if (hasPermission && !_uiState.value.hasContactsPermission) {
            // Permission was just granted, reload contacts
            loadContacts()
        } else {
            _uiState.update { it.copy(hasContactsPermission = hasPermission) }
        }
    }
}
