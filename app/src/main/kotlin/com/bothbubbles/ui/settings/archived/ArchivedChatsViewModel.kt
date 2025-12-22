package com.bothbubbles.ui.settings.archived

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.core.model.entity.UnifiedChatEntity
import com.bothbubbles.data.repository.UnifiedChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArchivedChatsViewModel @Inject constructor(
    private val unifiedChatRepository: UnifiedChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArchivedChatsUiState())
    val uiState: StateFlow<ArchivedChatsUiState> = _uiState.asStateFlow()

    init {
        observeArchivedChats()
    }

    private fun observeArchivedChats() {
        viewModelScope.launch {
            unifiedChatRepository.observeArchivedChats().collect { chats ->
                _uiState.update { it.copy(archivedChats = chats) }
            }
        }
    }

    fun unarchiveChat(unifiedChatId: String) {
        viewModelScope.launch {
            unifiedChatRepository.updateArchiveStatus(unifiedChatId, false)
        }
    }
}

data class ArchivedChatsUiState(
    val archivedChats: List<UnifiedChatEntity> = emptyList()
)
