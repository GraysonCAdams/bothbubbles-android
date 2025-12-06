package com.bluebubbles.ui.bubble

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.repository.ChatRepository
import com.bluebubbles.data.repository.MessageRepository
import com.bluebubbles.services.socket.SocketService
import com.bluebubbles.ui.components.MessageUiModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Simplified ViewModel for the bubble chat screen.
 * Focuses only on message display and quick replies.
 */
@HiltViewModel
class BubbleChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val socketService: SocketService
) : ViewModel() {

    private val chatGuid: String = checkNotNull(savedStateHandle[BubbleActivity.EXTRA_CHAT_GUID])

    private val _uiState = MutableStateFlow(BubbleChatUiState())
    val uiState: StateFlow<BubbleChatUiState> = _uiState.asStateFlow()

    init {
        loadChat()
        loadMessages()
        observeSocketConnection()
    }

    private fun loadChat() {
        viewModelScope.launch {
            chatRepository.observeChat(chatGuid).collect { chat ->
                chat?.let {
                    _uiState.update { state ->
                        state.copy(
                            chatTitle = chat.displayName ?: chat.chatIdentifier ?: "",
                            isLocalSmsChat = chat.isLocalSms,
                            isLoading = false
                        )
                    }
                }
            }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            // Only load last 15 messages for the bubble (compact view)
            messageRepository.observeMessagesForChat(chatGuid, limit = 15, offset = 0).collect { messages ->
                _uiState.update { state ->
                    state.copy(
                        messages = messages.map { msg ->
                            MessageUiModel(
                                guid = msg.guid,
                                text = msg.text,
                                isFromMe = msg.isFromMe,
                                dateCreated = msg.dateCreated,
                                formattedTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(msg.dateCreated)),
                                isSent = msg.dateCreated > 0,
                                hasError = msg.error != 0,
                                isDelivered = msg.dateDelivered != null,
                                isRead = msg.dateRead != null,
                                attachments = emptyList(), // Simplified for bubble
                                senderName = null,
                                messageSource = if (msg.isFromMe) "me" else "them",
                                reactions = emptyList(),
                                myReactions = emptySet(),
                                isReaction = false,
                                expressiveSendStyleId = null
                            )
                        },
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun observeSocketConnection() {
        viewModelScope.launch {
            socketService.connectionState.map { it == com.bluebubbles.services.socket.ConnectionState.CONNECTED }.collect { connected ->
                _uiState.update { it.copy(isServerConnected = connected) }
            }
        }
    }

    /**
     * Update the draft text for the message being composed.
     */
    fun updateDraft(text: String) {
        _uiState.update { it.copy(draftText = text) }
    }

    /**
     * Send the current draft message.
     * Uses unified send which auto-routes to iMessage or local SMS/MMS.
     */
    fun sendMessage() {
        val text = _uiState.value.draftText.trim()
        if (text.isBlank()) return

        _uiState.update { it.copy(isSending = true, draftText = "") }

        viewModelScope.launch {
            messageRepository.sendUnified(
                chatGuid = chatGuid,
                text = text
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSending = false) }
                },
                onFailure = { e ->
                    // Restore draft on error
                    _uiState.update { it.copy(isSending = false, draftText = text) }
                }
            )
        }
    }
}

/**
 * UI state for the bubble chat screen.
 */
data class BubbleChatUiState(
    val chatTitle: String = "",
    val messages: List<MessageUiModel> = emptyList(),
    val draftText: String = "",
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val isLocalSmsChat: Boolean = false,
    val isServerConnected: Boolean = false
)
