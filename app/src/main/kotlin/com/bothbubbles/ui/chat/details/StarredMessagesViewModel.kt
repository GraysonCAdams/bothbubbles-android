package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StarredMessagesUiState(
    val messages: List<MessageEntity> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class StarredMessagesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val route: Screen.StarredMessages = savedStateHandle.toRoute()
    private val chatGuid: String = route.chatGuid

    private val _uiState = MutableStateFlow(StarredMessagesUiState())
    val uiState: StateFlow<StarredMessagesUiState> = _uiState.asStateFlow()

    init {
        loadStarredMessages()
    }

    private fun loadStarredMessages() {
        viewModelScope.launch {
            val messages = messageRepository.getStarredMessagesForChat(chatGuid)
            _uiState.update {
                it.copy(
                    messages = messages,
                    isLoading = false
                )
            }
        }
    }
}
