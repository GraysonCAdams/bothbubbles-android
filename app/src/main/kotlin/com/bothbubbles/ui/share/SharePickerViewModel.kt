package com.bothbubbles.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.repository.UnifiedChatRepository
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class SharePickerViewModel @Inject constructor(
    private val unifiedChatRepository: UnifiedChatRepository
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
                unifiedChatRepository.observeActiveChats(),
                _searchQuery.debounce(300)
            ) { chats, query ->
                val filtered = if (query.isBlank()) {
                    chats
                } else {
                    chats.filter { chat ->
                        chat.displayName?.contains(query, ignoreCase = true) == true ||
                            chat.normalizedAddress.contains(query, ignoreCase = true)
                    }
                }

                // Sort by last message date, most recent first
                filtered.sortedByDescending { it.latestMessageDate ?: 0L }
            }.collect { conversations ->
                _uiState.update { state ->
                    state.copy(
                        conversations = conversations.map { chat ->
                            ShareConversationUiModel(
                                guid = chat.sourceId,
                                displayName = chat.displayName ?: PhoneNumberFormatter.format(chat.normalizedAddress),
                                avatarPath = chat.effectiveAvatarPath,
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
