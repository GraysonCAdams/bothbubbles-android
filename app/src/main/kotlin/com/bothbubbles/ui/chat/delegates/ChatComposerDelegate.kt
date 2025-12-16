package com.bothbubbles.ui.chat.delegates

import android.net.Uri
import android.util.Log
import com.bothbubbles.data.local.db.entity.QuickReplyTemplateEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.GifRepository
import com.bothbubbles.data.repository.QuickReplyTemplateRepository
import com.bothbubbles.services.contacts.ContactData
import com.bothbubbles.services.contacts.FieldOptions
import com.bothbubbles.services.contacts.VCardExporter
import com.bothbubbles.services.media.AttachmentLimitsProvider
import com.bothbubbles.services.messaging.MessageDeliveryMode
import com.bothbubbles.services.smartreply.SmartReplyService
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.chat.AttachmentWarning
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.ChatUiState
import com.bothbubbles.ui.chat.composer.AttachmentItem
import com.bothbubbles.ui.chat.composer.ComposerAttachmentWarning
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.chat.composer.ComposerPanel
import com.bothbubbles.ui.chat.composer.ComposerState
import com.bothbubbles.ui.chat.composer.MessagePreview
import com.bothbubbles.ui.chat.state.SendState
import com.bothbubbles.ui.chat.state.SyncState
import com.bothbubbles.ui.components.input.SuggestionItem
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.util.StableList
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Delegate that handles all composer-related state and logic for ChatViewModel.
 *
 * Responsibilities:
 * - Draft text state management
 * - Pending attachments (add, remove, reorder, edit)
 * - Attachment quality selection
 * - Composer panel state (MediaPicker, EmojiKeyboard, GifPicker)
 * - ComposerState derivation with memoization
 * - Composer event handling
 * - GIF picker integration
 * - vCard/contact attachment integration
 *
 * Uses AssistedInject to receive runtime parameters at construction time,
 * eliminating the need for a separate initialize() call.
 *
 * Phase 2: Uses SocketConnection interface instead of SocketService
 * for improved testability.
 */
@OptIn(FlowPreview::class)
class ChatComposerDelegate @AssistedInject constructor(
    private val attachmentRepository: AttachmentRepository,
    private val attachmentLimitsProvider: AttachmentLimitsProvider,
    private val gifRepository: GifRepository,
    private val vCardExporter: VCardExporter,
    private val settingsDataStore: SettingsDataStore,
    private val smartReplyService: SmartReplyService,
    private val quickReplyTemplateRepository: QuickReplyTemplateRepository,
    private val socketConnection: SocketConnection,
    private val chatRepository: ChatRepository,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val uiStateFlow: StateFlow<ChatUiState>,
    @Assisted private val syncStateFlow: StateFlow<SyncState>,
    @Assisted private val sendStateFlow: StateFlow<SendState>,
    @Assisted private val messagesStateFlow: StateFlow<StableList<MessageUiModel>>,
    @Assisted private val onUiStateUpdate: (ChatUiState.() -> ChatUiState) -> Unit
) {

    @AssistedFactory
    interface Factory {
        fun create(
            chatGuid: String,
            scope: CoroutineScope,
            uiStateFlow: StateFlow<ChatUiState>,
            syncStateFlow: StateFlow<SyncState>,
            sendStateFlow: StateFlow<SendState>,
            messagesStateFlow: StateFlow<StableList<MessageUiModel>>,
            onUiStateUpdate: (ChatUiState.() -> ChatUiState) -> Unit
        ): ChatComposerDelegate
    }

    companion object {
        private const val TAG = "ChatComposerDelegate"
    }

    // ============================================================================
    // TYPING INDICATOR STATE
    // ============================================================================

    private var typingDebounceJob: Job? = null
    private var isCurrentlyTyping = false
    private val typingDebounceMs = 3000L // 3 seconds after last keystroke to send stopped-typing
    private var lastStartedTypingTime = 0L
    private val typingCooldownMs = 500L // Min time between started-typing emissions

    // Cached settings for typing indicators (avoids suspend calls on every keystroke)
    @Volatile private var cachedPrivateApiEnabled = false
    @Volatile private var cachedTypingIndicatorsEnabled = false

    // ============================================================================
    // DRAFT PERSISTENCE
    // ============================================================================

    private var draftSaveJob: Job? = null
    private val draftSaveDebounceMs = 500L // Debounce draft saves to avoid excessive DB writes

    // ============================================================================
    // SMART REPLY STATE
    // ============================================================================

    private val _mlSuggestions = MutableStateFlow<List<String>>(emptyList())

    // ============================================================================
    // INTERNAL MUTABLE STATE
    // ============================================================================

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<PendingAttachmentInput>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachmentInput>> = _pendingAttachments.asStateFlow()

    private val _attachmentQuality = MutableStateFlow(AttachmentQuality.STANDARD)

    private val _activePanel = MutableStateFlow(ComposerPanel.None)
    val activePanel: StateFlow<ComposerPanel> = _activePanel.asStateFlow()

    // Remember quality setting
    private val _rememberQuality = MutableStateFlow(false)

    // ============================================================================
    // MEMOIZATION CACHES
    // For expensive transformations inside the combine block
    // ============================================================================

    @Volatile private var _cachedAttachmentItems: List<AttachmentItem> = emptyList()
    @Volatile private var _lastAttachmentInputs: List<PendingAttachmentInput>? = null
    @Volatile private var _lastAttachmentQuality: AttachmentQuality? = null
    @Volatile private var _cachedReplyPreview: MessagePreview? = null
    @Volatile private var _lastReplyMessageGuid: String? = null

    // ============================================================================
    // COMPOSER STATE
    // ============================================================================

    /**
     * Lightweight projection of ChatUiState containing only fields relevant to the composer.
     * This isolates the composer from unrelated UI state changes (messages, search, etc.).
     */
    private data class ComposerRelevantState(
        val replyToMessage: MessageUiModel? = null,
        val currentSendMode: ChatSendMode = ChatSendMode.SMS,
        val smsInputBlocked: Boolean = false,
        val isLocalSmsChat: Boolean = false,
        val isInSmsFallbackMode: Boolean = false,
        val attachmentWarning: AttachmentWarning? = null
    )

    // GIF picker state - delegated to repository
    val gifPickerState get() = gifRepository.state
    val gifSearchQuery get() = gifRepository.searchQuery

    // Combined state flow
    val state: StateFlow<ComposerState> = createComposerStateFlow()

    // Smart reply suggestions
    val smartReplySuggestions: StateFlow<List<SuggestionItem>> = combine(
        _mlSuggestions,
        quickReplyTemplateRepository.observeMostUsedTemplates(limit = 3)
    ) { mlSuggestions, templates ->
        getCombinedSuggestions(mlSuggestions, templates, maxTotal = 3)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Observe quality settings
        observeQualitySettings()
        // Observe typing indicator settings
        observeTypingIndicatorSettings()
        // Observe messages for smart reply generation
        observeSmartReplies()
        // Load draft text from database (once)
        loadDraftFromChat()
    }

    /**
     * Load draft text from the chat entity (runs once at initialization).
     */
    private fun loadDraftFromChat() {
        scope.launch {
            val chat = chatRepository.observeChat(chatGuid)
                .filterNotNull()
                .first()
            restoreDraftText(chat.textFieldText)
        }
    }

    /**
     * Creates the combined ComposerState flow with memoization.
     */
    private fun createComposerStateFlow(): StateFlow<ComposerState> {
        // Derived flow that extracts only composer-relevant fields
        val composerRelevantState: Flow<ComposerRelevantState> = combine(
            uiStateFlow,
            syncStateFlow
        ) { ui, sync ->
            ComposerRelevantState(
                replyToMessage = ui.replyToMessage,
                currentSendMode = ui.currentSendMode,
                smsInputBlocked = ui.smsInputBlocked,
                isLocalSmsChat = ui.isLocalSmsChat,
                isInSmsFallbackMode = sync.isInSmsFallbackMode,
                attachmentWarning = ui.attachmentWarning
            )
        }.distinctUntilChanged()

        // Combine all composer-related flows with memoization
        return combine(
            composerRelevantState,
            sendStateFlow.map { it.isSending }.distinctUntilChanged(),
            _draftText,
            _pendingAttachments,
            _attachmentQuality,
            _activePanel
        ) { values: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val relevant = values[0] as? ComposerRelevantState ?: ComposerRelevantState()
            val isSending = values[1] as? Boolean ?: false
            val text = values[2] as? String ?: ""
            val attachments = values[3] as? List<PendingAttachmentInput> ?: emptyList()
            val quality = values[4] as? AttachmentQuality ?: AttachmentQuality.STANDARD
            val panel = values[5] as? ComposerPanel ?: ComposerPanel.None

            // Memoized attachment transformation - only rebuild if inputs changed
            val attachmentItems = if (attachments === _lastAttachmentInputs && quality == _lastAttachmentQuality) {
                _cachedAttachmentItems
            } else {
                attachments.map {
                    AttachmentItem(
                        id = it.uri.toString(),
                        uri = it.uri,
                        mimeType = it.mimeType,
                        displayName = it.name,
                        sizeBytes = it.size,
                        quality = quality,
                        caption = it.caption
                    )
                }.also {
                    _cachedAttachmentItems = it
                    _lastAttachmentInputs = attachments
                    _lastAttachmentQuality = quality
                }
            }

            // Memoized MessagePreview transformation - only rebuild if reply target changed
            val replyPreview = relevant.replyToMessage?.let { msg ->
                if (msg.guid == _lastReplyMessageGuid && _cachedReplyPreview != null) {
                    _cachedReplyPreview
                } else {
                    MessagePreview.fromMessageUiModel(msg).also {
                        _cachedReplyPreview = it
                        _lastReplyMessageGuid = msg.guid
                    }
                }
            } ?: run {
                _cachedReplyPreview = null
                _lastReplyMessageGuid = null
                null
            }

            ComposerState(
                text = text,
                attachments = attachmentItems,
                attachmentWarning = relevant.attachmentWarning?.let { warning ->
                    ComposerAttachmentWarning(
                        message = warning.message,
                        isError = warning.isError,
                        suggestCompression = warning.suggestCompression,
                        affectedUri = warning.affectedUri
                    )
                },
                replyToMessage = replyPreview,
                sendMode = relevant.currentSendMode,
                isSending = isSending,
                smsInputBlocked = relevant.smsInputBlocked,
                isLocalSmsChat = relevant.isLocalSmsChat || relevant.isInSmsFallbackMode,
                currentImageQuality = quality,
                activePanel = panel
            )
        }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), ComposerState())
    }

    /**
     * Observe quality settings from DataStore.
     */
    private fun observeQualitySettings() {
        scope.launch {
            combine(
                settingsDataStore.defaultImageQuality,
                settingsDataStore.rememberLastQuality
            ) { qualityName, remember -> Pair(qualityName, remember) }
                .collect { (qualityName, remember) ->
                    val quality = try {
                        AttachmentQuality.valueOf(qualityName)
                    } catch (e: IllegalArgumentException) {
                        AttachmentQuality.STANDARD
                    }
                    _attachmentQuality.value = quality
                    _rememberQuality.value = remember
                    onUiStateUpdate { copy(attachmentQuality = quality, rememberQuality = remember) }
                }
        }
    }

    // ============================================================================
    // COMPOSER EVENT HANDLING
    // ============================================================================

    /**
     * Handle composer UI events.
     *
     * @param event The composer event to handle
     * @param onSend Callback when send is requested
     * @param onToggleSendMode Callback when send mode toggle is requested
     * @param onDismissReply Callback when reply dismissal is requested
     */
    fun onComposerEvent(
        event: ComposerEvent,
        onSend: () -> Unit = {},
        onToggleSendMode: (ChatSendMode) -> Unit = {},
        onDismissReply: () -> Unit = {}
    ) {
        when (event) {
            is ComposerEvent.TextChanged -> {
                // Log.d("PerfTrace", "TextChanged event received, text length=${event.text.length}")
                _draftText.value = event.text
            }
            is ComposerEvent.AddAttachments -> {
                addAttachments(event.uris)
            }
            is ComposerEvent.RemoveAttachment -> {
                removeAttachment(event.attachment.uri)
            }
            is ComposerEvent.ClearAllAttachments -> {
                clearAttachments()
            }
            is ComposerEvent.Send -> {
                onSend()
            }
            is ComposerEvent.ToggleSendMode -> {
                onToggleSendMode(event.newMode)
            }
            is ComposerEvent.DismissReply -> {
                onDismissReply()
            }
            is ComposerEvent.ToggleMediaPicker -> {
                _activePanel.update { if (it == ComposerPanel.MediaPicker) ComposerPanel.None else ComposerPanel.MediaPicker }
            }
            is ComposerEvent.ToggleEmojiPicker -> {
                _activePanel.update { if (it == ComposerPanel.EmojiKeyboard) ComposerPanel.None else ComposerPanel.EmojiKeyboard }
            }
            is ComposerEvent.ToggleGifPicker -> {
                _activePanel.update { if (it == ComposerPanel.GifPicker) ComposerPanel.None else ComposerPanel.GifPicker }
            }
            is ComposerEvent.OpenGallery -> {
                _activePanel.update { ComposerPanel.MediaPicker }
            }
            is ComposerEvent.DismissPanel -> {
                _activePanel.update { ComposerPanel.None }
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
                // Handle other events or ignore
            }
        }
    }

    // ============================================================================
    // DRAFT TEXT
    // ============================================================================

    /**
     * Update the draft text.
     */
    fun setDraftText(text: String) {
        _draftText.value = text
    }

    /**
     * Clear the draft text.
     */
    fun clearDraftText() {
        _draftText.value = ""
    }

    /**
     * Append text to the current draft (e.g., for emoji insertion).
     * PERF: This reads the current value internally, avoiding state reads in parent composables.
     */
    fun appendToDraft(text: String) {
        _draftText.value = _draftText.value + text
    }

    /**
     * Restore draft text (e.g., from database).
     */
    fun restoreDraftText(text: String?) {
        _draftText.value = text ?: ""
    }

    /**
     * Update draft text with typing indicator and persistence.
     * This is the main entry point for text changes from the UI.
     */
    fun updateDraft(text: String) {
        setDraftText(text)
        handleTypingIndicator(text)
        persistDraft(text)
    }

    /**
     * Persist draft to database with debouncing to avoid excessive writes.
     */
    private fun persistDraft(text: String) {
        draftSaveJob?.cancel()
        draftSaveJob = scope.launch {
            delay(draftSaveDebounceMs)
            chatRepository.updateDraftText(chatGuid, text)
        }
    }

    /**
     * Save draft immediately (called when leaving chat).
     */
    fun saveDraftImmediately() {
        draftSaveJob?.cancel()
        scope.launch {
            chatRepository.updateDraftText(chatGuid, _draftText.value)
        }
    }

    /**
     * Clear draft from database (called when message is sent).
     */
    fun clearDraftFromDatabase() {
        draftSaveJob?.cancel()
        scope.launch {
            chatRepository.updateDraftText(chatGuid, null)
        }
    }

    // ============================================================================
    // ATTACHMENTS
    // ============================================================================

    /**
     * Add a single attachment.
     */
    fun addAttachment(uri: Uri) {
        addAttachments(listOf(uri))
    }

    /**
     * Add multiple attachments with validation.
     */
    fun addAttachments(uris: List<Uri>) {
        scope.launch {
            val currentAttachments = _pendingAttachments.value
            val currentState = uiStateFlow.value
            val currentSendMode = currentState.currentSendMode
            val isLocalSms = currentState.isLocalSmsChat

            // Determine delivery mode for validation
            val deliveryMode = when {
                isLocalSms -> MessageDeliveryMode.LOCAL_MMS
                currentSendMode == ChatSendMode.SMS -> MessageDeliveryMode.LOCAL_MMS
                currentSendMode == ChatSendMode.IMESSAGE -> MessageDeliveryMode.IMESSAGE
                else -> MessageDeliveryMode.AUTO
            }

            // Calculate existing total size
            val existingTotalSize = currentAttachments.sumOf { it.size ?: 0L }

            val newAttachments = mutableListOf<PendingAttachmentInput>()
            var currentTotalSize = existingTotalSize
            var currentCount = currentAttachments.size
            var lastWarning: AttachmentWarning? = null

            for (uri in uris) {
                // Get file size for new attachment
                val newFileSize = try {
                    attachmentRepository.getAttachmentSize(uri) ?: 0L
                } catch (e: Exception) {
                    Log.w(TAG, "Could not determine attachment size", e)
                    0L
                }

                // Get mime type and name
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

                // Validate
                val validation = attachmentLimitsProvider.validateAttachment(
                    sizeBytes = newFileSize,
                    deliveryMode = deliveryMode,
                    existingTotalSize = currentTotalSize,
                    existingCount = currentCount
                )

                val warning = when {
                    !validation.isValid -> AttachmentWarning(
                        message = validation.message ?: "Attachment too large",
                        isError = true,
                        suggestCompression = validation.suggestCompression,
                        affectedUri = uri
                    )
                    validation.warning != null -> AttachmentWarning(
                        message = validation.warning,
                        isError = false,
                        suggestCompression = false,
                        affectedUri = uri
                    )
                    else -> null
                }

                if (warning != null && warning.isError) {
                    lastWarning = warning
                    continue // Skip adding this one if it's an error
                } else if (warning != null) {
                    lastWarning = warning
                }

                newAttachments.add(PendingAttachmentInput(uri, mimeType = mimeType, name = name, size = newFileSize))
                currentTotalSize += newFileSize
                currentCount++
            }

            if (newAttachments.isNotEmpty()) {
                _pendingAttachments.update { it + newAttachments }
            }

            onUiStateUpdate {
                copy(
                    attachmentCount = _pendingAttachments.value.size,
                    attachmentWarning = lastWarning
                )
            }
        }
    }

    /**
     * Remove an attachment by URI.
     */
    fun removeAttachment(uri: Uri) {
        _pendingAttachments.update { list -> list.filter { it.uri != uri } }
        // Clear warning if removing the attachment that caused the issue
        val currentWarning = uiStateFlow.value.attachmentWarning
        val clearWarning = currentWarning?.affectedUri == uri
        onUiStateUpdate {
            copy(
                attachmentCount = _pendingAttachments.value.size,
                attachmentWarning = if (clearWarning) null else attachmentWarning
            )
        }
    }

    /**
     * Reorder attachments based on drag-and-drop result.
     */
    fun reorderAttachments(reorderedList: List<PendingAttachmentInput>) {
        _pendingAttachments.update { reorderedList }
    }

    /**
     * Update an attachment after editing (e.g., image crop).
     */
    fun onAttachmentEdited(originalUri: Uri, editedUri: Uri, caption: String? = null) {
        _pendingAttachments.update { list ->
            list.map { input ->
                if (input.uri == originalUri) {
                    input.copy(uri = editedUri, caption = caption)
                } else {
                    input
                }
            }
        }
    }

    /**
     * Clear all attachments.
     */
    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
        onUiStateUpdate { copy(attachmentCount = 0, attachmentWarning = null) }
    }

    /**
     * Clear input state (text and attachments) after sending.
     */
    fun clearInput() {
        _draftText.value = ""
        _pendingAttachments.value = emptyList()
        onUiStateUpdate { copy(attachmentCount = 0) }
    }

    /**
     * Dismiss the attachment warning without removing the attachment.
     */
    fun dismissAttachmentWarning() {
        onUiStateUpdate { copy(attachmentWarning = null) }
    }

    // ============================================================================
    // ATTACHMENT QUALITY
    // ============================================================================

    /**
     * Update the attachment quality for the current session.
     * If "remember last quality" is enabled, this also updates the global default.
     */
    fun setAttachmentQuality(quality: AttachmentQuality) {
        _attachmentQuality.value = quality
        onUiStateUpdate { copy(attachmentQuality = quality) }

        if (_rememberQuality.value) {
            scope.launch {
                settingsDataStore.setDefaultImageQuality(quality.name)
            }
        }
    }

    // ============================================================================
    // GIF PICKER
    // ============================================================================

    /**
     * Update the GIF search query.
     */
    fun updateGifSearchQuery(query: String) {
        gifRepository.updateSearchQuery(query)
    }

    /**
     * Search for GIFs.
     */
    fun searchGifs(query: String) {
        scope.launch {
            gifRepository.search(query)
        }
    }

    /**
     * Load featured GIFs.
     */
    fun loadFeaturedGifs() {
        scope.launch {
            gifRepository.loadFeatured()
        }
    }

    /**
     * Select a GIF and add it as an attachment.
     */
    fun selectGif(gif: com.bothbubbles.ui.chat.composer.panels.GifItem) {
        scope.launch {
            val fileUri = gifRepository.downloadGif(gif)
            fileUri?.let { addAttachment(it) }
        }
    }

    // ============================================================================
    // CONTACT/VCARD
    // ============================================================================

    /**
     * Gets contact data from a contact URI for preview in options dialog.
     * Returns null if the contact cannot be read.
     */
    fun getContactData(contactUri: Uri): ContactData? {
        return vCardExporter.getContactData(contactUri)
    }

    /**
     * Adds a contact as a vCard attachment directly from a contact picker URI.
     * Uses default options (includes all fields).
     */
    fun addContactFromPicker(contactUri: Uri) {
        val contactData = vCardExporter.getContactData(contactUri) ?: return
        val defaultOptions = FieldOptions()
        addContactAsVCard(contactData, defaultOptions)
    }

    /**
     * Creates a vCard file from contact data with field options and adds it as an attachment.
     * Returns true if successful, false otherwise.
     */
    fun addContactAsVCard(contactData: ContactData, options: FieldOptions): Boolean {
        val vcardUri = vCardExporter.createVCardFromContactData(contactData, options)
        return if (vcardUri != null) {
            addAttachment(vcardUri)
            true
        } else {
            false
        }
    }

    // ============================================================================
    // PANEL MANAGEMENT
    // ============================================================================

    /**
     * Set the active composer panel.
     */
    fun setActivePanel(panel: ComposerPanel) {
        _activePanel.value = panel
    }

    /**
     * Dismiss the active panel.
     */
    fun dismissPanel() {
        _activePanel.value = ComposerPanel.None
    }

    // ============================================================================
    // TYPING INDICATORS
    // ============================================================================

    /**
     * Observe typing indicator settings and cache them for fast access.
     * This avoids suspend calls on every keystroke in handleTypingIndicator.
     */
    private fun observeTypingIndicatorSettings() {
        scope.launch {
            combine(
                settingsDataStore.enablePrivateApi,
                settingsDataStore.sendTypingIndicators
            ) { privateApi, typingIndicators -> Pair(privateApi, typingIndicators) }
                .collect { (privateApi, typingIndicators) ->
                    cachedPrivateApiEnabled = privateApi
                    cachedTypingIndicatorsEnabled = typingIndicators
                }
        }
    }

    /**
     * Handle typing indicator logic with debouncing and rate limiting.
     *
     * Optimizations:
     * 1. Uses cached settings (no suspend calls on every keystroke)
     * 2. Only sends started-typing once until stopped-typing is sent
     * 3. Rate limits started-typing to avoid rapid on/off transitions
     * 4. Debounces stopped-typing (3 seconds after last keystroke)
     */
    fun handleTypingIndicator(text: String) {
        // Only send typing indicators for iMessage chats (not local SMS)
        if (uiStateFlow.value.isLocalSmsChat) return

        // Use cached settings (no suspend required)
        if (!cachedPrivateApiEnabled || !cachedTypingIndicatorsEnabled) return

        // Cancel any pending stopped-typing
        typingDebounceJob?.cancel()

        if (text.isNotEmpty()) {
            // User is typing - send started-typing if not already sent
            // Also apply cooldown to avoid rapid started/stopped/started transitions
            val now = System.currentTimeMillis()
            if (!isCurrentlyTyping && (now - lastStartedTypingTime > typingCooldownMs)) {
                isCurrentlyTyping = true
                lastStartedTypingTime = now
                socketConnection.sendStartedTyping(chatGuid)
            }

            // Set up debounce to send stopped-typing after inactivity
            typingDebounceJob = scope.launch {
                delay(typingDebounceMs)
                if (isCurrentlyTyping) {
                    isCurrentlyTyping = false
                    socketConnection.sendStoppedTyping(chatGuid)
                }
            }
        } else {
            // Text cleared - immediately send stopped-typing
            if (isCurrentlyTyping) {
                isCurrentlyTyping = false
                socketConnection.sendStoppedTyping(chatGuid)
            }
        }
    }

    /**
     * Send stopped typing indicator immediately.
     * Called when leaving the chat or clearing text.
     */
    fun sendStoppedTyping() {
        typingDebounceJob?.cancel()
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            socketConnection.sendStoppedTyping(chatGuid)
        }
    }

    // ============================================================================
    // SMART REPLIES
    // ============================================================================

    /**
     * Observe messages and generate ML Kit smart reply suggestions.
     * Debounced to avoid excessive processing while scrolling.
     */
    private fun observeSmartReplies() {
        scope.launch {
            messagesStateFlow
                .debounce(500)  // Wait for conversation to settle
                .collect { messages ->
                    val suggestions = smartReplyService.getSuggestions(messages, maxSuggestions = 3)
                    _mlSuggestions.value = suggestions
                }
        }
    }

    /**
     * Combine ML suggestions and user templates into max N suggestions.
     * ML suggestions appear first (more contextual), user templates fill remaining slots.
     */
    private fun getCombinedSuggestions(
        mlSuggestions: List<String>,
        userTemplates: List<QuickReplyTemplateEntity>,
        maxTotal: Int
    ): List<SuggestionItem> {
        val combined = mutableListOf<SuggestionItem>()

        // Add ML suggestions first (most contextual)
        mlSuggestions.take(maxTotal).forEach { text ->
            combined.add(SuggestionItem(text = text, isSmartSuggestion = true))
        }

        // Fill remaining slots with user templates
        val remaining = maxTotal - combined.size
        userTemplates.take(remaining).forEach { template ->
            combined.add(SuggestionItem(
                text = template.title,
                isSmartSuggestion = false,
                templateId = template.id
            ))
        }

        return combined
    }

    /**
     * Record usage of a user template (for "most used" sorting).
     */
    fun recordTemplateUsage(templateId: Long) {
        scope.launch {
            quickReplyTemplateRepository.recordUsage(templateId)
        }
    }
}
