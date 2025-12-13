package com.bothbubbles.ui.chat.delegates

import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.imessage.IMessageAvailabilityService
import com.bothbubbles.services.messaging.ChatFallbackTracker
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.TutorialState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for managing send mode (iMessage vs SMS) logic.
 * Handles:
 * - iMessage availability checking
 * - Server stability tracking
 * - Send mode switching and persistence
 * - Tutorial state management
 * - Automatic fallback to SMS on server disconnect
 */
class ChatSendModeManager @Inject constructor(
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore,
    private val iMessageAvailabilityService: IMessageAvailabilityService,
    private val chatRepository: ChatRepository,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val chatFallbackTracker: ChatFallbackTracker,
    private val api: BothBubblesApi
) {
    companion object {
        private const val TAG = "ChatSendModeManager"
        private const val AVAILABILITY_CHECK_COOLDOWN = 5 * 60 * 1000L // 5 minutes
        private const val SERVER_STABILITY_PERIOD_MS = 30_000L // 30 seconds stable before allowing iMessage
        private const val FLIP_FLOP_WINDOW_MS = 60_000L // 1 minute window for flip/flop detection
        private const val FLIP_FLOP_THRESHOLD = 3 // 3+ state changes = unstable server
    }

    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope

    // Server stability tracking
    private var serverConnectedSince: Long? = null
    private val connectionStateChanges = mutableListOf<Long>()
    private var previousConnectionState: ConnectionState? = null
    private var hasEverConnected = false

    // Availability check state
    private var lastAvailabilityCheck: Long = 0
    private var iMessageAvailabilityCheckJob: Job? = null

    // Send mode state
    private val _currentSendMode = MutableStateFlow(ChatSendMode.SMS)
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

    private val _smsInputBlocked = MutableStateFlow(false)
    val smsInputBlocked: StateFlow<Boolean> = _smsInputBlocked.asStateFlow()

    private val _tutorialState = MutableStateFlow(TutorialState.NOT_SHOWN)
    val tutorialState: StateFlow<TutorialState> = _tutorialState.asStateFlow()

    /**
     * Initialize the manager with chat context.
     */
    fun initialize(
        chatGuid: String,
        scope: CoroutineScope,
        initialSendMode: ChatSendMode,
        isGroup: Boolean,
        participantPhone: String?
    ) {
        this.chatGuid = chatGuid
        this.scope = scope
        _currentSendMode.value = initialSendMode

        // Initialize server stability tracking if already connected
        if (socketService.connectionState.value == ConnectionState.CONNECTED) {
            serverConnectedSince = System.currentTimeMillis()
        }

        // Start observing connection state
        observeConnectionState(isGroup, participantPhone)

        // Load persisted send mode preference
        loadPersistedSendMode()
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
            showReveal: Boolean,
            smsBlocked: Boolean
        ) -> Unit
    ) {
        // Skip for ALL group chats
        if (isGroup) {
            Log.d(TAG, "Skipping iMessage check: group chat (isIMessage=$isIMessageChat)")
            return
        }

        if (participantPhone == null) {
            Log.w(TAG, "participantPhone is null, cannot check availability")
            return
        }

        // For existing iMessage 1-on-1 chats with phone numbers: enable toggle immediately
        val isEmailAddress = participantPhone.contains("@")
        if (isIMessageChat && !isGroup && !isEmailAddress) {
            Log.d(TAG, "iMessage chat with phone number - enabling SMS toggle immediately")
            _contactIMessageAvailable.value = true
            _canToggleSendMode.value = true
            _showSendModeRevealAnimation.value = !_sendModeManuallySet.value
            onUpdateState(true, false, _currentSendMode.value, true, _showSendModeRevealAnimation.value, false)
            initTutorialIfNeeded()
            return
        }

        Log.d(TAG, "Starting iMessage availability check for: $participantPhone")

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

                        // Check if SMS should be blocked
                        val isDefaultSmsApp = smsPermissionHelper.isDefaultSmsApp()
                        val smsEnabled = settingsDataStore.smsEnabled.first()
                        val shouldBlockSms = !available && !isEmailAddress && (!isDefaultSmsApp || !smsEnabled)

                        _contactIMessageAvailable.value = available
                        _isCheckingIMessageAvailability.value = false
                        _currentSendMode.value = newMode
                        _canToggleSendMode.value = canToggle
                        _showSendModeRevealAnimation.value = canToggle && !_sendModeManuallySet.value
                        _smsInputBlocked.value = shouldBlockSms

                        onUpdateState(available, false, newMode, canToggle, _showSendModeRevealAnimation.value, shouldBlockSms)

                        if (canToggle) {
                            initTutorialIfNeeded()
                        }
                    },
                    onFailure = { e ->
                        Log.w(TAG, "iMessage availability check FAILED: ${e.message}", e)
                        val newMode = if (isEmailAddress) ChatSendMode.IMESSAGE else _currentSendMode.value
                        _contactIMessageAvailable.value = if (isEmailAddress) true else null
                        _isCheckingIMessageAvailability.value = false
                        _currentSendMode.value = newMode
                        onUpdateState(if (isEmailAddress) true else null, false, newMode, false, false, false)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error checking iMessage availability: ${e.message}", e)
                _isCheckingIMessageAvailability.value = false
                onUpdateState(null, false, _currentSendMode.value, false, false, false)
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
            Log.d(TAG, "Skipping availability check - cooldown not passed")
            return
        }
        lastAvailabilityCheck = now

        scope.launch {
            if (participantPhone.isNullOrBlank()) {
                Log.d(TAG, "No address found for availability check")
                return@launch
            }

            if (socketService.connectionState.value != ConnectionState.CONNECTED) {
                Log.d(TAG, "Server not connected, skipping availability check")
                return@launch
            }

            try {
                Log.d(TAG, "Checking iMessage availability for $participantPhone")
                val response = api.checkIMessageAvailability(participantPhone)
                if (response.isSuccessful && response.body()?.data?.available == true) {
                    Log.d(TAG, "iMessage now available, exiting fallback mode")
                    chatFallbackTracker.exitFallbackMode(chatGuid)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check iMessage availability", e)
            }
        }
    }

    /**
     * Manually set send mode.
     */
    fun setSendMode(mode: ChatSendMode, persist: Boolean = true): Boolean {
        if (mode == ChatSendMode.IMESSAGE) {
            if (_contactIMessageAvailable.value != true) {
                Log.w(TAG, "Cannot switch to iMessage: contact doesn't support it")
                return false
            }
            if (!isServerStable()) {
                Log.w(TAG, "Cannot switch to iMessage: server not stable yet")
                return false
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
                    Log.e(TAG, "Failed to persist send mode preference", e)
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
     * Load persisted send mode preference.
     */
    private fun loadPersistedSendMode() {
        scope.launch {
            try {
                val chat = chatRepository.getChat(chatGuid)
                if (chat != null && chat.sendModeManuallySet && chat.preferredSendMode != null) {
                    val persistedMode = when (chat.preferredSendMode.lowercase()) {
                        "imessage" -> ChatSendMode.IMESSAGE
                        "sms" -> ChatSendMode.SMS
                        else -> null
                    }
                    if (persistedMode != null) {
                        _currentSendMode.value = persistedMode
                        _sendModeManuallySet.value = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load persisted send mode", e)
            }
        }
    }

    /**
     * Observe connection state for automatic fallback.
     */
    private fun observeConnectionState(isGroup: Boolean, participantPhone: String?) {
        scope.launch {
            socketService.connectionState.collect { state ->
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

                    // Restore iMessage mode if appropriate
                    val shouldBeIMessage = _contactIMessageAvailable.value == true
                    val wasAutoSwitchedToSms = _currentSendMode.value == ChatSendMode.SMS && !_sendModeManuallySet.value

                    if (shouldBeIMessage && wasAutoSwitchedToSms) {
                        if (isServerStable()) {
                            _currentSendMode.value = ChatSendMode.IMESSAGE
                        } else {
                            scheduleIMessageModeCheck()
                        }
                    }
                } else {
                    serverConnectedSince = null
                    iMessageAvailabilityCheckJob?.cancel()

                    // Auto-switch to SMS for non-iMessage-only chats
                    val isIMessageGroup = chatGuid.startsWith("iMessage;+;", ignoreCase = true)
                    val isEmailChat = participantPhone?.contains("@") == true
                    val isIMessageOnly = isIMessageGroup || isEmailChat

                    if (!isIMessageOnly) {
                        scope.launch {
                            delay(3000) // Debounce
                            _currentSendMode.value = ChatSendMode.SMS
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

            if (socketService.connectionState.value != ConnectionState.CONNECTED) {
                return@launch
            }

            if (_contactIMessageAvailable.value == true) {
                _currentSendMode.value = ChatSendMode.IMESSAGE
            }
        }
    }
}
