package com.bluebubbles.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.data.repository.ChatRepository
import com.bluebubbles.services.socket.ConnectionState
import com.bluebubbles.services.socket.SocketService
import com.bluebubbles.services.sync.SyncService
import com.bluebubbles.services.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val socketService: SocketService,
    private val syncService: SyncService,
    private val chatRepository: ChatRepository,
    private val settingsDataStore: SettingsDataStore
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
                settingsDataStore.sendSoundEnabled,
                settingsDataStore.receiveSoundEnabled
            ) { sendSound, receiveSound ->
                Pair(sendSound, receiveSound)
            }.collect { (sendSound, receiveSound) ->
                _uiState.update {
                    it.copy(
                        sendSoundEnabled = sendSound,
                        receiveSoundEnabled = receiveSound
                    )
                }
            }
        }
    }

    fun setSendSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSendSoundEnabled(enabled)
        }
    }

    fun setReceiveSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setReceiveSoundEnabled(enabled)
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            socketService.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    private fun observeServerSettings() {
        viewModelScope.launch {
            settingsDataStore.serverAddress.collect { address ->
                _uiState.update { it.copy(serverUrl = address) }
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
        socketService.reconnect()
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
    val sendSoundEnabled: Boolean = true,
    val receiveSoundEnabled: Boolean = true
)
