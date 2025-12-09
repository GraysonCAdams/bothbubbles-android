package com.bothbubbles.ui.settings.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.developer.ConnectionMode
import com.bothbubbles.services.developer.ConnectionModeManager
import com.bothbubbles.services.developer.DeveloperEvent
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.fcm.FcmTokenManager
import com.bothbubbles.services.fcm.FcmTokenState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeveloperEventLogViewModel @Inject constructor(
    private val developerEventLog: DeveloperEventLog,
    private val connectionModeManager: ConnectionModeManager,
    private val settingsDataStore: SettingsDataStore,
    private val fcmTokenManager: FcmTokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeveloperEventLogUiState())
    val uiState: StateFlow<DeveloperEventLogUiState> = _uiState.asStateFlow()

    init {
        observeEvents()
        observeConnectionMode()
        observeDeveloperMode()
        observeFcmTokenState()
    }

    private fun observeEvents() {
        viewModelScope.launch {
            developerEventLog.events.collect { events ->
                _uiState.update { it.copy(events = events) }
            }
        }
    }

    private fun observeConnectionMode() {
        viewModelScope.launch {
            combine(
                connectionModeManager.currentMode,
                connectionModeManager.isAppInForeground
            ) { mode, foreground ->
                Pair(mode, foreground)
            }.collect { (mode, foreground) ->
                _uiState.update {
                    it.copy(
                        connectionMode = mode,
                        isAppInForeground = foreground
                    )
                }
            }
        }
    }

    private fun observeDeveloperMode() {
        viewModelScope.launch {
            settingsDataStore.developerModeEnabled.collect { enabled ->
                _uiState.update { it.copy(developerModeEnabled = enabled) }
            }
        }
    }

    private fun observeFcmTokenState() {
        viewModelScope.launch {
            fcmTokenManager.tokenState.collect { state ->
                _uiState.update {
                    it.copy(
                        fcmTokenState = state,
                        fcmToken = when (state) {
                            is FcmTokenState.Available -> state.token
                            is FcmTokenState.Registered -> state.token
                            else -> null
                        }
                    )
                }
            }
        }
    }

    fun clearEvents() {
        developerEventLog.clear()
    }

    fun toggleDeveloperMode() {
        viewModelScope.launch {
            val currentValue = _uiState.value.developerModeEnabled
            settingsDataStore.setDeveloperModeEnabled(!currentValue)
        }
    }
}

data class DeveloperEventLogUiState(
    val events: List<DeveloperEvent> = emptyList(),
    val connectionMode: ConnectionMode = ConnectionMode.DISCONNECTED,
    val isAppInForeground: Boolean = true,
    val developerModeEnabled: Boolean = true,
    val fcmTokenState: FcmTokenState = FcmTokenState.Unknown,
    val fcmToken: String? = null
)
