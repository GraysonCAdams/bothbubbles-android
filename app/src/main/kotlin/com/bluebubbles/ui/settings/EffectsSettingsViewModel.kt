package com.bluebubbles.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.prefs.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EffectsSettingsUiState(
    val effectsEnabled: Boolean = true,
    val autoPlayEffects: Boolean = true,
    val replayEffectsOnScroll: Boolean = false,
    val reduceMotion: Boolean = false,
    val disableOnLowBattery: Boolean = true,
    val lowBatteryThreshold: Int = 20
)

@HiltViewModel
class EffectsSettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val uiState: StateFlow<EffectsSettingsUiState> = combine(
        settingsDataStore.effectsEnabled,
        settingsDataStore.autoPlayEffects,
        settingsDataStore.replayEffectsOnScroll,
        settingsDataStore.reduceMotion,
        settingsDataStore.disableEffectsOnLowBattery,
        settingsDataStore.lowBatteryThreshold
    ) { values ->
        EffectsSettingsUiState(
            effectsEnabled = values[0] as Boolean,
            autoPlayEffects = values[1] as Boolean,
            replayEffectsOnScroll = values[2] as Boolean,
            reduceMotion = values[3] as Boolean,
            disableOnLowBattery = values[4] as Boolean,
            lowBatteryThreshold = values[5] as Int
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EffectsSettingsUiState()
    )

    fun setEffectsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setEffectsEnabled(enabled)
        }
    }

    fun setAutoPlayEffects(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAutoPlayEffects(enabled)
        }
    }

    fun setReplayEffectsOnScroll(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setReplayEffectsOnScroll(enabled)
        }
    }

    fun setReduceMotion(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setReduceMotion(enabled)
        }
    }

    fun setDisableOnLowBattery(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setDisableEffectsOnLowBattery(enabled)
        }
    }

    fun setLowBatteryThreshold(threshold: Int) {
        viewModelScope.launch {
            settingsDataStore.setLowBatteryThreshold(threshold)
        }
    }
}
