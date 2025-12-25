package com.bothbubbles.ui.chat.delegates

import timber.log.Timber
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.imessage.IMessageAvailabilityService
import com.bothbubbles.services.messaging.ChatFallbackTracker
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.TutorialState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Delegate responsible for managing send mode (iMessage vs SMS) logic.
 * Handles:
 * - iMessage availability checking
 * - Server stability tracking
 * - Send mode switching and persistence
 * - Tutorial state management
 * - Automatic fallback to SMS on server disconnect
 *
 * Uses AssistedInject to receive runtime parameters at construction time,
 * eliminating the need for a separate initialize() call.
 *
 * Phase 2: Uses SocketConnection interface instead of SocketService
 * for improved testability.
 */
class ChatSendModeManager @AssistedInject constructor(
    private val socketConnection: SocketConnection,
    private val settingsDataStore: SettingsDataStore,
    private val iMessageAvailabilityService: IMessageAvailabilityService,
    private val chatRepository: ChatRepository,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val chatFallbackTracker: ChatFallbackTracker,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val initialSendMode: ChatSendMode,
    @Assisted("isGroup") private val isGroup: Boolean,
    @Assisted("participantPhone") private val participantPhone: String?
) {

    @AssistedFactory
    interface Factory {
        fun create(
            chatGuid: String,
            scope: CoroutineScope,
            initialSendMode: ChatSendMode,
            @Assisted("isGroup") isGroup: Boolean,
            @Assisted("participantPhone") participantPhone: String?
        ): ChatSendModeManager
    }

    companion object {
        private const val AVAILABILITY_CHECK_COOLDOWN = 5 * 60 * 1000L // 5 minutes
        private const val SERVER_STABILITY_PERIOD_MS = 30_000L // 30 seconds stable before allowing iMessage
        private const val FLIP_FLOP_WINDOW_MS = 60_000L // 1 minute window for flip/flop detection
        private const val FLIP_FLOP_THRESHOLD = 3 // 3+ state changes = unstable server
        private const val PERIODIC_FALLBACK_CHECK_INTERVAL_MS = 30_000L // 30 seconds between fallback exit checks
        private const val DISCONNECT_GRACE_PERIOD_MS = 5_000L // 5 seconds grace before SMS switch
    }

    // Server stability tracking
    private var serverConnectedSince: Long? = null
    private val connectionStateChanges = mutableListOf<Long>()
    private var previousConnectionState: ConnectionState? = null
    private var hasEverConnected = false

    // Availability check state
    private var lastAvailabilityCheck: Long = 0
    private var iMessageAvailabilityCheckJob: Job? = null
    private var periodicFallbackCheckJob: Job? = null
    private var pendingSmsSwitchJob: Job? = null

    // Send mode state
    private val _currentSendMode = MutableStateFlow(initialSendMode)
    val currentSendMode: StateFlow<ChatSendMode> = _currentSendMode.asStateFlow()

    private val _contactIMessageAvailable = MutableStateFlow<Boolean?>(null)
    val contactIMessageAvailable: StateFlow<Boolean?> = _contactIMessageAvailable.asStateFlow()

    private val _isCheckingIMessageAvailability = MutableStateFlow(false)
    val isCheckingIMessageAvailability: StateFlow<Boolean> = _isCheckingIMessageAvailability.asStateFlow()

    private val _canToggleSendMode = MutableStateFlow(false)
    val canToggleSendMode: StateFlow<Boolean> = _canToggleSendMode.asStateFlow()

    private val _showSendModeRevealAnimation = MutableStateFlow(false)
    val showSendModeRevealAnimation: StateFlow<Boolean> = _showSendModeRevealAnimation.asStateFlow()

    private val _sendModeManuallySet = MutableStateFlow(false)
    val sendModeManuallySet: StateFlow<Boolean> = _sendModeManuallySet.asStateFlow()

    private val _serverFallbackBlocked = MutableStateFlow(false)
    val serverFallbackBlocked: StateFlow<Boolean> = _serverFallbackBlocked.asStateFlow()

    private val _tutorialState = MutableStateFlow(TutorialState.NOT_SHOWN)
    val tutorialState: StateFlow<TutorialState> = _tutorialState.asStateFlow()

    // Auto-switch setting state
    private val _autoSwitchEnabled = MutableStateFlow(true)
    val autoSwitchEnabled: StateFlow<Boolean> = _autoSwitchEnabled.asStateFlow()

    init {
        // Initialize server stability tracking if already connected
        if (socketConnection.connectionState.value == ConnectionState.CONNECTED) {
            serverConnectedSince = System.currentTimeMillis()
        }

        // Start observing auto-switch setting
        observeAutoSwitchSetting()

        // Start observing connection state
        observeConnectionState()

        // Load persisted send mode preference
        loadPersistedSendMode()

        // Start periodic fallback exit check
        startPeriodicFallbackCheck()
    }

    /**
     * Check iMessage availability for the current chat's contact.
     */
    fun checkIMessageAvailability(
        isGroup: Boolean,
        isIMessageChat: Boolean,
        isLocalSmsChat: Boolean,
        participantPhone: String?,
        onUpdateState: (
            contactAvailable: Boolean?,
            isChecking: Boolean,
            sendMode: ChatSendMode,
            canToggle: Boolean,
            showReveal: Boolean
        ) -> Unit
    ) {
        // Skip for ALL group chats
        if (isGroup) {
            Timber.d("Skipping iMessage check: group chat (isIMessage=$isIMessageChat)")
            return
        }

        if (participantPhone == null) {
            Timber.w("participantPhone is null, cannot check availability")
            return
        }

        // For existing iMessage 1-on-1 chats with phone numbers: enable toggle immediately
        val isEmailAddress = participantPhone.contains("@")
        if (isIMessageChat && !isGroup && !isEmailAddress) {
            Timber.d("iMessage chat with phone number - enabling SMS toggle immediately")
            _contactIMessageAvailable.value = true
            _canToggleSendMode.value = true
            _showSendModeRevealAnimation.value = !_sendModeManuallySet.value
            // Clear fallback mode for existing iMessage chats so AUTO routing uses iMessage
            if (_currentSendMode.value == ChatSendMode.IMESSAGE && chatFallbackTracker.isInFallbackMode(chatGuid)) {
                Timber.i("Existing iMessage chat, clearing fallback mode for $chatGuid")
                chatFallbackTracker.exitFallbackMode(chatGuid)
            }
            onUpdateState(true, false, _currentSendMode.value, true, _showSendModeRevealAnimation.value)
            initTutorialIfNeeded()
            return
        }

        Timber.d("Starting iMessage availability check for: $participantPhone")

        scope.launch {
            _isCheckingIMessageAvailability.value = true

            try {
                // Check if cache is from previous session
                val needsRecheck = iMessageAvailabilityService.isCacheFromPreviousSession(participantPhone)

                val result = iMessageAvailabilityService.checkAvailability(
                    address = participantPhone,
                    forceRecheck = needsRecheck
                )

                val serverStable = isServerStable()

                result.fold(
                    onSuccess = { available ->
                        // Determine send mode based on availability and stability
                        val newMode = when {
                            isEmailAddress -> ChatSendMode.IMESSAGE
                            available && serverStable -> ChatSendMode.IMESSAGE
                            available -> _currentSendMode.value
                            else -> ChatSendMode.SMS
                        }

                        val canToggle = available && !isEmailAddress && !isGroup

                        _contactIMessageAvailable.value = available
                        _isCheckingIMessageAvailability.value = false
                        _currentSendMode.value = newMode
                        _canToggleSendMode.value = canToggle
                        _showSendModeRevealAnimation.value = canToggle && !_sendModeManuallySet.value

                        // Clear fallback mode when system determines iMessage should be used
                        if (newMode == ChatSendMode.IMESSAGE && chatFallbackTracker.isInFallbackMode(chatGuid)) {
                            Timber.i("iMessage available and stable, clearing fallback mode for $chatGuid")
                            chatFallbackTracker.exitFallbackMode(chatGuid)
                        }

                        onUpdateState(available, false, newMode, canToggle, _showSendModeRevealAnimation.value)

                        if (canToggle) {
                            initTutorialIfNeeded()
                        }
                    },
                    onFailure = { e ->
                        Timber.w(e, "iMessage availability check FAILED: ${e.message}")
                        val newMode = if (isEmailAddress) ChatSendMode.IMESSAGE else _currentSendMode.value
                        _contactIMessageAvailable.value = if (isEmailAddress) true else null
                        _isCheckingIMessageAvailability.value = false
                        _currentSendMode.value = newMode
                        onUpdateState(if (isEmailAddress) true else null, false, newMode, false, false)
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error checking iMessage availability: ${e.message}")
                _isCheckingIMessageAvailability.value = false
                onUpdateState(null, false, _currentSendMode.value, false, false)
            }
        }
    }

    /**
     * Check if iMessage is available and exit fallback mode if so.
     */
    fun checkAndMaybeExitFallback(participantPhone: String?) {
        val fallbackReason = chatFallbackTracker.getFallbackReason(chatGuid)

        // Only check for IMESSAGE_FAILED fallback
        if (fallbackReason != FallbackReason.IMESSAGE_FAILED) return

        val now = System.currentTimeMillis()
        if (now - lastAvailabilityCheck < AVAILABILITY_CHECK_COOLDOWN) {
            Timber.d("Skipping availability check - cooldown not passed")
            return
        }
        lastAvailabilityCheck = now

        scope.launch {
            if (participantPhone.isNullOrBlank()) {
                Timber.d("No address found for availability check")
                return@launch
            }

            if (socketConnection.connectionState.value != ConnectionState.CONNECTED) {
                Timber.d("Server not connected, skipping availability check")
                return@launch
            }

            try {
                Timber.d("Checking iMessage availability for $participantPhone")
                // Use IMessageAvailabilityService for consistent caching and retry logic
                val result = iMessageAvailabilityService.checkAvailability(participantPhone)
                if (result.getOrNull() == true) {
                    Timber.d("iMessage now available, exiting fallback mode")
                    chatFallbackTracker.exitFallbackMode(chatGuid)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to check iMessage availability")
            }
        }
    }

    /**
     * Manually set send mode.
     */
    fun setSendMode(mode: ChatSendMode, persist: Boolean = true): Boolean {
        if (mode == ChatSendMode.IMESSAGE) {
            if (_contactIMessageAvailable.value != true) {
                Timber.w("Cannot switch to iMessage: contact doesn't support it")
                return false
            }
            if (!isServerStable()) {
                Timber.w("Cannot switch to iMessage: server not stable yet")
                return false
            }
            // User explicitly chose iMessage - exit fallback mode so AUTO routing uses iMessage
            if (chatFallbackTracker.isInFallbackMode(chatGuid)) {
                Timber.i("User selected iMessage mode, exiting fallback mode for $chatGuid")
                chatFallbackTracker.exitFallbackMode(chatGuid)
            }
        }

        _currentSendMode.value = mode
        if (persist) {
            _sendModeManuallySet.value = true
        }

        if (persist) {
            scope.launch {
                try {
                    chatRepository.updatePreferredSendMode(
                        chatGuid = chatGuid,
                        mode = mode.name.lowercase(),
                        manuallySet = true
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to persist send mode preference")
                }
            }
        }

        return true
    }

    /**
     * Try to toggle send mode.
     */
    fun tryToggleSendMode(): Boolean {
        val currentMode = _currentSendMode.value
        val newMode = if (currentMode == ChatSendMode.SMS) ChatSendMode.IMESSAGE else ChatSendMode.SMS
        return setSendMode(newMode, persist = true)
    }

    /**
     * Check if send mode toggle is currently available.
     */
    fun canToggleSendModeNow(): Boolean {
        return _canToggleSendMode.value &&
                _contactIMessageAvailable.value == true &&
                isServerStable()
    }

    /**
     * Mark reveal animation as shown.
     */
    fun markRevealAnimationShown() {
        _showSendModeRevealAnimation.value = false
    }

    /**
     * Update tutorial state.
     */
    fun updateTutorialState(newState: TutorialState) {
        _tutorialState.value = newState

        if (newState == TutorialState.COMPLETED) {
            scope.launch {
                settingsDataStore.setHasCompletedSendModeTutorial(true)
            }
        }
    }

    /**
     * Handle successful toggle during tutorial.
     */
    fun onTutorialToggleSuccess() {
        when (_tutorialState.value) {
            TutorialState.STEP_1_SWIPE_UP -> {
                _tutorialState.value = TutorialState.STEP_2_SWIPE_BACK
            }
            TutorialState.STEP_2_SWIPE_BACK -> {
                _tutorialState.value = TutorialState.COMPLETED
                scope.launch {
                    settingsDataStore.setHasCompletedSendModeTutorial(true)
                }
            }
            else -> { /* No action needed */ }
        }
    }

    /**
     * Determine if the send mode switch menu item should be shown.
     * Returns true when:
     * - Auto-switch is disabled (manual mode)
     * - Not a group chat
     * - Not an email-only contact
     * - Contact supports iMessage OR currently in SMS mode
     */
    fun canShowSendModeSwitch(): Boolean {
        val isEmailContact = participantPhone?.contains("@") == true
        return !_autoSwitchEnabled.value &&
               !isGroup &&
               !isEmailContact &&
               (_currentSendMode.value == ChatSendMode.SMS || _contactIMessageAvailable.value == true)
    }

    /**
     * Observe the auto-switch setting from preferences.
     */
    private fun observeAutoSwitchSetting() {
        scope.launch {
            settingsDataStore.autoSwitchSendMode.collect { enabled ->
                _autoSwitchEnabled.value = enabled
            }
        }
    }

    /**
     * Check if server is stable.
     */
    private fun isServerStable(): Boolean {
        val now = System.currentTimeMillis()
        connectionStateChanges.removeAll { it < now - FLIP_FLOP_WINDOW_MS }

        if (connectionStateChanges.size < FLIP_FLOP_THRESHOLD) {
            return true
        }

        val connectedSince = serverConnectedSince ?: return false
        val stableFor = now - connectedSince
        return stableFor >= SERVER_STABILITY_PERIOD_MS
    }

    /**
     * Initialize tutorial if needed.
     */
    private fun initTutorialIfNeeded() {
        if (!_canToggleSendMode.value) return

        scope.launch {
            settingsDataStore.hasCompletedSendModeTutorial.first().let { completed ->
                if (!completed) {
                    _tutorialState.value = TutorialState.STEP_1_SWIPE_UP
                }
            }
        }
    }

    /**
     * Load persisted send mode preference from unified chat.
     */
    private fun loadPersistedSendMode() {
        scope.launch {
            try {
                val (preferredMode, manuallySet) = chatRepository.getSendModePreference(chatGuid) ?: return@launch
                if (manuallySet && preferredMode != null) {
                    val persistedMode = when (preferredMode.lowercase()) {
                        "imessage" -> ChatSendMode.IMESSAGE
                        "sms" -> ChatSendMode.SMS
                        else -> null
                    }
                    if (persistedMode != null) {
                        _currentSendMode.value = persistedMode
                        _sendModeManuallySet.value = true
                        // If user's persisted preference is iMessage, ensure fallback mode is cleared
                        // so AUTO routing respects the user's choice
                        if (persistedMode == ChatSendMode.IMESSAGE && chatFallbackTracker.isInFallbackMode(chatGuid)) {
                            Timber.i("Restoring iMessage mode preference, exiting fallback mode for $chatGuid")
                            chatFallbackTracker.exitFallbackMode(chatGuid)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load persisted send mode")
            }
        }
    }

    /**
     * Observe connection state for automatic fallback.
     */
    private fun observeConnectionState() {
        scope.launch {
            socketConnection.connectionState.collect { state ->
                val isConnected = state == ConnectionState.CONNECTED
                val now = System.currentTimeMillis()
                val wasConnected = previousConnectionState == ConnectionState.CONNECTED
                val stateActuallyChanged = previousConnectionState != null && previousConnectionState != state

                if (hasEverConnected && stateActuallyChanged && (wasConnected || isConnected)) {
                    connectionStateChanges.add(now)
                }

                connectionStateChanges.removeAll { it < now - FLIP_FLOP_WINDOW_MS }

                if (isConnected) {
                    hasEverConnected = true
                    if (serverConnectedSince == null) {
                        serverConnectedSince = System.currentTimeMillis()
                    }

                    // Cancel any pending SMS switch since we reconnected
                    pendingSmsSwitchJob?.cancel()
                    pendingSmsSwitchJob = null

                    // Clear input blocked state now that server is connected
                    _serverFallbackBlocked.value = false

                    // Restore iMessage mode if appropriate
                    val shouldBeIMessage = _contactIMessageAvailable.value == true
                    val wasAutoSwitchedToSms = _currentSendMode.value == ChatSendMode.SMS && !_sendModeManuallySet.value

                    if (shouldBeIMessage && wasAutoSwitchedToSms) {
                        if (isServerStable()) {
                            _currentSendMode.value = ChatSendMode.IMESSAGE
                            // Clear any fallback mode to ensure routing matches UI
                            if (chatFallbackTracker.isInFallbackMode(chatGuid)) {
                                Timber.i("Server reconnected and stable, clearing fallback mode for $chatGuid")
                                chatFallbackTracker.exitFallbackMode(chatGuid)
                            }
                        } else {
                            scheduleIMessageModeCheck()
                        }
                    }
                } else {
                    serverConnectedSince = null
                    iMessageAvailabilityCheckJob?.cancel()

                    // Skip auto-switch if manual mode is enabled and user has explicitly set mode
                    // This respects the user's choice - messages will queue until server reconnects
                    if (!_autoSwitchEnabled.value && _sendModeManuallySet.value) {
                        Timber.i("Manual mode: keeping ${_currentSendMode.value} despite server disconnect")
                        previousConnectionState = state
                        return@collect
                    }

                    // Auto-switch to SMS for non-iMessage-only chats (if SMS is available)
                    val isIMessageGroup = chatGuid.startsWith("iMessage;+;", ignoreCase = true)
                    val isEmailChat = participantPhone?.contains("@") == true
                    val isIMessageOnly = isIMessageGroup || isEmailChat

                    if (!isIMessageOnly) {
                        // Cancel any existing pending switch before starting a new one
                        pendingSmsSwitchJob?.cancel()
                        pendingSmsSwitchJob = scope.launch {
                            delay(DISCONNECT_GRACE_PERIOD_MS)

                            // Re-check connection state after grace period
                            if (socketConnection.connectionState.value == ConnectionState.CONNECTED) {
                                Timber.d("Server reconnected during grace period, staying in iMessage mode")
                                return@launch
                            }

                            // Check if SMS is actually available before switching
                            val smsCapability = smsPermissionHelper.getSmsCapabilityStatus()
                            if (smsCapability.isFullyFunctional && smsCapability.hasCellularConnectivity) {
                                // SMS is available - switch to SMS mode
                                _currentSendMode.value = ChatSendMode.SMS
                                Timber.i("Grace period expired, switched to SMS mode")
                            } else {
                                // If SMS isn't available, stay in iMessage mode - messages will queue
                                // and send when server reconnects, or user can retry manually
                                Timber.i("Grace period expired but SMS unavailable, staying in ${_currentSendMode.value} mode")
                            }
                        }
                    }
                }

                previousConnectionState = state
            }
        }
    }

    /**
     * Schedule iMessage mode check after stability period.
     */
    private fun scheduleIMessageModeCheck() {
        iMessageAvailabilityCheckJob?.cancel()
        iMessageAvailabilityCheckJob = scope.launch {
            delay(SERVER_STABILITY_PERIOD_MS)

            if (socketConnection.connectionState.value != ConnectionState.CONNECTED) {
                return@launch
            }

            if (_contactIMessageAvailable.value == true) {
                _currentSendMode.value = ChatSendMode.IMESSAGE
                // Clear any fallback mode to ensure routing matches UI
                if (chatFallbackTracker.isInFallbackMode(chatGuid)) {
                    Timber.i("Server stable after delay, clearing fallback mode for $chatGuid")
                    chatFallbackTracker.exitFallbackMode(chatGuid)
                }
            }
        }
    }

    /**
     * Start periodic check to exit fallback mode when iMessage becomes available.
     *
     * Runs every 30 seconds while the chat is open. The actual API call is throttled
     * by the 5-minute cooldown in checkAndMaybeExitFallback().
     */
    private fun startPeriodicFallbackCheck() {
        periodicFallbackCheckJob?.cancel()
        periodicFallbackCheckJob = scope.launch {
            while (true) {
                delay(PERIODIC_FALLBACK_CHECK_INTERVAL_MS)

                // Only check if we're actually in fallback mode
                val fallbackReason = chatFallbackTracker.getFallbackReason(chatGuid)
                if (fallbackReason == null) {
                    continue // Not in fallback mode, skip this check
                }

                // Only check if server is connected (needed for API call)
                if (socketConnection.connectionState.value != ConnectionState.CONNECTED) {
                    continue
                }

                // Check if iMessage is available and exit fallback if so
                // The cooldown in checkAndMaybeExitFallback will prevent excessive API calls
                checkAndMaybeExitFallback(participantPhone)

                // If we exited fallback mode, also restore iMessage send mode
                if (!chatFallbackTracker.isInFallbackMode(chatGuid)) {
                    if (_contactIMessageAvailable.value == true && !_sendModeManuallySet.value) {
                        _currentSendMode.value = ChatSendMode.IMESSAGE
                        _serverFallbackBlocked.value = false
                        Timber.i("Periodic check: exited fallback mode, restored iMessage send mode")
                    }
                }
            }
        }
    }
}
