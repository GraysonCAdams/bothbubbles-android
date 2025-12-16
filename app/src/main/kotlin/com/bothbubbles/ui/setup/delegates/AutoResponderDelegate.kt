package com.bothbubbles.ui.setup.delegates

import com.bothbubbles.data.local.prefs.SettingsDataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Handles auto-responder setup configuration.
 *
 * Phase 9: Uses AssistedInject to receive CoroutineScope at construction.
 * Exposes StateFlow<AutoResponderState> instead of mutating external state.
 */
class AutoResponderDelegate @AssistedInject constructor(
    private val settingsDataStore: SettingsDataStore,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): AutoResponderDelegate
    }

    private val _state = MutableStateFlow(AutoResponderState())
    val state: StateFlow<AutoResponderState> = _state.asStateFlow()

    fun updateAutoResponderFilter(filter: String) {
        _state.update { it.copy(autoResponderFilter = filter) }
    }

    fun enableAutoResponder() {
        scope.launch {
            settingsDataStore.setAutoResponderEnabled(true)
            settingsDataStore.setAutoResponderFilter(_state.value.autoResponderFilter)
            _state.update { it.copy(autoResponderEnabled = true) }
        }
    }

    fun skipAutoResponder() {
        scope.launch {
            settingsDataStore.setAutoResponderEnabled(false)
            _state.update { it.copy(autoResponderEnabled = false) }
        }
    }
}
