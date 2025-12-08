package com.bothbubbles.ui.chatcreator

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.CreateChatRequest
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.ui.components.PhoneAndCodeParsingUtils
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ChatCreatorViewModel @Inject constructor(
    private val handleDao: HandleDao,
    private val chatDao: ChatDao,
    private val api: BothBubblesApi,
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore,
    private val androidContactsService: AndroidContactsService
) : ViewModel() {

    companion object {
        private const val TAG = "ChatCreatorViewModel"
    }

    private val _searchQuery = MutableStateFlow("")
    private var iMessageCheckJob: Job? = null

    private val _uiState = MutableStateFlow(ChatCreatorUiState())
    val uiState: StateFlow<ChatCreatorUiState> = _uiState.asStateFlow()

    init {
        loadContacts()
        observeSearchQueryForAddressDetection()
    }

    private fun observeSearchQueryForAddressDetection() {
        viewModelScope.launch {
            _searchQuery
                .debounce(500) // Wait 500ms after user stops typing
                .distinctUntilChanged()
                .collect { query ->
                    checkIfValidAddress(query)
                }
        }
    }

    private suspend fun checkIfValidAddress(query: String) {
        val trimmedQuery = query.trim()

        // Check if the query looks like a phone number or email
        if (trimmedQuery.isPhoneNumber() || trimmedQuery.isEmail()) {
            _uiState.update { it.copy(isCheckingAvailability = true) }

            // Check if we're in SMS-only mode
            val smsOnlyMode = settingsDataStore.smsOnlyMode.first()
            val isConnected = socketService.connectionState.value == ConnectionState.CONNECTED

            var isIMessageAvailable = false
            var service = "SMS"

            // Only check iMessage availability if not in SMS-only mode and server is connected
            if (!smsOnlyMode && isConnected && trimmedQuery.isPhoneNumber()) {
                try {
                    val response = api.checkIMessageAvailability(trimmedQuery)
                    if (response.isSuccessful && response.body()?.data == true) {
                        isIMessageAvailable = true
                        service = "iMessage"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check iMessage availability", e)
                }
            } else if (!smsOnlyMode && isConnected && trimmedQuery.isEmail()) {
                // Emails are always iMessage
                isIMessageAvailable = true
                service = "iMessage"
            }

            _uiState.update {
                it.copy(
                    isCheckingAvailability = false,
                    manualAddressEntry = ManualAddressEntry(
                        address = trimmedQuery,
                        isIMessageAvailable = isIMessageAvailable,
                        service = service
                    )
                )
            }
        } else {
            _uiState.update { it.copy(manualAddressEntry = null, isCheckingAvailability = false) }
        }
    }

    private fun String.isPhoneNumber(): Boolean {
        val cleaned = this.replace(Regex("[^0-9+]"), "")
        return cleaned.startsWith("+") || (cleaned.length >= 10 && cleaned.all { it.isDigit() })
    }

    private fun String.isEmail(): Boolean {
        return this.contains("@") && this.contains(".") && this.length > 5
    }

    private fun loadContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Load contacts from phone contacts (not from handles)
            val phoneContacts = androidContactsService.getAllContacts()

            // Build a lookup map for iMessage availability from cached handles
            val handleServiceMap = mutableMapOf<String, String>()
            handleDao.getAllHandlesOnce().forEach { handle ->
                val normalized = normalizeAddress(handle.address)
                // Prefer iMessage when we know it's available
                if (handle.isIMessage || !handleServiceMap.containsKey(normalized)) {
                    handleServiceMap[normalized] = handle.service
                }
            }

            // Get recent addresses from handle cross-references
            val recentHandles = handleDao.getRecentContacts().first()
            val recentAddresses = recentHandles.map { normalizeAddress(it.address) }.toSet()

            // Convert phone contacts to ContactUiModel entries
            // Each contact can have multiple phone numbers and emails
            val allContacts = mutableListOf<ContactUiModel>()
            for (contact in phoneContacts) {
                // Add phone numbers
                for (phone in contact.phoneNumbers) {
                    val normalized = normalizeAddress(phone)
                    val service = handleServiceMap[normalized] ?: "SMS"
                    val isRecent = recentAddresses.contains(normalized)
                    allContacts.add(
                        ContactUiModel(
                            address = phone,
                            normalizedAddress = normalized,
                            formattedAddress = formatPhoneNumber(phone),
                            displayName = contact.displayName,
                            service = service,
                            avatarPath = contact.photoUri,
                            isFavorite = contact.isStarred,
                            isRecent = isRecent
                        )
                    )
                }
                // Add emails
                for (email in contact.emails) {
                    val normalized = normalizeAddress(email)
                    val isRecent = recentAddresses.contains(normalized)
                    allContacts.add(
                        ContactUiModel(
                            address = email,
                            normalizedAddress = normalized,
                            formattedAddress = email,
                            displayName = contact.displayName,
                            service = "iMessage", // Emails are always iMessage
                            avatarPath = contact.photoUri,
                            isFavorite = contact.isStarred,
                            isRecent = isRecent
                        )
                    )
                }
            }

            // De-duplicate by normalized address
            val deduped = allContacts
                .groupBy { it.normalizedAddress }
                .map { (_, group) ->
                    // Prefer iMessage handle when both exist
                    group.find { it.service == "iMessage" } ?: group.first()
                }

            // Observe search query and group chats
            combine(
                _searchQuery.flatMapLatest { query ->
                    if (query.isNotBlank()) {
                        chatDao.searchGroupChats(query)
                    } else {
                        chatDao.getRecentGroupChats()
                    }
                },
                _searchQuery
            ) { groupChats, query ->
                // Filter by search query
                val filtered = if (query.isNotBlank()) {
                    deduped.filter { contact ->
                        contact.displayName.contains(query, ignoreCase = true) ||
                            contact.address.contains(query, ignoreCase = true) ||
                            contact.formattedAddress.contains(query, ignoreCase = true)
                    }
                } else {
                    deduped
                }

                // Split into recent (up to 4) and rest
                val recent = filtered
                    .filter { it.isRecent }
                    .take(4)
                val recentNormalizedAddresses = recent.map { it.normalizedAddress }.toSet()

                // Rest excludes the recent ones we're showing at the top
                val rest = filtered.filter { !recentNormalizedAddresses.contains(it.normalizedAddress) }

                // Group rest by first letter of display name (excluding favorites)
                val grouped = rest
                    .filter { !it.isFavorite }
                    .sortedBy { it.displayName.uppercase() }
                    .groupBy { contact ->
                        val firstChar = contact.displayName.firstOrNull()?.uppercaseChar() ?: '#'
                        if (firstChar.isLetter()) firstChar.toString() else "#"
                    }
                    .toSortedMap()

                val favorites = rest.filter { it.isFavorite }.sortedBy { it.displayName.uppercase() }

                // Convert group chats to UI model
                val groupChatModels = groupChats.map { it.toGroupChatUiModel() }

                // Return all data
                data class ContactsData(
                    val recent: List<ContactUiModel>,
                    val grouped: Map<String, List<ContactUiModel>>,
                    val favorites: List<ContactUiModel>,
                    val groupChats: List<GroupChatUiModel>,
                    val query: String
                )
                ContactsData(recent, grouped, favorites, groupChatModels, query)
            }.collect { data ->
                _uiState.update { currentState ->
                    currentState.copy(
                        searchQuery = data.query,
                        recentContacts = data.recent,
                        groupedContacts = data.grouped,
                        favoriteContacts = data.favorites,
                        groupChats = data.groupChats,
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Format a phone number for display.
     */
    private fun formatPhoneNumber(phone: String): String {
        // Simple formatting - the phone number is already stored as-is from contacts
        return PhoneNumberFormatter.format(phone)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectContact(contact: ContactUiModel) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Create a new chat with the selected contact
                val response = api.createChat(
                    CreateChatRequest(
                        addresses = listOf(contact.address),
                        service = contact.service
                    )
                )

                val body = response.body()
                if (response.isSuccessful && body?.data != null) {
                    val chatGuid = body.data.guid
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            createdChatGuid = chatGuid
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = body?.message ?: "Failed to create chat"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to create chat"
                    )
                }
            }
        }
    }

    /**
     * Start a conversation with a manually entered address (phone number or email).
     */
    fun startConversationWithAddress(address: String, service: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // For SMS mode or when iMessage is not available, create a local SMS chat
                if (service == "SMS") {
                    // Normalize address to prevent duplicate conversations
                    val normalizedAddress = PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
                    // Create local SMS chat GUID
                    val chatGuid = "sms;-;$normalizedAddress"

                    // Try to find or create the chat in the local database
                    val existingChat = chatDao.getChatByGuid(chatGuid)
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
                        chatDao.insertChat(newChat)
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            createdChatGuid = chatGuid
                        )
                    }
                } else {
                    // Use BlueBubbles server to create iMessage chat
                    val response = api.createChat(
                        CreateChatRequest(
                            addresses = listOf(address),
                            service = service
                        )
                    )

                    val body = response.body()
                    if (response.isSuccessful && body?.data != null) {
                        val chatGuid = body.data.guid
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                createdChatGuid = chatGuid
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = body?.message ?: "Failed to create chat"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start conversation", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to create chat"
                    )
                }
            }
        }
    }

    fun resetCreatedChatGuid() {
        _uiState.update { it.copy(createdChatGuid = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Normalize an address for de-duplication purposes.
     * Strips non-essential characters from phone numbers, lowercases emails.
     */
    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            // Email - just lowercase
            address.lowercase()
        } else {
            // Phone number - strip non-digits except leading +
            address.replace(Regex("[^0-9+]"), "")
        }
    }

    private fun ChatEntity.toGroupChatUiModel(): GroupChatUiModel {
        val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormatter = SimpleDateFormat("MMM d", Locale.getDefault())

        val formattedTime = lastMessageDate?.let { timestamp ->
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            val oneDayMs = 24 * 60 * 60 * 1000L

            when {
                diff < oneDayMs -> timeFormatter.format(Date(timestamp))
                diff < 7 * oneDayMs -> dateFormatter.format(Date(timestamp))
                else -> dateFormatter.format(Date(timestamp))
            }
        }

        return GroupChatUiModel(
            guid = guid,
            displayName = displayName ?: chatIdentifier ?: "Group Chat",
            lastMessage = lastMessageText,
            lastMessageTime = formattedTime,
            avatarPath = customAvatarPath,
            participantCount = 0 // Could be populated from cross-ref count
        )
    }

    fun selectGroupChat(groupChat: GroupChatUiModel) {
        // Navigate directly to existing group chat
        _uiState.update { it.copy(createdChatGuid = groupChat.guid) }
    }

    /**
     * Toggle a recipient from contact selection (add if not selected, remove if selected)
     */
    fun toggleRecipient(contact: ContactUiModel) {
        val currentRecipients = _uiState.value.selectedRecipients
        val existingIndex = currentRecipients.indexOfFirst { it.address == contact.address }

        if (existingIndex >= 0) {
            // Already selected - remove it
            removeRecipient(contact.address)
        } else {
            // Not selected - add it with cached service immediately
            val recipient = SelectedRecipient(
                address = contact.address,
                displayName = contact.displayName,
                service = contact.service,
                avatarPath = contact.avatarPath,
                isManualEntry = false
            )
            addRecipientInternal(recipient)

            // If phone number and not already iMessage, check availability async
            // This allows the chip to update color if iMessage becomes available
            if (contact.address.isPhoneNumber() && !contact.service.equals("iMessage", ignoreCase = true)) {
                checkAndUpdateRecipientService(contact.address)
            }
        }
    }

    /**
     * Asynchronously check iMessage availability for a recipient and update their service if available.
     * This allows the chip color to update after selection.
     */
    private fun checkAndUpdateRecipientService(address: String) {
        viewModelScope.launch {
            val smsOnlyMode = settingsDataStore.smsOnlyMode.first()
            val isConnected = socketService.connectionState.value == ConnectionState.CONNECTED

            if (!smsOnlyMode && isConnected) {
                try {
                    val response = api.checkIMessageAvailability(address)
                    if (response.isSuccessful && response.body()?.data == true) {
                        // Update the recipient's service to iMessage
                        _uiState.update { state ->
                            val updated = state.selectedRecipients.map { recipient ->
                                if (recipient.address == address) {
                                    recipient.copy(service = "iMessage")
                                } else {
                                    recipient
                                }
                            }
                            state.copy(selectedRecipients = updated)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check iMessage availability for $address", e)
                }
            }
        }
    }

    /**
     * Add a recipient from contact selection
     */
    fun addRecipient(contact: ContactUiModel) {
        val recipient = SelectedRecipient(
            address = contact.address,
            displayName = contact.displayName,
            service = contact.service,
            avatarPath = contact.avatarPath,
            isManualEntry = false
        )
        addRecipientInternal(recipient)
    }

    /**
     * Add a recipient from manual address entry (phone number or email)
     */
    fun addManualRecipient(address: String, service: String) {
        val recipient = SelectedRecipient(
            address = address,
            displayName = address,
            service = service,
            avatarPath = null,
            isManualEntry = true
        )
        addRecipientInternal(recipient)
    }

    /**
     * Called when user presses Done on keyboard. Adds the current query as a recipient
     * if it's a valid phone number or email, even if the debounced check hasn't completed.
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

        when {
            query.isPhoneNumber() -> {
                // Check cached handle for last known service, then add recipient
                viewModelScope.launch {
                    // Try exact match first, then normalized format
                    val cachedHandle = handleDao.getHandleByAddressAny(query)
                        ?: handleDao.getHandleByAddressAny(
                            PhoneAndCodeParsingUtils.normalizePhoneNumber(query)
                        )
                    val service = cachedHandle?.service ?: "SMS"
                    addManualRecipient(query, service)
                }
            }
            query.isEmail() -> {
                // Emails are typically iMessage
                addManualRecipient(query, "iMessage")
            }
        }
    }

    private fun addRecipientInternal(recipient: SelectedRecipient) {
        val currentRecipients = _uiState.value.selectedRecipients.toMutableList()
        // Don't add duplicates
        if (currentRecipients.none { it.address == recipient.address }) {
            currentRecipients.add(recipient)
            _uiState.update {
                it.copy(
                    selectedRecipients = currentRecipients,
                    searchQuery = "",
                    manualAddressEntry = null
                )
            }
            _searchQuery.value = ""
        }
    }

    /**
     * Remove a recipient by address
     */
    fun removeRecipient(address: String) {
        val currentRecipients = _uiState.value.selectedRecipients.toMutableList()
        currentRecipients.removeAll { it.address == address }
        _uiState.update { it.copy(selectedRecipients = currentRecipients) }
    }

    /**
     * Remove the last recipient (for backspace handling when input is empty)
     */
    fun removeLastRecipient() {
        val currentRecipients = _uiState.value.selectedRecipients.toMutableList()
        if (currentRecipients.isNotEmpty()) {
            currentRecipients.removeAt(currentRecipients.lastIndex)
            _uiState.update { it.copy(selectedRecipients = currentRecipients) }
        }
    }

    /**
     * Continue action - handles 1 recipient vs multiple recipients
     */
    fun onContinue() {
        val recipients = _uiState.value.selectedRecipients
        when {
            recipients.isEmpty() -> {
                _uiState.update { it.copy(error = "Please add at least one recipient") }
            }
            recipients.size == 1 -> {
                // Single recipient - create direct chat
                val recipient = recipients.first()
                startConversationWithAddress(recipient.address, recipient.service)
            }
            else -> {
                // Multiple recipients - navigate to group setup
                val participantsJson = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(GroupParticipant.serializer()),
                    recipients.map { recipient ->
                        GroupParticipant(
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

                _uiState.update {
                    it.copy(
                        navigateToGroupSetup = GroupSetupNavigation(
                            participantsJson = participantsJson,
                            groupService = groupService
                        )
                    )
                }
            }
        }
    }

    fun resetGroupSetupNavigation() {
        _uiState.update { it.copy(navigateToGroupSetup = null) }
    }
}

data class ChatCreatorUiState(
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
