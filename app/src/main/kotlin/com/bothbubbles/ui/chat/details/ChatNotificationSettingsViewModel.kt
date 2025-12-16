package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class NotificationPriority(val value: String, val displayName: String, val description: String?) {
    PRIORITY("priority", "Priority", null),
    DEFAULT("default", "Default", "May ring or vibrate based on device settings"),
    SILENT("silent", "Silent", null)
}

enum class LockScreenVisibility(val value: String, val displayName: String) {
    SHOW_ALL("all", "Show all notification content"),
    HIDE_SENSITIVE("hide_sensitive", "Hide sensitive content"),
    HIDE_ALL("hide_all", "Don't show notifications")
}

data class ChatNotificationSettingsUiState(
    val chat: ChatEntity? = null,
    val participants: List<HandleEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    val displayName: String
        get() = chat?.displayName
            ?: participants.firstOrNull()?.displayName
            ?: chat?.chatIdentifier
            ?: ""

    val subtitle: String
        get() = when {
            isUsingDefaultSettings -> "Incoming messages • Default settings"
            else -> "Incoming messages • Custom settings"
        }

    val notificationsEnabled: Boolean
        get() = chat?.notificationsEnabled ?: true

    val notificationPriority: NotificationPriority
        get() = NotificationPriority.entries.find { it.value == chat?.notificationPriority }
            ?: NotificationPriority.DEFAULT

    val bubbleEnabled: Boolean
        get() = chat?.bubbleEnabled ?: false

    val popOnScreen: Boolean
        get() = chat?.popOnScreen ?: true

    val notificationSound: String?
        get() = chat?.customNotificationSound

    val notificationSoundDisplay: String
        get() = chat?.customNotificationSound ?: "Default"

    val lockScreenVisibility: LockScreenVisibility
        get() = LockScreenVisibility.entries.find { it.value == chat?.lockScreenVisibility }
            ?: LockScreenVisibility.SHOW_ALL

    val showNotificationDot: Boolean
        get() = chat?.showNotificationDot ?: true

    val vibrationEnabled: Boolean
        get() = chat?.vibrationEnabled ?: true

    val isUsingDefaultSettings: Boolean
        get() = notificationsEnabled &&
                notificationPriority == NotificationPriority.DEFAULT &&
                !bubbleEnabled &&
                popOnScreen &&
                notificationSound == null &&
                lockScreenVisibility == LockScreenVisibility.SHOW_ALL &&
                showNotificationDot &&
                vibrationEnabled
}

@HiltViewModel
class ChatNotificationSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val route: Screen.ChatNotificationSettings = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    val uiState: StateFlow<ChatNotificationSettingsUiState> = combine(
        chatRepository.observeChat(chatGuid),
        chatRepository.observeParticipantsForChat(chatGuid)
    ) { chat, participants ->
        ChatNotificationSettingsUiState(
            chat = chat,
            participants = participants,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatNotificationSettingsUiState()
    )

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            chatRepository.setNotificationsEnabled(chatGuid, enabled)
        }
    }

    fun setNotificationPriority(priority: NotificationPriority) {
        viewModelScope.launch {
            chatRepository.setNotificationPriority(chatGuid, priority.value)
        }
    }

    fun setBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            chatRepository.setBubbleEnabled(chatGuid, enabled)
        }
    }

    fun setPopOnScreen(enabled: Boolean) {
        viewModelScope.launch {
            chatRepository.setPopOnScreen(chatGuid, enabled)
        }
    }

    fun setNotificationSound(sound: String?) {
        viewModelScope.launch {
            chatRepository.setNotificationSound(chatGuid, sound)
        }
    }

    fun setLockScreenVisibility(visibility: LockScreenVisibility) {
        viewModelScope.launch {
            chatRepository.setLockScreenVisibility(chatGuid, visibility.value)
        }
    }

    fun setShowNotificationDot(enabled: Boolean) {
        viewModelScope.launch {
            chatRepository.setShowNotificationDot(chatGuid, enabled)
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            chatRepository.setVibrationEnabled(chatGuid, enabled)
        }
    }
}
