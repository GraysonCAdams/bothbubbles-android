package com.bothbubbles.ui.chat.delegates

import android.content.Context
import android.content.Intent
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.eta.DestinationFetchState
import com.bothbubbles.services.eta.DrivingStateTracker
import com.bothbubbles.services.eta.EtaSharingManager
import com.bothbubbles.services.eta.NavigationApp
import com.bothbubbles.util.PermissionStateMonitor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Delegate for handling ETA sharing functionality in ChatViewModel.
 * Manages the state and actions for sharing arrival times during navigation.
 *
 * Uses AssistedInject to receive runtime parameters (scope) at construction time,
 * eliminating the need for a separate initialize() call.
 */
class ChatEtaSharingDelegate @AssistedInject constructor(
    @ApplicationContext private val context: Context,
    private val etaSharingManager: EtaSharingManager,
    private val settingsDataStore: SettingsDataStore,
    private val drivingStateTracker: DrivingStateTracker,
    private val permissionStateMonitor: PermissionStateMonitor,
    @Assisted private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "ChatEtaSharingDelegate"
        private const val DEBOUNCE_MS = 500L
        private const val COUNTDOWN_SECONDS = 5
    }

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ChatEtaSharingDelegate
    }

    /**
     * Combined state for ETA sharing availability and status
     */
    data class EtaSharingUiState(
        val isEnabled: Boolean = false,
        val isNavigationActive: Boolean = false,
        val isCurrentlySharing: Boolean = false,
        val isBannerDismissed: Boolean = false,
        val currentEtaMinutes: Int = 0,
        val currentDestination: String? = null,
        val hasAccessibilityAccess: Boolean = false,
        val currentNavigationApp: NavigationApp? = null
    )

    /**
     * UI state for destination fetch dialogs
     */
    sealed class DestinationFetchUiState {
        data object Idle : DestinationFetchUiState()
        data object FetchingDestination : DestinationFetchUiState()
        data class ShowingDrivingWarning(val countdownSeconds: Int) : DestinationFetchUiState()
        data object ShowingDestinationInput : DestinationFetchUiState()
    }

    // Banner dismiss state - resets when navigation restarts
    private val _bannerDismissed = MutableStateFlow(false)

    // Countdown timer state for driving warning dialog
    private val _countdownSeconds = MutableStateFlow(COUNTDOWN_SECONDS)
    private var countdownJob: Job? = null

    // Debounce tracking
    private var lastShareTapTime = 0L

    // Track the latest ETA message GUID for showing "Stop Sharing" link
    private val _latestEtaMessageGuid = MutableStateFlow<String?>(null)
    val latestEtaMessageGuid: StateFlow<String?> = _latestEtaMessageGuid.asStateFlow()

    // Previous navigation state for detecting navigation restart
    private var wasNavigationActive = false

    // Check accessibility access (cached, refreshed on navigation restart)
    private val _hasAccessibilityAccess = MutableStateFlow(permissionStateMonitor.hasAccessibilityServiceEnabled())

    val etaSharingState: StateFlow<EtaSharingUiState> = combine(
        settingsDataStore.etaSharingEnabled,
        etaSharingManager.isNavigationActive,
        etaSharingManager.state,
        etaSharingManager.detectedDestination,
        _bannerDismissed,
        _hasAccessibilityAccess
    ) { values ->
        val enabled = values[0] as? Boolean ?: false
        val navActive = values[1] as? Boolean ?: false
        @Suppress("UNCHECKED_CAST")
        val state = values[2] as? com.bothbubbles.services.eta.EtaState
        @Suppress("UNCHECKED_CAST")
        val detectedDestination = values[3] as? com.bothbubbles.services.eta.ParsedDestinationData
        val dismissed = values[4] as? Boolean ?: false
        val hasAccessibility = values[5] as? Boolean ?: false

        // Reset dismiss state and refresh accessibility when navigation restarts
        if (navActive && !wasNavigationActive) {
            scope.launch {
                _bannerDismissed.value = false
                _hasAccessibilityAccess.value = permissionStateMonitor.hasAccessibilityServiceEnabled()
            }
        }
        wasNavigationActive = navActive

        // Get destination from accessibility detection or ETA data
        val destination = detectedDestination?.destination ?: state?.currentEta?.destination

        EtaSharingUiState(
            isEnabled = enabled,
            isNavigationActive = navActive,
            isCurrentlySharing = state?.isSharing ?: false,
            isBannerDismissed = dismissed,
            currentEtaMinutes = state?.currentEta?.etaMinutes ?: 0,
            currentDestination = destination,
            hasAccessibilityAccess = hasAccessibility,
            currentNavigationApp = state?.currentEta?.navigationApp
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EtaSharingUiState()
    )

    /**
     * UI state for destination fetch dialogs.
     * Maps from EtaSharingManager's DestinationFetchState to UI-specific state.
     */
    val destinationFetchUiState: StateFlow<DestinationFetchUiState> = combine(
        etaSharingManager.destinationFetchState,
        _countdownSeconds
    ) { fetchState, countdown ->
        when (fetchState) {
            is DestinationFetchState.Idle -> DestinationFetchUiState.Idle
            is DestinationFetchState.FetchingDestination -> DestinationFetchUiState.FetchingDestination
            is DestinationFetchState.DestinationReady -> DestinationFetchUiState.Idle
            is DestinationFetchState.FetchFailed -> {
                if (fetchState.isActivelyDriving) {
                    DestinationFetchUiState.ShowingDrivingWarning(countdown)
                } else {
                    DestinationFetchUiState.ShowingDestinationInput
                }
            }
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DestinationFetchUiState.Idle
    )

    /**
     * Start sharing ETA with the current chat recipient
     */
    fun startSharingEta(chatGuid: String, displayName: String) {
        val currentEta = etaSharingManager.state.value.currentEta
        etaSharingManager.startSharing(chatGuid, displayName, currentEta)
    }

    /**
     * Stop sharing ETA
     */
    fun stopSharingEta() {
        etaSharingManager.stopSharing()
        _latestEtaMessageGuid.value = null
    }

    /**
     * Dismiss the banner for this navigation session.
     * Will reappear when navigation stops and starts again.
     */
    fun dismissBanner() {
        _bannerDismissed.value = true
    }

    /**
     * Update the latest ETA message GUID (for showing "Stop Sharing" link)
     */
    fun setLatestEtaMessageGuid(guid: String?) {
        _latestEtaMessageGuid.value = guid
    }

    /**
     * Check if ETA sharing can be started
     */
    fun canStartSharing(): Boolean {
        val state = etaSharingState.value
        return state.isEnabled && state.isNavigationActive && !state.isCurrentlySharing
    }

    // ===== Accessibility-Based Destination Scraping Flow =====

    /**
     * Start sharing ETA with accessibility-based destination scraping.
     * If accessibility is enabled, opens the nav app to scrape destination first.
     *
     * @param chatGuid The chat to share ETA with
     * @param displayName Display name of the recipient
     * @param openNavApp Callback to open the navigation app (returns true if launched)
     */
    fun startSharingWithAccessibilityScrape(
        chatGuid: String,
        displayName: String,
        openNavApp: (NavigationApp) -> Boolean
    ) {
        // Debounce rapid taps
        val now = System.currentTimeMillis()
        if (now - lastShareTapTime < DEBOUNCE_MS) {
            Timber.d("$TAG: Debouncing rapid tap")
            return
        }
        lastShareTapTime = now

        val state = etaSharingState.value

        // If accessibility is not enabled, use standard flow
        if (!state.hasAccessibilityAccess) {
            Timber.d("$TAG: Accessibility not enabled, using standard flow")
            startSharingEta(chatGuid, displayName)
            return
        }

        // Request destination fetch
        val isActivelyDriving = drivingStateTracker.isActivelyDriving.value
        val navApp = etaSharingManager.requestDestinationFetch(chatGuid, displayName, isActivelyDriving)

        if (navApp != null) {
            // Launch the navigation app
            val launched = openNavApp(navApp)
            if (!launched) {
                Timber.w("$TAG: Failed to launch nav app, using standard flow")
                etaSharingManager.cancelDestinationFetch()
                startSharingEta(chatGuid, displayName)
            }
        }
        // If navApp is null, we already had a destination and sharing started immediately
    }

    /**
     * Create an intent to launch the navigation app.
     */
    fun createNavAppIntent(navApp: NavigationApp): Intent? {
        return context.packageManager.getLaunchIntentForPackage(navApp.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
    }

    /**
     * Called when user returns to BothBubbles from the navigation app.
     * Should be called from the Activity's onResume or similar lifecycle callback.
     */
    fun onReturnedFromNavApp() {
        val fetchState = etaSharingManager.destinationFetchState.value
        if (fetchState !is DestinationFetchState.FetchingDestination) {
            return
        }

        Timber.d("$TAG: Returned from nav app")
        val isActivelyDriving = drivingStateTracker.isActivelyDriving.value
        etaSharingManager.onReturnedFromNavApp(isActivelyDriving)

        // If we entered FetchFailed with isActivelyDriving, start the countdown
        scope.launch {
            // Small delay to let state update
            delay(50)
            val newState = etaSharingManager.destinationFetchState.value
            if (newState is DestinationFetchState.FetchFailed && newState.isActivelyDriving) {
                startCountdown()
            }
        }
    }

    /**
     * Start the 5-second countdown for auto-accepting without destination.
     */
    private fun startCountdown() {
        countdownJob?.cancel()
        _countdownSeconds.value = COUNTDOWN_SECONDS

        countdownJob = scope.launch {
            for (i in COUNTDOWN_SECONDS downTo 1) {
                _countdownSeconds.value = i
                delay(1000L)
            }
            _countdownSeconds.value = 0
            // Auto-accept when countdown reaches 0
            etaSharingManager.acceptShareWithoutDestination()
        }
    }

    /**
     * Cancel the countdown and the fetch flow.
     */
    fun cancelCountdownAndFetch() {
        Timber.d("$TAG: Cancelling countdown and fetch")
        countdownJob?.cancel()
        countdownJob = null
        _countdownSeconds.value = COUNTDOWN_SECONDS
        etaSharingManager.cancelDestinationFetch()
    }

    /**
     * Accept sharing without destination (from countdown or manual acceptance).
     */
    fun acceptShareWithoutDestination() {
        Timber.d("$TAG: Accepting share without destination")
        countdownJob?.cancel()
        countdownJob = null
        etaSharingManager.acceptShareWithoutDestination()
    }

    /**
     * Share with a manually entered destination.
     */
    fun shareWithManualDestination(destination: String?) {
        Timber.d("$TAG: Sharing with manual destination: $destination")
        etaSharingManager.shareWithManualDestination(destination)
    }
}
