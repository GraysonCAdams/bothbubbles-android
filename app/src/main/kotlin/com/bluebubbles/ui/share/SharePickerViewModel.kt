package com.bluebubbles.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class SharePickerViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharePickerUiState())
    val uiState: StateFlow<SharePickerUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    init {
        loadConversations()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            combine(
                chatRepository.observeActiveChats(),
                _searchQuery.debounce(300)
            ) { chats, query ->
                val filtered = if (query.isBlank()) {
                    chats
                } else {
                    chats.filter { chat ->
                        chat.displayName?.contains(query, ignoreCase = true) == true ||
                            chat.chatIdentifier?.contains(query, ignoreCase = true) == true
                    }
                }

                // Sort by last message date, most recent first
                filtered.sortedByDescending { it.lastMessageDate ?: 0L }
            }.collect { conversations ->
                _uiState.update {
                    it.copy(
                        conversations = conversations.map { chat ->
                            ShareConversationUiModel(
                                guid = chat.guid,
                                displayName = chat.displayName ?: chat.chatIdentifier ?: "Unknown",
                                avatarPath = chat.customAvatarPath,
                                isGroup = chat.isGroup,
                                participantNames = emptyList() // Could be populated from join table
                            )
                        },
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSharedContent(text: String?, uris: List<Uri>) {
        _uiState.update {
            it.copy(
                sharedText = text,
                sharedUris = uris
            )
        }
    }
}

data class SharePickerUiState(
    val conversations: List<ShareConversationUiModel> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val sharedText: String? = null,
    val sharedUris: List<Uri> = emptyList()
)

data class ShareConversationUiModel(
    val guid: String,
    val displayName: String,
    val avatarPath: String? = null,
    val isGroup: Boolean = false,
    val participantNames: List<String> = emptyList()
)
