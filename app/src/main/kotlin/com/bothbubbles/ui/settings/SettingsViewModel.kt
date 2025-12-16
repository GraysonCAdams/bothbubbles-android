package com.bothbubbles.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sound.SoundPlayer
import com.bothbubbles.services.sound.SoundTheme
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.services.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val socketConnection: SocketConnection,
    private val syncService: SyncService,
    private val chatRepository: ChatRepository,
    private val settingsDataStore: SettingsDataStore,
    private val soundPlayer: SoundPlayer
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeConnectionState()
        observeServerSettings()
        observeChatCounts()
        observeSyncState()
        observeAppTitleSetting()
        observePrivateApiSettings()
        observeSoundSettings()
        observeDeveloperMode()
        observeSmsEnabled()
        observeLinkPreviews()
    }

    private fun observeSmsEnabled() {
        viewModelScope.launch {
            settingsDataStore.smsEnabled.collect { enabled ->
                _uiState.update { it.copy(smsEnabled = enabled) }
            }
        }
    }

    private fun observeAppTitleSetting() {
        viewModelScope.launch {
            settingsDataStore.useSimpleAppTitle.collect { useSimple ->
                _uiState.update { it.copy(useSimpleAppTitle = useSimple) }
            }
        }
    }

    fun setUseSimpleAppTitle(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setUseSimpleAppTitle(enabled)
        }
    }

    private fun observePrivateApiSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.enablePrivateApi,
                settingsDataStore.sendTypingIndicators
            ) { privateApi, sendTyping ->
                Pair(privateApi, sendTyping)
            }.collect { (privateApi, sendTyping) ->
                _uiState.update {
                    it.copy(
                        enablePrivateApi = privateApi,
                        sendTypingIndicators = sendTyping
                    )
                }
            }
        }
    }

    fun setEnablePrivateApi(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setEnablePrivateApi(enabled)
        }
    }

    fun setSendTypingIndicators(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSendTypingIndicators(enabled)
        }
    }

    private fun observeSoundSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.messageSoundsEnabled,
                settingsDataStore.soundTheme
            ) { enabled, theme ->
                enabled to theme
            }.collect { (enabled, theme) ->
                _uiState.update { it.copy(messageSoundsEnabled = enabled, soundTheme = theme) }
            }
        }
    }

    fun setMessageSoundsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setMessageSoundsEnabled(enabled)
        }
    }

    fun setSoundTheme(theme: SoundTheme) {
        viewModelScope.launch {
            settingsDataStore.setSoundTheme(theme)
            // Preview the sounds when theme is selected
            soundPlayer.previewSounds(theme)
        }
    }

    private fun observeDeveloperMode() {
        viewModelScope.launch {
            settingsDataStore.developerModeEnabled.collect { enabled ->
                _uiState.update { it.copy(developerModeEnabled = enabled) }
            }
        }
    }

    private fun observeLinkPreviews() {
        viewModelScope.launch {
            settingsDataStore.linkPreviewsEnabled.collect { enabled ->
                _uiState.update { it.copy(linkPreviewsEnabled = enabled) }
            }
        }
    }

    fun setLinkPreviewsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setLinkPreviewsEnabled(enabled)
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            socketConnection.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    private fun observeServerSettings() {
        viewModelScope.launch {
            settingsDataStore.serverAddress.collect { address ->
                _uiState.update {
                    it.copy(
                        serverUrl = address,
                        isServerConfigured = address.isNotBlank()
                    )
                }
            }
        }
    }

    private fun observeChatCounts() {
        viewModelScope.launch {
            chatRepository.observeArchivedChatCount().collect { count ->
                _uiState.update { it.copy(archivedCount = count) }
            }
        }

        viewModelScope.launch {
            chatRepository.observeUnreadChatCount().collect { count ->
                _uiState.update { it.copy(unreadCount = count) }
            }
        }
    }

    private fun observeSyncState() {
        viewModelScope.launch {
            combine(
                syncService.syncState,
                settingsDataStore.lastSyncTime
            ) { state, time -> state to time }
                .collect { (state, time) ->
                    _uiState.update {
                        it.copy(
                            syncState = state,
                            lastSyncTime = time,
                            lastSyncFormatted = formatLastSyncTime(time)
                        )
                    }
                }
        }
    }

    /**
     * Mark all chats as read using batch operation
     */
    fun markAllAsRead() {
        viewModelScope.launch {
            _uiState.update { it.copy(isMarkingAllRead = true) }

            chatRepository.markAllChatsAsRead()
                .onSuccess {
                    _uiState.update { it.copy(isMarkingAllRead = false) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isMarkingAllRead = false,
                            error = e.message ?: "Failed to mark all as read"
                        )
                    }
                }
        }
    }

    /**
     * Reconnect to the server
     */
    fun reconnect() {
        socketConnection.reconnect()
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun formatLastSyncTime(timestamp: Long): String {
        if (timestamp == 0L) return "Never"

        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            else -> {
                val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                dateFormat.format(Date(timestamp))
            }
        }
    }
}

data class SettingsUiState(
    val serverUrl: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val archivedCount: Int = 0,
    val unreadCount: Int = 0,
    val syncState: SyncState = SyncState.Idle,
    val lastSyncTime: Long = 0L,
    val lastSyncFormatted: String = "Never",
    val isMarkingAllRead: Boolean = false,
    val error: String? = null,
    val useSimpleAppTitle: Boolean = false,
    // Private API settings
    val enablePrivateApi: Boolean = false,
    val sendTypingIndicators: Boolean = true,
    // Sound settings
    val messageSoundsEnabled: Boolean = true,
    val soundTheme: SoundTheme = SoundTheme.DEFAULT,
    // Server configuration state
    val isServerConfigured: Boolean = false,
    // SMS state
    val smsEnabled: Boolean = false,
    // Developer mode
    val developerModeEnabled: Boolean = false,
    // Link previews
    val linkPreviewsEnabled: Boolean = false
)
