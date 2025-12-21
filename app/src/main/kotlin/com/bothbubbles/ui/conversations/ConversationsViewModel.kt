package com.bothbubbles.ui.conversations

import com.bothbubbles.core.data.ConnectionState
import timber.log.Timber
import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.HandleRepository
import com.bothbubbles.data.repository.LinkPreviewRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.categorization.CategorizationRepository
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.services.sync.UnifiedSyncProgress
import com.bothbubbles.ui.components.common.ConnectionBannerState
import com.bothbubbles.ui.components.common.SmsBannerState
import com.bothbubbles.ui.components.common.determineConnectionBannerState
import com.bothbubbles.ui.components.common.determineSmsBannerState
import com.bothbubbles.ui.components.conversation.SwipeActionType
import com.bothbubbles.ui.components.conversation.SwipeConfig
import com.bothbubbles.ui.conversations.delegates.CategorizationState
import com.bothbubbles.ui.conversations.delegates.ConversationActionsDelegate
import com.bothbubbles.ui.conversations.delegates.ConversationEvent
import com.bothbubbles.ui.conversations.delegates.ConversationLoadingDelegate
import com.bothbubbles.ui.conversations.delegates.ConversationObserverDelegate
import com.bothbubbles.ui.conversations.delegates.ConversationSelectionDelegate
import com.bothbubbles.ui.conversations.delegates.SelectionState
import com.bothbubbles.ui.conversations.delegates.BatchAction
import com.bothbubbles.ui.conversations.delegates.SelectionEvent
import com.bothbubbles.ui.conversations.delegates.SmsImportState
import com.bothbubbles.ui.util.toStable
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the Conversations screen.
 *
 * Phase 8: Uses AssistedInject delegate factories for lifecycle-safe construction
 * and SharedFlow events for delegate-to-ViewModel communication.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val application: Application,
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val handleRepository: HandleRepository,
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val smsRepository: SmsRepository,
    private val androidContactsService: AndroidContactsService,
    private val notificationService: NotificationService,
    private val syncService: SyncService,
    private val categorizationRepository: CategorizationRepository,
    // Phase 8: Delegate factories instead of direct delegates
    private val loadingDelegateFactory: ConversationLoadingDelegate.Factory,
    private val observerDelegateFactory: ConversationObserverDelegate.Factory,
    private val actionsDelegateFactory: ConversationActionsDelegate.Factory,
    private val selectionDelegateFactory: ConversationSelectionDelegate.Factory
) : ViewModel() {

    // ============================================================================
    // Phase 8: Create delegates via factories (no lateinit, no initialize())
    // ============================================================================

    private val loadingDelegate: ConversationLoadingDelegate =
        loadingDelegateFactory.create(viewModelScope)

    private val observerDelegate: ConversationObserverDelegate =
        observerDelegateFactory.create(viewModelScope)

    private val actionsDelegate: ConversationActionsDelegate =
        actionsDelegateFactory.create(viewModelScope)

    private val selectionDelegate: ConversationSelectionDelegate =
        selectionDelegateFactory.create(viewModelScope)

    // ============================================================================
    // Selection State (Gmail-style Select All)
    // ============================================================================

    /** Selection state for multi-select operations */
    val selectionState: StateFlow<SelectionState> = selectionDelegate.selectionState

    // ============================================================================
    // UI State
    // ============================================================================

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _smsStateRefreshTrigger = MutableStateFlow(0)

    // Derived state for filter enums (moved from composition to avoid enum lookups in UI)
    val selectedConversationFilter: StateFlow<ConversationFilter> = _uiState.map { state ->
        ConversationFilter.entries.find {
            it.name.lowercase() == state.conversationFilter.lowercase()
        } ?: ConversationFilter.ALL
    }.stateIn(viewModelScope, SharingStarted.Lazily, ConversationFilter.ALL)

    val selectedCategoryFilter: StateFlow<com.bothbubbles.services.categorization.MessageCategory?> = _uiState.map { state ->
        state.categoryFilter?.let { savedCategory ->
            com.bothbubbles.services.categorization.MessageCategory.entries.find {
                it.name.equals(savedCategory, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val enabledCategories: StateFlow<Set<com.bothbubbles.services.categorization.MessageCategory>> = combine(
        _uiState.map { it.transactionsEnabled }.distinctUntilChanged(),
        _uiState.map { it.deliveriesEnabled }.distinctUntilChanged(),
        _uiState.map { it.promotionsEnabled }.distinctUntilChanged(),
        _uiState.map { it.remindersEnabled }.distinctUntilChanged()
    ) { transactionsEnabled, deliveriesEnabled, promotionsEnabled, remindersEnabled ->
        buildSet {
            if (transactionsEnabled) add(com.bothbubbles.services.categorization.MessageCategory.TRANSACTIONS)
            if (deliveriesEnabled) add(com.bothbubbles.services.categorization.MessageCategory.DELIVERIES)
            if (promotionsEnabled) add(com.bothbubbles.services.categorization.MessageCategory.PROMOTIONS)
            if (remindersEnabled) add(com.bothbubbles.services.categorization.MessageCategory.REMINDERS)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    // Track if we've already started categorization this session
    private var hasStartedCategorization = false

    // ============================================================================
    // SavedStateHandle-backed scroll position restoration (survives process death)
    // ============================================================================

    private companion object {
        const val KEY_SCROLL_INDEX = "scroll_index"
        const val KEY_SCROLL_OFFSET = "scroll_offset"
        const val KEY_SEARCH_ACTIVE = "search_active"
        // Default start date: January 1, 1960 (covers all reasonable message history)
        const val DEFAULT_START_DATE = -315619200000L // 1960-01-01 00:00:00 UTC
    }

    /** Saved scroll position index - survives process death */
    val savedScrollIndex: StateFlow<Int> = savedStateHandle.getStateFlow(KEY_SCROLL_INDEX, 0)

    /** Saved scroll position offset - survives process death */
    val savedScrollOffset: StateFlow<Int> = savedStateHandle.getStateFlow(KEY_SCROLL_OFFSET, 0)

    /** Whether search was active - survives process death */
    val savedSearchActive: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_SEARCH_ACTIVE, false)

    /** Save scroll position for restoration after process death */
    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle[KEY_SCROLL_INDEX] = index
        savedStateHandle[KEY_SCROLL_OFFSET] = offset
    }

    /** Save search active state */
    fun saveSearchActive(active: Boolean) {
        savedStateHandle[KEY_SEARCH_ACTIVE] = active
    }

    // ============================================================================
    // Initialization
    // ============================================================================

    init {
        // Phase 8: Collect events from delegates instead of callbacks
        collectObserverEvents()
        collectActionsEvents()

        // Load filter FIRST before loading conversations to avoid race condition
        viewModelScope.launch {
            val initialFilter = settingsDataStore.conversationFilter.first()
            parseAndApplyFilter(initialFilter)
            loadInitialConversations()
        }

        // Observe state from delegates
        observeConnectionBannerState()
        observeSmsBannerState()
        observeSwipeSettings()
        observeMessageSearch()
        observeAppTitleSetting()
        observeCategorizationEnabled()
        observeFilters()
        observeDelegateStates()

        // Lifecycle tasks
        loadUserProfile()
        checkPrivateApiPrompt()
        checkInitialSmsImport()
        checkAndResumeSync()
        markExistingMmsDrafts()
        observeCategorizationTrigger()
    }

    // ============================================================================
    // Phase 8: Event Collection from Delegates
    // ============================================================================

    /**
     * Collect events from ConversationObserverDelegate.
     * Replaces the callback-based initialize() approach.
     */
    private fun collectObserverEvents() {
        viewModelScope.launch {
            observerDelegate.events.collect { event ->
                when (event) {
                    is ConversationEvent.DataChanged -> refreshAllLoadedPages()
                    is ConversationEvent.NewMessage -> {
                        refreshAllLoadedPages()
                        _newMessageEvent.emit(Unit)
                    }
                    is ConversationEvent.MessageUpdated -> refreshAllLoadedPages()
                    is ConversationEvent.ChatRead -> optimisticallyMarkChatRead(event.chatGuid)
                    // Actions events are not emitted by observer delegate
                    else -> { /* Ignore other events */ }
                }
            }
        }
    }

    /**
     * Collect events from ConversationActionsDelegate.
     * Replaces the callback-based initialize() approach.
     */
    private fun collectActionsEvents() {
        viewModelScope.launch {
            actionsDelegate.events.collect { event ->
                when (event) {
                    is ConversationEvent.ConversationsUpdated -> {
                        _uiState.update { it.copy(conversations = event.conversations.toStable()) }
                    }
                    is ConversationEvent.ScrollToIndex -> {
                        _scrollToIndexEvent.emit(event.index)
                    }
                    is ConversationEvent.ActionError -> {
                        // Could show a snackbar or toast here
                        Timber.tag("ConversationsViewModel").e("Action error: ${event.message}")
                    }
                    // Observer events are not emitted by actions delegate
                    else -> { /* Ignore other events */ }
                }
            }
        }
    }

    // Phase 8: ScrollToIndex exposed as SharedFlow for UI to collect
    private val _scrollToIndexEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val scrollToIndexEvent: SharedFlow<Int> = _scrollToIndexEvent.asSharedFlow()

    // New message event for UI to trigger scroll-to-top when near top
    private val _newMessageEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val newMessageEvent: SharedFlow<Unit> = _newMessageEvent.asSharedFlow()

    /**
     * Observe state from delegates and merge into UI state.
     */
    private fun observeDelegateStates() {
        viewModelScope.launch {
            // Observer delegate state - simplified with unified sync progress
            combine(
                observerDelegate.isConnected,
                observerDelegate.connectionState,
                observerDelegate.unifiedSyncProgress,
                observerDelegate.totalUnreadCount,
                observerDelegate.showReconnectingIndicator
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                val isConnected = values[0] as? Boolean ?: false
                val connectionState = values[1] as? ConnectionState ?: ConnectionState.DISCONNECTED
                val unifiedSyncProgress = values[2] as? UnifiedSyncProgress
                val totalUnreadCount = values[3] as? Int ?: 0
                val showReconnectingIndicator = values[4] as? Boolean ?: false
                _uiState.update {
                    it.copy(
                        isConnected = isConnected,
                        connectionState = connectionState,
                        unifiedSyncProgress = unifiedSyncProgress,
                        totalUnreadCount = totalUnreadCount,
                        showReconnectingIndicator = showReconnectingIndicator
                    )
                }
            }.collect()
        }

        // Loading delegate state
        viewModelScope.launch {
            combine(
                loadingDelegate.isLoading,
                loadingDelegate.isLoadingMore,
                loadingDelegate.canLoadMore,
                loadingDelegate.currentPage,
                loadingDelegate.error
            ) { isLoading, isLoadingMore, canLoadMore, currentPage, error ->
                _uiState.update {
                    it.copy(
                        isLoading = isLoading,
                        isLoadingMore = isLoadingMore,
                        canLoadMore = canLoadMore,
                        currentPage = currentPage,
                        error = error
                    )
                }
            }.collect()
        }
    }

    /**
     * One-time migration to mark existing MMS drafts that were imported before
     * draft detection was added.
     */
    private fun markExistingMmsDrafts() {
        viewModelScope.launch {
            smsRepository.markExistingMmsDrafts()
        }
    }

    private fun observeAppTitleSetting() {
        viewModelScope.launch {
            combine(
                settingsDataStore.useSimpleAppTitle,
                settingsDataStore.showUnreadCountInHeader
            ) { useSimple, showUnread ->
                Pair(useSimple, showUnread)
            }.collect { (useSimple, showUnread) ->
                _uiState.update {
                    it.copy(
                        useSimpleAppTitle = useSimple,
                        showUnreadCountInHeader = showUnread
                    )
                }
            }
        }
    }

    private fun observeCategorizationEnabled() {
        viewModelScope.launch {
            settingsDataStore.categorizationEnabled.collect { enabled ->
                _uiState.update { it.copy(categorizationEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.transactionsCategoryEnabled.collect { enabled ->
                _uiState.update { it.copy(transactionsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.deliveriesCategoryEnabled.collect { enabled ->
                _uiState.update { it.copy(deliveriesEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.promotionsCategoryEnabled.collect { enabled ->
                _uiState.update { it.copy(promotionsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.remindersCategoryEnabled.collect { enabled ->
                _uiState.update { it.copy(remindersEnabled = enabled) }
            }
        }
    }

    /**
     * Observe the unified filter value and parse it to update UI state.
     * Both status filters and category filters are stored in a single preference.
     */
    private fun observeFilters() {
        viewModelScope.launch {
            settingsDataStore.conversationFilter.collect { storedFilter ->
                parseAndApplyFilter(storedFilter)
            }
        }
    }

    /**
     * Parse the stored filter value and update UI state accordingly.
     * Status filters: "all", "unread", "spam", "unknown_senders", "known_senders"
     * Category filters: "category:TRANSACTIONS", "category:DELIVERIES", etc.
     */
    private fun parseAndApplyFilter(storedFilter: String) {
        if (storedFilter.startsWith("category:")) {
            val category = storedFilter.removePrefix("category:")
            _uiState.update { it.copy(conversationFilter = "all", categoryFilter = category) }
        } else {
            _uiState.update { it.copy(conversationFilter = storedFilter, categoryFilter = null) }
        }
    }

    fun setConversationFilter(filter: String) {
        viewModelScope.launch {
            settingsDataStore.setConversationFilter(filter)
            // Scroll to top when filter changes
            _scrollToIndexEvent.emit(0)
        }
    }

    fun setCategoryFilter(category: String?) {
        viewModelScope.launch {
            val filterValue = category?.let { "category:$it" } ?: "all"
            settingsDataStore.setConversationFilter(filterValue)
            // Scroll to top when filter changes
            _scrollToIndexEvent.emit(0)
        }
    }

    /**
     * Observe SMS enabled state and trigger import when:
     * 1. SMS is enabled
     * 2. Initial import hasn't been completed
     * 3. We have read permission
     * 4. Not currently importing
     */
    private fun checkInitialSmsImport() {
        viewModelScope.launch {
            combine(
                settingsDataStore.smsEnabled,
                settingsDataStore.hasCompletedInitialSmsImport
            ) { smsEnabled, hasImported ->
                smsEnabled to hasImported
            }.distinctUntilChanged().collect { (smsEnabled, hasImported) ->
                // Check if SMS import is in progress via unified sync state
                val isImportingSms = _uiState.value.unifiedSyncProgress?.stages?.any {
                    it.type == com.bothbubbles.services.sync.SyncStageType.SMS_IMPORT &&
                    it.status == com.bothbubbles.services.sync.StageStatus.IN_PROGRESS
                } == true
                if (smsEnabled && !hasImported &&
                    smsPermissionHelper.hasReadSmsPermission() &&
                    !isImportingSms) {
                    startSmsImport()
                }
            }
        }
    }

    /**
     * Auto-enable Private API when connected to a server that supports it.
     */
    private fun checkPrivateApiPrompt() {
        viewModelScope.launch {
            observerDelegate.connectionState
                .filter { it == ConnectionState.CONNECTED }
                .take(1)
                .collect {
                    val hasChecked = settingsDataStore.hasShownPrivateApiPrompt.first()
                    if (hasChecked) return@collect

                    try {
                        val response = api.getServerInfo()
                        if (response.isSuccessful) {
                            val serverInfo = response.body()?.data
                            settingsDataStore.setServerCapabilities(
                                osVersion = serverInfo?.osVersion,
                                serverVersion = serverInfo?.serverVersion,
                                privateApiEnabled = serverInfo?.privateApi ?: false,
                                helperConnected = serverInfo?.helperConnected ?: false
                            )
                            if (serverInfo?.privateApi == true) {
                                settingsDataStore.setEnablePrivateApi(true)
                            }
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
                if (!androidContactsService.hasReadPermission()) return@withContext

                try {
                    val contentResolver = application.contentResolver
                    val profileUri = android.provider.ContactsContract.Profile.CONTENT_URI
                    val projection = arrayOf(
                        android.provider.ContactsContract.Profile.DISPLAY_NAME,
                        android.provider.ContactsContract.Profile.PHOTO_URI
                    )

                    contentResolver.query(profileUri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.ContactsContract.Profile.DISPLAY_NAME)
                            val photoIndex = cursor.getColumnIndex(android.provider.ContactsContract.Profile.PHOTO_URI)
                            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                            val photoUri = if (photoIndex >= 0) cursor.getString(photoIndex) else null
                            _uiState.update {
                                it.copy(userProfileName = name, userProfileAvatarUri = photoUri)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Silently fail
                }
            }
        }
    }

    /**
     * Refresh all contact info from device contacts.
     */
    fun refreshAllContacts() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                loadUserProfile()
                // Note: chatRepository.refreshAllContactInfo() is called from the repository
            }
        }
    }

    /**
     * Called when permission state might have changed.
     */
    fun onPermissionStateChanged() {
        if (androidContactsService.hasReadPermission()) {
            refreshAllContacts()
        }
    }

    /**
     * Load initial conversations using delegate.
     */
    private fun loadInitialConversations() {
        viewModelScope.launch {
            val typingChats = observerDelegate.typingChats.value
            val result = loadingDelegate.loadInitialConversations(typingChats)
            when (result) {
                is ConversationLoadingDelegate.LoadResult.Success -> {
                    _uiState.update {
                        it.copy(
                            conversations = result.conversations.toStable(),
                            canLoadMore = result.hasMore,
                            currentPage = result.currentPage
                        )
                    }
                }
                is ConversationLoadingDelegate.LoadResult.Error -> {
                    // Error already set by delegate
                }
                ConversationLoadingDelegate.LoadResult.AlreadyLoading -> {
                    // Should not happen for initial load
                }
            }
        }
    }

    /**
     * Refresh all loaded pages.
     */
    private suspend fun refreshAllLoadedPages() {
        // Skip refresh while loading more to avoid items popping in during scroll
        if (_uiState.value.isLoadingMore) return

        Timber.d("refreshAllLoadedPages: Starting refresh...")
        val typingChats = observerDelegate.typingChats.value
        val query = _searchQuery.value
        val conversations = loadingDelegate.refreshAllLoadedPages(typingChats, query)
        Timber.d("refreshAllLoadedPages: Got ${conversations.size} conversations")

        // Log any potential duplicates by contactKey
        val byContactKey = conversations.filter { !it.isGroup && it.contactKey.isNotBlank() }
            .groupBy { it.contactKey }
            .filter { it.value.size > 1 }
        if (byContactKey.isNotEmpty()) {
            Timber.w("refreshAllLoadedPages: DUPLICATES detected by contactKey: ${byContactKey.map { (key, convs) -> "$key -> ${convs.map { it.guid }}" }}")
        }

        _uiState.update { it.copy(conversations = conversations.toStable()) }
    }

    /**
     * Load more conversations when user scrolls near the bottom.
     */
    fun loadMoreConversations() {
        viewModelScope.launch {
            val currentConversations = _uiState.value.conversations
            val typingChats = observerDelegate.typingChats.value
            val result = loadingDelegate.loadMoreConversations(currentConversations, typingChats)
            when (result) {
                is ConversationLoadingDelegate.LoadResult.Success -> {
                    _uiState.update {
                        it.copy(
                            conversations = result.conversations.toStable(),
                            canLoadMore = result.hasMore,
                            currentPage = result.currentPage
                        )
                    }
                }
                is ConversationLoadingDelegate.LoadResult.Error -> {
                    // Error already set by delegate
                }
                ConversationLoadingDelegate.LoadResult.AlreadyLoading -> {
                    // Already loading
                }
            }
        }
    }

    /**
     * Load all remaining conversations when a filter is active.
     * Filters work on client-side data, so we need all conversations loaded
     * to show all matching items (e.g., all unread, not just unread in first page).
     */
    fun loadAllRemainingConversations() {
        viewModelScope.launch {
            val typingChats = observerDelegate.typingChats.value
            val result = loadingDelegate.loadAllRemainingPages(_uiState.value.conversations, typingChats)
            when (result) {
                is ConversationLoadingDelegate.LoadResult.Success -> {
                    _uiState.update {
                        it.copy(
                            conversations = result.conversations.toStable(),
                            canLoadMore = false, // All loaded
                            currentPage = result.currentPage
                        )
                    }
                }
                is ConversationLoadingDelegate.LoadResult.Error -> {
                    // Error already set by delegate
                }
                ConversationLoadingDelegate.LoadResult.AlreadyLoading -> {
                    // Already loading
                }
            }
        }
    }

    private fun observeConnectionBannerState() {
        viewModelScope.launch {
            combine(
                observerDelegate.connectionState,
                observerDelegate.startupGracePeriodPassed
            ) { connectionState, gracePeriodPassed ->
                // Note: retryAttempt is internal to socketService, we use connectionState instead
                if (!gracePeriodPassed && connectionState != ConnectionState.CONNECTED) {
                    ConnectionBannerState.Connected // Effectively hide the banner
                } else {
                    determineConnectionBannerState(
                        connectionState = connectionState,
                        retryAttempt = 0, // We don't have access to retry attempt anymore
                        wasEverConnected = observerDelegate.wasEverConnected()
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
        observerDelegate.retryConnection()
    }

    private fun observeSmsBannerState() {
        viewModelScope.launch {
            combine(
                settingsDataStore.smsEnabled,
                settingsDataStore.dismissedSmsBanner,
                _smsStateRefreshTrigger
            ) { smsEnabled, isSmsBannerDismissed, _ ->
                val smsStatus = smsPermissionHelper.getSmsCapabilityStatus()
                Triple(
                    smsEnabled,
                    smsStatus.isFullyFunctional,
                    determineSmsBannerState(
                        smsEnabled = smsEnabled,
                        isFullyFunctional = smsStatus.isFullyFunctional,
                        isSmsBannerDismissed = isSmsBannerDismissed
                    )
                )
            }.collect { (smsEnabled, isFullyFunctional, bannerState) ->
                _uiState.update {
                    it.copy(
                        smsBannerState = bannerState,
                        smsEnabled = smsEnabled,
                        isSmsFullyFunctional = isFullyFunctional
                    )
                }
            }
        }
    }

    fun dismissSmsBanner() {
        viewModelScope.launch {
            settingsDataStore.setDismissedSmsBanner(true)
        }
    }

    fun refreshSmsState() {
        _smsStateRefreshTrigger.value++
    }

    fun getSmsDefaultAppIntent() = smsPermissionHelper.createDefaultSmsAppIntent()

    /**
     * Optimistically mark chat as read (called from observer delegate on socket event).
     */
    private fun optimisticallyMarkChatRead(chatGuid: String) {
        _uiState.update { state ->
            val updated = state.conversations.map { conv ->
                if (conv.guid == chatGuid ||
                    conv.mergedChatGuids.contains(chatGuid) ||
                    normalizeGuid(conv.guid) == normalizeGuid(chatGuid)) {
                    conv.copy(unreadCount = 0)
                } else conv
            }
            state.copy(conversations = updated.toStable())
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
                Timber.tag("ConversationsViewModel").i("Resuming interrupted initial sync")
                syncService.resumeInitialSync()
            }
        }
    }

    /**
     * Observe sync states and trigger categorization when BOTH:
     * 1. iMessage/BlueBubbles sync is complete (not syncing)
     * 2. SMS import is complete (not importing)
     * 3. Categorization is enabled
     * 4. We haven't already started categorization this session
     */
    private fun observeCategorizationTrigger() {
        viewModelScope.launch {
            // Combine all relevant states to determine when to start categorization
            combine(
                observerDelegate.unifiedSyncProgress,
                settingsDataStore.categorizationEnabled,
                settingsDataStore.initialSyncComplete,
                settingsDataStore.hasCompletedInitialSmsImport
            ) { syncProgress, categorizationEnabled, syncComplete, smsImportComplete ->
                // Check if any syncing is in progress
                val isSyncing = syncProgress != null && !syncProgress.hasError
                // All conditions must be met
                !isSyncing && categorizationEnabled && syncComplete &&
                    // SMS import complete OR SMS is disabled
                    (smsImportComplete || !settingsDataStore.smsEnabled.first())
            }.distinctUntilChanged().collect { shouldCategorize ->
                if (shouldCategorize && !hasStartedCategorization) {
                    hasStartedCategorization = true
                    startCategorization()
                }
            }
        }
    }

    /**
     * Start retroactive categorization with progress tracking.
     * Called automatically when all syncs complete and categorization is enabled.
     */
    private fun startCategorization() {
        viewModelScope.launch {
            Timber.tag("ConversationsViewModel").i("Starting retroactive categorization")
            observerDelegate.updateCategorizationState(CategorizationState.Categorizing(0f, 0, 0))

            try {
                val categorized = categorizationRepository.categorizeAllChats { current, total ->
                    observerDelegate.updateCategorizationState(
                        CategorizationState.Categorizing(
                            progress = if (total > 0) current.toFloat() / total else 0f,
                            current = current,
                            total = total
                        )
                    )
                }
                Timber.tag("ConversationsViewModel").i("Categorization complete: $categorized chats")
                observerDelegate.updateCategorizationState(CategorizationState.Complete)
                // Clear completion state after a delay
                delay(2000)
                observerDelegate.updateCategorizationState(CategorizationState.Idle)
            } catch (e: Exception) {
                Timber.tag("ConversationsViewModel").e(e, "Categorization failed")
                observerDelegate.updateCategorizationState(CategorizationState.Error(e.message ?: "Unknown error"))
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
            // Combine search query with date range state for reactive search
            combine(
                _searchQuery.debounce(300).distinctUntilChanged(),
                _uiState.map { it.searchStartDate }.distinctUntilChanged(),
                _uiState.map { it.searchEndDate }.distinctUntilChanged()
            ) { query, startDate, endDate -> Triple(query, startDate, endDate) }
                .collect { (query, startDate, endDate) ->
                    val hasDateFilter = startDate != null || endDate != null
                    val hasTextQuery = query.length >= 2

                    when {
                        // Text search with optional date range
                        hasTextQuery -> {
                            // Apply default bounds: start = 1960, end = today
                            val effectiveStart = startDate ?: DEFAULT_START_DATE
                            val effectiveEnd = endDate ?: System.currentTimeMillis()

                            val textMatchMessages = messageRepository.searchMessagesInDateRange(
                                query = query,
                                startDate = effectiveStart,
                                endDate = effectiveEnd,
                                limit = 50
                            ).first()

                            val linkTitleMatches = linkPreviewRepository.searchByTitle(query, 20)
                            val matchedUrls = linkTitleMatches.map { it.url }.toSet()
                            val matchedPreviewsByUrl = linkTitleMatches.associateBy { it.url }
                            val linkMatchMessages = mutableListOf<com.bothbubbles.data.local.db.entity.MessageEntity>()
                            for (url in matchedUrls) {
                                val urlMatches = messageRepository.searchMessagesInDateRange(
                                    query = url,
                                    startDate = effectiveStart,
                                    endDate = effectiveEnd,
                                    limit = 10
                                ).first()
                                linkMatchMessages.addAll(urlMatches)
                            }
                            val allMessages = (textMatchMessages + linkMatchMessages)
                                .distinctBy { it.guid }
                                .take(50)

                            val results = buildMessageSearchResults(allMessages, emptyMap())
                            _uiState.update { it.copy(messageSearchResults = results.toStable()) }
                        }

                        // Date-only search (no text query required)
                        hasDateFilter -> {
                            // Apply default bounds: start = 1960, end = today
                            val effectiveStart = startDate ?: DEFAULT_START_DATE
                            val effectiveEnd = endDate ?: System.currentTimeMillis()

                            val messages = messageRepository.getMessagesInDateRange(
                                startDate = effectiveStart,
                                endDate = effectiveEnd,
                                limit = 50
                            ).first()

                            val results = buildMessageSearchResults(messages, emptyMap())
                            _uiState.update { it.copy(messageSearchResults = results.toStable()) }
                        }

                        // No search criteria
                        else -> {
                            _uiState.update { it.copy(messageSearchResults = emptyList<MessageSearchResult>().toStable()) }
                        }
                    }
                }
        }
    }

    private suspend fun buildMessageSearchResults(
        messages: List<com.bothbubbles.data.local.db.entity.MessageEntity>,
        previewsByUrl: Map<String, com.bothbubbles.data.local.db.entity.LinkPreviewEntity>
    ): List<MessageSearchResult> {
        return messages.map { message ->
            val chat = chatRepository.getChat(message.chatGuid)
            // Extract URL from message text to look up link preview
            val messageUrl = message.text?.let { text ->
                Regex("""https?://[^\s]+""").find(text)?.value
            }
            val linkPreview = messageUrl?.let { previewsByUrl[it] }

            // Get participants for proper display name resolution (matches ConversationMappers logic)
            val participants: List<HandleEntity> = chat?.let { chatRepository.getParticipantsForChat(it.guid) } ?: emptyList()
            val primaryParticipant: HandleEntity? = participants.firstOrNull()
            val participantNames = participants.map { it.displayName }
            val isGroup = chat?.isGroup ?: false

            // Determine display name using same logic as ConversationMappers.toUiModel()
            val resolvedDisplayName = if (!isGroup && primaryParticipant != null) {
                // For 1:1 chats: use participant's displayName (includes "Maybe:" prefix for inferred)
                primaryParticipant.displayName.takeIf { it.isNotBlank() }
                    ?: chat?.chatIdentifier?.let { PhoneNumberFormatter.format(it) }
                    ?: primaryParticipant.address.let { PhoneNumberFormatter.format(it) }
            } else if (isGroup) {
                // For group chats: explicit name > joined participant names > "Group Chat"
                chat?.displayName?.let { PhoneNumberFormatter.stripServiceSuffix(it) }?.takeIf { it.isNotBlank() }
                    ?: participantNames.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: "Group Chat"
            } else {
                // Fallback for edge cases
                chat?.displayName?.let { PhoneNumberFormatter.stripServiceSuffix(it) }?.takeIf { it.isNotBlank() }
                    ?: chat?.chatIdentifier?.let { PhoneNumberFormatter.format(it) }
                    ?: "Unknown"
            }

            // Determine message type
            val messageType = if (message.text?.isNotEmpty() == true) MessageType.TEXT else MessageType.ATTACHMENT

            MessageSearchResult(
                messageGuid = message.guid,
                chatGuid = message.chatGuid,
                chatDisplayName = resolvedDisplayName,
                messageText = message.text ?: "",
                timestamp = message.dateCreated,
                formattedTime = formatRelativeTime(message.dateCreated, application),
                isFromMe = message.isFromMe,
                avatarPath = primaryParticipant?.cachedAvatarPath
                    ?: chat?.customAvatarPath
                    ?: chat?.serverGroupPhotoPath,
                isGroup = isGroup,
                messageType = messageType,
                linkTitle = linkPreview?.title,
                linkDomain = linkPreview?.domain
            )
        }
    }

    fun handleSwipeAction(chatGuid: String, action: SwipeActionType) {
        actionsDelegate.handleSwipeAction(chatGuid, action, _uiState.value.conversations)
    }

    fun snoozeChat(chatGuid: String, durationMs: Long) {
        actionsDelegate.snoozeChat(chatGuid, durationMs, _uiState.value.conversations)
    }

    fun unsnoozeChat(chatGuid: String) {
        actionsDelegate.unsnoozeChat(chatGuid, _uiState.value.conversations)
    }

    fun markAsUnread(chatGuid: String) {
        actionsDelegate.markAsUnread(chatGuid, _uiState.value.conversations)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateSearchStartDate(date: Long?) {
        _uiState.update { it.copy(searchStartDate = date) }
    }

    fun updateSearchEndDate(date: Long?) {
        _uiState.update { it.copy(searchEndDate = date) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            syncService.performIncrementalSync()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun startSmsImport() {
        viewModelScope.launch {
            Timber.tag("ConversationsViewModel").d("Starting SMS import...")
            observerDelegate.updateSmsImportState(SmsImportState.Importing(0f, 0, 0))
            smsRepository.importAllThreads(
                limit = 500,
                onProgress = { current, total ->
                    Timber.tag("ConversationsViewModel").d("SMS import progress: $current of $total")
                    // Avoid division by zero when there are no threads
                    val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                    observerDelegate.updateSmsImportState(
                        SmsImportState.Importing(
                            progress = progress,
                            current = current,
                            total = total
                        )
                    )
                }
            ).fold(
                onSuccess = { count ->
                    Timber.tag("ConversationsViewModel").d("SMS import completed: $count threads imported")
                    observerDelegate.updateSmsImportState(SmsImportState.Complete)
                    settingsDataStore.setHasCompletedInitialSmsImport(true)
                    notificationService.showSmsImportCompleteNotification()
                    // Keep Complete state - progress bar hides when all stages are done
                },
                onFailure = { e ->
                    Timber.tag("ConversationsViewModel").e(e, "SMS import failed")
                    observerDelegate.updateSmsImportState(SmsImportState.Error(e.message ?: "Unknown error"))
                }
            )
        }
    }

    fun dismissSmsImportError() {
        observerDelegate.updateSmsImportState(SmsImportState.Idle)
    }

    /**
     * Toggle the expanded state of the unified sync progress bar.
     */
    fun toggleSyncProgressExpanded() {
        observerDelegate.toggleExpanded()
    }

    /**
     * Retry the failed sync operation.
     */
    fun retrySyncOperation() {
        val progress = _uiState.value.unifiedSyncProgress ?: return
        when (progress.failedStageType) {
            com.bothbubbles.services.sync.SyncStageType.IMESSAGE -> {
                // Retry iMessage sync
                syncService.startBackgroundSync()
            }
            com.bothbubbles.services.sync.SyncStageType.SMS_IMPORT -> {
                // Retry SMS import
                startSmsImport()
            }
            com.bothbubbles.services.sync.SyncStageType.CATEGORIZATION -> {
                // Retry categorization
                viewModelScope.launch {
                    // Categorization will be triggered by observeCategorizationTrigger
                }
            }
            null -> { /* No failed stage */ }
        }
    }

    /**
     * Dismiss sync error and hide the progress bar if all operations are complete.
     */
    fun dismissSyncError() {
        observerDelegate.dismissSyncError()
    }

    fun resetAppData(onReset: () -> Unit) {
        viewModelScope.launch {
            try {
                messageRepository.deleteAllMessages()
                // Note: Other deletions delegated to repositories
                settingsDataStore.clearSyncProgress()
                settingsDataStore.setSetupComplete(false)
                // Clear any sync error state
                observerDelegate.dismissSyncError()
                withContext(Dispatchers.Main) {
                    onReset()
                }
            } catch (e: Exception) {
                Timber.tag("ConversationsViewModel").e(e, "Failed to reset app data")
            }
        }
    }

    fun togglePin(chatGuid: String) {
        actionsDelegate.togglePin(chatGuid, _uiState.value.conversations)
    }

    fun canPinChat(chatGuid: String): Boolean {
        return actionsDelegate.canPinChat(chatGuid, _uiState.value.conversations)
    }

    fun reorderPins(reorderedGuids: List<String>) {
        actionsDelegate.reorderPins(reorderedGuids, _uiState.value.conversations)
    }

    fun setGroupPhoto(chatGuid: String, uri: Uri) {
        actionsDelegate.setGroupPhoto(chatGuid, uri)
    }

    fun toggleMute(chatGuid: String) {
        actionsDelegate.toggleMute(chatGuid, _uiState.value.conversations)
    }

    fun archiveChat(chatGuid: String) {
        actionsDelegate.archiveChat(chatGuid, _uiState.value.conversations)
    }

    fun deleteChat(chatGuid: String) {
        actionsDelegate.deleteChat(chatGuid, _uiState.value.conversations)
    }

    fun markAsRead(chatGuid: String) {
        actionsDelegate.markAsRead(chatGuid, _uiState.value.conversations)
    }

    fun markChatsAsUnread(chatGuids: Set<String>) {
        actionsDelegate.markChatsAsUnread(chatGuids, _uiState.value.conversations)
    }

    fun markChatsAsRead(chatGuids: Set<String>) {
        actionsDelegate.markChatsAsRead(chatGuids, _uiState.value.conversations)
    }

    fun blockChats(chatGuids: Set<String>) {
        actionsDelegate.blockChats(chatGuids, _uiState.value.conversations)
    }

    fun isContactStarred(address: String): Boolean {
        return actionsDelegate.isContactStarred(address)
    }

    fun dismissInferredName(address: String) {
        actionsDelegate.dismissInferredName(address)
    }

    fun refreshContactInfo(address: String) {
        actionsDelegate.refreshContactInfo(address)
    }

    // ============================================================================
    // Selection Operations (Gmail-style Select All)
    // ============================================================================

    /**
     * Toggle selection for a single conversation.
     * Pinned items can now be selected in selection mode.
     */
    fun toggleSelection(guid: String) {
        selectionDelegate.toggleSelection(guid)
    }

    /**
     * Clear all selections and exit selection mode.
     */
    fun clearSelection() {
        selectionDelegate.clearSelection()
    }

    /**
     * Trigger "Select All" for current filter.
     * Queries the database for total count and enters select-all mode.
     */
    fun selectAll() {
        val currentFilterString = _uiState.value.conversationFilter
        val currentFilter = ConversationFilter.entries.find {
            it.name.lowercase() == currentFilterString.lowercase()
        } ?: ConversationFilter.ALL
        val categoryFilter = _uiState.value.categoryFilter
        val visibleConversations = _uiState.value.conversations
        selectionDelegate.selectAll(currentFilter, categoryFilter, visibleConversations)
    }

    /**
     * Check if a conversation should be displayed as selected.
     * Used for newly loaded items in select-all mode.
     */
    fun isConversationSelected(guid: String): Boolean {
        return selectionState.value.isSelected(guid)
    }

    /**
     * Apply a batch action to all selected conversations.
     */
    fun applyBatchAction(action: BatchAction) {
        selectionDelegate.applyBatchAction(
            action = action,
            conversations = _uiState.value.conversations
        ) { guids ->
            when (action) {
                BatchAction.MARK_READ -> actionsDelegate.markChatsAsRead(guids, _uiState.value.conversations)
                BatchAction.MARK_UNREAD -> actionsDelegate.markChatsAsUnread(guids, _uiState.value.conversations)
                BatchAction.ARCHIVE -> guids.forEach { actionsDelegate.archiveChat(it, _uiState.value.conversations) }
                BatchAction.DELETE -> guids.forEach { actionsDelegate.deleteChat(it, _uiState.value.conversations) }
                BatchAction.BLOCK -> actionsDelegate.blockChats(guids, _uiState.value.conversations)
                BatchAction.SNOOZE -> { /* TODO: Implement batch snooze */ }
            }
        }
    }

    /**
     * Update selection filter context when filter changes.
     * Clears selection if filter changes while in select-all mode.
     */
    private fun updateSelectionFilterContext() {
        val currentFilterString = _uiState.value.conversationFilter
        val currentFilter = ConversationFilter.entries.find {
            it.name.lowercase() == currentFilterString.lowercase()
        } ?: ConversationFilter.ALL
        val categoryFilter = _uiState.value.categoryFilter
        selectionDelegate.updateFilterContext(currentFilter, categoryFilter)
    }
}
