package com.bluebubbles.ui.chatcreator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.HandleEntity
import com.bluebubbles.data.remote.api.BlueBubblesApi
import com.bluebubbles.data.remote.api.dto.CreateChatRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ChatCreatorViewModel @Inject constructor(
    private val handleDao: HandleDao,
    private val chatDao: ChatDao,
    private val api: BlueBubblesApi
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    private val _uiState = MutableStateFlow(ChatCreatorUiState())
    val uiState: StateFlow<ChatCreatorUiState> = _uiState.asStateFlow()

    init {
        loadContacts()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Observe handles and group chats from database
            combine(
                handleDao.getAllHandles(),
                _searchQuery.flatMapLatest { query ->
                    if (query.isNotBlank()) {
                        chatDao.searchGroupChats(query)
                    } else {
                        chatDao.getRecentGroupChats()
                    }
                },
                _searchQuery
            ) { handles, groupChats, query ->
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
                    .filter { !it.isFavorite }
                    .sortedBy { it.displayName.uppercase() }
                    .groupBy { contact ->
                        val firstChar = contact.displayName.firstOrNull()?.uppercaseChar() ?: '#'
                        if (firstChar.isLetter()) firstChar.toString() else "#"
                    }
                    .toSortedMap()

                val favorites = filtered.filter { it.isFavorite }

                // Convert group chats to UI model
                val groupChatModels = groupChats.map { it.toGroupChatUiModel() }

                ChatCreatorUiState(
                    searchQuery = query,
                    groupedContacts = grouped,
                    favoriteContacts = favorites,
                    groupChats = groupChatModels,
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
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

    fun resetCreatedChatGuid() {
        _uiState.update { it.copy(createdChatGuid = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun HandleEntity.toContactUiModel(): ContactUiModel {
        // Determine service label
        val serviceLabel = when {
            service.equals("SMS", ignoreCase = true) -> "SMS"
            // Could add RCS detection here if available
            else -> null // iMessage doesn't need a label
        }

        return ContactUiModel(
            address = address,
            formattedAddress = formattedAddress ?: address,
            displayName = displayName,
            service = service,
            avatarPath = cachedAvatarPath,
            isFavorite = false, // TODO: Implement favorites
            serviceLabel = serviceLabel
        )
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
}

data class ChatCreatorUiState(
    val searchQuery: String = "",
    val groupedContacts: Map<String, List<ContactUiModel>> = emptyMap(),
    val favoriteContacts: List<ContactUiModel> = emptyList(),
    val groupChats: List<GroupChatUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val createdChatGuid: String? = null
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
