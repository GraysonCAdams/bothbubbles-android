package com.bothbubbles.ui.settings.eta

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
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
        settingsDataStore.etaChangeThreshold,
        _hasNotificationAccess,
        etaSharingManager.isNavigationActive,
        etaSharingManager.state,
        settingsDataStore.developerModeEnabled
    ) { values: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val etaState = values[4] as? com.bothbubbles.services.eta.EtaState
        EtaSharingSettingsUiState(
            enabled = values[0] as? Boolean ?: false,
            changeThresholdMinutes = values[1] as? Int ?: 5,
            hasNotificationAccess = values[2] as? Boolean ?: false,
            isNavigationActive = values[3] as? Boolean ?: false,
            isCurrentlySharing = etaState?.isSharing ?: false,
            currentEtaMinutes = etaState?.currentEta?.etaMinutes ?: 0,
            destination = etaState?.currentEta?.destination,
            isDeveloperMode = values[5] as? Boolean ?: false
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

    fun setChangeThreshold(minutes: Int) {
        viewModelScope.launch {
            settingsDataStore.setEtaChangeThreshold(minutes)
        }
    }

    fun refreshNotificationAccess() {
        Log.d("EtaSettings", "refreshNotificationAccess() called")
        _hasNotificationAccess.value = checkNotificationAccess()
    }

    private fun checkNotificationAccess(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        val hasAccess = enabledPackages.contains(context.packageName)
        Log.d("EtaSettings", "checkNotificationAccess: enabledPackages = $enabledPackages")
        Log.d("EtaSettings", "checkNotificationAccess: looking for '${context.packageName}', hasAccess = $hasAccess")
        return hasAccess
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
