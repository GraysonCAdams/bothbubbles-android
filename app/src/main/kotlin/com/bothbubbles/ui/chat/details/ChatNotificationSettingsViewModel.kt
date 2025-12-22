package com.bothbubbles.ui.chat.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.notifications.NotificationChannelManager
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatNotificationSettingsUiState(
    val chat: ChatEntity? = null,
    val participants: List<HandleEntity> = emptyList(),
    val notificationsEnabled: Boolean = true,
    val isLoading: Boolean = true
) {
    val displayName: String
        get() = chat?.displayName
            ?: participants.firstOrNull()?.displayName
            ?: chat?.chatIdentifier
            ?: ""
}

/**
 * ViewModel for per-conversation notification settings.
 *
 * This simplified ViewModel delegates most notification settings to Android's
 * native per-channel notification settings. It only manages:
 * - Mute/unmute (notifications_enabled) - app-level toggle
 * - Channel ID retrieval for opening system settings
 */
@HiltViewModel
class ChatNotificationSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val notificationChannelManager: NotificationChannelManager
) : ViewModel() {

    private val route: Screen.ChatNotificationSettings = savedStateHandle.toRoute()
    val chatGuid: String = route.chatGuid

    private val _notificationsEnabled = kotlinx.coroutines.flow.MutableStateFlow(true)

    init {
        viewModelScope.launch {
            _notificationsEnabled.value = chatRepository.getNotificationsEnabled(chatGuid)
        }
    }

    val uiState: StateFlow<ChatNotificationSettingsUiState> = combine(
        chatRepository.observeChat(chatGuid),
        chatRepository.observeParticipantsForChat(chatGuid),
        _notificationsEnabled
    ) { chat, participants, notificationsEnabled ->
        ChatNotificationSettingsUiState(
            chat = chat,
            participants = participants,
            notificationsEnabled = notificationsEnabled,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatNotificationSettingsUiState()
    )

    /**
     * Toggle notifications for this conversation.
     * This is an app-level setting that prevents notifications from being shown,
     * independent of the Android channel settings.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        viewModelScope.launch {
            chatRepository.setNotificationsEnabled(chatGuid, enabled)
        }
    }

    /**
     * Get the notification channel ID for this conversation.
     * Creates the channel if it doesn't exist.
     * Used to open Android's notification channel settings.
     */
    fun getNotificationChannelId(): String {
        val chatTitle = uiState.value.displayName.ifEmpty { "Conversation" }
        return notificationChannelManager.getOrCreateConversationChannel(
            chatGuid = chatGuid,
            chatTitle = chatTitle
        )
    }
}
