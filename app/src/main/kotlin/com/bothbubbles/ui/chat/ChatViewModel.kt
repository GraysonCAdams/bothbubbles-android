package com.bothbubbles.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.BlockedNumberContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bothbubbles.data.local.db.dao.AttachmentDao
import com.bothbubbles.data.local.db.dao.ScheduledMessageDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ScheduledMessageEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.QuickReplyTemplateEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageDeliveryMode
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.QuickReplyTemplateRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.VCardService
import com.bothbubbles.services.messaging.ChatFallbackTracker
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.services.smartreply.SmartReplyService
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sound.SoundManager
import com.bothbubbles.services.scheduled.ScheduledMessageWorker
import com.bothbubbles.services.spam.SpamReportingService
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.ui.components.AttachmentUiModel
import com.bothbubbles.ui.components.MessageUiModel
import com.bothbubbles.ui.components.ReactionUiModel
import com.bothbubbles.ui.components.SuggestionItem
import com.bothbubbles.ui.components.Tapback
import com.bothbubbles.ui.effects.MessageEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val smsRepository: SmsRepository,
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore,
    private val chatFallbackTracker: ChatFallbackTracker,
    private val soundManager: SoundManager,
    private val spamRepository: SpamRepository,
    private val spamReportingService: SpamReportingService,
    private val attachmentDao: AttachmentDao,
    private val attachmentRepository: AttachmentRepository,
    private val androidContactsService: AndroidContactsService,
    private val vCardService: VCardService,
    private val smartReplyService: SmartReplyService,
    private val quickReplyTemplateRepository: QuickReplyTemplateRepository,
    private val scheduledMessageDao: ScheduledMessageDao,
    private val workManager: WorkManager
) : ViewModel() {

    private val chatGuid: String = checkNotNull(savedStateHandle["chatGuid"])

    // For merged conversations (iMessage + SMS), contains all chat GUIDs
    // If mergedGuids is null or has single entry, this is a regular (non-merged) chat
    private val mergedChatGuids: List<String> = run {
        val mergedGuidsStr: String? = savedStateHandle["mergedGuids"]
        if (mergedGuidsStr.isNullOrBlank()) {
            listOf(chatGuid)
        } else {
            mergedGuidsStr.split(",").filter { it.isNotBlank() }
        }
    }

    // True if this is a merged conversation with multiple underlying chats
    private val isMergedChat: Boolean = mergedChatGuids.size > 1

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<Uri>>(emptyList())

    // Attachment download progress tracking
    // Maps attachment GUID to download progress (0.0 to 1.0, or null if not downloading)
    private val _attachmentDownloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val attachmentDownloadProgress: StateFlow<Map<String, Float>> = _attachmentDownloadProgress.asStateFlow()

    // Whether to use auto-download mode (true) or manual download mode (false)
    private val _autoDownloadEnabled = MutableStateFlow(true)
    val autoDownloadEnabled: StateFlow<Boolean> = _autoDownloadEnabled.asStateFlow()

    // Smart reply suggestions (ML Kit + user templates, max 3)
    private val _mlSuggestions = MutableStateFlow<List<String>>(emptyList())

    val smartReplySuggestions: StateFlow<List<SuggestionItem>> = combine(
        _mlSuggestions,
        quickReplyTemplateRepository.observeMostUsedTemplates(limit = 3)
    ) { mlSuggestions, templates ->
        getCombinedSuggestions(mlSuggestions, templates, maxTotal = 3)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Typing indicator state
    private var typingDebounceJob: Job? = null
    private var isCurrentlyTyping = false
    private val typingDebounceMs = 3000L // 3 seconds after last keystroke to send stopped-typing

    // Draft persistence
    private var draftSaveJob: Job? = null
    private val draftSaveDebounceMs = 500L // Debounce draft saves to avoid excessive DB writes

    // Effect settings flows
    val autoPlayEffects = settingsDataStore.autoPlayEffects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val replayEffectsOnScroll = settingsDataStore.replayEffectsOnScroll
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val reduceMotion = settingsDataStore.reduceMotion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Screen effect state and queue
    data class ScreenEffectState(
        val effect: MessageEffect.Screen,
        val messageGuid: String,
        val messageText: String?
    )

    private val _activeScreenEffect = MutableStateFlow<ScreenEffectState?>(null)
    val activeScreenEffect: StateFlow<ScreenEffectState?> = _activeScreenEffect.asStateFlow()

    private val screenEffectQueue = mutableListOf<ScreenEffectState>()
    private var isPlayingScreenEffect = false

    init {
        loadChat()
        loadMessages()
        syncMessages()
        observeTypingIndicators()
        markAsRead()
        determineChatType()
        observeParticipantsForSaveContactBanner()
        observeFallbackMode()
        observeConnectionState()
        observeSmartReplies()
        observeAutoDownloadSetting()
    }

    /**
     * Observe the auto-download setting and trigger downloads when chat is opened.
     */
    private fun observeAutoDownloadSetting() {
        viewModelScope.launch {
            settingsDataStore.autoDownloadAttachments.collect { autoDownload ->
                _autoDownloadEnabled.value = autoDownload
                if (autoDownload) {
                    // Auto-download pending attachments for this chat
                    downloadPendingAttachments()
                }
            }
        }
    }

    /**
     * Download all pending attachments for this chat (or merged chats).
     * Called automatically when auto-download is enabled, or can be triggered manually.
     */
    private fun downloadPendingAttachments() {
        viewModelScope.launch {
            if (isMergedChat) {
                // Download from all merged chats
                attachmentRepository.downloadPendingForChats(mergedChatGuids) { downloaded, total ->
                    // Progress updates are handled per-attachment, this is overall progress
                }
            } else {
                attachmentRepository.downloadPendingForChat(chatGuid) { downloaded, total ->
                    // Progress updates are handled per-attachment, this is overall progress
                }
            }
        }
    }

    /**
     * Manually download a specific attachment.
     * Called when user taps on an attachment placeholder in manual download mode.
     */
    fun downloadAttachment(attachmentGuid: String) {
        viewModelScope.launch {
            // Mark as downloading
            _attachmentDownloadProgress.update { it + (attachmentGuid to 0f) }

            attachmentRepository.downloadAttachment(attachmentGuid) { progress ->
                _attachmentDownloadProgress.update { it + (attachmentGuid to progress) }
            }.fold(
                onSuccess = {
                    // Remove from progress map (download complete)
                    _attachmentDownloadProgress.update { it - attachmentGuid }
                },
                onFailure = { e ->
                    // Remove from progress map and show error
                    _attachmentDownloadProgress.update { it - attachmentGuid }
                    _uiState.update { it.copy(error = "Failed to download: ${e.message}") }
                }
            )
        }
    }

    /**
     * Check if an attachment is currently downloading.
     */
    fun isDownloading(attachmentGuid: String): Boolean {
        return attachmentGuid in _attachmentDownloadProgress.value
    }

    /**
     * Get download progress for an attachment (0.0 to 1.0).
     */
    fun getDownloadProgress(attachmentGuid: String): Float {
        return _attachmentDownloadProgress.value[attachmentGuid] ?: 0f
    }

    /**
     * Observe messages and generate ML Kit smart reply suggestions.
     * Debounced to avoid excessive processing while scrolling.
     */
    private fun observeSmartReplies() {
        viewModelScope.launch {
            _uiState.map { it.messages }
                .distinctUntilChanged()
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
        viewModelScope.launch {
            quickReplyTemplateRepository.recordUsage(templateId)
        }
    }

    private fun observeFallbackMode() {
        viewModelScope.launch {
            chatFallbackTracker.fallbackStates.collect { fallbackStates ->
                val entry = fallbackStates[chatGuid]
                _uiState.update {
                    it.copy(
                        isInSmsFallbackMode = entry != null,
                        fallbackReason = entry?.reason
                    )
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            socketService.connectionState.collect { state ->
                _uiState.update { it.copy(isServerConnected = state == ConnectionState.CONNECTED) }
            }
        }
    }

    private fun syncMessages() {
        if (messageRepository.isLocalSmsChat(chatGuid)) {
            syncSmsMessages()
        } else {
            syncMessagesFromServer()
        }
    }

    private fun syncSmsMessages() {
        viewModelScope.launch {
            smsRepository.importMessagesForChat(chatGuid, limit = 100).fold(
                onSuccess = { count ->
                    // Messages are now in Room and will be picked up by observeMessagesForChat
                },
                onFailure = { e ->
                    if (_uiState.value.messages.isEmpty()) {
                        _uiState.update { it.copy(error = "Failed to load SMS messages: ${e.message}") }
                    }
                }
            )
        }
    }

    private fun determineChatType() {
        val isLocalSms = messageRepository.isLocalSmsChat(chatGuid)
        val isServerForward = chatGuid.startsWith("SMS;", ignoreCase = true)
        _uiState.update {
            it.copy(
                isLocalSmsChat = isLocalSms,
                isIMessageChat = !(isLocalSms || isServerForward)
            )
        }
    }

    private fun observeParticipantsForSaveContactBanner() {
        viewModelScope.launch {
            // Combine chat info, participants, dismissed banners, and messages
            // This ensures we have the correct state before checking
            chatRepository.observeChat(chatGuid)
                .filterNotNull()
                .combine(chatRepository.observeParticipantsForChat(chatGuid)) { chat, participants ->
                    Triple(chat, participants, chat.isGroup)
                }
                .combine(settingsDataStore.dismissedSaveContactBanners) { (chat, participants, isGroup), dismissed ->
                    Triple(participants, isGroup, dismissed)
                }
                .combine(messageRepository.observeMessagesForChat(chatGuid, limit = 1, offset = 0)) { (participants, isGroup, dismissed), messages ->
                    // Check if there are any messages received from the other party (not from me)
                    val hasReceivedMessages = messages.any { !it.isFromMe }
                    object {
                        val participants = participants
                        val isGroup = isGroup
                        val dismissed = dismissed
                        val hasReceivedMessages = hasReceivedMessages
                    }
                }
                .collect { state ->
                    // Only show banner for 1-on-1 chats with unsaved contacts that have received messages
                    if (state.isGroup || !state.hasReceivedMessages) {
                        _uiState.update { it.copy(showSaveContactBanner = false, unsavedSenderAddress = null) }
                        return@collect
                    }

                    // For chats without participants in the cross-ref table,
                    // check if the chat title looks like a phone number (unsaved contact)
                    val chatTitle = _uiState.value.chatTitle
                    val participantPhone = _uiState.value.participantPhone

                    // Find the first unsaved participant (no cached display name)
                    val unsavedParticipant = state.participants.firstOrNull { participant ->
                        participant.cachedDisplayName == null &&
                            participant.address !in state.dismissed
                    }

                    // If we have an unsaved participant from the DB, use that
                    // Otherwise, check if the chat title looks like a phone/address (no contact name)
                    val unsavedAddress = when {
                        unsavedParticipant != null -> unsavedParticipant.address
                        state.participants.isEmpty() && participantPhone != null &&
                            participantPhone !in state.dismissed &&
                            looksLikePhoneOrAddress(chatTitle) -> participantPhone
                        else -> null
                    }

                    // Get inferred name from participant if available
                    val inferredName = unsavedParticipant?.inferredName

                    _uiState.update {
                        it.copy(
                            showSaveContactBanner = unsavedAddress != null,
                            unsavedSenderAddress = unsavedAddress,
                            inferredSenderName = inferredName
                        )
                    }
                }
        }
    }

    /**
     * Check if a string looks like a phone number or email address (not a contact name)
     */
    private fun looksLikePhoneOrAddress(text: String): Boolean {
        val trimmed = text.trim()
        // Check for phone number patterns (digits, spaces, dashes, parens, plus)
        val phonePattern = Regex("^[+]?[0-9\\s\\-().]+$")
        // Check for email pattern
        val emailPattern = Regex("^[^@]+@[^@]+\\.[^@]+$")
        return phonePattern.matches(trimmed) || emailPattern.matches(trimmed)
    }

    fun dismissSaveContactBanner() {
        val address = _uiState.value.unsavedSenderAddress ?: return
        viewModelScope.launch {
            settingsDataStore.dismissSaveContactBanner(address)
            _uiState.update { it.copy(showSaveContactBanner = false) }
        }
    }

    /**
     * Refresh contact info from device contacts.
     * Called when returning from the system contacts app after adding a contact.
     */
    fun refreshContactInfo() {
        val address = _uiState.value.unsavedSenderAddress
            ?: _uiState.value.participantPhone
            ?: return

        viewModelScope.launch {
            val displayName = androidContactsService.getContactDisplayName(address)
            if (displayName != null) {
                // Update the cached display name in the database
                chatRepository.updateHandleCachedContactInfo(address, displayName)
                // Hide the save contact banner since they saved the contact
                _uiState.update { it.copy(showSaveContactBanner = false) }
            }
        }
    }

    fun exitSmsFallback() {
        chatFallbackTracker.exitFallbackMode(chatGuid)
    }

    private fun loadChat() {
        viewModelScope.launch {
            var draftLoaded = false
            chatRepository.observeChat(chatGuid).collect { chat ->
                chat?.let {
                    _uiState.update { state ->
                        state.copy(
                            chatTitle = it.displayName ?: it.chatIdentifier ?: "Unknown",
                            isGroup = it.isGroup,
                            isArchived = it.isArchived,
                            isStarred = it.isStarred,
                            participantPhone = it.chatIdentifier,
                            isSpam = it.isSpam,
                            isReportedToCarrier = it.spamReportedToCarrier,
                            isSnoozed = it.isSnoozed,
                            snoozeUntil = it.snoozeUntil,
                            isLocalSmsChat = it.isLocalSms,
                            isIMessageChat = it.isIMessage,
                            // Load draft only on first observation to avoid overwriting user edits
                            draftText = if (!draftLoaded) it.textFieldText ?: "" else state.draftText
                        )
                    }
                    draftLoaded = true
                }
            }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            // For merged chats, observe messages from all chats
            val messagesFlow = if (isMergedChat) {
                messageRepository.observeMessagesForChats(mergedChatGuids, limit = 50, offset = 0)
            } else {
                messageRepository.observeMessagesForChat(chatGuid, limit = 50, offset = 0)
            }

            // For merged chats, combine participants from all chats
            val participantsFlow = if (isMergedChat) {
                // Observe participants from all merged chats and combine them
                combine(mergedChatGuids.map { guid -> chatRepository.observeParticipantsForChat(guid) }) { participantLists ->
                    participantLists.flatMap { it.toList() }.distinctBy { it.id }
                }
            } else {
                chatRepository.observeParticipantsForChat(chatGuid)
            }

            // Combine messages with participants to get sender names
            combine(
                messagesFlow,
                participantsFlow
            ) { messages, participants ->
                // Build a map from handleId to displayName for quick lookup
                val handleIdToName = participants.associate { it.id to it.displayName }
                Pair(messages, handleIdToName)
            }
                .distinctUntilChanged()
                .map { (messages, handleIdToName) ->
                    // Separate actual reactions (iMessage tapbacks)
                    val iMessageReactions = messages.filter { it.isReaction }

                    // Detect SMS-style reaction messages (e.g., 'Loved "Hello"')
                    val (smsReactionMessages, regularMessages) = messages
                        .filter { !it.isReaction }
                        .partition { msg ->
                            msg.text?.let { parseSmsReaction(it) } != null
                        }

                    // Build a map of message text to message GUID for matching SMS reactions
                    val textToGuid = regularMessages.associateBy(
                        keySelector = { it.text?.take(100) }, // Match first 100 chars
                        valueTransform = { it.guid }
                    )

                    // Convert SMS reaction messages to synthetic reaction entries
                    // Only include additions, not removals (removals are just filtered out)
                    val smsReactions = smsReactionMessages.mapNotNull { msg ->
                        val (tapback, quotedText, isRemoval) = parseSmsReaction(msg.text ?: "") ?: return@mapNotNull null
                        // Skip removal messages - they just get filtered from regular messages
                        if (isRemoval) return@mapNotNull null
                        // Find the original message by matching quoted text
                        val targetGuid = findOriginalMessageGuid(quotedText, regularMessages)
                        if (targetGuid != null) {
                            // Create a synthetic reaction entry
                            SyntheticReaction(
                                targetMessageGuid = targetGuid,
                                tapback = tapback,
                                isFromMe = msg.isFromMe,
                                senderName = null
                            )
                        } else null
                    }

                    // Group iMessage reactions by their associated message GUID
                    val reactionsByMessage = iMessageReactions.groupBy { it.associatedMessageGuid }

                    // Group SMS reactions by target message GUID
                    val smsReactionsByMessage = smsReactions.groupBy { it.targetMessageGuid }

                    regularMessages.map { message ->
                        val messageReactions = reactionsByMessage[message.guid].orEmpty()
                        val messageSmsReactions = smsReactionsByMessage[message.guid].orEmpty()
                        // Fetch attachments for this message
                        val attachments = attachmentDao.getAttachmentsForMessage(message.guid)
                        message.toUiModel(messageReactions, messageSmsReactions, attachments, handleIdToName)
                    }
                }
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

    /**
     * Parse an SMS-style reaction message.
     * Returns the Tapback type, quoted message text, and whether it's a removal.
     * Matches patterns like:
     *   - Adding: Loved "Hello there!"
     *   - Removing: Removed a heart from "Hello there!"
     */
    private fun parseSmsReaction(text: String): Triple<Tapback, String, Boolean>? {
        val trimmedText = text.trim()

        // Pattern for adding reactions: <Verb> "<quoted text>"
        val addPattern = Regex("""^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned)\s+"(.+)"$""")
        addPattern.find(trimmedText)?.let { match ->
            val verb = match.groupValues[1]
            val quotedText = match.groupValues[2]
            val tapback = when (verb) {
                "Loved" -> Tapback.LOVE
                "Liked" -> Tapback.LIKE
                "Disliked" -> Tapback.DISLIKE
                "Laughed at" -> Tapback.LAUGH
                "Emphasized" -> Tapback.EMPHASIZE
                "Questioned" -> Tapback.QUESTION
                else -> return null
            }
            return Triple(tapback, quotedText, false)
        }

        // Pattern for removing reactions: Removed a <type> from "<quoted text>"
        val removePattern = Regex("""^Removed a (heart|like|dislike|laugh|exclamation|question mark) from\s+"(.+)"$""")
        removePattern.find(trimmedText)?.let { match ->
            val type = match.groupValues[1]
            val quotedText = match.groupValues[2]
            val tapback = when (type) {
                "heart" -> Tapback.LOVE
                "like" -> Tapback.LIKE
                "dislike" -> Tapback.DISLIKE
                "laugh" -> Tapback.LAUGH
                "exclamation" -> Tapback.EMPHASIZE
                "question mark" -> Tapback.QUESTION
                else -> return null
            }
            return Triple(tapback, quotedText, true)
        }

        return null
    }

    /**
     * Find the original message GUID that matches the quoted text from an SMS reaction.
     */
    private fun findOriginalMessageGuid(quotedText: String, messages: List<MessageEntity>): String? {
        // Handle truncated quotes (ending with "...")
        val searchText = if (quotedText.endsWith("...")) {
            quotedText.dropLast(3)
        } else {
            quotedText
        }

        // Find a message that starts with or equals the quoted text
        return messages.find { msg ->
            msg.text?.startsWith(searchText) == true || msg.text == quotedText
        }?.guid
    }

    /**
     * Synthetic reaction parsed from SMS-style reaction text
     */
    private data class SyntheticReaction(
        val targetMessageGuid: String,
        val tapback: Tapback,
        val isFromMe: Boolean,
        val senderName: String?
    )

    private fun syncMessagesFromServer() {
        viewModelScope.launch {
            messageRepository.syncMessagesForChat(
                chatGuid = chatGuid,
                limit = 50
            ).onFailure { e ->
                // Only show error if we have no local messages
                if (_uiState.value.messages.isEmpty()) {
                    _uiState.update { it.copy(error = e.message) }
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
        handleTypingIndicator(text)
        persistDraft(text)
    }

    /**
     * Persist draft to database with debouncing to avoid excessive writes.
     */
    private fun persistDraft(text: String) {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(draftSaveDebounceMs)
            chatRepository.updateDraftText(chatGuid, text)
        }
    }

    /**
     * Handle typing indicator logic with debouncing.
     * Sends started-typing when user starts typing and stopped-typing after 3 seconds of inactivity.
     */
    private fun handleTypingIndicator(text: String) {
        // Only send typing indicators for iMessage chats (not local SMS)
        if (_uiState.value.isLocalSmsChat) return

        viewModelScope.launch {
            // Check if Private API and typing indicators are enabled
            val privateApiEnabled = settingsDataStore.enablePrivateApi.first()
            val typingEnabled = settingsDataStore.sendTypingIndicators.first()

            if (!privateApiEnabled || !typingEnabled) return@launch

            // Cancel any pending stopped-typing
            typingDebounceJob?.cancel()

            if (text.isNotEmpty()) {
                // User is typing - send started-typing if not already sent
                if (!isCurrentlyTyping) {
                    isCurrentlyTyping = true
                    socketService.sendStartedTyping(chatGuid)
                }

                // Set up debounce to send stopped-typing after inactivity
                typingDebounceJob = viewModelScope.launch {
                    delay(typingDebounceMs)
                    if (isCurrentlyTyping) {
                        isCurrentlyTyping = false
                        socketService.sendStoppedTyping(chatGuid)
                    }
                }
            } else {
                // Text cleared - immediately send stopped-typing
                if (isCurrentlyTyping) {
                    isCurrentlyTyping = false
                    socketService.sendStoppedTyping(chatGuid)
                }
            }
        }
    }

    /**
     * Called when leaving the chat to ensure we send stopped-typing and save draft
     */
    fun onChatLeave() {
        typingDebounceJob?.cancel()
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            socketService.sendStoppedTyping(chatGuid)
        }
        // Save draft immediately when leaving (cancel debounce and save now)
        draftSaveJob?.cancel()
        viewModelScope.launch {
            chatRepository.updateDraftText(chatGuid, _uiState.value.draftText)
        }
    }

    override fun onCleared() {
        super.onCleared()
        onChatLeave()
    }

    fun addAttachment(uri: Uri) {
        _pendingAttachments.update { it + uri }
        _uiState.update { it.copy(attachmentCount = _pendingAttachments.value.size) }
    }

    /**
     * Gets contact data from a contact URI for preview in options dialog.
     * Returns null if the contact cannot be read.
     */
    fun getContactData(contactUri: Uri): VCardService.ContactData? {
        return vCardService.getContactData(contactUri)
    }

    /**
     * Creates a vCard file from contact data with field options and adds it as an attachment.
     * Returns true if successful, false otherwise.
     */
    fun addContactAsVCard(contactData: VCardService.ContactData, options: VCardService.FieldOptions): Boolean {
        val vcardUri = vCardService.createVCardFromContactData(contactData, options)
        return if (vcardUri != null) {
            addAttachment(vcardUri)
            true
        } else {
            false
        }
    }

    fun removeAttachment(uri: Uri) {
        _pendingAttachments.update { it - uri }
        _uiState.update { it.copy(attachmentCount = _pendingAttachments.value.size) }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
        _uiState.update { it.copy(attachmentCount = 0) }
    }

    fun sendMessage(effectId: String? = null) {
        val text = _uiState.value.draftText.trim()
        val attachments = _pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        // Stop typing indicator immediately when sending
        typingDebounceJob?.cancel()
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            socketService.sendStoppedTyping(chatGuid)
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, draftText = "") }
            _pendingAttachments.value = emptyList()
            // Clear draft from database when message is sent
            draftSaveJob?.cancel()
            chatRepository.updateDraftText(chatGuid, null)

            // Use unified send which auto-routes to iMessage or local SMS/MMS
            messageRepository.sendUnified(
                chatGuid = chatGuid,
                text = text,
                attachments = attachments,
                effectId = effectId
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSending = false, attachmentCount = 0) }
                    soundManager.playSendSound()
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
            // Clear draft from database when message is sent
            draftSaveJob?.cancel()
            chatRepository.updateDraftText(chatGuid, null)

            messageRepository.sendUnified(
                chatGuid = chatGuid,
                text = text,
                attachments = attachments,
                deliveryMode = deliveryMode
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSending = false, attachmentCount = 0) }
                    soundManager.playSendSound()
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
     * Toggle a reaction on a message.
     * For iMessage: Uses native tapback API (adds or removes reaction).
     * For SMS/MMS: Sends a text message like 'Loved "message text"' that iPhones understand.
     */
    fun toggleReaction(messageGuid: String, tapback: Tapback) {
        val message = _uiState.value.messages.find { it.guid == messageGuid } ?: return
        val isLocalSms = _uiState.value.isLocalSmsChat
        val isRemoving = tapback in message.myReactions

        viewModelScope.launch {
            if (isLocalSms) {
                // For SMS chats, send a text message with the reaction
                // Format understood by iPhones: 'Loved "Hello"' or 'Removed a heart from "Hello"'
                val originalText = message.text ?: return@launch
                val reactionText = if (isRemoving) {
                    tapback.toSmsRemovalText(originalText)
                } else {
                    tapback.toSmsText(originalText)
                }

                messageRepository.sendUnified(
                    chatGuid = chatGuid,
                    text = reactionText,
                    deliveryMode = MessageDeliveryMode.AUTO
                )
            } else {
                // For iMessage, use the native reaction API
                if (isRemoving) {
                    // Remove the reaction
                    messageRepository.removeReaction(
                        chatGuid = chatGuid,
                        messageGuid = messageGuid,
                        reaction = tapback.apiName
                    )
                } else {
                    // Add the reaction
                    messageRepository.sendReaction(
                        chatGuid = chatGuid,
                        messageGuid = messageGuid,
                        reaction = tapback.apiName
                    )
                }
            }
        }
    }

    fun retryMessage(messageGuid: String) {
        viewModelScope.launch {
            messageRepository.retryMessage(messageGuid)
        }
    }

    /**
     * Retry a failed iMessage as SMS/MMS
     */
    fun retryMessageAsSms(messageGuid: String) {
        viewModelScope.launch {
            messageRepository.retryAsSms(messageGuid).fold(
                onSuccess = {
                    // Message was successfully sent via SMS
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
            )
        }
    }

    /**
     * Forward a message to another conversation.
     */
    fun forwardMessage(messageGuid: String, targetChatGuid: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isForwarding = true) }
            messageRepository.forwardMessage(messageGuid, targetChatGuid).fold(
                onSuccess = {
                    _uiState.update { it.copy(isForwarding = false, forwardSuccess = true) }
                    soundManager.playSendSound()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isForwarding = false, error = "Failed to forward: ${e.message}") }
                }
            )
        }
    }

    /**
     * Get all available chats for forwarding (excluding current chat).
     */
    fun getForwardableChats(): Flow<List<com.bothbubbles.data.local.db.entity.ChatEntity>> {
        return chatRepository.observeActiveChats()
            .map { chats -> chats.filter { it.guid != chatGuid } }
    }

    /**
     * Clear the forward success flag.
     */
    fun clearForwardSuccess() {
        _uiState.update { it.copy(forwardSuccess = false) }
    }

    /**
     * Check if a failed message can be retried as SMS
     */
    suspend fun canRetryAsSms(messageGuid: String): Boolean {
        return messageRepository.canRetryAsSms(messageGuid)
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

    // ===== Menu Actions =====

    fun archiveChat() {
        viewModelScope.launch {
            chatRepository.setArchived(chatGuid, true).fold(
                onSuccess = {
                    _uiState.update { it.copy(isArchived = true) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
            )
        }
    }

    fun unarchiveChat() {
        viewModelScope.launch {
            chatRepository.setArchived(chatGuid, false).fold(
                onSuccess = {
                    _uiState.update { it.copy(isArchived = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
            )
        }
    }

    fun toggleStarred() {
        val currentStarred = _uiState.value.isStarred
        viewModelScope.launch {
            chatRepository.setStarred(chatGuid, !currentStarred).fold(
                onSuccess = {
                    _uiState.update { it.copy(isStarred = !currentStarred) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
            )
        }
    }

    fun deleteChat() {
        viewModelScope.launch {
            chatRepository.deleteChat(chatGuid).fold(
                onSuccess = {
                    _uiState.update { it.copy(chatDeleted = true) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
            )
        }
    }

    fun toggleSubjectField() {
        _uiState.update { it.copy(showSubjectField = !it.showSubjectField) }
    }

    // ===== Inline Search =====

    fun activateSearch() {
        _uiState.update { it.copy(isSearchActive = true, searchQuery = "", searchMatchIndices = emptyList(), currentSearchMatchIndex = -1) }
    }

    fun closeSearch() {
        _uiState.update { it.copy(isSearchActive = false, searchQuery = "", searchMatchIndices = emptyList(), currentSearchMatchIndex = -1) }
    }

    fun updateSearchQuery(query: String) {
        val messages = _uiState.value.messages
        val matchIndices = if (query.isBlank()) {
            emptyList()
        } else {
            messages.mapIndexedNotNull { index, message ->
                if (message.text?.contains(query, ignoreCase = true) == true) index else null
            }
        }
        val currentIndex = if (matchIndices.isNotEmpty()) 0 else -1
        _uiState.update { it.copy(searchQuery = query, searchMatchIndices = matchIndices, currentSearchMatchIndex = currentIndex) }
    }

    fun navigateSearchUp() {
        val state = _uiState.value
        if (state.searchMatchIndices.isEmpty()) return
        val newIndex = if (state.currentSearchMatchIndex <= 0) {
            state.searchMatchIndices.size - 1
        } else {
            state.currentSearchMatchIndex - 1
        }
        _uiState.update { it.copy(currentSearchMatchIndex = newIndex) }
    }

    fun navigateSearchDown() {
        val state = _uiState.value
        if (state.searchMatchIndices.isEmpty()) return
        val newIndex = if (state.currentSearchMatchIndex >= state.searchMatchIndices.size - 1) {
            0
        } else {
            state.currentSearchMatchIndex + 1
        }
        _uiState.update { it.copy(currentSearchMatchIndex = newIndex) }
    }

    /**
     * Create intent to add contact to Android Contacts app.
     * Pre-fills the name if we have an inferred name from a self-introduction message.
     */
    fun getAddToContactsIntent(): Intent {
        val phone = _uiState.value.participantPhone ?: ""
        val inferredName = _uiState.value.inferredSenderName
        return Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, phone)
            // Pre-fill the contact name if we inferred it from a self-introduction message
            if (inferredName != null) {
                putExtra(ContactsContract.Intents.Insert.NAME, inferredName)
            }
        }
    }

    /**
     * Create intent to start Google Meet call
     */
    fun getGoogleMeetIntent(): Intent {
        // Open Google Meet to create a new meeting
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com/new"))
    }

    /**
     * Create intent to start WhatsApp video call
     */
    fun getWhatsAppCallIntent(): Intent? {
        val phone = _uiState.value.participantPhone?.replace(Regex("[^0-9+]"), "") ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone"))
    }

    /**
     * Create intent to open help page
     */
    fun getHelpIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BlueBubblesApp/bluebubbles-app/issues"))
    }

    /**
     * Block a phone number (SMS only)
     */
    fun blockContact(context: Context): Boolean {
        if (!_uiState.value.isLocalSmsChat) return false

        val phone = _uiState.value.participantPhone ?: return false

        return try {
            val values = android.content.ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, phone)
            }
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values
            )
            true
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Failed to block contact: ${e.message}") }
            false
        }
    }

    /**
     * Check if WhatsApp is installed
     */
    fun isWhatsAppAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.whatsapp", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun MessageEntity.toUiModel(
        reactions: List<MessageEntity> = emptyList(),
        smsReactions: List<SyntheticReaction> = emptyList(),
        attachments: List<AttachmentEntity> = emptyList(),
        handleIdToName: Map<Long, String> = emptyMap()
    ): MessageUiModel {
        // Build a set of removed reactions: (isFromMe, tapbackType)
        // These are reactions that have been explicitly removed (3xxx codes)
        val removedReactions = reactions
            .filter { isReactionRemoval(it.associatedMessageType) }
            .mapNotNull { reaction ->
                val tapbackType = parseRemovalType(reaction.associatedMessageType)
                tapbackType?.let { Pair(reaction.isFromMe, it) }
            }
            .toSet()

        // Parse iMessage reactions into UI models, filtering out any that have been removed
        val iMessageReactionUiModels = reactions.mapNotNull { reaction ->
            val tapbackType = parseReactionType(reaction.associatedMessageType)
            tapbackType?.let {
                // Check if this reaction was later removed by the same sender
                val removalKey = Pair(reaction.isFromMe, it)
                if (removalKey in removedReactions) {
                    null // This reaction was removed, don't show it
                } else {
                    ReactionUiModel(
                        tapback = it,
                        isFromMe = reaction.isFromMe,
                        senderName = reaction.handleId?.let { handleIdToName[it] }
                    )
                }
            }
        }

        // Convert SMS reactions to UI models
        val smsReactionUiModels = smsReactions.map { reaction ->
            ReactionUiModel(
                tapback = reaction.tapback,
                isFromMe = reaction.isFromMe,
                senderName = reaction.senderName
            )
        }

        // Combine all reactions
        val allReactions = iMessageReactionUiModels + smsReactionUiModels

        // Get my reactions (for highlighting in the menu)
        val myReactions = allReactions
            .filter { it.isFromMe }
            .map { it.tapback }
            .toSet()

        // Map attachments to UI models
        val attachmentUiModels = attachments
            .filter { !it.hideAttachment }
            .map { attachment ->
                AttachmentUiModel(
                    guid = attachment.guid,
                    mimeType = attachment.mimeType,
                    localPath = attachment.localPath,
                    webUrl = attachment.webUrl,
                    width = attachment.width,
                    height = attachment.height,
                    transferName = attachment.transferName,
                    totalBytes = attachment.totalBytes,
                    isSticker = attachment.isSticker,
                    blurhash = attachment.blurhash
                )
            }

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
            attachments = attachmentUiModels,
            senderName = handleId?.let { handleIdToName[it] },
            messageSource = messageSource,
            reactions = allReactions,
            myReactions = myReactions,
            expressiveSendStyleId = expressiveSendStyleId,
            effectPlayed = datePlayed != null
        )
    }

    /**
     * Parse the reaction type from the associatedMessageType field.
     * BlueBubbles format examples: "2000" (love), "2001" (like), etc.
     * Or text format: "love", "like", "dislike", "laugh", "emphasize", "question"
     */
    private fun parseReactionType(associatedMessageType: String?): Tapback? {
        if (associatedMessageType == null) return null

        // Try parsing as API name first (text format)
        // Note: "-love" indicates removal, so check for that first
        if (associatedMessageType.startsWith("-")) {
            return null // This is a removal, handled separately
        }
        Tapback.fromApiName(associatedMessageType)?.let { return it }

        // Parse numeric codes (iMessage internal format)
        // 2000 = love, 2001 = like, 2002 = dislike, 2003 = laugh, 2004 = emphasize, 2005 = question
        // 3000-3005 = removal of reactions (should not be counted as active reactions)
        return when {
            associatedMessageType.contains("3000") -> null // love removal
            associatedMessageType.contains("3001") -> null // like removal
            associatedMessageType.contains("3002") -> null // dislike removal
            associatedMessageType.contains("3003") -> null // laugh removal
            associatedMessageType.contains("3004") -> null // emphasize removal
            associatedMessageType.contains("3005") -> null // question removal
            associatedMessageType.contains("2000") -> Tapback.LOVE
            associatedMessageType.contains("2001") -> Tapback.LIKE
            associatedMessageType.contains("2002") -> Tapback.DISLIKE
            associatedMessageType.contains("2003") -> Tapback.LAUGH
            associatedMessageType.contains("2004") -> Tapback.EMPHASIZE
            associatedMessageType.contains("2005") -> Tapback.QUESTION
            associatedMessageType.contains("love") -> Tapback.LOVE
            associatedMessageType.contains("like") -> Tapback.LIKE
            associatedMessageType.contains("dislike") -> Tapback.DISLIKE
            associatedMessageType.contains("laugh") -> Tapback.LAUGH
            associatedMessageType.contains("emphasize") -> Tapback.EMPHASIZE
            associatedMessageType.contains("question") -> Tapback.QUESTION
            else -> null
        }
    }

    /**
     * Check if the reaction code indicates a removal (3000-3005 range).
     */
    private fun isReactionRemoval(associatedMessageType: String?): Boolean {
        if (associatedMessageType == null) return false
        if (associatedMessageType.startsWith("-")) return true
        return associatedMessageType.contains("3000") ||
                associatedMessageType.contains("3001") ||
                associatedMessageType.contains("3002") ||
                associatedMessageType.contains("3003") ||
                associatedMessageType.contains("3004") ||
                associatedMessageType.contains("3005")
    }

    /**
     * Parse the reaction type from a removal code (3xxx range).
     */
    private fun parseRemovalType(associatedMessageType: String?): Tapback? {
        if (associatedMessageType == null) return null
        if (associatedMessageType.startsWith("-")) {
            return Tapback.fromApiName(associatedMessageType.removePrefix("-"))
        }
        return when {
            associatedMessageType.contains("3000") -> Tapback.LOVE
            associatedMessageType.contains("3001") -> Tapback.LIKE
            associatedMessageType.contains("3002") -> Tapback.DISLIKE
            associatedMessageType.contains("3003") -> Tapback.LAUGH
            associatedMessageType.contains("3004") -> Tapback.EMPHASIZE
            associatedMessageType.contains("3005") -> Tapback.QUESTION
            else -> null
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
    }

    /**
     * Mark the current chat as safe (not spam).
     * This clears the spam flag and whitelists all participants.
     */
    fun markAsSafe() {
        viewModelScope.launch {
            spamRepository.markAsSafe(chatGuid)
        }
    }

    /**
     * Report the current chat as spam.
     * This marks the chat as spam and increments the spam count for all participants.
     */
    fun reportAsSpam() {
        viewModelScope.launch {
            spamRepository.reportAsSpam(chatGuid)
        }
    }

    /**
     * Report the spam to carrier via 7726.
     * Only works for SMS chats.
     */
    fun reportToCarrier(): Boolean {
        if (!uiState.value.isLocalSmsChat) return false

        viewModelScope.launch {
            val result = spamReportingService.reportToCarrier(chatGuid)
            if (result is SpamReportingService.ReportResult.Success) {
                _uiState.update { it.copy(isReportedToCarrier = true) }
            }
        }
        return true
    }

    /**
     * Check if the chat has already been reported to carrier.
     */
    fun checkReportedToCarrier() {
        viewModelScope.launch {
            val isReported = spamReportingService.isReportedToCarrier(chatGuid)
            _uiState.update { it.copy(isReportedToCarrier = isReported) }
        }
    }

    // ===== Effect Playback =====

    /**
     * Called when a bubble effect animation completes.
     */
    fun onBubbleEffectCompleted(messageGuid: String) {
        viewModelScope.launch {
            messageRepository.markEffectPlayed(messageGuid)
        }
    }

    /**
     * Trigger a screen effect for a message.
     * Effects are queued to prevent overlapping animations.
     */
    fun triggerScreenEffect(message: MessageUiModel) {
        val effect = MessageEffect.fromStyleId(message.expressiveSendStyleId) as? MessageEffect.Screen ?: return
        val state = ScreenEffectState(effect, message.guid, message.text)
        screenEffectQueue.add(state)
        if (!isPlayingScreenEffect) playNextScreenEffect()
    }

    private fun playNextScreenEffect() {
        val next = screenEffectQueue.removeFirstOrNull()
        if (next != null) {
            isPlayingScreenEffect = true
            _activeScreenEffect.value = next
        } else {
            isPlayingScreenEffect = false
        }
    }

    /**
     * Called when a screen effect animation completes.
     */
    fun onScreenEffectCompleted() {
        val state = _activeScreenEffect.value
        if (state != null) {
            viewModelScope.launch {
                messageRepository.markEffectPlayed(state.messageGuid)
            }
        }
        _activeScreenEffect.value = null
        isPlayingScreenEffect = false
        playNextScreenEffect()
    }

    // ===== Scheduled Messages =====

    /**
     * Schedule a message to be sent at a later time.
     *
     * Note: This uses client-side scheduling with WorkManager.
     * The phone must be on and have network connectivity for the message to send.
     */
    fun scheduleMessage(text: String, attachments: List<Uri>, sendAt: Long) {
        viewModelScope.launch {
            // Convert attachments to JSON array string
            val attachmentUrisJson = if (attachments.isNotEmpty()) {
                attachments.joinToString(",", "[", "]") { "\"${it}\"" }
            } else {
                null
            }

            // Create scheduled message entity
            val scheduledMessage = ScheduledMessageEntity(
                chatGuid = chatGuid,
                text = text.ifBlank { null },
                attachmentUris = attachmentUrisJson,
                scheduledAt = sendAt
            )

            // Insert into database
            val id = scheduledMessageDao.insert(scheduledMessage)

            // Calculate delay
            val delay = sendAt - System.currentTimeMillis()

            // Schedule WorkManager job
            val workRequest = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
                .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(ScheduledMessageWorker.KEY_SCHEDULED_MESSAGE_ID to id)
                )
                .build()

            workManager.enqueue(workRequest)

            // Save the work request ID for potential cancellation
            scheduledMessageDao.updateWorkRequestId(id, workRequest.id.toString())
        }
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
    val isIMessageChat: Boolean = false,
    val attachmentCount: Int = 0,
    // Menu-related state
    val isArchived: Boolean = false,
    val isStarred: Boolean = false,
    val showSubjectField: Boolean = false,
    val participantPhone: String? = null,
    val chatDeleted: Boolean = false,
    // Unsaved sender banner
    val showSaveContactBanner: Boolean = false,
    val unsavedSenderAddress: String? = null,
    val inferredSenderName: String? = null, // Inferred name from self-introduction (for add contact pre-fill)
    // Inline search state
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchMatchIndices: List<Int> = emptyList(),
    val currentSearchMatchIndex: Int = -1,
    // SMS fallback mode
    val isInSmsFallbackMode: Boolean = false,
    val isServerConnected: Boolean = true,
    val fallbackReason: FallbackReason? = null,
    // Spam detection
    val isSpam: Boolean = false,
    val isReportedToCarrier: Boolean = false,
    // Message forwarding
    val isForwarding: Boolean = false,
    val forwardSuccess: Boolean = false,
    // Snooze
    val isSnoozed: Boolean = false,
    val snoozeUntil: Long? = null
)
