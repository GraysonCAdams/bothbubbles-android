package com.bluebubbles.ui.conversations

import android.app.Application
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.ChatEntity
import com.bluebubbles.data.local.db.entity.MessageEntity
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.data.remote.api.BlueBubblesApi
import com.bluebubbles.data.repository.ChatRepository
import com.bluebubbles.data.repository.LinkPreviewRepository
import com.bluebubbles.data.repository.SmsRepository
import com.bluebubbles.services.socket.ConnectionState
import com.bluebubbles.services.socket.SocketEvent
import com.bluebubbles.services.socket.SocketService
import com.bluebubbles.services.sync.SyncService
import com.bluebubbles.services.sync.SyncState
import com.bluebubbles.ui.components.ConnectionBannerState
import com.bluebubbles.ui.components.SmsBannerState
import com.bluebubbles.ui.components.SwipeActionType
import com.bluebubbles.ui.components.SwipeConfig
import com.bluebubbles.ui.components.determineConnectionBannerState
import com.bluebubbles.ui.components.determineSmsBannerState
import com.bluebubbles.ui.components.UrlParsingUtils
import com.bluebubbles.services.contacts.AndroidContactsService
import com.bluebubbles.services.sms.SmsPermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val application: Application,
    private val chatRepository: ChatRepository,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val socketService: SocketService,
    private val syncService: SyncService,
    private val settingsDataStore: SettingsDataStore,
    private val api: BlueBubblesApi,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val smsRepository: SmsRepository,
    private val androidContactsService: AndroidContactsService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _typingChats = MutableStateFlow<Set<String>>(emptySet())
    private val _smsStateRefreshTrigger = MutableStateFlow(0)

    // Track if we've ever successfully connected (for banner logic)
    private var wasEverConnected = false

    init {
        loadConversations()
        observeConnectionState()
        observeConnectionBannerState()
        observeSmsBannerState()
        observeTypingIndicators()
        observeSyncState()
        observeSwipeSettings()
        observeMessageSearch()
        observeAppTitleSetting()
        loadUserProfile()
        checkPrivateApiPrompt()
        checkInitialSmsImport()
    }

    private fun observeAppTitleSetting() {
        viewModelScope.launch {
            settingsDataStore.useSimpleAppTitle.collect { useSimple ->
                _uiState.update { it.copy(useSimpleAppTitle = useSimple) }
            }
        }
    }

    /**
     * Observe SMS enabled state and trigger import when:
     * 1. SMS is enabled
     * 2. Initial import hasn't been completed
     * 3. We have read permission
     * 4. Not currently importing
     *
     * This allows import to trigger both on app startup and when SMS is
     * enabled/re-enabled in settings.
     */
    private fun checkInitialSmsImport() {
        viewModelScope.launch {
            // Combine SMS enabled state and initial import completed state
            combine(
                settingsDataStore.smsEnabled,
                settingsDataStore.hasCompletedInitialSmsImport
            ) { smsEnabled, hasImported ->
                smsEnabled to hasImported
            }.distinctUntilChanged().collect { (smsEnabled, hasImported) ->
                // Trigger import when SMS is enabled and not yet imported
                if (smsEnabled && !hasImported &&
                    smsPermissionHelper.hasReadSmsPermission() &&
                    !_uiState.value.isImportingSms) {
                    startSmsImport()
                }
            }
        }
    }

    /**
     * Auto-enable Private API when connected to a server that supports it.
     * This runs once per server connection to sync the client setting with server capability.
     */
    private fun checkPrivateApiPrompt() {
        viewModelScope.launch {
            // Wait for connection state to settle
            socketService.connectionState
                .filter { it == ConnectionState.CONNECTED }
                .take(1)
                .collect {
                    // Check if we've already synced Private API setting
                    val hasChecked = settingsDataStore.hasShownPrivateApiPrompt.first()
                    if (hasChecked) return@collect

                    // Fetch server info to check Private API availability
                    try {
                        val response = api.getServerInfo()
                        if (response.isSuccessful) {
                            val serverInfo = response.body()?.data
                            // Auto-enable if server supports it
                            if (serverInfo?.privateApi == true) {
                                settingsDataStore.setEnablePrivateApi(true)
                            }
                            // Mark as checked so we don't repeat
                            settingsDataStore.setHasShownPrivateApiPrompt(true)
                        }
                    } catch (e: Exception) {
                        // Silently fail - will retry on next launch
                    }
                }
        }
    }

    private fun loadUserProfile() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val contentResolver = application.contentResolver

                    // Query the device owner's profile contact
                    val profileUri = ContactsContract.Profile.CONTENT_URI
                    val projection = arrayOf(
                        ContactsContract.Profile.DISPLAY_NAME,
                        ContactsContract.Profile.PHOTO_URI
                    )

                    contentResolver.query(profileUri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(ContactsContract.Profile.DISPLAY_NAME)
                            val photoIndex = cursor.getColumnIndex(ContactsContract.Profile.PHOTO_URI)

                            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                            val photoUri = if (photoIndex >= 0) cursor.getString(photoIndex) else null

                            _uiState.update {
                                it.copy(
                                    userProfileName = name,
                                    userProfileAvatarUri = photoUri
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Silently fail if we can't access profile (missing permission, etc.)
                }
            }
        }
    }

    private fun loadConversations() {
        viewModelScope.launch {
            combine(
                chatRepository.observeActiveChats(),
                _searchQuery,
                _typingChats
            ) { chats, query, typingChats ->
                val filtered = if (query.isBlank()) {
                    chats
                } else {
                    chats.filter { chat ->
                        chat.displayName?.contains(query, ignoreCase = true) == true ||
                        chat.chatIdentifier?.contains(query, ignoreCase = true) == true
                    }
                }
                // Sort: pinned first, then by last message time
                filtered
                    .sortedWith(
                        compareByDescending<ChatEntity> { it.isPinned }
                            .thenByDescending { it.lastMessageDate ?: 0L }
                    )
                    .map { it.toUiModel(typingChats) }
            }.collect { conversations ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        conversations = conversations
                    )
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            socketService.connectionState.collect { state ->
                // Track if we ever connected (for banner logic)
                if (state == ConnectionState.CONNECTED) {
                    wasEverConnected = true
                    // Reset dismissed banner state when connection is established
                    settingsDataStore.resetSetupBannerDismissal()
                }

                _uiState.update {
                    it.copy(
                        isConnected = state == ConnectionState.CONNECTED,
                        connectionState = state
                    )
                }
            }
        }
    }

    private fun observeConnectionBannerState() {
        viewModelScope.launch {
            combine(
                socketService.connectionState,
                socketService.retryAttempt,
                settingsDataStore.dismissedSetupBanner
            ) { connectionState, retryAttempt, isSetupBannerDismissed ->
                determineConnectionBannerState(
                    connectionState = connectionState,
                    retryAttempt = retryAttempt,
                    isSetupBannerDismissed = isSetupBannerDismissed,
                    wasEverConnected = wasEverConnected
                )
            }.collect { bannerState ->
                _uiState.update { it.copy(connectionBannerState = bannerState) }
            }
        }
    }

    fun dismissSetupBanner() {
        viewModelScope.launch {
            settingsDataStore.setDismissedSetupBanner(true)
        }
    }

    fun retryConnection() {
        socketService.retryNow()
    }

    private fun observeSmsBannerState() {
        viewModelScope.launch {
            combine(
                settingsDataStore.smsEnabled,
                settingsDataStore.dismissedSmsBanner,
                _smsStateRefreshTrigger
            ) { smsEnabled, isSmsBannerDismissed, _ ->
                val smsStatus = smsPermissionHelper.getSmsCapabilityStatus()
                determineSmsBannerState(
                    smsEnabled = smsEnabled,
                    isFullyFunctional = smsStatus.isFullyFunctional,
                    isSmsBannerDismissed = isSmsBannerDismissed
                )
            }.collect { bannerState ->
                _uiState.update { it.copy(smsBannerState = bannerState) }
            }
        }
    }

    fun dismissSmsBanner() {
        viewModelScope.launch {
            settingsDataStore.setDismissedSmsBanner(true)
        }
    }

    /**
     * Refresh SMS banner state. Call this when returning to the screen
     * to re-check default app and permission status.
     */
    fun refreshSmsState() {
        _smsStateRefreshTrigger.value++
    }

    /**
     * Create intent to request becoming the default SMS app.
     * Returns the intent for the activity to launch.
     */
    fun getSmsDefaultAppIntent() = smsPermissionHelper.createDefaultSmsAppIntent()

    private fun observeTypingIndicators() {
        viewModelScope.launch {
            socketService.events
                .filterIsInstance<SocketEvent.TypingIndicator>()
                .collect { event ->
                    _typingChats.update { chats ->
                        if (event.isTyping) {
                            chats + event.chatGuid
                        } else {
                            chats - event.chatGuid
                        }
                    }
                }
        }
    }

    private fun observeSyncState() {
        viewModelScope.launch {
            syncService.syncState.collect { state ->
                _uiState.update {
                    it.copy(
                        isSyncing = state is SyncState.Syncing,
                        syncProgress = if (state is SyncState.Syncing) state.progress else null
                    )
                }
            }
        }
    }

    private fun observeSwipeSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.swipeGesturesEnabled,
                settingsDataStore.swipeLeftAction,
                settingsDataStore.swipeRightAction,
                settingsDataStore.swipeSensitivity
            ) { enabled, leftAction, rightAction, sensitivity ->
                SwipeConfig(
                    enabled = enabled,
                    leftAction = SwipeActionType.fromKey(leftAction),
                    rightAction = SwipeActionType.fromKey(rightAction),
                    sensitivity = sensitivity
                )
            }.collect { config ->
                _uiState.update { it.copy(swipeConfig = config) }
            }
        }
    }

    private fun observeMessageSearch() {
        viewModelScope.launch {
            _searchQuery
                .debounce(300) // Wait 300ms after typing stops
                .distinctUntilChanged()
                .collect { query ->
                    if (query.length >= 2) {
                        // Get text matches
                        val textMatchMessages = messageDao.searchMessages(query, 50).first()

                        // Get link preview title matches
                        val linkTitleMatches = linkPreviewRepository.searchByTitle(query, 20)
                        val matchedUrls = linkTitleMatches.map { it.url }.toSet()
                        val matchedPreviewsByUrl = linkTitleMatches.associateBy { it.url }

                        // Search messages containing matched link URLs
                        val linkMatchMessages = mutableListOf<MessageEntity>()
                        for (url in matchedUrls) {
                            linkMatchMessages.addAll(messageDao.searchMessages(url, 10).first())
                        }

                        // Combine and deduplicate by message guid
                        val allMessages = (textMatchMessages + linkMatchMessages)
                            .distinctBy { it.guid }
                            .take(50)

                        val results = allMessages.mapNotNull { message ->
                            val chat = chatDao.getChatByGuid(message.chatGuid)
                            if (chat != null) {
                                val messageText = message.text ?: ""

                                // Get participant info for avatar
                                val participants = chatDao.getParticipantsForChat(message.chatGuid)
                                val primaryParticipant = participants.firstOrNull()

                                // Determine message type and get link preview data
                                val (messageType, linkTitle, linkDomain) = if (messageText.contains("http://") || messageText.contains("https://")) {
                                    val detectedUrl = UrlParsingUtils.getFirstUrl(messageText)
                                    if (detectedUrl != null) {
                                        val preview = matchedPreviewsByUrl[detectedUrl.url]
                                            ?: linkPreviewRepository.getLinkPreview(detectedUrl.url)
                                        val title = preview?.title?.takeIf { t -> t.isNotBlank() }
                                        val domain = preview?.domain ?: detectedUrl.domain
                                        Triple(MessageType.LINK, title, domain)
                                    } else {
                                        Triple(MessageType.TEXT, null, null)
                                    }
                                } else {
                                    Triple(MessageType.TEXT, null, null)
                                }

                                // Resolve display name for 1:1 vs group chats
                                val resolvedDisplayName = if (!chat.isGroup && primaryParticipant != null) {
                                    primaryParticipant.displayName
                                } else {
                                    chat.displayName ?: chat.chatIdentifier ?: "Unknown"
                                }

                                MessageSearchResult(
                                    messageGuid = message.guid,
                                    chatGuid = message.chatGuid,
                                    chatDisplayName = resolvedDisplayName,
                                    messageText = messageText,
                                    timestamp = message.dateCreated,
                                    formattedTime = formatRelativeTime(message.dateCreated),
                                    isFromMe = message.isFromMe,
                                    avatarPath = primaryParticipant?.cachedAvatarPath,
                                    isGroup = chat.isGroup,
                                    messageType = messageType,
                                    linkTitle = linkTitle,
                                    linkDomain = linkDomain
                                )
                            } else null
                        }
                        _uiState.update { it.copy(messageSearchResults = results) }
                    } else {
                        _uiState.update { it.copy(messageSearchResults = emptyList()) }
                    }
                }
        }
    }

    fun handleSwipeAction(chatGuid: String, action: SwipeActionType) {
        viewModelScope.launch {
            when (action) {
                SwipeActionType.PIN, SwipeActionType.UNPIN -> togglePin(chatGuid)
                SwipeActionType.ARCHIVE -> archiveChat(chatGuid)
                SwipeActionType.DELETE -> deleteChat(chatGuid)
                SwipeActionType.MUTE, SwipeActionType.UNMUTE -> toggleMute(chatGuid)
                SwipeActionType.MARK_READ -> markAsRead(chatGuid)
                SwipeActionType.MARK_UNREAD -> markAsUnread(chatGuid)
                SwipeActionType.SNOOZE -> snoozeChat(chatGuid, 1 * 60 * 60 * 1000L) // Quick snooze for 1 hour
                SwipeActionType.UNSNOOZE -> unsnoozeChat(chatGuid)
                SwipeActionType.NONE -> { /* No action */ }
            }
        }
    }

    fun snoozeChat(chatGuid: String, durationMs: Long) {
        viewModelScope.launch {
            chatRepository.snoozeChat(chatGuid, durationMs)
        }
    }

    fun unsnoozeChat(chatGuid: String) {
        viewModelScope.launch {
            chatRepository.unsnoozeChat(chatGuid)
        }
    }

    fun markAsUnread(chatGuid: String) {
        viewModelScope.launch {
            chatRepository.markChatAsUnread(chatGuid)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            syncService.performIncrementalSync()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * Start importing SMS/MMS messages from the device.
     * Shows progress at the bottom of the conversation list.
     */
    fun startSmsImport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isImportingSms = true, smsImportProgress = 0f, smsImportError = null) }

            smsRepository.importAllThreads(
                limit = 500,
                onProgress = { current, total ->
                    _uiState.update {
                        it.copy(smsImportProgress = current.toFloat() / total.toFloat())
                    }
                }
            ).fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            isImportingSms = false,
                            smsImportProgress = 1f
                        )
                    }
                    // Mark as completed ONLY after successful import
                    settingsDataStore.setHasCompletedInitialSmsImport(true)
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isImportingSms = false,
                            smsImportError = e.message
                        )
                    }
                }
            )
        }
    }

    fun dismissSmsImportError() {
        _uiState.update { it.copy(smsImportError = null) }
    }

    fun togglePin(chatGuid: String) {
        viewModelScope.launch {
            val chat = chatRepository.getChat(chatGuid)
            chat?.let {
                // If already pinned, always allow unpinning
                // If not pinned, only allow pinning if the contact is saved
                if (it.isPinned || canPinChat(chatGuid)) {
                    chatRepository.setPinned(chatGuid, !it.isPinned)
                }
            }
        }
    }

    /**
     * Check if a chat can be pinned.
     * Only chats with saved contacts can be pinned.
     * Chats that are already pinned can always be unpinned.
     */
    fun canPinChat(chatGuid: String): Boolean {
        val conversation = _uiState.value.conversations.find { it.guid == chatGuid }
        return conversation?.hasContact == true
    }

    fun toggleMute(chatGuid: String) {
        viewModelScope.launch {
            val chat = chatRepository.getChat(chatGuid)
            chat?.let {
                val currentlyMuted = it.muteType != null
                chatRepository.setMuted(chatGuid, !currentlyMuted)
            }
        }
    }

    fun archiveChat(chatGuid: String) {
        viewModelScope.launch {
            chatRepository.setArchived(chatGuid, true)
        }
    }

    fun deleteChat(chatGuid: String) {
        viewModelScope.launch {
            chatRepository.deleteChat(chatGuid)
        }
    }

    fun markAsRead(chatGuid: String) {
        viewModelScope.launch {
            chatRepository.markChatAsRead(chatGuid)
        }
    }

    fun markChatsAsUnread(chatGuids: Set<String>) {
        viewModelScope.launch {
            chatGuids.forEach { chatGuid ->
                chatRepository.markChatAsUnread(chatGuid)
            }
        }
    }

    fun markChatsAsRead(chatGuids: Set<String>) {
        viewModelScope.launch {
            chatGuids.forEach { chatGuid ->
                chatRepository.markChatAsRead(chatGuid)
            }
        }
    }

    fun blockChats(chatGuids: Set<String>) {
        viewModelScope.launch {
            chatGuids.forEach { chatGuid ->
                // Get the phone number for this chat
                val address = chatRepository.getChatParticipantAddress(chatGuid)

                // Block using Android's native BlockedNumberContract
                if (address != null) {
                    androidContactsService.blockNumber(address)
                }

                // Also archive the chat locally
                chatRepository.setArchived(chatGuid, true)
            }
        }
    }

    /**
     * Check if a contact is starred (favorite) in Android Contacts.
     * Should be called on IO thread for best performance.
     */
    fun isContactStarred(address: String): Boolean {
        return androidContactsService.isContactStarred(address)
    }

    /**
     * Toggle the starred (favorite) status of a contact in Android Contacts.
     * Returns true if the toggle was successful.
     */
    fun toggleContactStarred(address: String, starred: Boolean): Boolean {
        return androidContactsService.setContactStarred(address, starred)
    }

    /**
     * Dismiss an inferred name for a contact (user indicated it was wrong).
     */
    fun dismissInferredName(address: String) {
        viewModelScope.launch {
            handleDao.clearInferredNameByAddress(address)
        }
    }

    private suspend fun ChatEntity.toUiModel(typingChats: Set<String>): ConversationUiModel {
        val lastMessage = messageDao.getLatestMessageForChat(guid)
        val messageText = lastMessage?.text ?: lastMessageText ?: ""
        val isFromMe = lastMessage?.isFromMe ?: false

        // Get participants for this chat
        val participants = chatDao.getParticipantsForChat(guid)
        val participantNames = participants.map { it.displayName }
        val primaryParticipant = participants.firstOrNull()
        val address = primaryParticipant?.address ?: chatIdentifier ?: ""
        val avatarPath = primaryParticipant?.cachedAvatarPath

        // Determine message type from attachments or content
        val messageType = when {
            lastMessage?.hasAttachments == true -> {
                // Check attachment mime type if available
                val mimeType = lastMessage.associatedMessageType ?: ""
                when {
                    mimeType.startsWith("image/") -> MessageType.IMAGE
                    mimeType.startsWith("video/") -> MessageType.VIDEO
                    mimeType.startsWith("audio/") -> MessageType.AUDIO
                    mimeType.isNotEmpty() -> MessageType.ATTACHMENT
                    else -> MessageType.TEXT
                }
            }
            messageText.contains("http://") || messageText.contains("https://") -> MessageType.LINK
            else -> MessageType.TEXT
        }

        // Determine message status for outgoing messages
        val messageStatus = when {
            !isFromMe -> MessageStatus.NONE
            lastMessage == null -> MessageStatus.NONE
            lastMessage.guid.startsWith("temp-") -> MessageStatus.SENDING
            lastMessage.dateRead != null -> MessageStatus.READ
            lastMessage.dateDelivered != null -> MessageStatus.DELIVERED
            lastMessage.error == 0 -> MessageStatus.SENT
            else -> MessageStatus.NONE
        }

        // For 1:1 chats, use the participant's display name (includes "Maybe:" prefix for inferred names)
        // For group chats, use the chat's display name
        val resolvedDisplayName = if (!isGroup && primaryParticipant != null) {
            primaryParticipant.displayName
        } else {
            displayName ?: chatIdentifier ?: "Unknown"
        }

        // Fetch link preview data for LINK type messages
        val (linkTitle, linkDomain) = if (messageType == MessageType.LINK) {
            val detectedUrl = UrlParsingUtils.getFirstUrl(messageText)
            if (detectedUrl != null) {
                val preview = linkPreviewRepository.getLinkPreview(detectedUrl.url)
                val title = preview?.title?.takeIf { it.isNotBlank() }
                val domain = preview?.domain ?: detectedUrl.domain
                title to domain
            } else {
                null to null
            }
        } else {
            null to null
        }

        return ConversationUiModel(
            guid = guid,
            displayName = resolvedDisplayName,
            avatarPath = avatarPath,
            lastMessageText = messageText,
            lastMessageTime = formatRelativeTime(lastMessage?.dateCreated ?: lastMessageDate ?: 0L),
            lastMessageTimestamp = lastMessage?.dateCreated ?: lastMessageDate ?: 0L,
            unreadCount = unreadCount,
            isPinned = isPinned,
            isMuted = muteType != null,
            isGroup = isGroup,
            isTyping = guid in typingChats,
            isFromMe = isFromMe,
            hasDraft = !textFieldText.isNullOrBlank(),
            draftText = textFieldText,
            lastMessageType = messageType,
            lastMessageStatus = messageStatus,
            participantNames = participantNames,
            address = address,
            hasInferredName = primaryParticipant?.hasInferredName == true,
            inferredName = primaryParticipant?.inferredName,
            lastMessageLinkTitle = linkTitle,
            lastMessageLinkDomain = linkDomain,
            isSpam = isSpam,
            category = category,
            isSnoozed = isSnoozed,
            snoozeUntil = snoozeUntil
        )
    }

    private fun formatRelativeTime(timestamp: Long): String {
        if (timestamp == 0L) return ""

        val now = System.currentTimeMillis()
        val messageDate = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()

        val isToday = messageDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                messageDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)

        val isSameYear = messageDate.get(Calendar.YEAR) == today.get(Calendar.YEAR)

        // Check if within the last 7 days
        val daysDiff = (now - timestamp) / (1000 * 60 * 60 * 24)

        // Get system time format (12h or 24h)
        val is24Hour = DateFormat.is24HourFormat(application)
        val timePattern = if (is24Hour) "HH:mm" else "h:mm a"

        return when {
            isToday -> SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(timestamp))
            daysDiff < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
            isSameYear -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
            else -> SimpleDateFormat("M/d/yy", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

data class ConversationsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val syncProgress: Float? = null,
    val isConnected: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionBannerState: ConnectionBannerState = ConnectionBannerState.Dismissed,
    val smsBannerState: SmsBannerState = SmsBannerState.Disabled,
    val conversations: List<ConversationUiModel> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null,
    val swipeConfig: SwipeConfig = SwipeConfig(),
    val messageSearchResults: List<MessageSearchResult> = emptyList(),
    val useSimpleAppTitle: Boolean = false,
    val userProfileName: String? = null,
    val userProfileAvatarUri: String? = null,
    // SMS import state
    val isImportingSms: Boolean = false,
    val smsImportProgress: Float = 0f,
    val smsImportError: String? = null
)

/**
 * Represents a message that matched a search query.
 * Contains all data needed to render using GoogleStyleConversationTile.
 */
data class MessageSearchResult(
    val messageGuid: String,
    val chatGuid: String,
    val chatDisplayName: String,
    val messageText: String,
    val timestamp: Long,
    val formattedTime: String,
    val isFromMe: Boolean,
    val avatarPath: String? = null,
    val isGroup: Boolean = false,
    val messageType: MessageType = MessageType.TEXT,
    val linkTitle: String? = null,
    val linkDomain: String? = null
) {
    /**
     * Converts this search result to a ConversationUiModel for rendering with GoogleStyleConversationTile.
     * Uses simplified defaults for fields not relevant to search result display.
     */
    fun toConversationUiModel(): ConversationUiModel = ConversationUiModel(
        guid = chatGuid,
        displayName = chatDisplayName,
        avatarPath = avatarPath,
        lastMessageText = messageText,
        lastMessageTime = formattedTime,
        lastMessageTimestamp = timestamp,
        unreadCount = 0,
        isPinned = false,
        isMuted = false,
        isGroup = isGroup,
        isTyping = false,
        isFromMe = isFromMe,
        hasDraft = false,
        draftText = null,
        lastMessageType = messageType,
        lastMessageStatus = MessageStatus.NONE,
        participantNames = emptyList(),
        address = "",
        hasInferredName = false,
        inferredName = null,
        lastMessageLinkTitle = linkTitle,
        lastMessageLinkDomain = linkDomain,
        isSpam = false,
        category = null
    )
}

data class ConversationUiModel(
    val guid: String,
    val displayName: String,
    val avatarPath: String?,
    val lastMessageText: String,
    val lastMessageTime: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isGroup: Boolean,
    val isTyping: Boolean,
    val isFromMe: Boolean = false,
    val hasDraft: Boolean = false,
    val draftText: String? = null,
    val lastMessageType: MessageType = MessageType.TEXT,
    val lastMessageStatus: MessageStatus = MessageStatus.NONE,
    val participantNames: List<String> = emptyList(),
    val address: String = "", // Primary address (phone/email) for the chat
    val hasInferredName: Boolean = false, // True if displaying an inferred "Maybe: X" name
    val inferredName: String? = null, // Raw inferred name without "Maybe:" prefix (for add contact)
    val lastMessageLinkTitle: String? = null, // Link preview title for LINK type messages
    val lastMessageLinkDomain: String? = null, // Link domain for LINK type messages
    val isSpam: Boolean = false, // Whether this conversation is marked as spam
    val category: String? = null, // Message category: "transactions", "deliveries", "promotions", "reminders"
    val isSnoozed: Boolean = false, // Whether notifications are snoozed
    val snoozeUntil: Long? = null // When snooze expires (-1 = indefinite)
) {
    /**
     * Returns true if this conversation has a saved contact.
     * A contact is considered "missing" if the displayName looks like a phone number or email address,
     * or if it's showing an inferred "Maybe:" name.
     */
    val hasContact: Boolean
        get() = !hasInferredName &&
                !displayName.contains("@") &&
                !displayName.matches(Regex("^[+\\d\\s()-]+$"))

    /**
     * Raw display name without "Maybe:" prefix - use for contact intents and avatars.
     */
    val rawDisplayName: String
        get() = if (hasInferredName && inferredName != null) inferredName else displayName
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    LINK,
    ATTACHMENT
}

/**
 * Status of the last sent message for display in conversation list
 */
enum class MessageStatus {
    NONE,       // Not from me or no status available
    SENDING,    // Message is being sent (temp guid)
    SENT,       // Message sent but not delivered
    DELIVERED,  // Message delivered to recipient
    READ,       // Message read by recipient
    FAILED      // Message failed to send
}
