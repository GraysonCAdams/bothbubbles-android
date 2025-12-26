package com.bothbubbles.ui.settings.server

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.StitchSettingsRepository
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.seam.stitches.bluebubbles.BlueBubblesStitch
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.theme.StitchDefaultColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val socketConnection: SocketConnection,
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi,
    private val stitchSettingsRepository: StitchSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSettingsUiState())
    val uiState: StateFlow<ServerSettingsUiState> = _uiState.asStateFlow()

    init {
        observeConnectionState()
        observeServerSettings()
        observePrivateApiSettings()
        observeCustomColor()
    }

    private fun observePrivateApiSettings() {
        viewModelScope.launch {
            settingsDataStore.enablePrivateApi.collect { enabled ->
                _uiState.update { it.copy(privateApiEnabled = enabled) }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            socketConnection.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }

                // When connected, fetch server info to check for Private API
                if (state == ConnectionState.CONNECTED) {
                    fetchServerInfo()
                }
            }
        }
    }

    private fun fetchServerInfo() {
        viewModelScope.launch {
            try {
                val response = api.getServerInfo()
                if (response.isSuccessful) {
                    val serverInfo = response.body()?.data
                    _uiState.update {
                        it.copy(
                            serverVersion = serverInfo?.serverVersion,
                            serverPrivateApiEnabled = serverInfo?.privateApi ?: false,
                            serverOsVersion = serverInfo?.osVersion,
                            helperConnected = serverInfo?.helperConnected ?: false
                        )
                    }

                    // Persist server capabilities for feature flag detection
                    settingsDataStore.setServerCapabilities(
                        osVersion = serverInfo?.osVersion,
                        serverVersion = serverInfo?.serverVersion,
                        privateApiEnabled = serverInfo?.privateApi ?: false,
                        helperConnected = serverInfo?.helperConnected ?: false
                    )

                    // Auto-enable Private API if server supports it (first time only)
                    val hasChecked = settingsDataStore.hasShownPrivateApiPrompt.first()
                    if (!hasChecked && serverInfo?.privateApi == true) {
                        settingsDataStore.setEnablePrivateApi(true)
                        settingsDataStore.setHasShownPrivateApiPrompt(true)
                    }
                }
            } catch (e: Exception) {
                // Silently fail - server info is optional
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

    fun reconnect() {
        _uiState.update { it.copy(isReconnecting = true) }
        socketConnection.reconnect()

        viewModelScope.launch {
            // Wait a bit then update the state
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(isReconnecting = false) }
        }
    }

    fun disconnect() {
        socketConnection.disconnect()
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }

            try {
                val startTime = System.currentTimeMillis()
                val response = api.ping()
                val latency = System.currentTimeMillis() - startTime

                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = ConnectionTestResult(
                                success = true,
                                latencyMs = latency
                            )
                        )
                    }
                } else {
                    val errorMessage = when (response.code()) {
                        401 -> "Authentication failed - check your password"
                        403 -> "Access forbidden - server rejected the request"
                        404 -> "Server endpoint not found - check server version"
                        500 -> "Server internal error"
                        502 -> "Bad gateway - check if BlueBubbles server is running"
                        503 -> "Server unavailable - BlueBubbles may be starting up"
                        else -> "Server returned HTTP ${response.code()}"
                    }
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = ConnectionTestResult(
                                success = false,
                                error = errorMessage
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                val errorMessage = getConnectionErrorMessage(e)
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        testResult = ConnectionTestResult(
                            success = false,
                            error = errorMessage
                        )
                    )
                }
            }
        }
    }

    private fun getConnectionErrorMessage(e: Exception): String {
        return when {
            e is java.net.UnknownHostException ->
                "Cannot resolve server address - check the URL or your internet connection"
            e is java.net.ConnectException ->
                "Cannot connect to server - verify the server is running and the port is correct"
            e is java.net.SocketTimeoutException ->
                "Connection timed out - server may be unreachable or too slow"
            e is javax.net.ssl.SSLHandshakeException ->
                "SSL certificate error - check HTTPS settings or try HTTP"
            e is javax.net.ssl.SSLException ->
                "SSL connection error - ${e.message ?: "check server HTTPS configuration"}"
            e.cause is java.net.UnknownHostException ->
                "Cannot resolve server address - check the URL or your internet connection"
            e.cause is java.net.ConnectException ->
                "Cannot connect to server - verify the server is running and the port is correct"
            e.cause is java.net.SocketTimeoutException ->
                "Connection timed out - server may be unreachable or too slow"
            e.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
                "HTTP blocked by Android - use HTTPS or enable cleartext in server settings"
            else -> e.message ?: "Unknown connection error"
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearTestResult() {
        _uiState.update { it.copy(testResult = null) }
    }

    fun showCapabilitiesDialog() {
        _uiState.update { it.copy(showCapabilitiesDialog = true) }
    }

    fun hideCapabilitiesDialog() {
        _uiState.update { it.copy(showCapabilitiesDialog = false) }
    }

    // ===== Custom Color =====

    private fun observeCustomColor() {
        viewModelScope.launch {
            stitchSettingsRepository.observeEffectiveColor(BlueBubblesStitch.ID, isDarkTheme = false)
                .collect { color ->
                    _uiState.update { it.copy(currentBubbleColor = color) }
                }
        }

        viewModelScope.launch {
            stitchSettingsRepository.observeAllCustomColors().collect { customColors ->
                val hasCustom = customColors.containsKey(BlueBubblesStitch.ID)
                _uiState.update { it.copy(isUsingDefaultColor = !hasCustom) }
            }
        }
    }

    /**
     * Set a custom bubble color for iMessage/BlueBubbles messages.
     */
    fun setCustomColor(color: Color) {
        viewModelScope.launch {
            stitchSettingsRepository.setCustomColor(BlueBubblesStitch.ID, color)
        }
    }

    /**
     * Reset the bubble color to the default iMessage blue.
     */
    fun resetColorToDefault() {
        viewModelScope.launch {
            stitchSettingsRepository.resetColorToDefault(BlueBubblesStitch.ID)
        }
    }

    /**
     * Get the default bubble color for iMessage.
     */
    fun getDefaultColor(): Color {
        return StitchDefaultColors.getDefaultBubbleColor(BlueBubblesStitch.ID, isDarkTheme = false)
    }
}

data class ServerSettingsUiState(
    val serverUrl: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val serverVersion: String? = null,
    val serverOsVersion: String? = null,
    val serverPrivateApiEnabled: Boolean = false,
    val helperConnected: Boolean = false,
    val privateApiEnabled: Boolean = false,
    val isReconnecting: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val error: String? = null,
    val showCapabilitiesDialog: Boolean = false,
    // Bubble color customization
    val currentBubbleColor: Color = StitchDefaultColors.getDefaultBubbleColor(BlueBubblesStitch.ID, false),
    val isUsingDefaultColor: Boolean = true
)

data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long? = null,
    val error: String? = null
)
