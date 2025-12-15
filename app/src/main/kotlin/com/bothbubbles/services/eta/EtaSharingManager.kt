package com.bothbubbles.services.eta

import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
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
    private val settingsDataStore: SettingsDataStore
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
    private var lastKnownDestination: String? = null

    /**
     * Start sharing ETA with a recipient
     */
    fun startSharing(chatGuid: String, displayName: String, initialEta: ParsedEtaData?) {
        Log.d(TAG, "Starting ETA sharing with $displayName (chat: $chatGuid)")

        val session = EtaSharingSession(
            recipientGuid = chatGuid,
            recipientDisplayName = displayName,
            destination = initialEta?.destination,
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

        val session = currentState.session ?: return
        if (!currentState.isSharing) return

        // Terminal state check: If we already sent "arrived" for this destination,
        // don't send any more updates until destination changes or cooldown expires
        if (isInTerminalState(etaData)) {
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

        // Priority 2: Check for destination change
        if (eta.destination != null && session.destination != null &&
            !isSameDestination(eta.destination, session.destination)
        ) {
            Log.d(TAG, "Update type: DESTINATION_CHANGE (${session.destination} → ${eta.destination})")
            return EtaMessageType.DESTINATION_CHANGE
        }

        // Priority 3: Check for "arriving soon" (only send once)
        if (newEtaMinutes <= ARRIVING_SOON_THRESHOLD &&
            session.lastEtaMinutes > ARRIVING_SOON_THRESHOLD &&
            session.lastMessageType != EtaMessageType.ARRIVING_SOON
        ) {
            Log.d(TAG, "Update type: ARRIVING_SOON (ETA dropped to $newEtaMinutes min)")
            return EtaMessageType.ARRIVING_SOON
        }

        // Priority 4: Significant ETA change (user-configurable threshold)
        if (etaDelta >= changeThreshold) {
            Log.d(TAG, "Update type: CHANGE (delta: $etaDelta min, threshold: $changeThreshold)")
            return EtaMessageType.CHANGE
        }

        // No meaningful change
        return null
    }

    /**
     * Check if we're in terminal state (already sent arrived for this destination)
     */
    private fun isInTerminalState(etaData: ParsedEtaData): Boolean {
        if (arrivedSentTimestamp == 0L) return false

        val timeSinceArrived = System.currentTimeMillis() - arrivedSentTimestamp

        // Reset terminal state if:
        // 1. Cooldown expired (30 min) - likely a new trip
        if (timeSinceArrived > TERMINAL_STATE_COOLDOWN_MS) {
            Log.d(TAG, "Terminal state expired (cooldown)")
            clearTerminalState()
            return false
        }

        // 2. Destination changed - definitely a new trip
        // Use fuzzy matching to handle variations like "123 Main St" vs "123 Main Street"
        if (etaData.destination != null && !isSameDestination(etaData.destination, lastKnownDestination)) {
            Log.d(TAG, "Terminal state cleared (new destination: ${etaData.destination})")
            clearTerminalState()
            return false
        }

        return true
    }

    /**
     * Fuzzy match destinations to handle variations
     * e.g., "123 Main St" vs "123 Main Street", "Home" vs "home"
     */
    private fun isSameDestination(dest1: String?, dest2: String?): Boolean {
        if (dest1 == null && dest2 == null) return true
        if (dest1 == null || dest2 == null) return false

        // Normalize: lowercase, remove common abbreviation differences
        val norm1 = normalizeDestination(dest1)
        val norm2 = normalizeDestination(dest2)

        // Exact match after normalization
        if (norm1 == norm2) return true

        // Check if one contains the other (handles partial matches)
        if (norm1.contains(norm2) || norm2.contains(norm1)) return true

        // Check similarity ratio (Jaccard-like)
        val words1 = norm1.split(" ").toSet()
        val words2 = norm2.split(" ").toSet()
        val intersection = words1.intersect(words2)
        val union = words1.union(words2)

        if (union.isEmpty()) return false
        val similarity = intersection.size.toFloat() / union.size
        return similarity >= 0.6f  // 60% word overlap = same destination
    }

    private fun normalizeDestination(dest: String): String {
        return dest.lowercase()
            .replace("street", "st")
            .replace("avenue", "ave")
            .replace("boulevard", "blvd")
            .replace("drive", "dr")
            .replace("road", "rd")
            .replace("lane", "ln")
            .replace("court", "ct")
            .replace("place", "pl")
            .replace(Regex("[.,#]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun enterTerminalState(destination: String?) {
        arrivedSentTimestamp = System.currentTimeMillis()
        lastKnownDestination = destination
        Log.d(TAG, "Entered terminal state for destination: $destination")
    }

    private fun clearTerminalState() {
        arrivedSentTimestamp = 0
        lastKnownDestination = null
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

        // If we were sharing, determine if this was arrival or cancellation
        if (currentState.isSharing && currentState.session != null) {
            val session = currentState.session
            val lastEta = session.lastEtaMinutes

            // If we were close to destination (≤3 min), assume we arrived
            val wasNearDestination = lastEta <= ARRIVING_SOON_THRESHOLD

            scope.launch {
                if (wasNearDestination) {
                    Log.d(TAG, "Navigation stopped near destination - sending arrived message")
                    // Create a fake ETA for the arrived message
                    val arrivedEta = ParsedEtaData(
                        etaMinutes = 0,
                        destination = session.destination,
                        distanceText = null,
                        arrivalTimeText = null,
                        navigationApp = currentState.currentEta?.navigationApp ?: NavigationApp.GOOGLE_MAPS
                    )
                    sendMessageForType(session, arrivedEta, EtaMessageType.ARRIVED)
                } else {
                    Log.d(TAG, "Navigation stopped far from destination - sending cancelled message")
                    sendStoppedMessage(session)
                    stopSharing(sendFinalMessage = false)
                }
            }
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
            EtaMessageType.DESTINATION_CHANGE -> buildDestinationChangeMessage(eta)
            EtaMessageType.CHANGE -> buildChangeMessage(eta, session.lastEtaMinutes)
            EtaMessageType.ARRIVING_SOON -> buildArrivingSoonMessage(eta)
            EtaMessageType.ARRIVED -> buildArrivedMessage(eta.destination ?: session.destination)
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
                destination = eta.destination ?: session.destination,
                lastMessageType = messageType
            )
            _state.value = _state.value.copy(session = updatedSession)

            // Handle terminal state for ARRIVED
            if (messageType == EtaMessageType.ARRIVED) {
                enterTerminalState(eta.destination)
                stopSharing(sendFinalMessage = false)
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to send $messageType message", e)
        }
    }

    // ===== Message Builders =====

    private fun buildInitialMessage(eta: ParsedEtaData): String {
        val dest = eta.destination ?: "destination"
        return buildString {
            append("📍 On my way to $dest!")
            append("\nETA: ${formatEtaTime(eta.etaMinutes)}")
            eta.arrivalTimeText?.let { append(" (arriving ~$it)") }
        }
    }

    private fun buildDestinationChangeMessage(eta: ParsedEtaData): String {
        val dest = eta.destination ?: "new destination"
        return buildString {
            append("📍 Change of plans!")
            append("\nNow heading to $dest")
            append("\nETA: ${formatEtaTime(eta.etaMinutes)}")
            eta.arrivalTimeText?.let { append(" (arriving ~$it)") }
        }
    }

    private fun buildChangeMessage(eta: ParsedEtaData, previousEta: Int): String {
        val dest = eta.destination ?: "destination"
        return buildString {
            append("📍 ETA Update")
            append("\nNow ${formatEtaTime(eta.etaMinutes)} to $dest")
            if (previousEta > 0) {
                append(" (was ${formatEtaTime(previousEta)})")
            }
        }
    }

    private fun buildArrivingSoonMessage(eta: ParsedEtaData): String {
        val dest = eta.destination?.let { " at $it" } ?: ""
        return "📍 Almost there! Arriving$dest in ~${eta.etaMinutes} min"
    }

    private fun buildArrivedMessage(destination: String?): String {
        val dest = destination?.let { " at $it" } ?: ""
        return "📍 I've arrived$dest!"
    }

    private fun buildCancelledMessage(): String {
        return "📍 Stopped sharing location"
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
    fun simulateNavigation(etaMinutes: Int, destination: String = "Home") {
        Log.d(TAG, "[DEBUG] Simulating navigation: $etaMinutes min to $destination")

        val fakeEta = ParsedEtaData(
            etaMinutes = etaMinutes,
            destination = destination,
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
