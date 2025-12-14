package com.bothbubbles.ui.settings.eta

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.eta.EtaSharingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EtaSharingSettingsUiState(
    val enabled: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val updateIntervalMinutes: Int = 15,
    val changeThresholdMinutes: Int = 5,
    val isNavigationActive: Boolean = false,
    val isCurrentlySharing: Boolean = false,
    val currentEtaMinutes: Int = 0,
    val destination: String? = null,
    val isDeveloperMode: Boolean = false
)

@HiltViewModel
class EtaSharingSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val etaSharingManager: EtaSharingManager
) : ViewModel() {

    private val _hasNotificationAccess = MutableStateFlow(checkNotificationAccess())

    val uiState: StateFlow<EtaSharingSettingsUiState> = combine(
        settingsDataStore.etaSharingEnabled,
        settingsDataStore.etaUpdateInterval,
        settingsDataStore.etaChangeThreshold,
        _hasNotificationAccess,
        etaSharingManager.isNavigationActive,
        etaSharingManager.state,
        settingsDataStore.developerModeEnabled
    ) { values: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val etaState = values[5] as? com.bothbubbles.services.eta.EtaState
        EtaSharingSettingsUiState(
            enabled = values[0] as? Boolean ?: false,
            updateIntervalMinutes = values[1] as? Int ?: 15,
            changeThresholdMinutes = values[2] as? Int ?: 5,
            hasNotificationAccess = values[3] as? Boolean ?: false,
            isNavigationActive = values[4] as? Boolean ?: false,
            isCurrentlySharing = etaState?.isSharing ?: false,
            currentEtaMinutes = etaState?.currentEta?.etaMinutes ?: 0,
            destination = etaState?.currentEta?.destination,
            isDeveloperMode = values[6] as? Boolean ?: false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EtaSharingSettingsUiState()
    )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setEtaSharingEnabled(enabled)
        }
    }

    fun setUpdateInterval(minutes: Int) {
        viewModelScope.launch {
            settingsDataStore.setEtaUpdateInterval(minutes)
        }
    }

    fun setChangeThreshold(minutes: Int) {
        viewModelScope.launch {
            settingsDataStore.setEtaChangeThreshold(minutes)
        }
    }

    fun refreshNotificationAccess() {
        _hasNotificationAccess.value = checkNotificationAccess()
    }

    private fun checkNotificationAccess(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val componentName = ComponentName(
            context.packageName,
            "com.bothbubbles.services.eta.NavigationListenerService"
        ).flattenToString()

        return enabledListeners.contains(componentName)
    }

    fun getNotificationAccessSettingsIntent(): android.content.Intent {
        return android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    // ===== Debug/Testing Methods =====

    /**
     * Simulate starting navigation (for testing)
     */
    fun debugSimulateNavigation(etaMinutes: Int, destination: String) {
        etaSharingManager.simulateNavigation(etaMinutes, destination)
    }

    /**
     * Simulate updating ETA (for testing)
     */
    fun debugUpdateEta(etaMinutes: Int) {
        val currentDestination = uiState.value.destination ?: "Test Location"
        etaSharingManager.simulateNavigation(etaMinutes, currentDestination)
    }

    /**
     * Simulate stopping navigation (for testing)
     */
    fun debugStopNavigation() {
        etaSharingManager.simulateNavigationStop()
    }

    /**
     * Reset terminal state (for testing)
     */
    fun debugResetTerminalState() {
        etaSharingManager.debugResetTerminalState()
    }
}
