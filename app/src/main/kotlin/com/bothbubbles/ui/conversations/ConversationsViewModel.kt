package com.bothbubbles.ui.conversations

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.MessageEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketEvent
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.services.sync.SyncState
import com.bothbubbles.ui.components.ConnectionBannerState
import com.bothbubbles.ui.components.SmsBannerState
import com.bothbubbles.ui.components.SwipeActionType
import com.bothbubbles.ui.components.SwipeConfig
import com.bothbubbles.ui.components.determineConnectionBannerState
import com.bothbubbles.ui.components.determineSmsBannerState
import com.bothbubbles.ui.components.UrlParsingUtils
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.format.DateFormat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

private const val PAGE_SIZE = 25

@OptIn(FlowPreview::class)
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val application: Application,
    private val chatRepository: ChatRepository,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val socketService: SocketService,
    private val syncService: SyncService,
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val smsRepository: SmsRepository,
    private val androidContactsService: AndroidContactsService,
    private val notificationService: NotificationService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _typingChats = MutableStateFlow<Set<String>>(emptySet())
    private val _smsStateRefreshTrigger = MutableStateFlow(0)

    // Track if we've ever successfully connected (for banner logic)
    private var wasEverConnected = false

    // Grace period to avoid flashing "not connected" banner on app startup
    private val _startupGracePeriodPassed = MutableStateFlow(false)

    init {
        // Start grace period timer - don't show "not connected" banner immediately on app start
        viewModelScope.launch {
            delay(2500) // 2.5 second grace period
            _startupGracePeriodPassed.value = true
        }

        loadInitialConversations()
        observeDataChanges()
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
        checkAndResumeSync()
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

                    // Fetch server info to check Private API availability and persist capabilities
                    try {
                        val response = api.getServerInfo()
                        if (response.isSuccessful) {
                            val serverInfo = response.body()?.data

                            // Persist server capabilities for feature flag detection
                            settingsDataStore.setServerCapabilities(
                                osVersion = serverInfo?.osVersion,
                                serverVersion = serverInfo?.serverVersion,
                                privateApiEnabled = serverInfo?.privateApi ?: false,
                                helperConnected = serverInfo?.helperConnected ?: false
                            )

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
                // Check for READ_CONTACTS permission first
                if (!androidContactsService.hasReadPermission()) {
                    return@withContext
                }

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

    /**
     * Refresh all contact info from device contacts.
     * Called when:
     * - READ_CONTACTS permission is newly granted
     * - App resumes (to catch contact changes made while app was backgrounded)
     */
    fun refreshAllContacts() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Refresh user profile
                loadUserProfile()

                // Refresh all handle contact info (names and photos)
                chatRepository.refreshAllContactInfo()
            }
        }
    }

    /**
     * Called when the app resumes or when contacts permission state might have changed.
     * Checks permission and refreshes contact data if available.
     */
    fun onPermissionStateChanged() {
        if (androidContactsService.hasReadPermission()) {
            refreshAllContacts()
        }
    }

    /**
     * Load the first page of conversations on startup.
     * Uses paginated queries to avoid loading all conversations at once.
     */
    private fun loadInitialConversations() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Load first page of unified groups (1:1 merged iMessage+SMS)
                val unifiedGroups = unifiedChatGroupDao.getActiveGroupsPaginated(PAGE_SIZE, 0)

                // Load all group chats (typically few, no pagination needed)
                val groupChats = chatDao.observeActiveGroupChats().first()

                // Load first page of non-group chats (for orphans not in unified groups)
                val nonGroupChats = chatDao.getNonGroupChatsPaginated(PAGE_SIZE, 0)

                val conversations = buildConversationList(
                    unifiedGroups = unifiedGroups,
                    groupChats = groupChats,
                    nonGroupChats = nonGroupChats,
                    typingChats = _typingChats.value
                )

                // Check if more data exists beyond first page
                val totalUnified = unifiedChatGroupDao.getActiveGroupCount()
                val totalNonGroup = chatDao.getNonGroupChatCount()
                val hasMore = (unifiedGroups.size + nonGroupChats.size) < (totalUnified + totalNonGroup)

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        conversations = conversations,
                        canLoadMore = hasMore,
                        currentPage = 0
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ConversationsViewModel", "Failed to load conversations", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Observe data changes to refresh loaded conversations reactively.
     * Triggers on any change to unified groups, chats, or typing indicators.
     */
    private fun observeDataChanges() {
        viewModelScope.launch {
            combine(
                unifiedChatGroupDao.observeActiveGroupCount(),
                chatDao.observeNonGroupChatCount(),
                chatDao.observeActiveGroupChats(),
                _typingChats,
                _searchQuery
            ) { _, _, _, _, _ -> Unit }
            .debounce(200)
            .collect {
                refreshAllLoadedPages()
            }
        }
    }

    /**
     * Refresh all currently loaded pages to pick up data changes.
     * Called when sync updates data or when new messages arrive.
     */
    private suspend fun refreshAllLoadedPages() {
        val currentPage = _uiState.value.currentPage
        val totalLoaded = (currentPage + 1) * PAGE_SIZE

        try {
            // Re-fetch all loaded unified groups
            val unifiedGroups = unifiedChatGroupDao.getActiveGroupsPaginated(totalLoaded, 0)

            // Re-fetch all group chats
            val groupChats = chatDao.observeActiveGroupChats().first()

            // Re-fetch all loaded non-group chats
            val nonGroupChats = chatDao.getNonGroupChatsPaginated(totalLoaded, 0)

            val typingChats = _typingChats.value
            val query = _searchQuery.value

            val conversations = buildConversationList(
                unifiedGroups = unifiedGroups,
                groupChats = groupChats,
                nonGroupChats = nonGroupChats,
                typingChats = typingChats
            )

            // Apply search filter if active
            val filtered = if (query.isBlank()) {
                conversations
            } else {
                conversations.filter { conv ->
                    conv.displayName.contains(query, ignoreCase = true) ||
                    conv.address.contains(query, ignoreCase = true)
                }
            }

            // Check if more data exists
            val totalUnified = unifiedChatGroupDao.getActiveGroupCount()
            val totalNonGroup = chatDao.getNonGroupChatCount()
            val hasMore = (unifiedGroups.size + nonGroupChats.size) < (totalUnified + totalNonGroup)

            _uiState.update { state ->
                state.copy(
                    conversations = filtered,
                    canLoadMore = hasMore
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ConversationsViewModel", "Failed to refresh conversations", e)
        }
    }

    /**
     * Load more conversations when user scrolls near the bottom.
     */
    fun loadMoreConversations() {
        if (_uiState.value.isLoadingMore || !_uiState.value.canLoadMore) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            try {
                val nextPage = _uiState.value.currentPage + 1
                val offset = nextPage * PAGE_SIZE

                // Load next page of unified groups
                val moreUnifiedGroups = unifiedChatGroupDao.getActiveGroupsPaginated(PAGE_SIZE, offset)

                // Load next page of non-group chats
                val moreNonGroupChats = chatDao.getNonGroupChatsPaginated(PAGE_SIZE, offset)

                val newConversations = buildConversationList(
                    unifiedGroups = moreUnifiedGroups,
                    groupChats = emptyList(), // Group chats already loaded in full
                    nonGroupChats = moreNonGroupChats,
                    typingChats = _typingChats.value
                )

                // Merge with existing, deduplicate, and sort
                val existingGuids = _uiState.value.conversations.map { it.guid }.toSet()
                val uniqueNew = newConversations.filter { it.guid !in existingGuids }

                val merged = (_uiState.value.conversations + uniqueNew)
                    .distinctBy { it.guid }
                    .sortedWith(
                        compareByDescending<ConversationUiModel> { it.isPinned }
                            .thenByDescending { it.lastMessageTimestamp }
                    )

                _uiState.update { state ->
                    state.copy(
                        isLoadingMore = false,
                        conversations = merged,
                        currentPage = nextPage,
                        canLoadMore = newConversations.isNotEmpty()
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ConversationsViewModel", "Failed to load more conversations", e)
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    /**
     * Build conversation list from paginated entities.
     * Converts unified groups, group chats, and orphan 1:1 chats to UI models.
     */
    private suspend fun buildConversationList(
        unifiedGroups: List<UnifiedChatGroupEntity>,
        groupChats: List<ChatEntity>,
        nonGroupChats: List<ChatEntity>,
        typingChats: Set<String>
    ): List<ConversationUiModel> {
        val conversations = mutableListOf<ConversationUiModel>()
        val handledChatGuids = mutableSetOf<String>()

        // Process unified chat groups (single-contact iMessage+SMS merged)
        for (group in unifiedGroups) {
            val chatGuids = unifiedChatGroupDao.getChatGuidsForGroup(group.id)
            handledChatGuids.addAll(chatGuids)
            handledChatGuids.add(group.primaryChatGuid)

            val uiModel = unifiedGroupToUiModel(group, chatGuids, typingChats)
            if (uiModel != null) {
                conversations.add(uiModel)
            }
        }

        // Add group chats (not unified - they stay separate)
        for (chat in groupChats) {
            if (chat.guid !in handledChatGuids) {
                conversations.add(chat.toUiModel(typingChats))
                handledChatGuids.add(chat.guid)
            }
        }

        // Add orphan 1:1 chats not in unified groups
        for (chat in nonGroupChats) {
            if (chat.guid !in handledChatGuids && !chat.isGroup && chat.dateDeleted == null && !chat.isArchived) {
                conversations.add(chat.toUiModel(typingChats))
                handledChatGuids.add(chat.guid)
            }
        }

        // Sort: pinned first, then by last message time
        return conversations
            .distinctBy { it.guid }
            .sortedWith(
                compareByDescending<ConversationUiModel> { it.isPinned }
                    .thenByDescending { it.lastMessageTimestamp }
            )
    }

    /**
     * Convert a unified chat group to a UI model.
     * Queries messages from all linked chats to find the most recent.
     */
    private suspend fun unifiedGroupToUiModel(
        group: UnifiedChatGroupEntity,
        chatGuids: List<String>,
        typingChats: Set<String>
    ): ConversationUiModel? {
        if (chatGuids.isEmpty()) return null

        // Get all chats in this group
        val chats = chatGuids.mapNotNull { chatDao.getChatByGuid(it) }
        if (chats.isEmpty()) return null

        // Find the most recent message across all chats
        var latestMessage: MessageEntity? = null
        var latestTimestamp = 0L
        for (chatGuid in chatGuids) {
            val msg = messageDao.getLatestMessageForChat(chatGuid)
            if (msg != null && msg.dateCreated > latestTimestamp) {
                latestMessage = msg
                latestTimestamp = msg.dateCreated
            }
        }

        // Use the primary chat for display info
        val primaryChat = chats.find { it.guid == group.primaryChatGuid } ?: chats.first()

        // Get participants from ALL chats in the unified group (not just primary)
        // This ensures we find contact info even if it's only linked to one of the chats
        val allParticipants = chatRepository.getParticipantsForChats(chatGuids)

        // Prefer participant with cached contact info, otherwise use first available
        val primaryParticipant = chatRepository.getBestParticipant(allParticipants)
        val address = primaryParticipant?.address ?: primaryChat.chatIdentifier ?: group.identifier

        // Determine display name - prioritize contact card name (cachedDisplayName) above all else
        // Priority: 1. Device contact name, 2. Cached group/chat name, 3. Inferred name, 4. Formatted phone
        val displayName = primaryParticipant?.cachedDisplayName?.takeIf { it.isNotBlank() }
            ?: group.displayName?.takeIf { it.isNotBlank() }
            ?: primaryChat.displayName?.takeIf { it.isNotBlank() }
            ?: primaryParticipant?.inferredName?.let { "Maybe: $it" }
            ?: primaryChat.chatIdentifier?.let { PhoneNumberFormatter.format(it) }
            ?: address.let { PhoneNumberFormatter.format(it) }

        // Sum unread counts
        val totalUnread = chats.sumOf { it.unreadCount }

        // Check for any typing indicators
        val anyTyping = chatGuids.any { it in typingChats }

        // Check for any pinned
        val anyPinned = chats.any { it.isPinned } || group.isPinned

        // Check if all muted
        val allMuted = chats.all { it.muteType != null } || group.muteType != null

        // Check for drafts
        val chatWithDraft = chats.find { !it.textFieldText.isNullOrBlank() }

        val messageText = latestMessage?.text ?: primaryChat.lastMessageText ?: ""
        val isFromMe = latestMessage?.isFromMe ?: false

        // Determine message type
        val messageType = when {
            latestMessage?.hasAttachments == true -> MessageType.ATTACHMENT
            messageText.contains("http://") || messageText.contains("https://") -> MessageType.LINK
            else -> MessageType.TEXT
        }

        // Determine message status
        val messageStatus = when {
            !isFromMe -> MessageStatus.NONE
            latestMessage == null -> MessageStatus.NONE
            latestMessage.guid.startsWith("temp-") -> MessageStatus.SENDING
            latestMessage.dateRead != null -> MessageStatus.READ
            latestMessage.dateDelivered != null -> MessageStatus.DELIVERED
            latestMessage.error == 0 -> MessageStatus.SENT
            else -> MessageStatus.NONE
        }

        // Get link preview data for LINK type
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
            guid = group.primaryChatGuid,
            displayName = displayName,
            avatarPath = primaryParticipant?.cachedAvatarPath,
            lastMessageText = messageText,
            lastMessageTime = formatRelativeTime(latestTimestamp.takeIf { it > 0 } ?: group.latestMessageDate ?: 0L),
            lastMessageTimestamp = latestTimestamp.takeIf { it > 0 } ?: group.latestMessageDate ?: 0L,
            unreadCount = totalUnread.takeIf { it > 0 } ?: group.unreadCount,
            isPinned = anyPinned,
            isMuted = allMuted,
            isGroup = false,
            isTyping = anyTyping,
            isFromMe = isFromMe,
            hasDraft = chatWithDraft != null,
            draftText = chatWithDraft?.textFieldText,
            lastMessageType = messageType,
            lastMessageStatus = messageStatus,
            participantNames = allParticipants.map { it.displayName },
            address = address,
            hasInferredName = primaryParticipant?.hasInferredName == true,
            inferredName = primaryParticipant?.inferredName,
            lastMessageLinkTitle = linkTitle,
            lastMessageLinkDomain = linkDomain,
            isSpam = chats.any { it.isSpam },
            category = primaryChat.category,
            isSnoozed = group.snoozeUntil != null,
            snoozeUntil = group.snoozeUntil,
            lastMessageSource = latestMessage?.messageSource,
            mergedChatGuids = chatGuids,
            isMerged = chatGuids.size > 1,
            contactKey = PhoneNumberFormatter.getContactKey(address)
        )
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
                settingsDataStore.dismissedSetupBanner,
                _startupGracePeriodPassed
            ) { connectionState, retryAttempt, isSetupBannerDismissed, gracePeriodPassed ->
                // During startup grace period, suppress reconnecting banner to avoid flash
                if (!gracePeriodPassed && connectionState != ConnectionState.CONNECTED) {
                    ConnectionBannerState.Connected // Effectively hide the banner
                } else {
                    determineConnectionBannerState(
                        connectionState = connectionState,
                        retryAttempt = retryAttempt,
                        isSetupBannerDismissed = isSetupBannerDismissed,
                        wasEverConnected = wasEverConnected
                    )
                }
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
                when (state) {
                    is SyncState.Syncing -> {
                        _uiState.update {
                            it.copy(
                                isSyncing = true,
                                syncProgress = state.progress,
                                syncStage = state.stage,
                                syncTotalChats = state.totalChats,
                                syncProcessedChats = state.processedChats,
                                syncedMessages = state.syncedMessages,
                                syncCurrentChatName = state.currentChatName,
                                isInitialSync = state.isInitialSync,
                                syncError = null,
                                isSyncCorrupted = false
                            )
                        }
                    }
                    is SyncState.Completed -> {
                        // Capture state before updating to check if this was initial sync
                        val wasInitialSync = _uiState.value.isInitialSync
                        val messageCount = _uiState.value.syncedMessages

                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                syncProgress = null,
                                syncStage = null,
                                syncTotalChats = 0,
                                syncProcessedChats = 0,
                                syncedMessages = 0,
                                syncCurrentChatName = null,
                                isInitialSync = false,
                                syncError = null,
                                isSyncCorrupted = false
                            )
                        }

                        // Show notification for first-time initial sync completion
                        if (wasInitialSync && messageCount > 0) {
                            notificationService.showBlueBubblesSyncCompleteNotification(messageCount)
                        }
                    }
                    is SyncState.Error -> {
                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                syncProgress = null,
                                syncStage = null,
                                syncError = state.message,
                                isSyncCorrupted = state.isCorrupted
                            )
                        }
                    }
                    SyncState.Idle -> {
                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                syncProgress = null,
                                syncStage = null,
                                syncError = null,
                                isSyncCorrupted = false
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Check for and resume interrupted initial sync on app startup.
     */
    private fun checkAndResumeSync() {
        viewModelScope.launch {
            val syncStarted = settingsDataStore.initialSyncStarted.first()
            val syncComplete = settingsDataStore.initialSyncComplete.first()

            if (syncStarted && !syncComplete) {
                // Interrupted sync detected - resume it
                android.util.Log.i("ConversationsViewModel", "Resuming interrupted initial sync")
                syncService.resumeInitialSync()
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
                                    chat.displayName ?: chat.chatIdentifier?.let { PhoneNumberFormatter.format(it) }
                                        ?: primaryParticipant?.address?.let { PhoneNumberFormatter.format(it) } ?: ""
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
                    // Show notification for first-time SMS import completion
                    notificationService.showSmsImportCompleteNotification()
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

    /**
     * Reset app data when corruption is detected.
     * Clears all local data and returns to setup flow.
     */
    fun resetAppData(onReset: () -> Unit) {
        viewModelScope.launch {
            try {
                // Clear all local data
                messageDao.deleteAllMessages()
                chatDao.deleteAllChatHandleCrossRefs()
                chatDao.deleteAllChats()
                handleDao.deleteAllHandles()
                unifiedChatGroupDao.deleteAllData()

                // Clear sync progress and reset setup
                settingsDataStore.clearSyncProgress()
                settingsDataStore.setSetupComplete(false)

                // Clear error state
                _uiState.update { it.copy(syncError = null, isSyncCorrupted = false) }

                // Navigate back to setup
                withContext(Dispatchers.Main) {
                    onReset()
                }
            } catch (e: Exception) {
                android.util.Log.e("ConversationsViewModel", "Failed to reset app data", e)
            }
        }
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

    /**
     * Set a custom photo for a group chat.
     * Saves the image to local storage and updates the chat entity.
     */
    fun setGroupPhoto(chatGuid: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val avatarPath = saveGroupPhoto(chatGuid, uri)
                if (avatarPath != null) {
                    chatDao.updateCustomAvatarPath(chatGuid, avatarPath)
                }
            } catch (e: Exception) {
                android.util.Log.e("ConversationsViewModel", "Failed to set group photo", e)
            }
        }
    }

    /**
     * Save group photo to local storage.
     * @return The path to the saved file, or null if failed
     */
    private fun saveGroupPhoto(chatGuid: String, uri: Uri): String? {
        return try {
            val avatarsDir = File(application.filesDir, "group_avatars")
            if (!avatarsDir.exists()) {
                avatarsDir.mkdirs()
            }

            // Sanitize chatGuid for filename
            val sanitizedGuid = chatGuid.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val photoFile = File(avatarsDir, "${sanitizedGuid}.jpg")

            // Copy and compress the image
            application.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                photoFile.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                }
            }

            photoFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("ConversationsViewModel", "Failed to save group photo", e)
            null
        }
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

    /**
     * Refresh contact info from device contacts.
     * Called when returning from the system contacts app after adding a contact.
     */
    fun refreshContactInfo(address: String) {
        viewModelScope.launch {
            val displayName = androidContactsService.getContactDisplayName(address)
            val photoUri = androidContactsService.getContactPhotoUri(address)
            if (displayName != null || photoUri != null) {
                chatRepository.updateHandleCachedContactInfo(address, displayName, photoUri)
            }
        }
    }

    private suspend fun ChatEntity.toUiModel(typingChats: Set<String>): ConversationUiModel {
        val lastMessage = messageDao.getLatestMessageForChat(guid)
        val messageText = lastMessage?.text ?: lastMessageText ?: ""
        val isFromMe = lastMessage?.isFromMe ?: false

        // Get participants for this chat
        val participants = chatDao.getParticipantsForChat(guid)
        val participantNames = participants.map { it.displayName }
        val participantAvatarPaths = participants.map { it.cachedAvatarPath }
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
        // For group chats, use the chat's display name, then fall back to joined participant names
        // Use takeIf to convert empty strings to null for proper fallback
        val resolvedDisplayName = if (!isGroup && primaryParticipant != null) {
            primaryParticipant.displayName.takeIf { it.isNotBlank() }
                ?: chatIdentifier?.let { PhoneNumberFormatter.format(it) }
                ?: address.let { PhoneNumberFormatter.format(it) }
        } else if (isGroup) {
            // For group chats: explicit name > joined participant names > formatted identifier
            displayName?.takeIf { it.isNotBlank() }
                ?: participantNames.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")
                ?: "Group Chat"
        } else {
            displayName?.takeIf { it.isNotBlank() }
                ?: chatIdentifier?.let { PhoneNumberFormatter.format(it) }
                ?: address.let { PhoneNumberFormatter.format(it) }
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

        // Generate contact key for potential merging (only for 1:1 chats)
        val contactKey = if (!isGroup && address.isNotBlank()) {
            PhoneNumberFormatter.getContactKey(address)
        } else {
            ""
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
            participantAvatarPaths = participantAvatarPaths,
            address = address,
            hasInferredName = primaryParticipant?.hasInferredName == true,
            inferredName = primaryParticipant?.inferredName,
            lastMessageLinkTitle = linkTitle,
            lastMessageLinkDomain = linkDomain,
            isSpam = isSpam,
            category = category,
            isSnoozed = isSnoozed,
            snoozeUntil = snoozeUntil,
            lastMessageSource = lastMessage?.messageSource,
            mergedChatGuids = listOf(guid), // Initially just this chat
            isMerged = false,
            contactKey = contactKey
        )
    }

    private fun formatRelativeTime(timestamp: Long): String {
        if (timestamp == 0L) return ""

        val now = System.currentTimeMillis()
        val messageDate = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()

        val isSameYear = messageDate.get(Calendar.YEAR) == today.get(Calendar.YEAR)

        // Check if within the last 24 hours (show time instead of day name)
        val hoursDiff = (now - timestamp) / (1000 * 60 * 60)
        val isWithin24Hours = hoursDiff < 24

        // Check if within the last 7 days
        val daysDiff = (now - timestamp) / (1000 * 60 * 60 * 24)

        // Get system time format (12h or 24h)
        val is24Hour = DateFormat.is24HourFormat(application)
        val timePattern = if (is24Hour) "HH:mm" else "h:mm a"

        return when {
            isWithin24Hours -> SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(timestamp))
            daysDiff < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
            isSameYear -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
            else -> SimpleDateFormat("M/d/yy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /**
     * Merge 1:1 conversations with the same contact into a single entry.
     * Groups chats by their contactKey (normalized phone number) and combines:
     * - Uses the most recent message
     * - Sums up unread counts
     * - Combines all chat GUIDs for navigation
     * - Prefers saved contact names over inferred names
     *
     * Group chats are never merged.
     */
    private fun mergeConversations(conversations: List<ConversationUiModel>): List<ConversationUiModel> {
        // Separate group chats (never merge) from 1:1 chats
        val (groupChats, oneOnOneChats) = conversations.partition { it.isGroup }

        // Group 1:1 chats by contact key
        val grouped = oneOnOneChats.groupBy { it.contactKey }

        val mergedChats = grouped.map { (contactKey, chats) ->
            if (chats.size == 1 || contactKey.isEmpty()) {
                // Single chat or no valid contact key - no merging needed
                chats.first()
            } else {
                // Multiple chats to merge
                mergeChatGroup(chats)
            }
        }

        // Combine merged 1:1 chats with group chats
        return (mergedChats + groupChats)
            .sortedWith(
                compareByDescending<ConversationUiModel> { it.isPinned }
                    .thenByDescending { it.lastMessageTimestamp }
            )
    }

    /**
     * Merge multiple 1:1 chats (same contact) into a single conversation entry.
     */
    private fun mergeChatGroup(chats: List<ConversationUiModel>): ConversationUiModel {
        // Sort by most recent message
        val sortedByRecent = chats.sortedByDescending { it.lastMessageTimestamp }
        val primary = sortedByRecent.first()

        // Combine all chat GUIDs
        val allGuids = chats.map { it.guid }

        // Sum unread counts
        val totalUnread = chats.sumOf { it.unreadCount }

        // Any typing indicator from any chat
        val anyTyping = chats.any { it.isTyping }

        // Any pinned
        val anyPinned = chats.any { it.isPinned }

        // All muted
        val allMuted = chats.all { it.isMuted }

        // Prefer saved contact name over inferred name
        val preferredChat = chats.find { it.hasContact } ?: primary
        val displayName = preferredChat.displayName
        val hasInferredName = preferredChat.hasInferredName
        val inferredName = preferredChat.inferredName
        val avatarPath = preferredChat.avatarPath ?: chats.firstNotNullOfOrNull { it.avatarPath }

        // Check for any draft
        val chatWithDraft = chats.find { it.hasDraft }
        val hasDraft = chatWithDraft != null
        val draftText = chatWithDraft?.draftText

        return primary.copy(
            guid = primary.guid, // Primary guid for navigation (will use mergedChatGuids in chat)
            displayName = displayName,
            avatarPath = avatarPath,
            unreadCount = totalUnread,
            isPinned = anyPinned,
            isMuted = allMuted,
            isTyping = anyTyping,
            hasDraft = hasDraft,
            draftText = draftText,
            hasInferredName = hasInferredName,
            inferredName = inferredName,
            mergedChatGuids = allGuids,
            isMerged = true
        )
    }
}

data class ConversationsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    // Pagination state
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val currentPage: Int = 0,
    val syncProgress: Float? = null,
    val syncStage: String? = null,
    // Detailed sync info for initial sync
    val syncTotalChats: Int = 0,
    val syncProcessedChats: Int = 0,
    val syncedMessages: Int = 0,
    val syncCurrentChatName: String? = null,
    val isInitialSync: Boolean = false,
    // Sync error state
    val syncError: String? = null,
    val isSyncCorrupted: Boolean = false,
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
    val participantAvatarPaths: List<String?> = emptyList(), // Avatar paths for group participants
    val address: String = "", // Primary address (phone/email) for the chat
    val hasInferredName: Boolean = false, // True if displaying an inferred "Maybe: X" name
    val inferredName: String? = null, // Raw inferred name without "Maybe:" prefix (for add contact)
    val lastMessageLinkTitle: String? = null, // Link preview title for LINK type messages
    val lastMessageLinkDomain: String? = null, // Link domain for LINK type messages
    val isSpam: Boolean = false, // Whether this conversation is marked as spam
    val category: String? = null, // Message category: "transactions", "deliveries", "promotions", "reminders"
    val isSnoozed: Boolean = false, // Whether notifications are snoozed
    val snoozeUntil: Long? = null, // When snooze expires (-1 = indefinite)
    val lastMessageSource: String? = null, // Message source: IMESSAGE, SERVER_SMS, LOCAL_SMS, LOCAL_MMS
    // Merged conversation support (for combining iMessage and SMS threads to same contact)
    val mergedChatGuids: List<String> = listOf(), // All chat GUIDs in this merged conversation
    val isMerged: Boolean = false, // True if this represents multiple merged chats
    val contactKey: String = "" // Normalized phone/email for matching
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
