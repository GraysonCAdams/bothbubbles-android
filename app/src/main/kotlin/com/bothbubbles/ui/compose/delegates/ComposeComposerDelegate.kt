package com.bothbubbles.ui.compose.delegates

import android.net.Uri
import timber.log.Timber
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.GifRepository
import com.bothbubbles.services.media.AttachmentLimitsProvider
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.AttachmentItem
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.chat.composer.ComposerInputMode
import com.bothbubbles.ui.chat.composer.ComposerPanel
import com.bothbubbles.ui.chat.composer.ComposerState
import com.bothbubbles.ui.chat.composer.panels.GifItem
import com.bothbubbles.ui.chat.composer.panels.GifPickerState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Simplified composer delegate for the ComposeScreen.
 *
 * Unlike ChatComposerDelegate, this doesn't need:
 * - Typing indicators (no chat selected yet)
 * - Draft persistence to specific chat
 * - Reply-to functionality
 * - Mentions (no group chat context yet)
 *
 * It provides the core composer functionality:
 * - Text input
 * - Attachments (add, remove, reorder)
 * - Panel management (media picker, emoji, GIF)
 * - Send mode (iMessage/SMS)
 */
class ComposeComposerDelegate @Inject constructor(
    private val attachmentRepository: AttachmentRepository,
    private val attachmentLimitsProvider: AttachmentLimitsProvider,
    private val gifRepository: GifRepository
) {
    private var scope: CoroutineScope? = null

    // Core state
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<PendingAttachmentInput>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachmentInput>> = _pendingAttachments.asStateFlow()

    private val _activePanel = MutableStateFlow(ComposerPanel.None)
    val activePanel: StateFlow<ComposerPanel> = _activePanel.asStateFlow()

    private val _sendMode = MutableStateFlow(ChatSendMode.IMESSAGE)
    val sendMode: StateFlow<ChatSendMode> = _sendMode.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _isTextFieldFocused = MutableStateFlow(false)
    private val _attachmentQuality = MutableStateFlow(AttachmentQuality.STANDARD)

    // GIF picker state - delegated to repository
    val gifPickerState: StateFlow<GifPickerState> get() = gifRepository.state
    val gifSearchQuery: StateFlow<String> get() = gifRepository.searchQuery

    // Combined composer state
    private lateinit var _composerState: StateFlow<ComposerState>
    val composerState: StateFlow<ComposerState> get() = _composerState

    /**
     * Initialize the delegate with a coroutine scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        _composerState = createComposerStateFlow(scope)
    }

    private fun createComposerStateFlow(scope: CoroutineScope): StateFlow<ComposerState> {
        return combine(
            _text,
            _pendingAttachments,
            _activePanel,
            _sendMode,
            _isSending,
            _isTextFieldFocused,
            _attachmentQuality
        ) { values: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val text = values[0] as? String ?: ""
            val attachments = values[1] as? List<PendingAttachmentInput> ?: emptyList()
            val panel = values[2] as? ComposerPanel ?: ComposerPanel.None
            val mode = values[3] as? ChatSendMode ?: ChatSendMode.IMESSAGE
            val sending = values[4] as? Boolean ?: false
            val focused = values[5] as? Boolean ?: false
            val quality = values[6] as? AttachmentQuality ?: AttachmentQuality.STANDARD

            val attachmentItems = attachments.map {
                AttachmentItem(
                    id = it.uri.toString(),
                    uri = it.uri,
                    mimeType = it.mimeType,
                    displayName = it.name,
                    sizeBytes = it.size,
                    quality = quality,
                    caption = it.caption
                )
            }

            ComposerState(
                text = text,
                isTextFieldFocused = focused,
                attachments = attachmentItems,
                sendMode = mode,
                isSending = sending,
                activePanel = panel,
                currentImageQuality = quality,
                inputMode = ComposerInputMode.TEXT,
                mentions = persistentListOf(),
                isGroupChat = false
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), ComposerState())
    }

    /**
     * Handle composer UI events.
     */
    fun onComposerEvent(
        event: ComposerEvent,
        onSend: () -> Unit = {}
    ) {
        when (event) {
            is ComposerEvent.TextChanged -> {
                // Protect against race condition: don't let empty text overwrite shared content
                // during initialization. This happens when ComposerTextField's EditText fires
                // a TextChanged('') before the StateFlow has propagated the shared content.
                if (event.text.isEmpty() && _text.value.isNotEmpty()) {
                    Timber.d("ComposeComposerDelegate: Ignoring empty TextChanged - preserving '${_text.value}'")
                    return
                }
                Timber.d("ComposeComposerDelegate: TextChanged to '${event.text}'")
                _text.value = event.text
            }
            is ComposerEvent.TextFieldFocusChanged -> {
                _isTextFieldFocused.value = event.isFocused
                if (event.isFocused && _activePanel.value != ComposerPanel.None) {
                    _activePanel.value = ComposerPanel.None
                }
            }
            is ComposerEvent.AddAttachments -> {
                addAttachments(event.uris)
            }
            is ComposerEvent.RemoveAttachment -> {
                removeAttachment(event.attachment.uri)
            }
            is ComposerEvent.ClearAllAttachments -> {
                _pendingAttachments.value = emptyList()
            }
            is ComposerEvent.Send -> {
                onSend()
            }
            is ComposerEvent.ToggleSendMode -> {
                _sendMode.value = event.newMode
            }
            is ComposerEvent.ToggleMediaPicker -> {
                _activePanel.update { current ->
                    when (current) {
                        ComposerPanel.MediaPicker, ComposerPanel.GifPicker -> ComposerPanel.None
                        else -> ComposerPanel.MediaPicker
                    }
                }
            }
            is ComposerEvent.ToggleEmojiPicker -> {
                _activePanel.update {
                    if (it == ComposerPanel.EmojiKeyboard) ComposerPanel.None
                    else ComposerPanel.EmojiKeyboard
                }
            }
            is ComposerEvent.ToggleGifPicker -> {
                _activePanel.update {
                    if (it == ComposerPanel.GifPicker) ComposerPanel.None
                    else ComposerPanel.GifPicker
                }
            }
            is ComposerEvent.OpenGallery -> {
                _activePanel.value = ComposerPanel.MediaPicker
            }
            is ComposerEvent.DismissPanel -> {
                _activePanel.value = ComposerPanel.None
            }
            is ComposerEvent.ReorderAttachments -> {
                val reorderedUris = event.attachments.map { it.uri }
                _pendingAttachments.update { currentList ->
                    reorderedUris.mapNotNull { uri ->
                        currentList.find { it.uri == uri }
                    }
                }
            }
            else -> {
                // Ignore other events not applicable to compose screen
            }
        }
    }

    /**
     * Add multiple attachments with validation.
     */
    private fun addAttachments(uris: List<Uri>) {
        scope?.launch {
            val currentAttachments = _pendingAttachments.value
            val deliveryMode = when (_sendMode.value) {
                ChatSendMode.SMS -> MessageDeliveryMode.LOCAL_MMS
                ChatSendMode.IMESSAGE -> MessageDeliveryMode.IMESSAGE
            }

            val existingTotalSize = currentAttachments.sumOf { it.size ?: 0L }
            val newAttachments = mutableListOf<PendingAttachmentInput>()
            var currentTotalSize = existingTotalSize
            var currentCount = currentAttachments.size

            for (uri in uris) {
                val newFileSize = try {
                    attachmentRepository.getAttachmentSize(uri) ?: 0L
                } catch (e: Exception) {
                    Timber.w(e, "Could not determine attachment size")
                    0L
                }

                val mimeType = try {
                    attachmentRepository.getMimeType(uri)
                } catch (e: Exception) {
                    null
                }

                val name = try {
                    attachmentRepository.getFileName(uri)
                } catch (e: Exception) {
                    null
                }

                val validation = attachmentLimitsProvider.validateAttachment(
                    sizeBytes = newFileSize,
                    deliveryMode = deliveryMode,
                    existingTotalSize = currentTotalSize,
                    existingCount = currentCount
                )

                if (validation.isValid) {
                    newAttachments.add(
                        PendingAttachmentInput(
                            uri = uri,
                            mimeType = mimeType,
                            name = name,
                            size = newFileSize
                        )
                    )
                    currentTotalSize += newFileSize
                    currentCount++
                } else {
                    Timber.w("Attachment validation failed: ${validation.message}")
                }
            }

            if (newAttachments.isNotEmpty()) {
                _pendingAttachments.update { it + newAttachments }
            }
        }
    }

    /**
     * Remove an attachment by URI.
     */
    private fun removeAttachment(uri: Uri) {
        _pendingAttachments.update { list -> list.filter { it.uri != uri } }
    }

    /**
     * Add a single attachment.
     */
    fun addAttachment(uri: Uri) {
        addAttachments(listOf(uri))
    }

    /**
     * Update send mode based on selected recipients.
     */
    fun updateSendMode(mode: ChatSendMode) {
        _sendMode.value = mode
    }

    /**
     * Set sending state.
     */
    fun setSending(sending: Boolean) {
        _isSending.value = sending
    }

    /**
     * Clear input state after sending.
     */
    fun clearInput() {
        _text.value = ""
        _pendingAttachments.value = emptyList()
    }

    /**
     * Get current text.
     */
    fun getText(): String = _text.value

    /**
     * Get current attachments.
     */
    fun getAttachments(): List<PendingAttachmentInput> = _pendingAttachments.value

    // GIF picker functions
    fun updateGifSearchQuery(query: String) {
        gifRepository.updateSearchQuery(query)
    }

    fun searchGifs(query: String) {
        scope?.launch {
            gifRepository.search(query)
        }
    }

    fun loadFeaturedGifs() {
        scope?.launch {
            gifRepository.loadFeatured()
        }
    }

    fun selectGif(gif: GifItem) {
        scope?.launch {
            val fileUri = gifRepository.downloadGif(gif)
            fileUri?.let { addAttachment(it) }
        }
    }
}
