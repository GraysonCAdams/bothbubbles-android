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

        // Debouncing rules
        const val SIGNIFICANT_CHANGE_MINUTES = 5  // Send update if ETA changes by 5+ min
        const val PERIODIC_UPDATE_MS = 15 * 60 * 1000L  // Send update every 15 minutes
        const val ARRIVAL_THRESHOLD_MINUTES = 2  // Send "arriving" message when under 2 min

        // Terminal state protection
        const val TERMINAL_STATE_COOLDOWN_MS = 30 * 60 * 1000L  // 30 min before allowing new session
        const val ARRIVED_ETA_THRESHOLD = 1  // Consider "arrived" when ETA is 0-1 min
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
            lastEtaMinutes = initialEta?.etaMinutes ?: 0
        )

        _state.value = EtaState(
            isSharing = true,
            session = session,
            currentEta = initialEta,
            isNavigationActive = _isNavigationActive.value
        )

        // Send initial message
        initialEta?.let { eta ->
            sendEtaUpdate(session, eta, isInitial = true)
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

        // Check if we just arrived (ETA hit 0-1 min)
        if (etaData.etaMinutes <= ARRIVED_ETA_THRESHOLD && session.lastEtaMinutes > ARRIVED_ETA_THRESHOLD) {
            Log.d(TAG, "ETA reached arrival threshold - entering terminal state")
            enterTerminalState(etaData.destination)
            scope.launch {
                sendArrivedMessage(session)
            }
            stopSharing(sendFinalMessage = false)
            return
        }

        if (shouldSendUpdate(session, etaData.etaMinutes)) {
            sendEtaUpdate(session, etaData, isInitial = false)
        }
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
     */
    fun onNavigationStopped() {
        Log.d(TAG, "Navigation stopped")
        _isNavigationActive.value = false

        val currentState = _state.value
        _state.value = currentState.copy(isNavigationActive = false)

        // If we were sharing, send arrival message and stop
        if (currentState.isSharing && currentState.session != null) {
            scope.launch {
                sendArrivedMessage(currentState.session)
            }
            stopSharing(sendFinalMessage = false)
        }
    }

    /**
     * Check if we should send an update based on debouncing rules
     */
    private fun shouldSendUpdate(session: EtaSharingSession, newEtaMinutes: Int): Boolean {
        val timeSinceLastSend = System.currentTimeMillis() - session.lastSentTime
        val etaDelta = abs(newEtaMinutes - session.lastEtaMinutes)

        // Rule 1: Significant change in ETA
        if (etaDelta >= SIGNIFICANT_CHANGE_MINUTES) {
            Log.d(TAG, "Sending update: significant ETA change ($etaDelta min)")
            return true
        }

        // Rule 2: Periodic update
        if (timeSinceLastSend >= PERIODIC_UPDATE_MS) {
            Log.d(TAG, "Sending update: periodic (${timeSinceLastSend / 1000}s since last)")
            return true
        }

        // Rule 3: About to arrive
        if (newEtaMinutes <= ARRIVAL_THRESHOLD_MINUTES && session.lastEtaMinutes > ARRIVAL_THRESHOLD_MINUTES) {
            Log.d(TAG, "Sending update: arriving soon")
            return true
        }

        return false
    }

    /**
     * Send an ETA update message via WorkManager for background safety
     */
    private fun sendEtaUpdate(session: EtaSharingSession, eta: ParsedEtaData, isInitial: Boolean) {
        scope.launch {
            val message = buildEtaMessage(eta, isInitial)
            Log.d(TAG, "Queueing ETA update: $message")

            // Use PendingMessageRepository for WorkManager-backed delivery
            // This ensures messages are sent even if phone is locked/app backgrounded
            pendingMessageRepository.queueMessage(
                chatGuid = session.recipientGuid,
                text = message
            ).onSuccess {
                Log.d(TAG, "ETA message queued successfully: $it")

                // Update session with new send time
                val updatedSession = session.copy(
                    lastSentTime = System.currentTimeMillis(),
                    lastEtaMinutes = eta.etaMinutes,
                    updateCount = session.updateCount + 1,
                    destination = eta.destination ?: session.destination
                )

                _state.value = _state.value.copy(session = updatedSession)
            }.onFailure { e ->
                Log.e(TAG, "Failed to queue ETA update", e)
            }
        }
    }

    /**
     * Build the ETA message text
     */
    private fun buildEtaMessage(eta: ParsedEtaData, isInitial: Boolean): String {
        val prefix = if (isInitial) "📍 On my way!" else "📍 ETA Update"
        val etaText = formatEtaTime(eta.etaMinutes)
        val destText = eta.destination?.let { " to $it" } ?: ""

        return buildString {
            append(prefix)
            append("\n")
            append(etaText)
            append(destText)
            eta.arrivalTimeText?.let {
                append("\nArriving around $it")
            }
        }
    }

    /**
     * Format ETA minutes into a readable string
     */
    private fun formatEtaTime(minutes: Int): String {
        return when {
            minutes < 1 -> "Arriving now"
            minutes < 60 -> "$minutes min away"
            else -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins > 0) "${hours}hr ${mins}min away" else "${hours}hr away"
            }
        }
    }

    /**
     * Send message that we've arrived via WorkManager
     */
    private suspend fun sendArrivedMessage(session: EtaSharingSession) {
        val message = "📍 I've arrived${session.destination?.let { " at $it" } ?: ""}!"
        pendingMessageRepository.queueMessage(
            chatGuid = session.recipientGuid,
            text = message
        ).onFailure { e ->
            Log.e(TAG, "Failed to queue arrived message", e)
        }
    }

    /**
     * Send message that sharing was stopped via WorkManager
     */
    private suspend fun sendStoppedMessage(session: EtaSharingSession) {
        val message = "📍 Stopped sharing location"
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
