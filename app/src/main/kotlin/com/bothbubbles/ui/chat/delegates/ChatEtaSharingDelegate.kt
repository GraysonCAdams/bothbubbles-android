package com.bothbubbles.ui.chat.delegates

import android.content.Context
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.eta.EtaSharingManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    @Assisted private val scope: CoroutineScope
) {

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
        val currentDestination: String? = null
    )

    // Banner dismiss state - resets when navigation restarts
    private val _bannerDismissed = MutableStateFlow(false)

    // Track the latest ETA message GUID for showing "Stop Sharing" link
    private val _latestEtaMessageGuid = MutableStateFlow<String?>(null)
    val latestEtaMessageGuid: StateFlow<String?> = _latestEtaMessageGuid.asStateFlow()

    // Previous navigation state for detecting navigation restart
    private var wasNavigationActive = false

    val etaSharingState: StateFlow<EtaSharingUiState> = combine(
        settingsDataStore.etaSharingEnabled,
        etaSharingManager.isNavigationActive,
        etaSharingManager.state,
        etaSharingManager.detectedDestination,
        _bannerDismissed
    ) { enabled, navActive, state, detectedDestination, dismissed ->
        // Reset dismiss state when navigation restarts (was inactive, now active)
        if (navActive && !wasNavigationActive) {
            // Navigation just started - reset dismiss state
            scope.launch {
                _bannerDismissed.value = false
            }
        }
        wasNavigationActive = navActive

        // Get destination from accessibility detection or ETA data
        val destination = detectedDestination?.destination ?: state.currentEta?.destination

        EtaSharingUiState(
            isEnabled = enabled,
            isNavigationActive = navActive,
            isCurrentlySharing = state.isSharing,
            isBannerDismissed = dismissed,
            currentEtaMinutes = state.currentEta?.etaMinutes ?: 0,
            currentDestination = destination
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EtaSharingUiState()
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
}
