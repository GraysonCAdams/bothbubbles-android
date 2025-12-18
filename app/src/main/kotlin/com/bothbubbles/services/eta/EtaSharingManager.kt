package com.bothbubbles.services.eta

import timber.log.Timber
import com.bothbubbles.data.local.prefs.FeaturePreferences
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.AutoShareContact
import com.bothbubbles.data.repository.AutoShareContactRepository
import com.bothbubbles.data.repository.PendingMessageSource
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
    private val pendingMessageSource: PendingMessageSource,
    private val settingsDataStore: SettingsDataStore,
    private val featurePreferences: FeaturePreferences,
    private val autoShareContactRepository: AutoShareContactRepository
) {
    companion object {
        // Thresholds
        const val ARRIVING_SOON_THRESHOLD = 3   // Send "arriving soon" at 3 min
        const val ARRIVED_THRESHOLD = 1         // Consider "arrived" at 0-1 min

        // Change notification timing (fixed, not configurable)
        const val CHANGE_DELTA_MINUTES = 5              // Arrival time must shift by ≥5 min
        const val CHANGE_COOLDOWN_MS = 10 * 60 * 1000L  // 10 min between CHANGE messages
        const val CHANGE_GRACE_PERIOD_MS = 5 * 60 * 1000L  // No changes within 5 min of session start

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

    // Track if arriving_soon was already sent this session (to prevent duplicates)
    // Reset only if ETA goes back up > RESET_THRESHOLD_MINUTES
    private var arrivingSoonSentForSession: Boolean = false
    private var arrivingSoonSentAtEta: Int = 0  // Track what ETA we were at when we sent it

    // Auto-share state observable
    private val _autoShareState = MutableStateFlow<AutoShareState>(AutoShareState.Inactive)
    val autoShareState: StateFlow<AutoShareState> = _autoShareState.asStateFlow()

    /**
     * Start sharing ETA with a recipient
     */
    fun startSharing(chatGuid: String, displayName: String, initialEta: ParsedEtaData?) {
        Timber.d("Starting ETA sharing with $displayName (chat: $chatGuid)")

        // Reset session tracking for new session
        arrivingSoonSentForSession = false
        arrivingSoonSentAtEta = 0
        clearTerminalState()

        val session = EtaSharingSession(
            recipientGuid = chatGuid,
            recipientDisplayName = displayName,
            lastEtaMinutes = initialEta?.etaMinutes ?: 0,
            lastArrivalTimeMillis = initialEta?.arrivalTimeMillis,
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
     * Stop sharing ETA.
     * No "stopped sharing" message is sent - it's considered noisy.
     */
    fun stopSharing() {
        Timber.d("Stopping ETA sharing")

        // Reset session tracking
        arrivingSoonSentForSession = false
        arrivingSoonSentAtEta = 0

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
            Timber.d("Ignoring update - in terminal state (arrived already sent)")
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
     *
     * Message flow: INITIAL → (CHANGE)* → ARRIVING_SOON → ARRIVED
     * Once ARRIVING_SOON is sent, only ARRIVED can follow (no more CHANGE messages).
     *
     * Change detection uses arrival time (absolute clock time), not travel time remaining.
     */
    private suspend fun determineUpdateType(
        session: EtaSharingSession,
        eta: ParsedEtaData
    ): EtaMessageType? {
        val newEtaMinutes = eta.etaMinutes
        val now = System.currentTimeMillis()

        // Priority 1: Check for arrival (terminal state - only send once EVER per session)
        if (newEtaMinutes <= ARRIVED_THRESHOLD && session.lastMessageType != EtaMessageType.ARRIVED) {
            Timber.d("Update type: ARRIVED (ETA: $newEtaMinutes min)")
            return EtaMessageType.ARRIVED
        }

        // Once arriving_soon is sent, only ARRIVED can follow - no more updates
        if (arrivingSoonSentForSession) {
            Timber.d("Waiting for arrival (arriving_soon already sent)")
            return null
        }

        // Priority 2: Check for "arriving soon" (only send once per session)
        if (newEtaMinutes <= ARRIVING_SOON_THRESHOLD &&
            session.lastMessageType != EtaMessageType.ARRIVED  // Don't send if already arrived
        ) {
            Timber.d("Update type: ARRIVING_SOON (ETA dropped to $newEtaMinutes min)")
            arrivingSoonSentForSession = true
            arrivingSoonSentAtEta = newEtaMinutes
            return EtaMessageType.ARRIVING_SOON
        }

        // Priority 3: ETA change notification (based on arrival time, not travel time)
        // Check if change notifications are enabled
        val changeNotificationsEnabled = featurePreferences.etaChangeNotificationsEnabled.first()
        if (!changeNotificationsEnabled) {
            Timber.d("ETA change notifications disabled")
            return null
        }

        // Check grace period: no changes within 5 min of session start
        val timeSinceStart = now - session.startedAt
        if (timeSinceStart < CHANGE_GRACE_PERIOD_MS) {
            Timber.d("In grace period (${timeSinceStart / 1000}s since start)")
            return null
        }

        // Check cooldown: 10 min since last CHANGE message
        if (session.lastChangeMessageTime > 0) {
            val timeSinceLastChange = now - session.lastChangeMessageTime
            if (timeSinceLastChange < CHANGE_COOLDOWN_MS) {
                Timber.d("In cooldown (${timeSinceLastChange / 1000}s since last change)")
                return null
            }
        }

        // Compare arrival times (not travel time remaining)
        val newArrivalMillis = eta.arrivalTimeMillis
        val lastArrivalMillis = session.lastArrivalTimeMillis

        if (newArrivalMillis != null && lastArrivalMillis != null) {
            val arrivalTimeDeltaMs = abs(newArrivalMillis - lastArrivalMillis)
            val arrivalTimeDeltaMinutes = arrivalTimeDeltaMs / 60_000

            if (arrivalTimeDeltaMinutes >= CHANGE_DELTA_MINUTES) {
                Timber.d("Update type: CHANGE (arrival time shifted by $arrivalTimeDeltaMinutes min)")
                return EtaMessageType.CHANGE
            }
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
            Timber.d("Terminal state expired (cooldown)")
            clearTerminalState()
            return false
        }

        return true
    }

    private fun enterTerminalState() {
        arrivedSentTimestamp = System.currentTimeMillis()
        Timber.d("Entered terminal state")
    }

    private fun clearTerminalState() {
        arrivedSentTimestamp = 0
    }

    /**
     * Handle navigation stopped (notification removed)
     *
     * Logic:
     * - If we were at destination (≤1 min) AND haven't already sent arrived, send arrived
     * - If we already sent arrived, do nothing (session is done)
     * - Otherwise, assume trip was cancelled
     */
    fun onNavigationStopped() {
        Timber.d("Navigation stopped")
        _isNavigationActive.value = false

        val currentState = _state.value
        _state.value = currentState.copy(isNavigationActive = false)

        // Reset session tracking for next navigation
        autoShareCheckedForSession = false
        arrivingSoonSentForSession = false
        arrivingSoonSentAtEta = 0

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

            // If we already sent ARRIVED, session is complete
            if (session.lastMessageType == EtaMessageType.ARRIVED) {
                Timber.d("Navigation stopped - ARRIVED already sent, ending session")
                stopSharing()
                return
            }

            // Only send "arrived" if we were at ≤1 min (ARRIVED_THRESHOLD)
            val wasAtDestination = lastEta <= ARRIVED_THRESHOLD

            if (wasAtDestination) {
                scope.launch {
                    Timber.d("Navigation stopped at destination ($lastEta min) - sending arrived message")
                    val arrivedEta = ParsedEtaData(
                        etaMinutes = 0,
                        destination = null,
                        distanceText = null,
                        arrivalTimeText = null,
                        navigationApp = currentState.currentEta?.navigationApp ?: NavigationApp.GOOGLE_MAPS
                    )
                    sendMessageForType(session, arrivedEta, EtaMessageType.ARRIVED)
                }
            } else {
                // Navigation cancelled mid-trip - just end silently
                Timber.d("Navigation stopped at $lastEta min - ending session silently")
                stopSharing()
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
            Timber.d("Auto-share: ETA sharing disabled, skipping")
            return
        }

        // Check minimum ETA threshold
        val minimumEta = featurePreferences.autoShareMinimumEtaMinutes.first()
        if (etaData.etaMinutes < minimumEta) {
            Timber.d("Auto-share: ETA ${etaData.etaMinutes} min below threshold ($minimumEta min), skipping")
            return
        }

        // Get enabled auto-share contacts
        val contacts = autoShareContactRepository.getEnabled()
        if (contacts.isEmpty()) {
            Timber.d("Auto-share: No enabled contacts configured")
            return
        }

        Timber.d("Auto-share: Starting with ${contacts.size} contacts, ETA: ${etaData.etaMinutes} min")

        // Reset session tracking for new auto-share session
        arrivingSoonSentForSession = false
        arrivingSoonSentAtEta = 0
        clearTerminalState()

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
            Timber.d("Auto-share: Sending initial message to ${session.recipientDisplayName}")

            pendingMessageSource.queueMessage(
                chatGuid = session.recipientGuid,
                text = message
            ).onSuccess {
                Timber.d("Auto-share: Initial message sent to ${session.recipientDisplayName}")
            }.onFailure { e ->
                Timber.e(e, "Auto-share: Failed to send to ${session.recipientDisplayName}")
            }
        }

        Timber.d("Auto-share: Started sharing with ${recipientNames.joinToString()}")
    }

    /**
     * Process ETA update for all active auto-share sessions.
     */
    private suspend fun processAutoShareUpdate(etaData: ParsedEtaData) {
        if (activeAutoShareSessions.isEmpty()) return

        val updatedSessions = mutableListOf<EtaSharingSession>()
        var allArrived = true

        for (session in activeAutoShareSessions) {
            // Skip sessions that have already sent ARRIVED - they're done
            if (session.lastMessageType == EtaMessageType.ARRIVED) {
                Timber.d("Auto-share: ${session.recipientDisplayName} already arrived, skipping")
                continue
            }

            val messageType = determineUpdateType(session, etaData)

            if (messageType != null) {
                val message = when (messageType) {
                    EtaMessageType.INITIAL -> buildInitialMessage(etaData)
                    EtaMessageType.CHANGE -> buildChangeMessage(etaData, session.lastEtaMinutes)
                    EtaMessageType.ARRIVING_SOON -> buildArrivingSoonMessage(etaData)
                    EtaMessageType.ARRIVED -> buildArrivedMessage()
                }

                Timber.d("Auto-share: Sending $messageType to ${session.recipientDisplayName}")

                pendingMessageSource.queueMessage(
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

                // If we sent ARRIVED, don't add to updated sessions - session is complete
                if (messageType == EtaMessageType.ARRIVED) {
                    Timber.d("Auto-share: ${session.recipientDisplayName} arrived - session complete")
                } else {
                    updatedSessions.add(updatedSession)
                    allArrived = false
                }
            } else {
                updatedSessions.add(session)
                allArrived = false
            }
        }

        activeAutoShareSessions = updatedSessions

        // If all sessions are complete (all arrived), clear auto-share state
        if (allArrived && updatedSessions.isEmpty()) {
            Timber.d("Auto-share: All recipients arrived - ending auto-share")
            _autoShareState.value = AutoShareState.Inactive
        }
    }

    /**
     * Handle navigation stopped for auto-share sessions.
     * Only sends "arrived" if at destination - no "cancelled" messages (too noisy).
     */
    private suspend fun handleAutoShareNavigationStopped(lastEta: ParsedEtaData?) {
        if (activeAutoShareSessions.isEmpty()) return

        for (session in activeAutoShareSessions) {
            // If we already sent ARRIVED to this recipient, skip - session is complete
            if (session.lastMessageType == EtaMessageType.ARRIVED) {
                Timber.d("Auto-share: ${session.recipientDisplayName} - ARRIVED already sent, skipping")
                continue
            }

            // Only send arrived if we were at ≤1 min (ARRIVED_THRESHOLD)
            val wasAtDestination = session.lastEtaMinutes <= ARRIVED_THRESHOLD

            if (wasAtDestination) {
                Timber.d("Auto-share: Sending arrived to ${session.recipientDisplayName}")
                pendingMessageSource.queueMessage(
                    chatGuid = session.recipientGuid,
                    text = buildArrivedMessage()
                )
            } else {
                // Navigation cancelled mid-trip - end silently
                Timber.d("Auto-share: ${session.recipientDisplayName} - ending silently (was at ${session.lastEtaMinutes} min)")
            }
        }

        // Clear auto-share state
        activeAutoShareSessions.clear()
        _autoShareState.value = AutoShareState.Inactive

        Timber.d("Auto-share: Session ended")
    }

    /**
     * Manually stop auto-sharing (e.g., user dismisses notification).
     * No "stopped sharing" message is sent - it's considered noisy.
     */
    fun stopAutoSharing() {
        if (activeAutoShareSessions.isEmpty()) return

        activeAutoShareSessions.clear()
        _autoShareState.value = AutoShareState.Inactive

        Timber.d("Auto-share: Manually stopped")
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

        Timber.d("Sending $messageType message: $message")

        pendingMessageSource.queueMessage(
            chatGuid = session.recipientGuid,
            text = message
        ).onSuccess {
            val now = System.currentTimeMillis()

            // Update session state
            val updatedSession = session.copy(
                lastSentTime = now,
                lastEtaMinutes = eta.etaMinutes,
                lastArrivalTimeMillis = eta.arrivalTimeMillis,
                lastChangeMessageTime = if (messageType == EtaMessageType.CHANGE) now else session.lastChangeMessageTime,
                updateCount = session.updateCount + 1,
                lastMessageType = messageType
            )
            _state.value = _state.value.copy(session = updatedSession)

            // Handle terminal state for ARRIVED
            if (messageType == EtaMessageType.ARRIVED) {
                enterTerminalState()
                stopSharing()
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to send $messageType message")
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
        Timber.d("[DEBUG] Simulating navigation: $etaMinutes min")

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
        Timber.d("[DEBUG] Simulating navigation stop")
        onNavigationStopped()
    }

    /**
     * Reset terminal state (for testing)
     */
    fun debugResetTerminalState() {
        Timber.d("[DEBUG] Resetting terminal state")
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
