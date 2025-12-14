package com.bothbubbles.ui.settings.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
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
                settingsDataStore.notifyOnChatList,
                settingsDataStore.bubbleFilterMode,
                settingsDataStore.selectedBubbleChats
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                val enabled = values[0] as? Boolean ?: true
                val notifyOnChatList = values[1] as? Boolean ?: false
                val bubbleFilterMode = values[2] as? String ?: "all"
                val selectedBubbleChats = values[3] as? Set<String> ?: emptySet()
                NotificationSettingsUiState(
                    notificationsEnabled = enabled,
                    notifyOnChatList = notifyOnChatList,
                    bubbleFilterMode = bubbleFilterMode,
                    selectedBubbleChatCount = selectedBubbleChats.size
                )
            }.collect { state ->
                _uiState.value = state
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

    fun setBubbleFilterMode(mode: String) {
        viewModelScope.launch {
            settingsDataStore.setBubbleFilterMode(mode)
        }
    }
}

data class NotificationSettingsUiState(
    val notificationsEnabled: Boolean = true,
    val notifyOnChatList: Boolean = false,
    val bubbleFilterMode: String = "all",
    val selectedBubbleChatCount: Int = 0
)
