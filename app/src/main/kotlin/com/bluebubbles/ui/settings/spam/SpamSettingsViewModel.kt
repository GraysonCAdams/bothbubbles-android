package com.bluebubbles.ui.settings.spam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.entity.HandleEntity
import com.bluebubbles.data.local.prefs.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpamSettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val handleDao: HandleDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpamSettingsUiState())
    val uiState: StateFlow<SpamSettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        loadWhitelistedHandles()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.spamDetectionEnabled,
                settingsDataStore.spamThreshold
            ) { enabled, threshold ->
                enabled to threshold
            }.collect { (enabled, threshold) ->
                _uiState.update {
                    it.copy(
                        spamDetectionEnabled = enabled,
                        spamThreshold = threshold,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun loadWhitelistedHandles() {
        viewModelScope.launch {
            handleDao.getAllHandles().collect { handles ->
                val whitelisted = handles.filter { it.isWhitelisted }
                _uiState.update { it.copy(whitelistedHandles = whitelisted) }
            }
        }
    }

    fun setSpamDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSpamDetectionEnabled(enabled)
        }
    }

    fun setSpamThreshold(threshold: Int) {
        viewModelScope.launch {
            settingsDataStore.setSpamThreshold(threshold)
        }
    }

    fun removeFromWhitelist(handle: HandleEntity) {
        viewModelScope.launch {
            handleDao.updateWhitelisted(handle.id, false)
        }
    }
}

data class SpamSettingsUiState(
    val isLoading: Boolean = true,
    val spamDetectionEnabled: Boolean = true,
    val spamThreshold: Int = 70,
    val whitelistedHandles: List<HandleEntity> = emptyList()
) {
    val sensitivityLabel: String
        get() = when {
            spamThreshold <= 40 -> "Very aggressive"
            spamThreshold <= 55 -> "Aggressive"
            spamThreshold <= 70 -> "Balanced"
            spamThreshold <= 85 -> "Relaxed"
            else -> "Very relaxed"
        }
}
