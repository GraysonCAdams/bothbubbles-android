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
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.ScheduledMessageDao
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.ScheduledMessageEntity
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.data.local.db.entity.QuickReplyTemplateEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.local.db.entity.PendingMessageEntity
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.data.repository.AttachmentRepository
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageDeliveryMode
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.PendingMessageRepository
import com.bothbubbles.data.repository.QuickReplyTemplateRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.VCardService
import com.bothbubbles.services.media.AttachmentDownloadQueue
import com.bothbubbles.services.media.AttachmentPreloader
import com.bothbubbles.services.messaging.ChatFallbackTracker
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.services.smartreply.SmartReplyService
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.MessageDto
import android.util.Log
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sound.SoundManager
import com.bothbubbles.services.scheduled.ScheduledMessageWorker
import com.bothbubbles.services.spam.SpamReportingService
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.ui.components.AttachmentUiModel
import com.bothbubbles.ui.components.MessageUiModel
import com.bothbubbles.ui.components.ReactionUiModel
import com.bothbubbles.ui.components.ReplyPreviewData
import com.bothbubbles.ui.components.SuggestionItem
import com.bothbubbles.ui.components.Tapback
import com.bothbubbles.ui.components.ThreadChain
import com.bothbubbles.ui.components.analyzeEmojis
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.PerformanceProfiler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    private val attachmentDownloadQueue: AttachmentDownloadQueue,
    private val androidContactsService: AndroidContactsService,
    private val vCardService: VCardService,
    private val smartReplyService: SmartReplyService,
    private val quickReplyTemplateRepository: QuickReplyTemplateRepository,
    private val scheduledMessageDao: ScheduledMessageDao,
    private val workManager: WorkManager,
    private val handleDao: HandleDao,
    private val activeConversationManager: ActiveConversationManager,
    private val api: BothBubblesApi,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val attachmentPreloader: AttachmentPreloader,
    private val pendingMessageRepository: PendingMessageRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val AVAILABILITY_CHECK_COOLDOWN = 5 * 60 * 1000L // 5 minutes
    }

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

    // Dynamic message limit for pagination - increases as user scrolls up to load older messages
    // Start with 75 messages (~5-7 screens) to reduce early pagination triggers
    private val _messageLimit = MutableStateFlow(75)

    // Trigger to refresh messages (incremented when attachments are downloaded)
    private val _attachmentRefreshTrigger = MutableStateFlow(0)

    // Separate draft text flow for TextField performance - avoids full screen recomposition on each keystroke
    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

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
    private var lastStartedTypingTime = 0L
    private val typingCooldownMs = 500L // Min time between started-typing emissions

    // Cached settings for typing indicators (avoids suspend calls on every keystroke)
    @Volatile private var cachedPrivateApiEnabled = false
    @Volatile private var cachedTypingIndicatorsEnabled = false

    // Draft persistence
    private var draftSaveJob: Job? = null
    private val draftSaveDebounceMs = 500L // Debounce draft saves to avoid excessive DB writes

    // Scroll position tracking for state restoration
    private var lastScrollPosition: Int = 0
    private var lastScrollOffset: Int = 0
    private var lastScrollSaveTime: Long = 0L
    private val scrollSaveDebounceMs = 1000L // Debounce scroll saves

    // Throttle preloader to avoid calling on every scroll frame
    private var lastPreloadIndex: Int = -1
    private var lastPreloadTime: Long = 0L
    private val preloadThrottleMs = 150L // Only preload every 150ms at most

    // Cached attachment list for preloading - avoids flatMap on every scroll frame
    private var cachedAttachments: List<AttachmentUiModel> = emptyList()
    private var cachedAttachmentsMessageCount: Int = 0

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

    // Thread overlay state - shows the thread chain when user taps a reply indicator
    private val _threadOverlayState = MutableStateFlow<ThreadChain?>(null)
    val threadOverlayState: StateFlow<ThreadChain?> = _threadOverlayState.asStateFlow()

    // Scroll-to-message event - emitted when user taps a message in thread overlay
    private val _scrollToGuid = MutableSharedFlow<String>()
    val scrollToGuid: SharedFlow<String> = _scrollToGuid.asSharedFlow()

    // iMessage availability check cooldown (per-session, resets on ViewModel creation)
    private var lastAvailabilityCheck: Long = 0

    init {
        // Track this conversation as active to suppress notifications while viewing
        activeConversationManager.setActiveConversation(chatGuid, mergedChatGuids.toSet())

        // Notify server which chat is open (helps server optimize notification delivery)
        socketService.sendOpenChat(chatGuid)

        // Set this chat as the active chat for download queue prioritization
        attachmentDownloadQueue.setActiveChat(chatGuid)

        loadChat()
        loadMessages()
        syncMessages()
        observeTypingIndicators()
        observeTypingIndicatorSettings()
        observeNewMessages()
        observeMessageUpdates()
        markAsRead()
        determineChatType()
        observeParticipantsForSaveContactBanner()
        observeFallbackMode()
        observeConnectionState()
        observeSmartReplies()
        observeAutoDownloadSetting()
        observeUploadProgress()
        saveCurrentChatState()
        observeQueuedMessages()

        // Check if iMessage is available again (for chats in SMS fallback mode)
        // Delay slightly to ensure chat data is loaded first
        viewModelScope.launch {
            delay(500) // Wait for loadChat() to populate participantPhone
            checkAndMaybeExitFallback()
        }
    }

    /**
     * Observe typing indicator settings and cache them for fast access.
     * This avoids suspend calls on every keystroke in handleTypingIndicator.
     */
    private fun observeTypingIndicatorSettings() {
        viewModelScope.launch {
            settingsDataStore.enablePrivateApi.collect { enabled ->
                cachedPrivateApiEnabled = enabled
            }
        }
        viewModelScope.launch {
            settingsDataStore.sendTypingIndicators.collect { enabled ->
                cachedTypingIndicatorsEnabled = enabled
            }
        }
    }

    /**
     * Save current chat state immediately for state restoration.
     * Called in init so state is persisted as soon as chat opens.
     */
    private fun saveCurrentChatState() {
        android.util.Log.e("StateRestore", "saveCurrentChatState CALLED: chatGuid=$chatGuid")
        viewModelScope.launch {
            val mergedGuidsStr = if (isMergedChat) mergedChatGuids.joinToString(",") else null
            android.util.Log.e("StateRestore", "saveCurrentChatState SAVING: chatGuid=$chatGuid")
            settingsDataStore.setLastOpenChat(chatGuid, mergedGuidsStr)
        }
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
     * Observe upload progress from MessageRepository for determinate progress bar.
     * Updates the first pending message with attachments and recalculates aggregate progress.
     */
    private fun observeUploadProgress() {
        viewModelScope.launch {
            messageRepository.uploadProgress.collect { progress ->
                if (progress != null) {
                    // Calculate individual message progress (0.0 to 1.0)
                    val attachmentBase = progress.attachmentIndex.toFloat() / progress.totalAttachments
                    val currentProgress = progress.progress / progress.totalAttachments
                    val messageProgress = attachmentBase + currentProgress

                    // Update the first pending message with attachments
                    _uiState.update { state ->
                        val pendingList = state.pendingMessages.toMutableList()
                        val attachmentIndex = pendingList.indexOfFirst { it.hasAttachments }
                        if (attachmentIndex >= 0) {
                            pendingList[attachmentIndex] = pendingList[attachmentIndex].copy(progress = messageProgress)
                        }
                        state.copy(
                            pendingMessages = pendingList,
                            sendProgress = calculateAggregateProgress(pendingList)
                        )
                    }
                }
                // Don't reset progress to 0 when progress is null - let completion handlers manage that
            }
        }
    }

    /**
     * Download all pending attachments for this chat (or merged chats).
     * Called automatically when auto-download is enabled, or can be triggered manually.
     * Uses the download queue for prioritized, concurrent downloads.
     */
    private fun downloadPendingAttachments() {
        viewModelScope.launch {
            // Enqueue all pending attachments for this chat with ACTIVE_CHAT priority
            if (isMergedChat) {
                mergedChatGuids.forEach { guid ->
                    attachmentDownloadQueue.enqueueAllForChat(guid, AttachmentDownloadQueue.Priority.ACTIVE_CHAT)
                }
            } else {
                attachmentDownloadQueue.enqueueAllForChat(chatGuid, AttachmentDownloadQueue.Priority.ACTIVE_CHAT)
            }
        }

        // Observe download completions to trigger UI refresh
        viewModelScope.launch {
            attachmentDownloadQueue.downloadCompletions.collect { attachmentGuid ->
                // Remove from progress map if it was being tracked
                _attachmentDownloadProgress.update { it - attachmentGuid }
                // Trigger message refresh so UI shows the downloaded attachment
                _attachmentRefreshTrigger.value++
            }
        }
    }

    /**
     * Manually download a specific attachment.
     * Called when user taps on an attachment placeholder in manual download mode.
     * Uses IMMEDIATE priority to jump ahead of background downloads.
     */
    fun downloadAttachment(attachmentGuid: String) {
        // Mark as downloading
        _attachmentDownloadProgress.update { it + (attachmentGuid to 0f) }

        // Enqueue with IMMEDIATE priority - will be processed ahead of other downloads
        attachmentDownloadQueue.enqueue(attachmentGuid, chatGuid, AttachmentDownloadQueue.Priority.IMMEDIATE)

        // Progress and completion are tracked via the queue's SharedFlow in downloadPendingAttachments()
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
     * Observe queued messages from the database for offline-first UI.
     * These are messages that have been queued for sending but not yet delivered.
     */
    private fun observeQueuedMessages() {
        viewModelScope.launch {
            pendingMessageRepository.observePendingForChat(chatGuid)
                .collect { pending ->
                    _uiState.update { state ->
                        state.copy(
                            queuedMessages = pending.map { it.toQueuedUiModel() }
                        )
                    }
                }
        }
    }

    /**
     * Convert PendingMessageEntity to UI model.
     */
    private fun PendingMessageEntity.toQueuedUiModel(): QueuedMessageUiModel {
        return QueuedMessageUiModel(
            localId = localId,
            text = text,
            hasAttachments = false, // TODO: Check attachments table
            syncStatus = try {
                PendingSyncStatus.valueOf(syncStatus)
            } catch (e: Exception) {
                PendingSyncStatus.PENDING
            },
            errorMessage = errorMessage,
            createdAt = createdAt
        )
    }

    /**
     * Retry a failed queued message.
     */
    fun retryQueuedMessage(localId: String) {
        viewModelScope.launch {
            pendingMessageRepository.retryMessage(localId)
                .onFailure { e ->
                    Log.e(TAG, "Failed to retry message: $localId", e)
                    _uiState.update { it.copy(error = "Failed to retry: ${e.message}") }
                }
        }
    }

    /**
     * Cancel a queued message.
     */
    fun cancelQueuedMessage(localId: String) {
        viewModelScope.launch {
            pendingMessageRepository.cancelMessage(localId)
                .onFailure { e ->
                    Log.e(TAG, "Failed to cancel message: $localId", e)
                    _uiState.update { it.copy(error = "Failed to cancel: ${e.message}") }
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

    /**
     * Check if iMessage is available for this chat and auto-exit fallback mode if so.
     * Only checks if:
     * 1. Chat is in SMS fallback due to IMESSAGE_FAILED reason
     * 2. Cooldown has passed (5 minutes)
     * 3. Server is connected
     */
    private fun checkAndMaybeExitFallback() {
        val fallbackReason = chatFallbackTracker.getFallbackReason(chatGuid)

        // Only check for IMESSAGE_FAILED fallback, not server disconnected or user requested
        if (fallbackReason != FallbackReason.IMESSAGE_FAILED) return

        val now = System.currentTimeMillis()
        if (now - lastAvailabilityCheck < AVAILABILITY_CHECK_COOLDOWN) {
            Log.d(TAG, "Skipping availability check - cooldown not passed")
            return
        }
        lastAvailabilityCheck = now

        viewModelScope.launch {
            // Get the primary address from chat identifier or first participant
            val address = _uiState.value.participantPhone
            if (address.isNullOrBlank()) {
                Log.d(TAG, "No address found for availability check")
                return@launch
            }

            // Only check if server is connected
            if (socketService.connectionState.value != ConnectionState.CONNECTED) {
                Log.d(TAG, "Server not connected, skipping availability check")
                return@launch
            }

            try {
                Log.d(TAG, "Checking iMessage availability for $address")
                val response = api.checkIMessageAvailability(address)
                if (response.isSuccessful && response.body()?.data?.available == true) {
                    Log.d(TAG, "iMessage now available for $address, exiting fallback mode")
                    chatFallbackTracker.exitFallbackMode(chatGuid)
                    _uiState.update { it.copy(isInSmsFallbackMode = false, fallbackReason = null) }
                } else {
                    Log.d(TAG, "iMessage still unavailable for $address")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check iMessage availability", e)
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
        _uiState.update { it.copy(isSyncingMessages = true) }
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
            _uiState.update { it.copy(isSyncingMessages = false) }
        }
    }

    private fun determineChatType() {
        val isLocalSms = messageRepository.isLocalSmsChat(chatGuid)
        val isServerForward = chatGuid.startsWith("SMS;", ignoreCase = true)
        val isSmsChat = isLocalSms || isServerForward
        val isDefaultSmsApp = smsPermissionHelper.isDefaultSmsApp()
        _uiState.update {
            it.copy(
                isLocalSmsChat = isLocalSms,
                isIMessageChat = !isSmsChat,
                smsInputBlocked = isSmsChat && !isDefaultSmsApp
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
            val photoUri = androidContactsService.getContactPhotoUri(address)
            if (displayName != null || photoUri != null) {
                // Update the cached display name and photo in the database
                chatRepository.updateHandleCachedContactInfo(address, displayName, photoUri)
                // Hide the save contact banner since they saved the contact
                _uiState.update { it.copy(showSaveContactBanner = false) }
            }
        }
    }

    fun exitSmsFallback() {
        chatFallbackTracker.exitFallbackMode(chatGuid)
    }

    // ===== Thread Overlay Functions =====

    /**
     * Load a thread chain for display in the thread overlay.
     * Called when user taps a reply indicator.
     */
    fun loadThread(originGuid: String) {
        viewModelScope.launch {
            val threadMessages = messageRepository.getThreadMessages(originGuid)
            val origin = threadMessages.find { it.guid == originGuid }
            val replies = threadMessages.filter { it.threadOriginatorGuid == originGuid }

            // Get participants for sender name and avatar resolution
            val participants = chatRepository.getParticipantsForChats(mergedChatGuids)
            val handleIdToName = participants.associate { it.id to it.displayName }
            val addressToName = participants.associate { normalizeAddress(it.address) to it.displayName }
            val addressToAvatarPath = participants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }

            // Batch load attachments for all thread messages
            val allAttachments = attachmentDao.getAttachmentsForMessages(
                threadMessages.map { it.guid }
            ).groupBy { it.messageGuid }

            // Filter out placed stickers from thread overlay - they're visual overlays, not actual replies
            val filteredReplies = replies.filter { msg ->
                val msgAttachments = allAttachments[msg.guid].orEmpty()
                val isPlacedSticker = msg.associatedMessageGuid != null &&
                    msgAttachments.any { it.mimeType?.contains("sticker") == true }
                !isPlacedSticker
            }

            _threadOverlayState.value = ThreadChain(
                originMessage = origin?.toUiModel(
                    attachments = allAttachments[origin.guid].orEmpty(),
                    handleIdToName = handleIdToName,
                    addressToName = addressToName,
                    addressToAvatarPath = addressToAvatarPath
                ),
                replies = filteredReplies.map { msg ->
                    msg.toUiModel(
                        attachments = allAttachments[msg.guid].orEmpty(),
                        handleIdToName = handleIdToName,
                        addressToName = addressToName,
                        addressToAvatarPath = addressToAvatarPath
                    )
                }
            )
        }
    }

    /**
     * Dismiss the thread overlay.
     */
    fun dismissThreadOverlay() {
        _threadOverlayState.value = null
    }

    /**
     * Scroll to a specific message in the main chat.
     * Called when user taps a message in the thread overlay.
     */
    fun scrollToMessage(guid: String) {
        viewModelScope.launch {
            dismissThreadOverlay()
            _scrollToGuid.emit(guid)
        }
    }

    /**
     * Highlight a message with an iOS-like blink animation.
     * Called after scrolling to a message from notification deep-link.
     */
    fun highlightMessage(guid: String) {
        _uiState.update { it.copy(highlightedMessageGuid = guid) }
    }

    /**
     * Clear the highlighted message after animation completes.
     */
    fun clearHighlight() {
        _uiState.update { it.copy(highlightedMessageGuid = null) }
    }

    private fun loadChat() {
        viewModelScope.launch {
            var draftLoaded = false

            // Observe participants from all chats in merged conversation
            val participantsFlow = chatRepository.observeParticipantsForChats(mergedChatGuids)

            // Combine chat with participants to resolve display name properly
            combine(
                chatRepository.observeChat(chatGuid),
                participantsFlow
            ) { chat, participants -> chat to participants }
            .collect { (chat, participants) ->
                chat?.let {
                    val chatTitle = resolveChatTitle(it, participants)
                    // Load draft only on first observation to avoid overwriting user edits
                    if (!draftLoaded) {
                        _draftText.value = it.textFieldText ?: ""
                    }
                    _uiState.update { state ->
                        state.copy(
                            chatTitle = chatTitle,
                            isGroup = it.isGroup,
                            avatarPath = participants.firstOrNull()?.cachedAvatarPath,
                            participantNames = participants.map { p -> p.displayName },
                            participantAvatarPaths = participants.map { p -> p.cachedAvatarPath },
                            isArchived = it.isArchived,
                            isStarred = it.isStarred,
                            participantPhone = it.chatIdentifier,
                            isSpam = it.isSpam,
                            isReportedToCarrier = it.spamReportedToCarrier,
                            isSnoozed = it.isSnoozed,
                            snoozeUntil = it.snoozeUntil,
                            isLocalSmsChat = it.isLocalSms,  // Only local SMS, not server forwarding
                            isIMessageChat = it.isIMessage,
                            smsInputBlocked = it.isSmsChat && !smsPermissionHelper.isDefaultSmsApp()
                        )
                    }
                    draftLoaded = true
                }
            }
        }
    }

    /**
     * Resolve the display name for a chat, using consistent logic with the conversation list.
     * For 1:1 chats: prefer participant's displayName (from contacts or inferred)
     * For group chats: use chat displayName or generate from participant names
     */
    private fun resolveChatTitle(chat: ChatEntity, participants: List<HandleEntity>): String {
        // For group chats: use explicit group name or generate from participants
        if (chat.isGroup) {
            return chat.displayName?.takeIf { it.isNotBlank() }
                ?: participants.take(3).joinToString(", ") { it.displayName }
                    .let { names -> if (participants.size > 3) "$names +${participants.size - 3}" else names }
                    .ifEmpty { PhoneNumberFormatter.format(chat.chatIdentifier ?: "") }
        }

        // For 1:1 chats: prefer participant's displayName (handles contact lookup, inferred names)
        val primaryParticipant = participants.firstOrNull()
        return primaryParticipant?.displayName
            ?: chat.displayName?.takeIf { it.isNotBlank() }
            ?: PhoneNumberFormatter.format(chat.chatIdentifier ?: primaryParticipant?.address ?: "")
    }

    private fun loadMessages() {
        viewModelScope.launch {
            // Observe participants from all chats in merged conversation
            val participantsFlow = chatRepository.observeParticipantsForChats(mergedChatGuids)

            // Use flatMapLatest to react to message limit changes for pagination
            // When _messageLimit increases, automatically re-query with larger limit
            // Also react to attachment refresh trigger (when downloads complete)
            combine(_messageLimit, _attachmentRefreshTrigger) { limit, _ -> limit }
            .flatMapLatest { limit ->
                // For merged chats, observe messages from all chats
                val messagesFlow = if (isMergedChat) {
                    messageRepository.observeMessagesForChats(mergedChatGuids, limit = limit, offset = 0)
                } else {
                    messageRepository.observeMessagesForChat(chatGuid, limit = limit, offset = 0)
                }

                // Combine messages with participants to get sender names and avatar paths
                combine(
                    messagesFlow,
                    participantsFlow
                ) { messages, participants ->
                // Build a map from handleId to displayName for quick lookup
                val handleIdToName = participants.associate { it.id to it.displayName }.toMutableMap()

                // Build a map by normalized address for looking up sender names from senderAddress
                val addressToName = participants.associate { normalizeAddress(it.address) to it.displayName }

                // Build a map by normalized address for looking up avatar paths
                val addressToAvatarPath = participants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }

                // For messages with handleId not in participants, look up the handle and match by address
                val missingHandleIds = messages
                    .filter { !it.isFromMe && it.handleId != null && it.handleId !in handleIdToName }
                    .mapNotNull { it.handleId }
                    .distinct()

                // PERF: Batch fetch missing handles in a single query instead of sequential calls
                if (missingHandleIds.isNotEmpty()) {
                    val handles = handleDao.getHandlesByIds(missingHandleIds)
                    handles.forEach { handle ->
                        // Try to find a participant with matching address
                        val normalizedAddress = normalizeAddress(handle.address)
                        val matchingName = addressToName[normalizedAddress]
                        if (matchingName != null) {
                            handleIdToName[handle.id] = matchingName
                        } else {
                            // No participant match - use handle's own displayName (address fallback)
                            handleIdToName[handle.id] = handle.displayName
                        }
                    }
                }

                ParticipantMaps(messages, handleIdToName.toMap(), addressToName, addressToAvatarPath)
            }
                .distinctUntilChanged()
                .map { (messages, handleIdToName, addressToName, addressToAvatarPath) ->
                    // Separate actual reactions (iMessage tapbacks)
                    val iMessageReactions = messages.filter { it.isReaction }

                    // Detect SMS-style reaction messages (e.g., 'Loved "Hello"')
                    val (smsReactionMessages, regularMessages) = messages
                        .filter { !it.isReaction }
                        .partition { msg ->
                            msg.text?.let { parseSmsReaction(it) } != null
                        }

                    // PERF: Build map for O(1) exact match lookup
                    // Prefix matching uses regionMatches to avoid string allocations
                    val exactTextToGuid = mutableMapOf<String, String>()
                    regularMessages.forEach { msg ->
                        msg.text?.let { text ->
                            exactTextToGuid[text] = msg.guid
                        }
                    }

                    // Helper to find message by prefix match without allocating substrings
                    fun findMessageByPrefix(searchText: String, prefixLen: Int): String? {
                        if (searchText.length < 10) return null
                        val compareLen = minOf(prefixLen, searchText.length)
                        return regularMessages.find { msg ->
                            msg.text?.let { text ->
                                text.length >= compareLen &&
                                text.regionMatches(0, searchText, 0, compareLen)
                            } ?: false
                        }?.guid
                    }

                    // Convert SMS reaction messages to synthetic reaction entries
                    // Only include additions, not removals (removals are just filtered out)
                    val smsReactions = smsReactionMessages.mapNotNull { msg ->
                        val (tapback, quotedText, isRemoval) = parseSmsReaction(msg.text ?: "") ?: return@mapNotNull null
                        // Skip removal messages - they just get filtered from regular messages
                        if (isRemoval) return@mapNotNull null
                        // Try exact match first (O(1)), then prefix match (O(n) but allocation-free)
                        val searchText = if (quotedText.endsWith("...")) quotedText.dropLast(3) else quotedText
                        val targetGuid = exactTextToGuid[quotedText]
                            ?: findMessageByPrefix(searchText, 50)
                            ?: findMessageByPrefix(searchText, 30)
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

                    // Batch load all attachments in a single query
                    val allAttachments = attachmentDao.getAttachmentsForMessages(
                        regularMessages.map { it.guid }
                    ).groupBy { it.messageGuid }

                    // Collect all unique threadOriginatorGuids for reply preview loading
                    val replyGuids = regularMessages
                        .mapNotNull { it.threadOriginatorGuid }
                        .distinct()

                    // Build a map of guid -> message for quick lookup (from loaded messages)
                    val loadedMessagesMap = regularMessages.associateBy { it.guid }

                    // Batch fetch original messages that are not in the loaded set
                    val missingGuids = replyGuids.filter { it !in loadedMessagesMap }
                    val fetchedOriginals = if (missingGuids.isNotEmpty()) {
                        messageRepository.getMessagesByGuids(missingGuids).associateBy { it.guid }
                    } else {
                        emptyMap()
                    }

                    // Combine loaded and fetched messages for reply preview lookup
                    val allMessagesMap = loadedMessagesMap + fetchedOriginals

                    // Build reply preview data map
                    val replyPreviewMap = replyGuids.mapNotNull { originGuid ->
                        val originalMessage = allMessagesMap[originGuid]
                        if (originalMessage != null) {
                            originGuid to ReplyPreviewData(
                                originalGuid = originGuid,
                                previewText = originalMessage.text?.take(50),
                                senderName = resolveSenderName(
                                    originalMessage.senderAddress,
                                    originalMessage.handleId,
                                    addressToName,
                                    handleIdToName
                                ),
                                isFromMe = originalMessage.isFromMe,
                                hasAttachment = originalMessage.hasAttachments,
                                isNotLoaded = false
                            )
                        } else {
                            // Original message not found - mark as not loaded
                            originGuid to ReplyPreviewData(
                                originalGuid = originGuid,
                                previewText = null,
                                senderName = null,
                                isFromMe = false,
                                hasAttachment = false,
                                isNotLoaded = true
                            )
                        }
                    }.toMap()

                    regularMessages.map { message ->
                        val messageReactions = reactionsByMessage[message.guid].orEmpty()
                        val messageSmsReactions = smsReactionsByMessage[message.guid].orEmpty()
                        val attachments = allAttachments[message.guid].orEmpty()
                        val replyPreview = message.threadOriginatorGuid?.let { replyPreviewMap[it] }
                        message.toUiModel(
                            reactions = messageReactions,
                            smsReactions = messageSmsReactions,
                            attachments = attachments,
                            handleIdToName = handleIdToName,
                            addressToName = addressToName,
                            addressToAvatarPath = addressToAvatarPath,
                            replyPreview = replyPreview
                        )
                    }
                }
            } // End flatMapLatest
            .collect { messageModels ->
                val collectId = PerformanceProfiler.start("Chat.messagesCollected", "${messageModels.size} messages")
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        messages = messageModels
                    )
                }
                PerformanceProfiler.end(collectId)
            }
        }
    }

    /**
     * Parse an SMS-style reaction message.
     * Returns the Tapback type, quoted message text, and whether it's a removal.
     * Matches patterns like:
     *   - Adding: Loved "Hello there!"
     *   - Removing: Removed a heart from "Hello there!"
     *   - Custom emoji (iOS 16.4+): Reacted with 😂 to "Hello" or Reacted with 😂 to an image
     */
    private fun parseSmsReaction(text: String): Triple<Tapback, String, Boolean>? {
        val trimmedText = text.trim()

        // Pattern for custom emoji reactions (iOS 16.4+):
        // - "Reacted EMOJI to an image" (attachments)
        // - "Reacted EMOJI to "quoted text"" (text messages)
        // - "Reacted with EMOJI to ..." (alternate format)
        // These are filtered out but can't be displayed as standard tapbacks since they're arbitrary emojis
        val customEmojiPattern = Regex("""^Reacted( with)? .+ to (".*"|an? .+)$""")
        if (customEmojiPattern.matches(trimmedText)) {
            // Return LOVE as a placeholder - the message will be filtered from view
            // The quoted text won't match anything, preventing duplicate display
            return Triple(Tapback.LOVE, "__custom_emoji_reaction__", false)
        }

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
            _uiState.update { it.copy(isSyncingMessages = false) }
        }
    }

    private fun observeTypingIndicators() {
        viewModelScope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.TypingIndicator>()
                .filter { event ->
                    // Use normalized GUID comparison to handle format differences
                    // Server may send "+1234567890" but local has "+1-234-567-890"
                    val normalizedEventGuid = normalizeGuid(event.chatGuid)
                    mergedChatGuids.any { normalizeGuid(it) == normalizedEventGuid } ||
                        normalizeGuid(chatGuid) == normalizedEventGuid ||
                        // Fallback: match by address/phone number only
                        extractAddress(event.chatGuid)?.let { eventAddress ->
                            mergedChatGuids.any { extractAddress(it) == eventAddress } ||
                                extractAddress(chatGuid) == eventAddress
                        } == true
                }
                .collect { event ->
                    _uiState.update { it.copy(isTyping = event.isTyping) }
                }
        }
    }

    /**
     * Observe new messages from socket events for immediate UI updates.
     * This supplements the Room Flow observation to ensure messages appear instantly
     * even if Room's Flow invalidation is delayed or there are chatGuid mismatches.
     *
     * When we receive a new message for this chat, we:
     * 1. Directly add the message to the UI state (like original Flutter client)
     * 2. Mark as read since user is viewing the chat
     */
    private fun observeNewMessages() {
        viewModelScope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.NewMessage>()
                .filter { event ->
                    // Use normalized GUID comparison to handle format differences
                    // Server may send "+1234567890" but local has "+1-234-567-890"
                    val normalizedEventGuid = normalizeGuid(event.chatGuid)
                    mergedChatGuids.any { normalizeGuid(it) == normalizedEventGuid } ||
                        normalizeGuid(chatGuid) == normalizedEventGuid ||
                        // Fallback: match by address/phone number only
                        extractAddress(event.chatGuid)?.let { eventAddress ->
                            mergedChatGuids.any { extractAddress(it) == eventAddress } ||
                                extractAddress(chatGuid) == eventAddress
                        } == true
                }
                .collect { event ->
                    android.util.Log.d("ChatViewModel", "Socket: New message received for ${event.chatGuid}, guid=${event.message.guid}")

                    // Directly add the new message to UI state (like original Flutter client)
                    // This is more reliable than waiting for Room Flow invalidation
                    directlyAddMessageToUi(event.message, event.chatGuid)

                    // Note: Sound is already played by SocketService.onNewMessage
                    // We don't play it again here to avoid double sounds

                    // Mark as read since user is viewing the chat
                    markAsRead()
                }
        }
    }

    /**
     * Directly add a new message to the UI state without waiting for Room Flow.
     * This mirrors the original Flutter client's pattern of manual list management.
     */
    private suspend fun directlyAddMessageToUi(messageDto: MessageDto, eventChatGuid: String) {
        // Do heavy work on background thread to avoid blocking main thread
        val messageModel = withContext(Dispatchers.Default) {
            // Convert DTO to Entity
            val messageEntity = messageDto.toMessageEntity(eventChatGuid)

            // Get participant info for sender name and avatar resolution
            val participants = chatRepository.observeParticipantsForChats(mergedChatGuids).first()
            val handleIdToName = participants.associate { it.id to it.displayName }
            val addressToName = participants.associate { normalizeAddress(it.address) to it.displayName }
            val addressToAvatarPath = participants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }

            // Load attachments for this message
            val attachments = if (messageEntity.hasAttachments) {
                attachmentDao.getAttachmentsForMessage(messageEntity.guid)
            } else {
                emptyList()
            }

            // Convert to UI model
            messageEntity.toUiModel(
                reactions = emptyList(), // New messages typically don't have reactions yet
                smsReactions = emptyList(),
                attachments = attachments,
                handleIdToName = handleIdToName,
                addressToName = addressToName,
                addressToAvatarPath = addressToAvatarPath
            )
        }

        // Add to UI state at the correct position (beginning for DESC sort)
        // Duplicate check MUST be inside update lambda to avoid race with Room Flow
        _uiState.update { state ->
            if (state.messages.any { it.guid == messageDto.guid }) {
                android.util.Log.d("ChatViewModel", "Message ${messageDto.guid} already in UI, skipping")
                return@update state
            }
            val updatedMessages = listOf(messageModel) + state.messages
            android.util.Log.d("ChatViewModel", "Added message to UI, total: ${updatedMessages.size}")
            state.copy(messages = updatedMessages)
        }
    }

    /**
     * Convert a MessageDto to MessageEntity for UI processing.
     */
    private fun MessageDto.toMessageEntity(chatGuid: String): MessageEntity {
        return MessageEntity(
            guid = guid,
            chatGuid = chatGuid,
            handleId = handleId,
            senderAddress = handle?.address,
            text = text,
            subject = subject,
            dateCreated = dateCreated ?: System.currentTimeMillis(),
            dateRead = dateRead,
            dateDelivered = dateDelivered,
            dateEdited = dateEdited,
            datePlayed = datePlayed,
            isFromMe = isFromMe,
            error = error,
            itemType = itemType,
            groupTitle = groupTitle,
            groupActionType = groupActionType,
            balloonBundleId = balloonBundleId,
            associatedMessageGuid = associatedMessageGuid,
            associatedMessagePart = associatedMessagePart,
            associatedMessageType = associatedMessageType,
            expressiveSendStyleId = expressiveSendStyleId,
            threadOriginatorGuid = threadOriginatorGuid,
            threadOriginatorPart = threadOriginatorPart,
            hasAttachments = attachments?.isNotEmpty() == true,
            hasReactions = hasReactions,
            bigEmoji = bigEmoji,
            wasDeliveredQuietly = wasDeliveredQuietly,
            didNotifyRecipient = didNotifyRecipient,
            messageSource = if (handle?.service?.equals("SMS", ignoreCase = true) == true) "SERVER_SMS" else "IMESSAGE"
        )
    }

    /**
     * Observe message updates from socket for immediate UI updates.
     * Handles read receipts, delivery status, edits, reactions, etc.
     */
    private fun observeMessageUpdates() {
        viewModelScope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.MessageUpdated>()
                .filter { event ->
                    val normalizedEventGuid = normalizeGuid(event.chatGuid)
                    mergedChatGuids.any { normalizeGuid(it) == normalizedEventGuid } ||
                        normalizeGuid(chatGuid) == normalizedEventGuid ||
                        extractAddress(event.chatGuid)?.let { eventAddress ->
                            mergedChatGuids.any { extractAddress(it) == eventAddress } ||
                                extractAddress(chatGuid) == eventAddress
                        } == true
                }
                .collect { event ->
                    android.util.Log.d("ChatViewModel", "Socket: Message updated for ${event.chatGuid}, guid=${event.message.guid}")
                    // Directly update the message in UI state
                    directlyUpdateMessageInUi(event.message, event.chatGuid)
                }
        }
    }

    /**
     * Directly update an existing message in the UI state.
     * Used for read receipts, delivery status, edits, etc.
     */
    private suspend fun directlyUpdateMessageInUi(messageDto: MessageDto, eventChatGuid: String) {
        var shouldPlaySound = false

        // All checks and updates MUST be inside update lambda to avoid race with Room Flow
        _uiState.update { state ->
            val existingIndex = state.messages.indexOfFirst { it.guid == messageDto.guid }

            if (existingIndex == -1) {
                android.util.Log.d("ChatViewModel", "Message ${messageDto.guid} not in UI for update, will add via Room Flow")
                // Message not in UI yet - don't add here, let Room Flow handle it
                // This avoids duplicate add race conditions
                return@update state
            }

            // Update the existing message
            val existingMessage = state.messages[existingIndex]

            // Check if delivery was just confirmed for our outgoing message - play sound
            val wasDelivered = existingMessage.isDelivered
            val isNowDelivered = messageDto.dateDelivered != null
            if (!wasDelivered && isNowDelivered && existingMessage.isFromMe) {
                shouldPlaySound = true
            }

            // Create updated message with new status info (using UI model fields)
            val updatedMessage = existingMessage.copy(
                isDelivered = messageDto.dateDelivered != null || existingMessage.isDelivered,
                isRead = messageDto.dateRead != null || existingMessage.isRead,
                text = messageDto.text ?: existingMessage.text,
                hasError = messageDto.error != 0
            )

            val updatedMessages = state.messages.toMutableList()
            updatedMessages[existingIndex] = updatedMessage
            android.util.Log.d("ChatViewModel", "Updated message at index $existingIndex")
            state.copy(messages = updatedMessages)
        }

        // Play sound outside the update lambda
        if (shouldPlaySound) {
            soundManager.playSendSound()
        }
    }

    /**
     * Normalize a chat GUID for comparison by stripping formatting from phone numbers.
     * Handles cases where server sends "+1234567890" but local has "+1-234-567-890".
     */
    private fun normalizeGuid(guid: String): String {
        val parts = guid.split(";-;")
        if (parts.size != 2) return guid.lowercase()
        val prefix = parts[0].lowercase()
        val address = if (parts[1].contains("@")) {
            // Email address - just lowercase
            parts[1].lowercase()
        } else {
            // Phone number - strip non-digits except leading +
            parts[1].replace(Regex("[^0-9+]"), "")
        }
        return "$prefix;-;$address"
    }

    /**
     * Extract just the address/phone portion from a chat GUID for fallback matching.
     * Returns null if the GUID format is invalid.
     */
    private fun extractAddress(guid: String): String? {
        val parts = guid.split(";-;")
        if (parts.size != 2) return null
        return if (parts[1].contains("@")) {
            parts[1].lowercase()
        } else {
            parts[1].replace(Regex("[^0-9+]"), "")
        }
    }

    /**
     * Force a one-time refresh of messages from Room.
     * This is used when we know new messages have arrived but Room's Flow hasn't emitted.
     */
    private suspend fun forceRefreshMessages() {
        val participantsFlow = chatRepository.observeParticipantsForChats(mergedChatGuids)
        val participants = participantsFlow.first()
        val handleIdToName = participants.associate { it.id to it.displayName }
        val addressToName = participants.associate { normalizeAddress(it.address) to it.displayName }
        val addressToAvatarPath = participants.associate { normalizeAddress(it.address) to it.cachedAvatarPath }

        val messagesFlow = if (isMergedChat) {
            messageRepository.observeMessagesForChats(mergedChatGuids, limit = 50, offset = 0)
        } else {
            messageRepository.observeMessagesForChat(chatGuid, limit = 50, offset = 0)
        }

        val messages = messagesFlow.first()

        // Process messages same as in loadMessages()
        val iMessageReactions = messages.filter { it.isReaction }
        val (smsReactionMessages, regularMessages) = messages
            .filter { !it.isReaction }
            .partition { msg -> msg.text?.let { parseSmsReaction(it) } != null }

        val smsReactions = smsReactionMessages.mapNotNull { msg ->
            val (tapback, quotedText, isRemoval) = parseSmsReaction(msg.text ?: "") ?: return@mapNotNull null
            if (isRemoval) return@mapNotNull null
            val targetGuid = findOriginalMessageGuid(quotedText, regularMessages)
            if (targetGuid != null) {
                SyntheticReaction(
                    targetMessageGuid = targetGuid,
                    tapback = tapback,
                    isFromMe = msg.isFromMe,
                    senderName = null
                )
            } else null
        }

        val reactionsByMessage = iMessageReactions.groupBy { it.associatedMessageGuid }
        val smsReactionsByMessage = smsReactions.groupBy { it.targetMessageGuid }

        // Batch load all attachments in a single query
        val allAttachments = attachmentDao.getAttachmentsForMessages(
            regularMessages.map { it.guid }
        ).groupBy { it.messageGuid }

        val messageModels = regularMessages.map { message ->
            val messageReactions = reactionsByMessage[message.guid].orEmpty()
            val messageSmsReactions = smsReactionsByMessage[message.guid].orEmpty()
            val attachments = allAttachments[message.guid].orEmpty()
            message.toUiModel(
                reactions = messageReactions,
                smsReactions = messageSmsReactions,
                attachments = attachments,
                handleIdToName = handleIdToName,
                addressToName = addressToName,
                addressToAvatarPath = addressToAvatarPath
            )
        }

        _uiState.update { state ->
            state.copy(
                isLoading = false,
                messages = messageModels
            )
        }
    }

    private fun markAsRead() {
        viewModelScope.launch {
            // Mark all chats in merged conversation as read
            for (guid in mergedChatGuids) {
                try {
                    val chat = chatRepository.getChat(guid)

                    if (chat?.isLocalSms == true) {
                        // Local SMS/MMS chat - mark in Android system
                        smsRepository.markThreadAsRead(guid)
                    } else {
                        // iMessage or server SMS - mark via server API
                        chatRepository.markChatAsRead(guid)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ChatViewModel", "Failed to mark $guid as read", e)
                    // Continue with other chats
                }
            }
        }
    }

    fun updateDraft(text: String) {
        _draftText.value = text
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
     * Handle typing indicator logic with debouncing and rate limiting.
     *
     * Optimizations:
     * 1. Uses cached settings (no suspend calls on every keystroke)
     * 2. Only sends started-typing once until stopped-typing is sent
     * 3. Rate limits started-typing to avoid rapid on/off transitions
     * 4. Debounces stopped-typing (3 seconds after last keystroke)
     */
    private fun handleTypingIndicator(text: String) {
        // Only send typing indicators for iMessage chats (not local SMS)
        if (_uiState.value.isLocalSmsChat) return

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

    /**
     * Called when leaving the chat to ensure we send stopped-typing and save draft
     */
    fun onChatLeave() {
        // Clear active conversation tracking to resume notifications
        activeConversationManager.clearActiveConversation()

        // Notify server we're leaving this chat
        socketService.sendCloseChat(chatGuid)

        typingDebounceJob?.cancel()
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            socketService.sendStoppedTyping(chatGuid)
        }
        // Save draft immediately when leaving (cancel debounce and save now)
        draftSaveJob?.cancel()
        viewModelScope.launch {
            chatRepository.updateDraftText(chatGuid, _draftText.value)
            // Save scroll position for state restoration
            val mergedGuidsStr = if (isMergedChat) mergedChatGuids.joinToString(",") else null
            android.util.Log.d("StateRestore", "onChatLeave: saving chatGuid=$chatGuid, scroll=($lastScrollPosition, $lastScrollOffset)")
            settingsDataStore.setLastOpenChat(chatGuid, mergedGuidsStr)
            settingsDataStore.setLastScrollPosition(lastScrollPosition, lastScrollOffset)
        }
    }

    /**
     * Update scroll position for state restoration.
     * Called from ChatScreen when scroll position changes.
     * IMPORTANT: This runs on every scroll frame - must be extremely lightweight!
     */
    fun updateScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int, visibleItemCount: Int = 10) {
        lastScrollPosition = firstVisibleItemIndex
        lastScrollOffset = firstVisibleItemScrollOffset

        // Throttle preloader - only call if BOTH index changed AND enough time has passed
        // Using AND (not OR) to minimize work during scroll
        val now = System.currentTimeMillis()
        val indexChanged = firstVisibleItemIndex != lastPreloadIndex
        val timeElapsed = now - lastPreloadTime >= preloadThrottleMs

        if (indexChanged && timeElapsed) {
            lastPreloadIndex = firstVisibleItemIndex
            lastPreloadTime = now

            // Defer preload work to avoid any main thread blocking
            val messages = _uiState.value.messages
            if (messages.isNotEmpty()) {
                // Update cached attachments only if message count changed
                if (messages.size != cachedAttachmentsMessageCount) {
                    cachedAttachments = messages.flatMap { it.attachments }
                    cachedAttachmentsMessageCount = messages.size
                }

                if (cachedAttachments.isNotEmpty()) {
                    val visibleEnd = (firstVisibleItemIndex + visibleItemCount).coerceAtMost(messages.size - 1)
                    // Preload images for ~3 screens in each direction to prevent image pop-in
                    attachmentPreloader.preloadNearby(
                        attachments = cachedAttachments,
                        visibleRange = firstVisibleItemIndex..visibleEnd,
                        preloadCount = 15
                    )

                    // Boost download priority for attachments in extended visible range
                    // This ensures attachments needing download from server get prioritized
                    val extendedStart = (firstVisibleItemIndex - 15).coerceAtLeast(0)
                    val extendedEnd = (visibleEnd + 15).coerceAtMost(messages.size - 1)
                    for (i in extendedStart..extendedEnd) {
                        messages.getOrNull(i)?.attachments?.forEach { attachment ->
                            if (attachment.needsDownload) {
                                attachmentDownloadQueue.enqueue(
                                    attachmentGuid = attachment.guid,
                                    chatGuid = chatGuid,
                                    priority = AttachmentDownloadQueue.Priority.VISIBLE
                                )
                            }
                        }
                    }
                }
            }
        }

        // Throttled save to DataStore - uses timestamp instead of Job cancel/recreate
        // This avoids object allocation and scheduling overhead on every scroll frame
        val saveTimeElapsed = now - lastScrollSaveTime >= scrollSaveDebounceMs
        if (saveTimeElapsed) {
            lastScrollSaveTime = now
            viewModelScope.launch {
                settingsDataStore.setLastScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
            }
        }
    }

    /**
     * Mark that the user has navigated away from chat (back to conversation list).
     * This clears the saved chat state so the app opens to conversation list next time.
     */
    fun onNavigateBack() {
        android.util.Log.d("StateRestore", "onNavigateBack: clearing saved chat state")
        viewModelScope.launch {
            settingsDataStore.clearLastOpenChat()
        }
    }

    override fun onCleared() {
        super.onCleared()
        onChatLeave()
        attachmentPreloader.clearTracking()
        // Clear active chat from download queue so priority is reset
        attachmentDownloadQueue.setActiveChat(null)
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
        val text = _draftText.value.trim()
        val attachments = _pendingAttachments.value
        val replyToGuid = _uiState.value.replyingToGuid

        if (text.isBlank() && attachments.isEmpty()) return

        // Stop typing indicator immediately when sending
        typingDebounceJob?.cancel()
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            socketService.sendStoppedTyping(chatGuid)
        }

        viewModelScope.launch {
            val sendId = PerformanceProfiler.start("Message.send", "${text.take(20)}...")
            val isLocalSms = _uiState.value.isLocalSmsChat

            // Clear UI state immediately for responsive feel
            _draftText.value = ""
            _pendingAttachments.value = emptyList()
            _uiState.update { state ->
                state.copy(
                    replyingToGuid = null,
                    attachmentCount = 0
                )
            }
            // Clear draft from database
            draftSaveJob?.cancel()
            chatRepository.updateDraftText(chatGuid, null)

            // Determine delivery mode
            val deliveryMode = when {
                isLocalSms -> if (attachments.isNotEmpty()) MessageDeliveryMode.LOCAL_MMS else MessageDeliveryMode.LOCAL_SMS
                else -> MessageDeliveryMode.AUTO
            }

            // Queue message for offline-first delivery via WorkManager
            // Message is persisted to database immediately and sent when network is available
            pendingMessageRepository.queueMessage(
                chatGuid = chatGuid,
                text = text,
                replyToGuid = replyToGuid,
                effectId = effectId,
                attachments = attachments,
                deliveryMode = deliveryMode
            ).fold(
                onSuccess = { localId ->
                    Log.d(TAG, "Message queued successfully: $localId")
                    PerformanceProfiler.end(sendId, "queued")

                    // Play sound for local SMS (optimistic - actual send happens via WorkManager)
                    // For iMessage, sound plays when delivery is confirmed via socket event
                    if (isLocalSms) {
                        soundManager.playSendSound()
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to queue message", e)
                    _uiState.update { state ->
                        state.copy(error = "Failed to queue message: ${e.message}")
                    }
                    PerformanceProfiler.end(sendId, "queue-failed: ${e.message}")
                }
            )
        }
    }

    /**
     * Calculate aggregate progress across all pending messages.
     * Each message contributes: 10% base + 90% * its progress
     */
    private fun calculateAggregateProgress(pendingMessages: List<PendingMessage>): Float {
        if (pendingMessages.isEmpty()) return 0f
        val totalProgress = pendingMessages.sumOf { msg ->
            (0.1f + 0.9f * msg.progress).toDouble()
        }
        return (totalProgress / pendingMessages.size).toFloat()
    }

    /**
     * Send message with explicit delivery mode override
     */
    fun sendMessageVia(deliveryMode: MessageDeliveryMode) {
        val text = _draftText.value.trim()
        val attachments = _pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        viewModelScope.launch {
            val isLocalSms = deliveryMode == MessageDeliveryMode.LOCAL_SMS ||
                            deliveryMode == MessageDeliveryMode.LOCAL_MMS

            // Clear UI state immediately
            _draftText.value = ""
            _pendingAttachments.value = emptyList()
            _uiState.update { it.copy(attachmentCount = 0) }

            // Clear draft from database
            draftSaveJob?.cancel()
            chatRepository.updateDraftText(chatGuid, null)

            // Queue message for offline-first delivery
            pendingMessageRepository.queueMessage(
                chatGuid = chatGuid,
                text = text,
                attachments = attachments,
                deliveryMode = deliveryMode
            ).fold(
                onSuccess = { localId ->
                    Log.d(TAG, "Message queued via $deliveryMode: $localId")
                    if (isLocalSms) {
                        soundManager.playSendSound()
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to queue message via $deliveryMode", e)
                    _uiState.update { state ->
                        state.copy(error = "Failed to queue message: ${e.message}")
                    }
                }
            )
        }
    }

    /**
     * Set the message to reply to (for swipe-to-reply)
     */
    fun setReplyTo(messageGuid: String) {
        _uiState.update { it.copy(replyingToGuid = messageGuid) }
    }

    /**
     * Clear the reply state
     */
    fun clearReply() {
        _uiState.update { it.copy(replyingToGuid = null) }
    }

    /**
     * Toggle a reaction on a message.
     * For iMessage: Uses native tapback API (adds or removes reaction).
     * For SMS (local or server): Sends a text message like 'Loved "message text"' that iPhones understand.
     */
    fun toggleReaction(messageGuid: String, tapback: Tapback) {
        val message = _uiState.value.messages.find { it.guid == messageGuid } ?: return
        val isLocalSms = _uiState.value.isLocalSmsChat
        val isServerSms = chatGuid.startsWith("SMS;", ignoreCase = true)  // Server text forwarding
        val isRemoving = tapback in message.myReactions

        viewModelScope.launch {
            when {
                isLocalSms -> {
                    // Local SMS: send text reaction directly via Android SMS
                    val originalText = message.text ?: return@launch
                    val reactionText = if (isRemoving) {
                        tapback.toSmsRemovalText(originalText)
                    } else {
                        tapback.toSmsText(originalText)
                    }
                    messageRepository.sendUnified(
                        chatGuid = chatGuid,
                        text = reactionText,
                        deliveryMode = MessageDeliveryMode.LOCAL_SMS
                    )
                }
                isServerSms -> {
                    // Server SMS (text forwarding): send text reaction via BlueBubbles server
                    // The server will forward this as SMS to the recipient
                    val originalText = message.text ?: return@launch
                    val reactionText = if (isRemoving) {
                        tapback.toSmsRemovalText(originalText)
                    } else {
                        tapback.toSmsText(originalText)
                    }
                    messageRepository.sendUnified(
                        chatGuid = chatGuid,
                        text = reactionText,
                        deliveryMode = MessageDeliveryMode.IMESSAGE  // Routes through server
                    )
                }
                else -> {
                    // iMessage: use the native reaction API
                    if (isRemoving) {
                        messageRepository.removeReaction(
                            chatGuid = chatGuid,
                            messageGuid = messageGuid,
                            reaction = tapback.apiName
                        )
                    } else {
                        messageRepository.sendReaction(
                            chatGuid = chatGuid,
                            messageGuid = messageGuid,
                            reaction = tapback.apiName
                        )
                    }
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
        Log.d("ChatScroll", "[VM] loadMoreMessages called: isLoadingMore=${_uiState.value.isLoadingMore}, canLoadMore=${_uiState.value.canLoadMore}")
        if (_uiState.value.isLoadingMore || !_uiState.value.canLoadMore) {
            Log.d("ChatScroll", "[VM] loadMoreMessages SKIPPED - already loading or no more to load")
            return
        }

        val oldestMessage = _uiState.value.messages.lastOrNull()
        if (oldestMessage == null) {
            Log.d("ChatScroll", "[VM] loadMoreMessages SKIPPED - no messages in list")
            return
        }

        Log.d("ChatScroll", "[VM] >>> STARTING loadMoreMessages: oldestDate=${oldestMessage.dateCreated}, currentMsgCount=${_uiState.value.messages.size}")

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            val startTime = System.currentTimeMillis()
            messageRepository.syncMessagesForChat(
                chatGuid = chatGuid,
                before = oldestMessage.dateCreated,
                limit = 50
            ).fold(
                onSuccess = { messages ->
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d("ChatScroll", "[VM] loadMoreMessages SUCCESS: fetched=${messages.size} messages in ${elapsed}ms")
                    // Increase the message limit to include newly fetched older messages
                    // This triggers flatMapLatest in loadMessages() to re-query with expanded limit
                    _messageLimit.value += messages.size
                    Log.d("ChatScroll", "[VM] loadMoreMessages: new messageLimit=${_messageLimit.value}")

                    _uiState.update { state ->
                        state.copy(
                            isLoadingMore = false,
                            canLoadMore = messages.size == 50
                        )
                    }
                    Log.d("ChatScroll", "[VM] loadMoreMessages COMPLETE: canLoadMore=${messages.size == 50}")
                },
                onFailure = { error ->
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.e("ChatScroll", "[VM] loadMoreMessages FAILED after ${elapsed}ms: ${error.message}")
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
        handleIdToName: Map<Long, String> = emptyMap(),
        addressToName: Map<String, String> = emptyMap(),
        addressToAvatarPath: Map<String, String?> = emptyMap(),
        replyPreview: ReplyPreviewData? = null
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
                    blurhash = attachment.blurhash,
                    thumbnailPath = attachment.thumbnailPath
                )
            }

        return MessageUiModel(
            guid = guid,
            text = text,
            subject = subject,
            dateCreated = dateCreated,
            formattedTime = formatTime(dateCreated),
            isFromMe = isFromMe,
            isSent = !guid.startsWith("temp-") && error == 0,
            isDelivered = dateDelivered != null,
            isRead = dateRead != null,
            hasError = error != 0,
            isReaction = associatedMessageType?.contains("reaction") == true,
            attachments = attachmentUiModels,
            // Resolve sender name: try senderAddress first (most accurate), then fall back to handleId lookup
            senderName = resolveSenderName(senderAddress, handleId, addressToName, handleIdToName),
            senderAvatarPath = resolveSenderAvatarPath(senderAddress, addressToAvatarPath),
            messageSource = messageSource,
            reactions = allReactions,
            myReactions = myReactions,
            expressiveSendStyleId = expressiveSendStyleId,
            effectPlayed = datePlayed != null,
            associatedMessageGuid = associatedMessageGuid,
            // Reply indicator fields
            threadOriginatorGuid = threadOriginatorGuid,
            replyPreview = replyPreview,
            // Pre-compute emoji analysis to avoid recalculating on every composition
            emojiAnalysis = analyzeEmojis(text)
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
     * Normalize an address for comparison/lookup.
     * Strips non-essential characters from phone numbers, lowercases emails.
     */
    private fun normalizeAddress(address: String): String {
        return if (address.contains("@")) {
            address.lowercase()
        } else {
            address.replace(Regex("[^0-9+]"), "")
        }
    }

    /**
     * Resolve sender name from available data sources.
     * Priority: senderAddress lookup > handleId lookup > formatted address
     */
    private fun resolveSenderName(
        senderAddress: String?,
        handleId: Long?,
        addressToName: Map<String, String>,
        handleIdToName: Map<Long, String>
    ): String? {
        // 1. Try looking up by senderAddress (most accurate for group chats)
        senderAddress?.let { address ->
            val normalized = normalizeAddress(address)
            addressToName[normalized]?.let { return it }
            // No contact match - return formatted phone number
            return PhoneNumberFormatter.format(address)
        }

        // 2. Fall back to handleId lookup
        return handleId?.let { handleIdToName[it] }
    }

    /**
     * Resolve sender avatar path from address.
     */
    private fun resolveSenderAvatarPath(
        senderAddress: String?,
        addressToAvatarPath: Map<String, String?>
    ): String? {
        senderAddress?.let { address ->
            val normalized = normalizeAddress(address)
            return addressToAvatarPath[normalized]
        }
        return null
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

private data class ParticipantMaps(
    val messages: List<MessageEntity>,
    val handleIdToName: Map<Long, String>,
    val addressToName: Map<String, String>,
    val addressToAvatarPath: Map<String, String?>
)

/**
 * Tracks a pending outgoing message for progress bar display.
 */
data class PendingMessage(
    val tempGuid: String,
    val progress: Float,  // 0.0 to 1.0 (individual message progress)
    val hasAttachments: Boolean,
    val isLocalSms: Boolean  // Track protocol for color coding
)

/**
 * UI model for queued messages (persisted pending messages from the offline queue).
 */
data class QueuedMessageUiModel(
    val localId: String,
    val text: String?,
    val hasAttachments: Boolean,
    val syncStatus: PendingSyncStatus,
    val errorMessage: String?,
    val createdAt: Long
)

data class ChatUiState(
    val chatTitle: String = "",
    val isGroup: Boolean = false,
    val avatarPath: String? = null,
    val participantNames: List<String> = emptyList(),
    val participantAvatarPaths: List<String?> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isSyncingMessages: Boolean = false,
    val isSending: Boolean = false,
    val isSendingWithAttachments: Boolean = false, // True if current send includes attachments
    val sendProgress: Float = 0f, // 0.0 to 1.0 progress for uploads
    val pendingMessages: List<PendingMessage> = emptyList(), // Track multiple pending sends (in-memory)
    val queuedMessages: List<QueuedMessageUiModel> = emptyList(), // Persisted pending messages from offline queue
    val canLoadMore: Boolean = true,
    val messages: List<MessageUiModel> = emptyList(),
    val isTyping: Boolean = false,
    val error: String? = null,
    val isLocalSmsChat: Boolean = false,
    val smsInputBlocked: Boolean = false, // True if SMS chat but not default SMS app
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
    // Highlighted message (from notification deep-link)
    val highlightedMessageGuid: String? = null,
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
    val snoozeUntil: Long? = null,
    // Reply state
    val replyingToGuid: String? = null
)
