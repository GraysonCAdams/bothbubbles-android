package com.bothbubbles.ui.settings

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EffectsSettingsUiState(
    val autoPlayEffects: Boolean = true,
    val replayEffectsOnScroll: Boolean = false,
    val reduceMotion: Boolean = false,
    val isPowerSaveMode: Boolean = false
)

@HiltViewModel
class EffectsSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    val uiState: StateFlow<EffectsSettingsUiState> = combine(
        settingsDataStore.autoPlayEffects,
        settingsDataStore.replayEffectsOnScroll,
        settingsDataStore.reduceMotion
    ) { autoPlay, replayOnScroll, reduceMotion ->
        EffectsSettingsUiState(
            autoPlayEffects = autoPlay,
            replayEffectsOnScroll = replayOnScroll,
            reduceMotion = reduceMotion,
            isPowerSaveMode = powerManager.isPowerSaveMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EffectsSettingsUiState(isPowerSaveMode = powerManager.isPowerSaveMode)
    )

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
}
