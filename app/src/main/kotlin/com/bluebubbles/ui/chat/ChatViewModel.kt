package com.bluebubbles.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.db.entity.MessageEntity
import com.bluebubbles.data.local.db.entity.MessageSource
import com.bluebubbles.data.repository.ChatRepository
import com.bluebubbles.data.repository.MessageDeliveryMode
import com.bluebubbles.data.repository.MessageRepository
import com.bluebubbles.services.socket.SocketEvent
import com.bluebubbles.services.socket.SocketService
import com.bluebubbles.ui.components.MessageUiModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val socketService: SocketService
) : ViewModel() {

    private val chatGuid: String = checkNotNull(savedStateHandle["chatGuid"])

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<Uri>>(emptyList())

    init {
        loadChat()
        loadMessages()
        observeTypingIndicators()
        markAsRead()
        determineChatType()
    }

    private fun determineChatType() {
        val isLocalSms = messageRepository.isLocalSmsChat(chatGuid)
        _uiState.update { it.copy(isLocalSmsChat = isLocalSms) }
    }

    private fun loadChat() {
        viewModelScope.launch {
            chatRepository.observeChat(chatGuid).collect { chat ->
                chat?.let {
                    _uiState.update { state ->
                        state.copy(
                            chatTitle = it.displayName ?: it.chatIdentifier ?: "Unknown",
                            isGroup = it.isGroup
                        )
                    }
                }
            }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            messageRepository.observeMessagesForChat(chatGuid, limit = 50, offset = 0)
                .map { messages -> messages.map { it.toUiModel() } }
                .collect { messageModels ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            messages = messageModels
                        )
                    }
                }
        }
    }

    private fun observeTypingIndicators() {
        viewModelScope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.TypingIndicator>()
                .filter { it.chatGuid == chatGuid }
                .collect { event ->
                    _uiState.update { it.copy(isTyping = event.isTyping) }
                }
        }
    }

    private fun markAsRead() {
        viewModelScope.launch {
            chatRepository.markChatAsRead(chatGuid)
        }
    }

    fun updateDraft(text: String) {
        _uiState.update { it.copy(draftText = text) }
    }

    fun addAttachment(uri: Uri) {
        _pendingAttachments.update { it + uri }
        _uiState.update { it.copy(attachmentCount = _pendingAttachments.value.size) }
    }

    fun removeAttachment(uri: Uri) {
        _pendingAttachments.update { it - uri }
        _uiState.update { it.copy(attachmentCount = _pendingAttachments.value.size) }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
        _uiState.update { it.copy(attachmentCount = 0) }
    }

    fun sendMessage() {
        val text = _uiState.value.draftText.trim()
        val attachments = _pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, draftText = "") }
            _pendingAttachments.value = emptyList()

            // Use unified send which auto-routes to iMessage or local SMS/MMS
            messageRepository.sendUnified(
                chatGuid = chatGuid,
                text = text,
                attachments = attachments
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSending = false, attachmentCount = 0) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            attachmentCount = 0,
                            error = e.message
                        )
                    }
                }
            )
        }
    }

    /**
     * Send message with explicit delivery mode override
     */
    fun sendMessageVia(deliveryMode: MessageDeliveryMode) {
        val text = _uiState.value.draftText.trim()
        val attachments = _pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, draftText = "") }
            _pendingAttachments.value = emptyList()

            messageRepository.sendUnified(
                chatGuid = chatGuid,
                text = text,
                attachments = attachments,
                deliveryMode = deliveryMode
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSending = false, attachmentCount = 0) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            attachmentCount = 0,
                            error = e.message
                        )
                    }
                }
            )
        }
    }

    fun sendReaction(messageGuid: String, reaction: String) {
        viewModelScope.launch {
            messageRepository.sendReaction(
                chatGuid = chatGuid,
                messageGuid = messageGuid,
                reaction = reaction
            )
        }
    }

    fun retryMessage(messageGuid: String) {
        viewModelScope.launch {
            messageRepository.retryMessage(messageGuid)
        }
    }

    fun loadMoreMessages() {
        if (_uiState.value.isLoadingMore || !_uiState.value.canLoadMore) return

        val oldestMessage = _uiState.value.messages.lastOrNull() ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            messageRepository.syncMessagesForChat(
                chatGuid = chatGuid,
                before = oldestMessage.dateCreated,
                limit = 50
            ).fold(
                onSuccess = { messages ->
                    _uiState.update { state ->
                        state.copy(
                            isLoadingMore = false,
                            canLoadMore = messages.size == 50
                        )
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun MessageEntity.toUiModel(): MessageUiModel {
        return MessageUiModel(
            guid = guid,
            text = text,
            dateCreated = dateCreated,
            formattedTime = formatTime(dateCreated),
            isFromMe = isFromMe,
            isSent = !guid.startsWith("temp-") && error == 0,
            isDelivered = dateDelivered != null,
            isRead = dateRead != null,
            hasError = error != 0,
            isReaction = associatedMessageType?.contains("reaction") == true,
            attachments = emptyList(), // TODO: Load attachments
            senderName = null, // TODO: Get from handle
            messageSource = messageSource
        )
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
    }
}

data class ChatUiState(
    val chatTitle: String = "",
    val isGroup: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isSending: Boolean = false,
    val canLoadMore: Boolean = true,
    val messages: List<MessageUiModel> = emptyList(),
    val draftText: String = "",
    val isTyping: Boolean = false,
    val error: String? = null,
    val isLocalSmsChat: Boolean = false,
    val attachmentCount: Int = 0
)
