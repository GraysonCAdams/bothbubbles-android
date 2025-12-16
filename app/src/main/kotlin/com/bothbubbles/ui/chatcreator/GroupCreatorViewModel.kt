package com.bothbubbles.ui.chatcreator

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.CreateChatRequest
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.PhoneContact
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class GroupCreatorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val handleRepository: HandleRepository,
    private val chatRepository: ChatRepository,
    private val api: BothBubblesApi,
    private val socketConnection: SocketConnection,
    private val settingsDataStore: SettingsDataStore,
    private val androidContactsService: AndroidContactsService
) : ViewModel() {

    companion object {
        private const val TAG = "GroupCreatorViewModel"
    }

    private val route: Screen.GroupCreator = savedStateHandle.toRoute()

    private val _searchQuery = MutableStateFlow("")

    private val _uiState = MutableStateFlow(GroupCreatorUiState())
    val uiState: StateFlow<GroupCreatorUiState> = _uiState.asStateFlow()

    init {
        // Pre-select participant if provided from navigation
        route.preSelectedAddress?.let { address ->
            val preSelectedParticipant = GroupParticipant(
                address = address,
                displayName = route.preSelectedDisplayName ?: address,
                service = route.preSelectedService ?: "iMessage",
                avatarPath = route.preSelectedAvatarPath
            )
            val groupService = determineGroupService(listOf(preSelectedParticipant))
            _uiState.update {
                it.copy(
                    selectedParticipants = listOf(preSelectedParticipant),
                    groupService = groupService
                )
            }
        }

        loadContacts()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Load contacts from phone contacts (not from handles)
            val phoneContacts = androidContactsService.getAllContacts()

            // Build a lookup map for iMessage availability from cached handles
            val handleServiceMap = mutableMapOf<String, String>()
            handleRepository.getAllHandlesOnce().forEach { handle ->
                val normalized = normalizeAddress(handle.address)
                // Prefer iMessage when we know it's available
                if (handle.isIMessage || !handleServiceMap.containsKey(normalized)) {
                    handleServiceMap[normalized] = handle.service
                }
            }

            // Convert phone contacts to ContactUiModel entries
            val allContacts = mutableListOf<ContactUiModel>()
            for (contact in phoneContacts) {
                // Add phone numbers
                for (phone in contact.phoneNumbers) {
                    val normalized = normalizeAddress(phone)
                    val service = handleServiceMap[normalized] ?: "SMS"
                    allContacts.add(
                        ContactUiModel(
                            address = phone,
                            normalizedAddress = normalized,
                            formattedAddress = PhoneNumberFormatter.format(phone),
                            displayName = contact.displayName,
                            service = service,
                            avatarPath = contact.photoUri,
                            isFavorite = contact.isStarred,
                            isRecent = false
                        )
                    )
                }
                // Add emails
                for (email in contact.emails) {
                    val normalized = normalizeAddress(email)
                    allContacts.add(
                        ContactUiModel(
                            address = email,
                            normalizedAddress = normalized,
                            formattedAddress = email,
                            displayName = contact.displayName,
                            service = "iMessage", // Emails are always iMessage
                            avatarPath = contact.photoUri,
                            isFavorite = contact.isStarred,
                            isRecent = false
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

            // Observe search query changes
            _searchQuery.collect { query ->
                val filtered = if (query.isNotBlank()) {
                    deduped.filter { contact ->
                        contact.displayName.contains(query, ignoreCase = true) ||
                            contact.address.contains(query, ignoreCase = true) ||
                            contact.formattedAddress.contains(query, ignoreCase = true)
                    }
                } else {
                    deduped
                }

                // Group by first letter of display name
                val grouped = filtered
                    .sortedBy { it.displayName.uppercase() }
                    .groupBy { contact ->
                        val firstChar = contact.displayName.firstOrNull()?.uppercaseChar() ?: '#'
                        if (firstChar.isLetter()) firstChar.toString() else "#"
                    }
                    .toSortedMap()

                _uiState.update { state ->
                    state.copy(
                        searchQuery = query,
                        groupedContacts = grouped,
                        isLoading = false
                    )
                }
            }
        }

        // Observe search query for manual address detection
        viewModelScope.launch {
            _searchQuery
                .debounce(500)
                .distinctUntilChanged()
                .collect { query ->
                    checkIfValidAddress(query)
                }
        }
    }

    /**
     * Normalize an address for de-duplication purposes.
     */
    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            address.lowercase()
        } else {
            address.replace(Regex("[^0-9+]"), "")
        }
    }

    private suspend fun checkIfValidAddress(query: String) {
        val trimmedQuery = query.trim()

        // Check if the query looks like a phone number or email
        if (trimmedQuery.isPhoneNumber() || trimmedQuery.isEmail()) {
            _uiState.update { it.copy(isCheckingAvailability = true) }

            val smsOnlyMode = settingsDataStore.smsOnlyMode.first()
            val isConnected = socketConnection.connectionState.value == ConnectionState.CONNECTED

            var isIMessageAvailable = false
            var service = "SMS"

            if (!smsOnlyMode && isConnected && trimmedQuery.isPhoneNumber()) {
                try {
                    val response = api.checkIMessageAvailability(trimmedQuery)
                    if (response.isSuccessful && response.body()?.data?.available == true) {
                        isIMessageAvailable = true
                        service = "iMessage"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check iMessage availability", e)
                }
            } else if (!smsOnlyMode && isConnected && trimmedQuery.isEmail()) {
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

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleParticipant(participant: GroupParticipant) {
        val currentParticipants = _uiState.value.selectedParticipants.toMutableList()
        val existingIndex = currentParticipants.indexOfFirst { it.address == participant.address }

        if (existingIndex >= 0) {
            currentParticipants.removeAt(existingIndex)
        } else {
            currentParticipants.add(participant)
        }

        // Determine group service type based on participants
        val groupService = determineGroupService(currentParticipants)

        _uiState.update {
            it.copy(
                selectedParticipants = currentParticipants,
                groupService = groupService
            )
        }
    }

    fun addManualAddress(address: String, service: String) {
        val participant = GroupParticipant(
            address = address,
            displayName = address,
            service = service,
            isManualEntry = true
        )

        val currentParticipants = _uiState.value.selectedParticipants.toMutableList()
        if (currentParticipants.none { it.address == address }) {
            currentParticipants.add(participant)
        }

        val groupService = determineGroupService(currentParticipants)

        _uiState.update {
            it.copy(
                selectedParticipants = currentParticipants,
                groupService = groupService,
                searchQuery = "",
                manualAddressEntry = null
            )
        }
        _searchQuery.value = ""
    }

    fun removeParticipant(address: String) {
        val currentParticipants = _uiState.value.selectedParticipants.toMutableList()
        currentParticipants.removeAll { it.address == address }

        val groupService = determineGroupService(currentParticipants)

        _uiState.update {
            it.copy(
                selectedParticipants = currentParticipants,
                groupService = groupService
            )
        }
    }

    /**
     * Determines the group service type:
     * - If ALL participants support iMessage, the group will be iMessage
     * - If ANY participant is SMS-only, the group will be MMS (SMS group)
     */
    private fun determineGroupService(participants: List<GroupParticipant>): GroupServiceType {
        if (participants.isEmpty()) return GroupServiceType.UNDETERMINED

        val allIMessage = participants.all { it.service.equals("iMessage", ignoreCase = true) }
        return if (allIMessage) {
            GroupServiceType.IMESSAGE
        } else {
            GroupServiceType.MMS
        }
    }

    fun createGroup() {
        viewModelScope.launch {
            val participants = _uiState.value.selectedParticipants
            if (participants.size < 2) {
                _uiState.update { it.copy(error = "A group needs at least 2 participants") }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true) }

            try {
                val addresses = participants.map { it.address }
                val groupService = _uiState.value.groupService

                when (groupService) {
                    GroupServiceType.MMS -> {
                        // Create local MMS group chat
                        val chatGuid = "mms;-;${addresses.joinToString(",")}"

                        val existingChat = chatRepository.getChatByGuid(chatGuid)
                        if (existingChat == null) {
                            val newChat = ChatEntity(
                                guid = chatGuid,
                                chatIdentifier = addresses.joinToString(", "),
                                displayName = null,
                                isArchived = false,
                                isPinned = false,
                                isGroup = true,
                                hasUnreadMessage = false,
                                unreadCount = 0,
                                lastMessageDate = System.currentTimeMillis(),
                                lastMessageText = null
                            )
                            chatRepository.insertChat(newChat)
                        }

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                createdChatGuid = chatGuid
                            )
                        }
                    }

                    GroupServiceType.IMESSAGE -> {
                        // Create iMessage group via BlueBubbles server
                        val response = api.createChat(
                            CreateChatRequest(
                                addresses = addresses,
                                service = "iMessage"
                            )
                        )

                        val body = response.body()
                        if (response.isSuccessful && body?.data != null) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    createdChatGuid = body.data.guid
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = body?.message ?: "Failed to create group"
                                )
                            }
                        }
                    }

                    GroupServiceType.UNDETERMINED -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Please add participants to the group"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create group", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to create group"
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
     * Serialize participants to JSON for navigation to GroupSetupScreen
     */
    fun getParticipantsJson(): String {
        return Json.encodeToString(_uiState.value.selectedParticipants)
    }

    private fun String.isPhoneNumber(): Boolean {
        val cleaned = this.replace(Regex("[^0-9+]"), "")
        return cleaned.startsWith("+") || (cleaned.length >= 10 && cleaned.all { it.isDigit() })
    }

    private fun String.isEmail(): Boolean {
        return this.contains("@") && this.contains(".") && this.length > 5
    }
}

data class GroupCreatorUiState(
    val searchQuery: String = "",
    val groupedContacts: Map<String, List<ContactUiModel>> = emptyMap(),
    val selectedParticipants: List<GroupParticipant> = emptyList(),
    val groupService: GroupServiceType = GroupServiceType.UNDETERMINED,
    val isLoading: Boolean = false,
    val isCheckingAvailability: Boolean = false,
    val manualAddressEntry: ManualAddressEntry? = null,
    val error: String? = null,
    val createdChatGuid: String? = null
)

// GroupParticipant is defined in ChatCreatorModels.kt

enum class GroupServiceType {
    UNDETERMINED,
    IMESSAGE,
    MMS
}
