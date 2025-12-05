package com.bluebubbles.ui.settings.archived

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArchivedChatsViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArchivedChatsUiState())
    val uiState: StateFlow<ArchivedChatsUiState> = _uiState.asStateFlow()

    init {
        observeArchivedChats()
    }

    private fun observeArchivedChats() {
        viewModelScope.launch {
            chatRepository.observeArchivedChats().collect { chats ->
                _uiState.update { it.copy(archivedChats = chats) }
            }
        }
    }

    fun unarchiveChat(chatGuid: String) {
        viewModelScope.launch {
            chatRepository.setArchived(chatGuid, false)
        }
    }
}

data class ArchivedChatsUiState(
    val archivedChats: List<ChatEntity> = emptyList()
)
