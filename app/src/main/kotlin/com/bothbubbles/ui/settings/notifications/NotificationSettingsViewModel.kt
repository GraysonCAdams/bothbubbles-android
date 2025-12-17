package com.bothbubbles.ui.settings.notifications

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationSettingsUiState())
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        observeSettings()
    }

    /**
     * Check if bubbles are enabled at the Android system level.
     * Call this when the screen resumes to get updated state.
     */
    fun refreshBubblePermissionState() {
        val bubbleState = checkBubblePermission()
        _uiState.update { it.copy(systemBubblesState = bubbleState) }
    }

    private fun checkBubblePermission(): SystemBubblesState {
        // Bubbles require API 29+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return SystemBubblesState.NOT_SUPPORTED
        }

        // On API 31+, we can check the bubble preference directly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (notificationManager.bubblePreference) {
                NotificationManager.BUBBLE_PREFERENCE_ALL -> SystemBubblesState.ENABLED
                NotificationManager.BUBBLE_PREFERENCE_SELECTED -> SystemBubblesState.ENABLED
                NotificationManager.BUBBLE_PREFERENCE_NONE -> SystemBubblesState.DISABLED
                else -> SystemBubblesState.ENABLED // Default to enabled if unknown
            }
        }

        // On API 29-30, bubbles are enabled by default if the app declares them
        // We can't easily check the system preference, so assume enabled
        return SystemBubblesState.ENABLED
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
    val selectedBubbleChatCount: Int = 0,
    val systemBubblesState: SystemBubblesState = SystemBubblesState.ENABLED
)

enum class SystemBubblesState {
    /** Bubbles are enabled in Android system settings */
    ENABLED,
    /** Bubbles are disabled in Android system settings */
    DISABLED,
    /** Device doesn't support bubbles (API < 29) */
    NOT_SUPPORTED
}
