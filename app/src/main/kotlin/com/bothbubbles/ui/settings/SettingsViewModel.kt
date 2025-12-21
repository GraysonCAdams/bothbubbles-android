package com.bothbubbles.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.network.api.dto.FindMyDeviceDto
import com.bothbubbles.core.network.api.dto.FindMyFriendDto
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sound.SoundPlayer
import com.bothbubbles.services.sound.SoundTheme
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.services.sync.SyncState
import com.bothbubbles.util.PermissionStateMonitor
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
    private val soundPlayer: SoundPlayer,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val permissionStateMonitor: PermissionStateMonitor,
    private val api: BothBubblesApi
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
        observeHapticsSettings()
        observeDeveloperMode()
        observeSmsEnabled()
        observeLinkPreviews()
        refreshSmsCapability()
        refreshContactsPermission()
    }

    private fun observeSmsEnabled() {
        viewModelScope.launch {
            settingsDataStore.smsEnabled.collect { enabled ->
                _uiState.update { it.copy(smsEnabled = enabled) }
            }
        }
    }

    /**
     * Refresh SMS capability status. Called on screen resume to ensure
     * up-to-date status after returning from SMS settings.
     */
    fun refreshSmsCapability() {
        val status = smsPermissionHelper.getSmsCapabilityStatus()
        _uiState.update {
            it.copy(
                isSmsFullyFunctional = status.isFullyFunctional,
                isDefaultSmsApp = status.isDefaultSmsApp
            )
        }
    }

    /**
     * Refresh contacts permission status. Called on screen resume to ensure
     * up-to-date status after returning from permission request or system settings.
     */
    fun refreshContactsPermission() {
        _uiState.update {
            it.copy(hasContactsPermission = permissionStateMonitor.hasContactsPermission())
        }
    }

    private fun observeAppTitleSetting() {
        viewModelScope.launch {
            combine(
                settingsDataStore.useSimpleAppTitle,
                settingsDataStore.showUnreadCountInHeader
            ) { useSimple, showUnread ->
                Pair(useSimple, showUnread)
            }.collect { (useSimple, showUnread) ->
                _uiState.update {
                    it.copy(
                        useSimpleAppTitle = useSimple,
                        showUnreadCountInHeader = showUnread
                    )
                }
            }
        }
    }

    fun setUseSimpleAppTitle(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setUseSimpleAppTitle(enabled)
        }
    }

    fun setShowUnreadCountInHeader(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setShowUnreadCountInHeader(enabled)
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

    private fun observeHapticsSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.hapticsEnabled,
                settingsDataStore.audioHapticSyncEnabled
            ) { enabled, syncEnabled ->
                enabled to syncEnabled
            }.collect { (enabled, syncEnabled) ->
                _uiState.update { it.copy(hapticsEnabled = enabled, audioHapticSyncEnabled = syncEnabled) }
                // Update global haptics state
                HapticUtils.enabled = enabled
            }
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setHapticsEnabled(enabled)
            HapticUtils.enabled = enabled
        }
    }

    fun setAudioHapticSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAudioHapticSyncEnabled(enabled)
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

    /**
     * Fetch Find My data (devices and friends) for debug display
     */
    fun fetchFindMyData() {
        viewModelScope.launch {
            _uiState.update { it.copy(findMyLoading = true) }

            try {
                // Fetch devices
                val devicesResponse = api.getFindMyDevices()
                val devices = if (devicesResponse.isSuccessful) {
                    devicesResponse.body()?.data ?: emptyList()
                } else {
                    emptyList()
                }

                // Fetch friends
                val friendsResponse = api.getFindMyFriends()
                val friends = if (friendsResponse.isSuccessful) {
                    friendsResponse.body()?.data ?: emptyList()
                } else {
                    emptyList()
                }

                _uiState.update {
                    it.copy(
                        findMyDevices = devices,
                        findMyFriends = friends,
                        findMyLoading = false,
                        findMyError = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        findMyLoading = false,
                        findMyError = e.message ?: "Failed to fetch Find My data"
                    )
                }
            }
        }
    }

    /**
     * Clear Find My data
     */
    fun clearFindMyData() {
        _uiState.update {
            it.copy(
                findMyDevices = emptyList(),
                findMyFriends = emptyList(),
                findMyError = null
            )
        }
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
    val showUnreadCountInHeader: Boolean = true,
    // Private API settings
    val enablePrivateApi: Boolean = false,
    val sendTypingIndicators: Boolean = true,
    // Sound settings
    val messageSoundsEnabled: Boolean = true,
    val soundTheme: SoundTheme = SoundTheme.DEFAULT,
    // Haptics settings
    val hapticsEnabled: Boolean = true,
    val audioHapticSyncEnabled: Boolean = true,
    // Server configuration state
    val isServerConfigured: Boolean = false,
    // SMS state
    val smsEnabled: Boolean = false,
    val isSmsFullyFunctional: Boolean = false,
    val isDefaultSmsApp: Boolean = false,
    // Developer mode
    val developerModeEnabled: Boolean = false,
    // Link previews
    val linkPreviewsEnabled: Boolean = false,
    // Contacts permission
    val hasContactsPermission: Boolean = false,
    // Find My debug data
    val findMyDevices: List<FindMyDeviceDto> = emptyList(),
    val findMyFriends: List<FindMyFriendDto> = emptyList(),
    val findMyLoading: Boolean = false,
    val findMyError: String? = null
)
