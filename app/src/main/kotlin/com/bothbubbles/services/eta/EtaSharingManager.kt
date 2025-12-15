package com.bothbubbles.services.eta

import android.util.Log
import com.bothbubbles.data.local.prefs.FeaturePreferences
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.AutoShareContact
import com.bothbubbles.data.repository.AutoShareContactRepository
import com.bothbubbles.data.repository.PendingMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Manages ETA sharing sessions including:
 * - State tracking for active sharing
 * - Debouncing logic to prevent spam
 * - Message sending coordination via WorkManager for background safety
 */
@Singleton
class EtaSharingManager @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val settingsDataStore: SettingsDataStore,
    private val featurePreferences: FeaturePreferences,
    private val autoShareContactRepository: AutoShareContactRepository
) {
    companion object {
        private const val TAG = "EtaSharingManager"

        // Thresholds
        const val ARRIVING_SOON_THRESHOLD = 3   // Send "arriving soon" at 3 min
        const val ARRIVED_THRESHOLD = 1         // Consider "arrived" at 0-1 min
        const val DEFAULT_CHANGE_THRESHOLD = 5  // Default if not configured

        // Terminal state protection
        const val TERMINAL_STATE_COOLDOWN_MS = 30 * 60 * 1000L  // 30 min before allowing new session
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(EtaState())
    val state: StateFlow<EtaState> = _state.asStateFlow()

    private val _isNavigationActive = MutableStateFlow(false)
    val isNavigationActive: StateFlow<Boolean> = _isNavigationActive.asStateFlow()

    // Terminal state tracking to prevent "arrived" spam loop
    private var arrivedSentTimestamp: Long = 0

    // Auto-share tracking
    private var autoShareCheckedForSession: Boolean = false
    private var activeAutoShareSessions: MutableList<EtaSharingSession> = mutableListOf()

    // Auto-share state observable
    private val _autoShareState = MutableStateFlow<AutoShareState>(AutoShareState.Inactive)
    val autoShareState: StateFlow<AutoShareState> = _autoShareState.asStateFlow()

    /**
     * Start sharing ETA with a recipient
     */
    fun startSharing(chatGuid: String, displayName: String, initialEta: ParsedEtaData?) {
        Log.d(TAG, "Starting ETA sharing with $displayName (chat: $chatGuid)")

        val session = EtaSharingSession(
            recipientGuid = chatGuid,
            recipientDisplayName = displayName,
            lastEtaMinutes = initialEta?.etaMinutes ?: 0,
            lastMessageType = EtaMessageType.INITIAL
        )

        _state.value = EtaState(
            isSharing = true,
            session = session,
            currentEta = initialEta,
            isNavigationActive = _isNavigationActive.value
        )

        // Send initial message
        initialEta?.let { eta ->
            scope.launch {
                sendMessageForType(session, eta, EtaMessageType.INITIAL)
            }
        }
    }

    /**
     * Stop sharing ETA
     */
    fun stopSharing(sendFinalMessage: Boolean = true) {
        val currentSession = _state.value.session

        if (sendFinalMessage && currentSession != null) {
            scope.launch {
                sendStoppedMessage(currentSession)
            }
        }

        Log.d(TAG, "Stopping ETA sharing")
        _state.value = EtaState(
            isSharing = false,
            session = null,
            currentEta = null,
            isNavigationActive = _isNavigationActive.value
        )
    }

    /**
     * Handle a new ETA update from navigation
     */
    fun onEtaUpdate(etaData: ParsedEtaData) {
        val currentState = _state.value
        _state.value = currentState.copy(
            currentEta = etaData,
            isNavigationActive = true
        )
        _isNavigationActive.value = true

        // Check for auto-share contacts when navigation starts (not already checked)
        if (!autoShareCheckedForSession) {
            autoShareCheckedForSession = true
            scope.launch {
                checkAutoShareContacts(etaData)
            }
        }

        // Handle auto-share sessions (multi-recipient)
        if (activeAutoShareSessions.isNotEmpty()) {
            scope.launch {
                processAutoShareUpdate(etaData)
            }
        }

        // Handle manual single-recipient session
        val session = currentState.session
        if (session == null || !currentState.isSharing) return

        // Terminal state check: If we already sent "arrived",
        // don't send any more updates until cooldown expires
        if (isInTerminalState()) {
            Log.d(TAG, "Ignoring update - in terminal state (arrived already sent)")
            return
        }

        // Determine what type of message to send (if any)
        scope.launch {
            val messageType = determineUpdateType(session, etaData)

            if (messageType != null) {
                sendMessageForType(session, etaData, messageType)
            }
        }
    }

    /**
     * Determine what type of update message to send, if any.
     * Returns null if no update should be sent.
     */
    private suspend fun determineUpdateType(
        session: EtaSharingSession,
        eta: ParsedEtaData
    ): EtaMessageType? {
        val newEtaMinutes = eta.etaMinutes
        val etaDelta = abs(newEtaMinutes - session.lastEtaMinutes)
        val changeThreshold = settingsDataStore.etaChangeThreshold.first()

        // Priority 1: Check for arrival (terminal state)
        if (newEtaMinutes <= ARRIVED_THRESHOLD && session.lastMessageType != EtaMessageType.ARRIVED) {
            Log.d(TAG, "Update type: ARRIVED (ETA: $newEtaMinutes min)")
            return EtaMessageType.ARRIVED
        }

        // Priority 2: Check for "arriving soon" (only send once)
        if (newEtaMinutes <= ARRIVING_SOON_THRESHOLD &&
            session.lastEtaMinutes > ARRIVING_SOON_THRESHOLD &&
            session.lastMessageType != EtaMessageType.ARRIVING_SOON
        ) {
            Log.d(TAG, "Update type: ARRIVING_SOON (ETA dropped to $newEtaMinutes min)")
            return EtaMessageType.ARRIVING_SOON
        }

        // Priority 3: Significant ETA change (user-configurable threshold)
        if (etaDelta >= changeThreshold) {
            Log.d(TAG, "Update type: CHANGE (delta: $etaDelta min, threshold: $changeThreshold)")
            return EtaMessageType.CHANGE
        }

        // No meaningful change
        return null
    }

    /**
     * Check if we're in terminal state (already sent arrived)
     */
    private fun isInTerminalState(): Boolean {
        if (arrivedSentTimestamp == 0L) return false

        val timeSinceArrived = System.currentTimeMillis() - arrivedSentTimestamp

        // Reset terminal state if cooldown expired (30 min) - likely a new trip
        if (timeSinceArrived > TERMINAL_STATE_COOLDOWN_MS) {
            Log.d(TAG, "Terminal state expired (cooldown)")
            clearTerminalState()
            return false
        }

        return true
    }

    private fun enterTerminalState() {
        arrivedSentTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Entered terminal state")
    }

    private fun clearTerminalState() {
        arrivedSentTimestamp = 0
    }

    /**
     * Handle navigation stopped (notification removed)
     *
     * Logic:
     * - If we were close to destination (≤3 min), assume we arrived
     * - Otherwise, assume trip was cancelled
     */
    fun onNavigationStopped() {
        Log.d(TAG, "Navigation stopped")
        _isNavigationActive.value = false

        val currentState = _state.value
        _state.value = currentState.copy(isNavigationActive = false)

        // Reset auto-share session check for next navigation
        autoShareCheckedForSession = false

        // Handle auto-share sessions
        if (activeAutoShareSessions.isNotEmpty()) {
            scope.launch {
                handleAutoShareNavigationStopped(currentState.currentEta)
            }
        }

        // If we were sharing, determine if this was arrival or cancellation
        if (currentState.isSharing && currentState.session != null) {
            val session = currentState.session
            val lastEta = session.lastEtaMinutes

            // If we were close (≤3 min), assume we arrived
            val wasNearDestination = lastEta <= ARRIVING_SOON_THRESHOLD

            scope.launch {
                if (wasNearDestination) {
                    Log.d(TAG, "Navigation stopped near destination - sending arrived message")
                    // Create a fake ETA for the arrived message
                    val arrivedEta = ParsedEtaData(
                        etaMinutes = 0,
                        destination = null,
                        distanceText = null,
                        arrivalTimeText = null,
                        navigationApp = currentState.currentEta?.navigationApp ?: NavigationApp.GOOGLE_MAPS
                    )
                    sendMessageForType(session, arrivedEta, EtaMessageType.ARRIVED)
                } else {
                    Log.d(TAG, "Navigation stopped - sending cancelled message")
                    sendStoppedMessage(session)
                    stopSharing(sendFinalMessage = false)
                }
            }
        }
    }

    // ===== Auto-Share Logic =====

    /**
     * Check auto-share contacts when navigation starts.
     * If ETA meets minimum threshold, automatically start sharing with all enabled contacts.
     */
    private suspend fun checkAutoShareContacts(etaData: ParsedEtaData) {
        // Check if ETA sharing is enabled
        if (!settingsDataStore.etaSharingEnabled.first()) {
            Log.d(TAG, "Auto-share: ETA sharing disabled, skipping")
            return
        }

        // Check minimum ETA threshold
        val minimumEta = featurePreferences.autoShareMinimumEtaMinutes.first()
        if (etaData.etaMinutes < minimumEta) {
            Log.d(TAG, "Auto-share: ETA ${etaData.etaMinutes} min below threshold ($minimumEta min), skipping")
            return
        }

        // Get enabled auto-share contacts
        val contacts = autoShareContactRepository.getEnabled()
        if (contacts.isEmpty()) {
            Log.d(TAG, "Auto-share: No enabled contacts configured")
            return
        }

        Log.d(TAG, "Auto-share: Starting with ${contacts.size} contacts, ETA: ${etaData.etaMinutes} min")

        // Create sessions for all contacts
        activeAutoShareSessions = contacts.map { contact ->
            EtaSharingSession(
                recipientGuid = contact.chatGuid,
                recipientDisplayName = contact.displayName,
                lastEtaMinutes = etaData.etaMinutes,
                lastMessageType = EtaMessageType.INITIAL
            )
        }.toMutableList()

        // Update auto-share state
        val recipientNames = contacts.map { it.displayName }
        _autoShareState.value = AutoShareState.Active(recipientNames = recipientNames)

        // Send initial messages to all recipients
        for (session in activeAutoShareSessions) {
            val message = buildInitialMessage(etaData)
            Log.d(TAG, "Auto-share: Sending initial message to ${session.recipientDisplayName}")

            pendingMessageRepository.queueMessage(
                chatGuid = session.recipientGuid,
                text = message
            ).onSuccess {
                Log.d(TAG, "Auto-share: Initial message sent to ${session.recipientDisplayName}")
            }.onFailure { e ->
                Log.e(TAG, "Auto-share: Failed to send to ${session.recipientDisplayName}", e)
            }
        }

        Log.d(TAG, "Auto-share: Started sharing with ${recipientNames.joinToString()}")
    }

    /**
     * Process ETA update for all active auto-share sessions.
     */
    private suspend fun processAutoShareUpdate(etaData: ParsedEtaData) {
        if (activeAutoShareSessions.isEmpty()) return

        val updatedSessions = mutableListOf<EtaSharingSession>()

        for (session in activeAutoShareSessions) {
            val messageType = determineUpdateType(session, etaData)

            if (messageType != null) {
                val message = when (messageType) {
                    EtaMessageType.INITIAL -> buildInitialMessage(etaData)
                    EtaMessageType.CHANGE -> buildChangeMessage(etaData, session.lastEtaMinutes)
                    EtaMessageType.ARRIVING_SOON -> buildArrivingSoonMessage(etaData)
                    EtaMessageType.ARRIVED -> buildArrivedMessage()
                }

                Log.d(TAG, "Auto-share: Sending $messageType to ${session.recipientDisplayName}")

                pendingMessageRepository.queueMessage(
                    chatGuid = session.recipientGuid,
                    text = message
                )

                // Update session state
                val updatedSession = session.copy(
                    lastSentTime = System.currentTimeMillis(),
                    lastEtaMinutes = etaData.etaMinutes,
                    updateCount = session.updateCount + 1,
                    lastMessageType = messageType
                )
                updatedSessions.add(updatedSession)
            } else {
                updatedSessions.add(session)
            }
        }

        activeAutoShareSessions = updatedSessions
    }

    /**
     * Handle navigation stopped for auto-share sessions.
     */
    private suspend fun handleAutoShareNavigationStopped(lastEta: ParsedEtaData?) {
        if (activeAutoShareSessions.isEmpty()) return

        val wasNearDestination = activeAutoShareSessions.firstOrNull()?.let {
            it.lastEtaMinutes <= ARRIVING_SOON_THRESHOLD
        } ?: false

        for (session in activeAutoShareSessions) {
            val message = if (wasNearDestination) {
                buildArrivedMessage()
            } else {
                buildCancelledMessage()
            }

            Log.d(TAG, "Auto-share: Sending ${if (wasNearDestination) "arrived" else "cancelled"} to ${session.recipientDisplayName}")

            pendingMessageRepository.queueMessage(
                chatGuid = session.recipientGuid,
                text = message
            )
        }

        // Clear auto-share state
        activeAutoShareSessions.clear()
        _autoShareState.value = AutoShareState.Inactive

        Log.d(TAG, "Auto-share: Session ended")
    }

    /**
     * Manually stop auto-sharing (e.g., user dismisses notification).
     */
    fun stopAutoSharing(sendFinalMessage: Boolean = true) {
        if (activeAutoShareSessions.isEmpty()) return

        scope.launch {
            if (sendFinalMessage) {
                for (session in activeAutoShareSessions) {
                    pendingMessageRepository.queueMessage(
                        chatGuid = session.recipientGuid,
                        text = buildCancelledMessage()
                    )
                }
            }

            activeAutoShareSessions.clear()
            _autoShareState.value = AutoShareState.Inactive

            Log.d(TAG, "Auto-share: Manually stopped")
        }
    }

    /**
     * Send a message based on the determined type
     */
    private suspend fun sendMessageForType(
        session: EtaSharingSession,
        eta: ParsedEtaData,
        messageType: EtaMessageType
    ) {
        val message = when (messageType) {
            EtaMessageType.INITIAL -> buildInitialMessage(eta)
            EtaMessageType.CHANGE -> buildChangeMessage(eta, session.lastEtaMinutes)
            EtaMessageType.ARRIVING_SOON -> buildArrivingSoonMessage(eta)
            EtaMessageType.ARRIVED -> buildArrivedMessage()
        }

        Log.d(TAG, "Sending $messageType message: $message")

        pendingMessageRepository.queueMessage(
            chatGuid = session.recipientGuid,
            text = message
        ).onSuccess {
            // Update session state
            val updatedSession = session.copy(
                lastSentTime = System.currentTimeMillis(),
                lastEtaMinutes = eta.etaMinutes,
                updateCount = session.updateCount + 1,
                lastMessageType = messageType
            )
            _state.value = _state.value.copy(session = updatedSession)

            // Handle terminal state for ARRIVED
            if (messageType == EtaMessageType.ARRIVED) {
                enterTerminalState()
                stopSharing(sendFinalMessage = false)
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to send $messageType message", e)
        }
    }

    // ===== Message Builders =====

    private fun buildInitialMessage(eta: ParsedEtaData): String {
        return buildString {
            append("📍 On my way! ETA: ${formatEtaTime(eta.etaMinutes)}")
            eta.arrivalTimeText?.let { append(" (arriving ~$it)") }
        }
    }

    private fun buildChangeMessage(eta: ParsedEtaData, previousEta: Int): String {
        return buildString {
            append("📍 ETA Update: Now ${formatEtaTime(eta.etaMinutes)}")
            if (previousEta > 0) {
                append(" (was ${formatEtaTime(previousEta)})")
            }
        }
    }

    private fun buildArrivingSoonMessage(eta: ParsedEtaData): String {
        return "📍 Almost there! ~${eta.etaMinutes} min away"
    }

    private fun buildArrivedMessage(): String {
        return "📍 I've arrived!"
    }

    private fun buildCancelledMessage(): String {
        return "📍 Stopped sharing ETA"
    }

    /**
     * Format ETA minutes into a readable string
     */
    private fun formatEtaTime(minutes: Int): String {
        return when {
            minutes < 1 -> "arriving now"
            minutes == 1 -> "1 min"
            minutes < 60 -> "$minutes min"
            else -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins > 0) "${hours}hr ${mins}min" else "${hours}hr"
            }
        }
    }

    /**
     * Send message that sharing was stopped via WorkManager
     */
    private suspend fun sendStoppedMessage(session: EtaSharingSession) {
        val message = buildCancelledMessage()
        pendingMessageRepository.queueMessage(
            chatGuid = session.recipientGuid,
            text = message
        ).onFailure { e ->
            Log.e(TAG, "Failed to queue stopped message", e)
        }
    }

    /**
     * Check if ETA sharing is available (navigation is active)
     */
    fun getUnavailableReason(): EtaUnavailableReason? {
        return when {
            !_isNavigationActive.value -> EtaUnavailableReason.NoNavigationActive
            else -> null
        }
    }

    // ===== Debug/Testing Methods =====

    /**
     * Simulate a navigation notification for testing without driving.
     * Only works in debug builds.
     */
    fun simulateNavigation(etaMinutes: Int) {
        Log.d(TAG, "[DEBUG] Simulating navigation: $etaMinutes min")

        val fakeEta = ParsedEtaData(
            etaMinutes = etaMinutes,
            destination = null,
            distanceText = "${(etaMinutes * 0.8).toInt()} mi",
            arrivalTimeText = null,
            navigationApp = NavigationApp.GOOGLE_MAPS
        )

        onEtaUpdate(fakeEta)
    }

    /**
     * Simulate stopping navigation
     */
    fun simulateNavigationStop() {
        Log.d(TAG, "[DEBUG] Simulating navigation stop")
        onNavigationStopped()
    }

    /**
     * Reset terminal state (for testing)
     */
    fun debugResetTerminalState() {
        Log.d(TAG, "[DEBUG] Resetting terminal state")
        clearTerminalState()
    }
}

/**
 * State for auto-share tracking.
 */
sealed class AutoShareState {
    data object Inactive : AutoShareState()

    data class Active(
        val recipientNames: List<String>
    ) : AutoShareState()
}
