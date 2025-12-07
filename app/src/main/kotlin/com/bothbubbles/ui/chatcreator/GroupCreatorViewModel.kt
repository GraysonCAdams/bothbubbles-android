package com.bothbubbles.ui.chatcreator

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.CreateChatRequest
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketService
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
    private val handleDao: HandleDao,
    private val chatDao: ChatDao,
    private val api: BothBubblesApi,
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore
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

            combine(
                handleDao.getAllHandles(),
                _searchQuery
            ) { handles, query ->
                val contacts = handles.map { it.toContactUiModel() }

                val filtered = if (query.isNotBlank()) {
                    contacts.filter { contact ->
                        contact.displayName.contains(query, ignoreCase = true) ||
                            contact.address.contains(query, ignoreCase = true) ||
                            contact.formattedAddress.contains(query, ignoreCase = true)
                    }
                } else {
                    contacts
                }

                // Group by first letter of display name
                val grouped = filtered
                    .sortedBy { it.displayName.uppercase() }
                    .groupBy { contact ->
                        val firstChar = contact.displayName.firstOrNull()?.uppercaseChar() ?: '#'
                        if (firstChar.isLetter()) firstChar.toString() else "#"
                    }
                    .toSortedMap()

                _uiState.value.copy(
                    searchQuery = query,
                    groupedContacts = grouped,
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state.copy(
                    selectedParticipants = _uiState.value.selectedParticipants,
                    manualAddressEntry = _uiState.value.manualAddressEntry,
                    isCheckingAvailability = _uiState.value.isCheckingAvailability
                )
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

    private suspend fun checkIfValidAddress(query: String) {
        val trimmedQuery = query.trim()

        // Check if the query looks like a phone number or email
        if (trimmedQuery.isPhoneNumber() || trimmedQuery.isEmail()) {
            _uiState.update { it.copy(isCheckingAvailability = true) }

            val smsOnlyMode = settingsDataStore.smsOnlyMode.first()
            val isConnected = socketService.connectionState.value == ConnectionState.CONNECTED

            var isIMessageAvailable = false
            var service = "SMS"

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

                        val existingChat = chatDao.getChatByGuid(chatGuid)
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
                            chatDao.insertChat(newChat)
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

    private fun HandleEntity.toContactUiModel(): ContactUiModel {
        val serviceLabel = when {
            service.equals("SMS", ignoreCase = true) -> "SMS"
            else -> null
        }

        return ContactUiModel(
            address = address,
            formattedAddress = formattedAddress ?: address,
            displayName = displayName,
            service = service,
            avatarPath = cachedAvatarPath,
            isFavorite = false,
            serviceLabel = serviceLabel
        )
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

@kotlinx.serialization.Serializable
data class GroupParticipant(
    val address: String,
    val displayName: String,
    val service: String,
    val avatarPath: String? = null,
    val isManualEntry: Boolean = false
)

enum class GroupServiceType {
    UNDETERMINED,
    IMESSAGE,
    MMS
}
