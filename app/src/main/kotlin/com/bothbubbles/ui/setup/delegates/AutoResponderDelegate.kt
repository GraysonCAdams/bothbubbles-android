package com.bothbubbles.ui.setup.delegates

import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.ui.setup.SetupUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Handles auto-responder setup configuration.
 */
class AutoResponderDelegate(
    private val settingsDataStore: SettingsDataStore
) {
    fun updateAutoResponderFilter(uiState: MutableStateFlow<SetupUiState>, filter: String) {
        uiState.value = uiState.value.copy(autoResponderFilter = filter)
    }

    fun enableAutoResponder(scope: CoroutineScope, uiState: MutableStateFlow<SetupUiState>) {
        scope.launch {
            settingsDataStore.setAutoResponderEnabled(true)
            settingsDataStore.setAutoResponderFilter(uiState.value.autoResponderFilter)
            uiState.value = uiState.value.copy(autoResponderEnabled = true)
        }
    }

    fun skipAutoResponder(scope: CoroutineScope, uiState: MutableStateFlow<SetupUiState>) {
        scope.launch {
            settingsDataStore.setAutoResponderEnabled(false)
            uiState.value = uiState.value.copy(autoResponderEnabled = false)
        }
    }
}
