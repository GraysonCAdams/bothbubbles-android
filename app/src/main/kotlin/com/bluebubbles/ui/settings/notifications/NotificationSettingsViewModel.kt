package com.bluebubbles.ui.settings.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.prefs.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationSettingsUiState())
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.notificationsEnabled,
                settingsDataStore.notifyOnChatList
            ) { enabled, notifyOnChatList ->
                enabled to notifyOnChatList
            }.collect { (enabled, notifyOnChatList) ->
                _uiState.update {
                    it.copy(
                        notificationsEnabled = enabled,
                        notifyOnChatList = notifyOnChatList
                    )
                }
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setNotificationsEnabled(enabled)
        }
    }

    fun setNotifyOnChatList(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setNotifyOnChatList(enabled)
        }
    }
}

data class NotificationSettingsUiState(
    val notificationsEnabled: Boolean = true,
    val notifyOnChatList: Boolean = false
)
