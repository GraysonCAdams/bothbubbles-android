package com.bothbubbles.ui.chat.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.ui.chat.ChatSendMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the ChatComposer component.
 * Manages the state of the composer and handles user interactions.
 *
 * This ViewModel is designed to be used either as a standalone ViewModel
 * or as a delegate within the main ChatViewModel.
 */
class ComposerViewModel : ViewModel() {

    private val _state = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    fun onEvent(event: ComposerEvent) {
        when (event) {
            // Text Input
            is ComposerEvent.TextChanged -> {
                _state.update { it.copy(text = event.text) }
            }
            is ComposerEvent.TextFieldFocusChanged -> {
                _state.update { it.copy(isTextFieldFocused = event.isFocused) }
            }
            is ComposerEvent.CursorPositionChanged -> {
                _state.update { it.copy(cursorPosition = event.position) }
            }

            // Send Mode
            is ComposerEvent.ToggleSendMode -> {
                _state.update { it.copy(sendMode = event.newMode) }
            }

            // Panels
            is ComposerEvent.ToggleMediaPicker -> {
                togglePanel(ComposerPanel.MediaPicker)
            }
            is ComposerEvent.ToggleEmojiPicker -> {
                togglePanel(ComposerPanel.EmojiKeyboard)
            }
            is ComposerEvent.ToggleGifPicker -> {
                togglePanel(ComposerPanel.GifPicker)
            }
            is ComposerEvent.DismissPanel -> {
                _state.update { it.copy(activePanel = ComposerPanel.None) }
            }

            // Attachments
            is ComposerEvent.RemoveAttachment -> {
                _state.update { 
                    val newAttachments = it.attachments.filter { item -> item.id != event.attachment.id }
                    it.copy(attachments = newAttachments)
                }
            }
            is ComposerEvent.ClearAllAttachments -> {
                _state.update { it.copy(attachments = emptyList()) }
            }

            // Reply
            is ComposerEvent.DismissReply -> {
                _state.update { it.copy(replyToMessage = null) }
            }

            // Smart Replies
            is ComposerEvent.SelectSmartReply -> {
                // When selecting a smart reply, we usually send it immediately or populate text
                // For now, let's populate text
                _state.update { it.copy(text = event.reply) }
            }

            // Placeholder for other events that might need side effects or external handling
            // These might be handled by the parent ChatViewModel or side effects
            is ComposerEvent.Send -> {
                // Logic to send message
                _state.update { it.copy(isSending = true) }
            }
            is ComposerEvent.StartVoiceRecording -> {
                _state.update { it.copy(inputMode = ComposerInputMode.VOICE_RECORDING) }
            }
            is ComposerEvent.StopVoiceRecording -> {
                _state.update { it.copy(inputMode = ComposerInputMode.TEXT) }
            }
            is ComposerEvent.CancelVoiceRecording -> {
                _state.update { it.copy(inputMode = ComposerInputMode.TEXT) }
            }
            
            else -> {
                // Handle other events or ignore
            }
        }
    }

    private fun togglePanel(panel: ComposerPanel) {
        _state.update { 
            if (it.activePanel == panel) {
                it.copy(activePanel = ComposerPanel.None)
            } else {
                it.copy(activePanel = panel)
            }
        }
    }
}
