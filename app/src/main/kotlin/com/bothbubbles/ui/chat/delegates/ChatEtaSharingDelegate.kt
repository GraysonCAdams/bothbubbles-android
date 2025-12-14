package com.bothbubbles.ui.chat.delegates

import android.content.Context
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.eta.EtaSharingManager
import com.bothbubbles.services.eta.EtaState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Delegate for handling ETA sharing functionality in ChatViewModel.
 * Manages the state and actions for sharing arrival times during navigation.
 */
class ChatEtaSharingDelegate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val etaSharingManager: EtaSharingManager,
    private val settingsDataStore: SettingsDataStore
) {
    private lateinit var scope: CoroutineScope

    /**
     * Combined state for ETA sharing availability and status
     */
    data class EtaSharingUiState(
        val isEnabled: Boolean = false,
        val isNavigationActive: Boolean = false,
        val isCurrentlySharing: Boolean = false,
        val currentEtaMinutes: Int = 0,
        val destination: String? = null
    )

    lateinit var etaSharingState: StateFlow<EtaSharingUiState>
        private set

    /**
     * Initialize the delegate with the ViewModel's coroutine scope
     */
    fun initialize(viewModelScope: CoroutineScope) {
        scope = viewModelScope

        etaSharingState = combine(
            settingsDataStore.etaSharingEnabled,
            etaSharingManager.isNavigationActive,
            etaSharingManager.state
        ) { enabled, navActive, state ->
            EtaSharingUiState(
                isEnabled = enabled,
                isNavigationActive = navActive,
                isCurrentlySharing = state.isSharing,
                currentEtaMinutes = state.currentEta?.etaMinutes ?: 0,
                destination = state.currentEta?.destination ?: state.session?.destination
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EtaSharingUiState()
        )
    }

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
        etaSharingManager.stopSharing(sendFinalMessage = true)
    }

    /**
     * Check if ETA sharing can be started
     */
    fun canStartSharing(): Boolean {
        val state = etaSharingState.value
        return state.isEnabled && state.isNavigationActive && !state.isCurrentlySharing
    }
}
