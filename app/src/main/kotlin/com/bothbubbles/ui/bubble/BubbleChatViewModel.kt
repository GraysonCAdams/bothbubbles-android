package com.bothbubbles.ui.bubble

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.messaging.MessageSender
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.AttachmentItem
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.chat.composer.ComposerPanel
import com.bothbubbles.ui.chat.composer.ComposerState
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.util.StableList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the bubble chat screen with full composer support.
 *
 * Provides the same composer functionality as the main chat screen:
 * - Text input with draft persistence
 * - Attachment support (images, videos)
 * - Panel state (media picker, emoji, GIF)
 * - Protocol-aware send mode (iMessage vs SMS)
 */
@HiltViewModel
class BubbleChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val messageSender: MessageSender,
    private val socketConnection: SocketConnection
) : ViewModel() {

    private val chatGuid: String = checkNotNull(savedStateHandle[BubbleActivity.EXTRA_CHAT_GUID])

    // ============================================================================
    // UI STATE
    // ============================================================================

    private val _uiState = MutableStateFlow(BubbleChatUiState())
    val uiState: StateFlow<BubbleChatUiState> = _uiState.asStateFlow()

    // ============================================================================
    // COMPOSER STATE
    // ============================================================================

    private val _draftText = MutableStateFlow("")
    private val _pendingAttachments = MutableStateFlow<List<AttachmentItem>>(emptyList())
    private val _activePanel = MutableStateFlow(ComposerPanel.None)
    private val _isSending = MutableStateFlow(false)
    private val _attachmentQuality = MutableStateFlow(AttachmentQuality.STANDARD)

    /**
     * Composer state derived from internal state flows.
     * Used by ChatComposer component.
     */
    val composerState: StateFlow<ComposerState> = combine(
        _draftText,
        _pendingAttachments,
        _activePanel,
        _isSending,
        _uiState.map { it.isLocalSmsChat },
        _attachmentQuality
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val text = values[0] as? String ?: ""
        val attachments = values[1] as? List<AttachmentItem> ?: emptyList()
        val panel = values[2] as? ComposerPanel ?: ComposerPanel.None
        val sending = values[3] as? Boolean ?: false
        val isLocalSms = values[4] as? Boolean ?: false
        val quality = values[5] as? AttachmentQuality ?: AttachmentQuality.STANDARD

        ComposerState(
            text = text,
            attachments = attachments,
            activePanel = panel,
            isSending = sending,
            sendMode = if (isLocalSms) ChatSendMode.SMS else ChatSendMode.IMESSAGE,
            isLocalSmsChat = isLocalSms,
            currentImageQuality = quality
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ComposerState()
    )

    // Draft persistence
    private var draftSaveJob: Job? = null
    private val draftSaveDebounceMs = 500L

    init {
        loadChat()
        loadMessages()
        observeSocketConnection()
    }

    private fun loadChat() {
        viewModelScope.launch {
            var draftLoaded = false
            chatRepository.observeChat(chatGuid).collect { chat ->
                chat?.let {
                    _uiState.update { state ->
                        state.copy(
                            chatTitle = chat.displayName ?: chat.chatIdentifier ?: "",
                            isLocalSmsChat = chat.isLocalSms,
                            isLoading = false
                        )
                    }
                    // Load draft only on first observation
                    if (!draftLoaded) {
                        _draftText.value = chat.textFieldText ?: ""
                        draftLoaded = true
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
                                attachments = StableList(emptyList()),
                                senderName = null,
                                messageSource = if (msg.isFromMe) "me" else "them",
                                reactions = StableList(emptyList()),
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
            socketConnection.connectionState.map { it == ConnectionState.CONNECTED }.collect { connected ->
                _uiState.update { it.copy(isServerConnected = connected) }
            }
        }
    }

    // ============================================================================
    // COMPOSER EVENT HANDLING
    // ============================================================================

    /**
     * Handle events from the ChatComposer component.
     */
    fun onComposerEvent(event: ComposerEvent) {
        when (event) {
            is ComposerEvent.TextChanged -> updateDraft(event.text)
            is ComposerEvent.Send -> sendMessage()
            is ComposerEvent.AddAttachments -> addAttachments(event.uris)
            is ComposerEvent.RemoveAttachment -> removeAttachment(event.attachment)
            is ComposerEvent.ClearAllAttachments -> _pendingAttachments.value = emptyList()
            is ComposerEvent.ToggleMediaPicker -> togglePanel(ComposerPanel.MediaPicker)
            is ComposerEvent.ToggleEmojiPicker -> togglePanel(ComposerPanel.EmojiKeyboard)
            is ComposerEvent.ToggleGifPicker -> togglePanel(ComposerPanel.GifPicker)
            is ComposerEvent.DismissPanel -> _activePanel.value = ComposerPanel.None
            is ComposerEvent.OpenQualitySheet -> { /* Not implemented in bubble */ }
            else -> { /* Other events not needed for bubble */ }
        }
    }

    private fun updateDraft(text: String) {
        _draftText.value = text
        persistDraft(text)
    }

    private fun persistDraft(text: String) {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(draftSaveDebounceMs)
            chatRepository.updateDraftText(chatGuid, text)
        }
    }

    private fun togglePanel(panel: ComposerPanel) {
        _activePanel.value = if (_activePanel.value == panel) ComposerPanel.None else panel
    }

    private fun addAttachments(uris: List<Uri>) {
        val newItems = uris.map { uri ->
            AttachmentItem(
                id = UUID.randomUUID().toString(),
                uri = uri,
                mimeType = null, // Will be resolved on send
                displayName = null,
                sizeBytes = null,
                quality = _attachmentQuality.value
            )
        }
        _pendingAttachments.update { current -> current + newItems }
    }

    private fun removeAttachment(attachment: AttachmentItem) {
        _pendingAttachments.update { current ->
            current.filterNot { it.id == attachment.id }
        }
    }

    /**
     * Send the current draft message with any attachments.
     */
    private fun sendMessage() {
        val text = _draftText.value.trim()
        val attachments = _pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        _isSending.value = true
        _draftText.value = ""
        _pendingAttachments.value = emptyList()
        _activePanel.value = ComposerPanel.None

        // Clear draft from database
        draftSaveJob?.cancel()
        viewModelScope.launch {
            chatRepository.updateDraftText(chatGuid, null)
        }

        viewModelScope.launch {
            val pendingInputs = attachments.map { item ->
                PendingAttachmentInput(
                    uri = item.uri,
                    mimeType = item.mimeType,
                    name = item.displayName,
                    size = item.sizeBytes
                )
            }

            messageSender.sendUnified(
                chatGuid = chatGuid,
                text = text,
                attachments = pendingInputs
            ).fold(
                onSuccess = {
                    _isSending.value = false
                },
                onFailure = { e ->
                    // Restore draft on error
                    _isSending.value = false
                    _draftText.value = text
                    // Note: Attachments are not restored as URIs may have expired
                }
            )
        }
    }
}

/**
 * UI state for the bubble chat screen.
 */
@Stable
data class BubbleChatUiState(
    val chatTitle: String = "",
    val messages: List<MessageUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val isLocalSmsChat: Boolean = false,
    val isServerConnected: Boolean = false
)
