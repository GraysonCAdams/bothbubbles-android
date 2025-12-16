package com.bothbubbles.ui.setup.delegates

import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Handles server connection testing and QR code scanning for setup.
 *
 * Phase 9: Uses AssistedInject to receive CoroutineScope at construction.
 * Exposes StateFlow<ServerConnectionState> instead of mutating external state.
 */
class ServerConnectionDelegate @AssistedInject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ServerConnectionDelegate
    }

    private val _state = MutableStateFlow(ServerConnectionState())
    val state: StateFlow<ServerConnectionState> = _state.asStateFlow()

    init {
        loadSavedSettings()
    }

    private fun loadSavedSettings() {
        scope.launch {
            val serverAddress = settingsDataStore.serverAddress.first()
            val password = settingsDataStore.guidAuthKey.first()
            _state.update {
                it.copy(serverUrl = serverAddress, password = password)
            }
        }
    }

    fun updateServerUrl(url: String) {
        _state.update { it.copy(serverUrl = url, connectionError = null) }
    }

    fun updatePassword(password: String) {
        _state.update { it.copy(password = password, connectionError = null) }
    }

    fun testConnection() {
        val serverUrl = _state.value.serverUrl.trim()
        val password = _state.value.password.trim()

        if (serverUrl.isBlank()) {
            _state.update { it.copy(connectionError = "Please enter a server URL") }
            return
        }

        if (password.isBlank()) {
            _state.update { it.copy(connectionError = "Please enter a password") }
            return
        }

        scope.launch {
            _state.update { it.copy(isTestingConnection = true, connectionError = null) }

            try {
                // Save settings temporarily to make the API call
                settingsDataStore.setServerAddress(serverUrl)
                settingsDataStore.setGuidAuthKey(password)

                // Test connection
                val response = api.getServerInfo()

                if (response.isSuccessful) {
                    // Persist server capabilities for feature flag detection
                    val serverInfo = response.body()?.data
                    settingsDataStore.setServerCapabilities(
                        osVersion = serverInfo?.osVersion,
                        serverVersion = serverInfo?.serverVersion,
                        privateApiEnabled = serverInfo?.privateApi ?: false,
                        helperConnected = serverInfo?.helperConnected ?: false
                    )

                    _state.update {
                        it.copy(
                            isTestingConnection = false,
                            isConnectionSuccessful = true,
                            connectionError = null
                        )
                    }
                } else {
                    val errorMessage = when (response.code()) {
                        401 -> "Invalid password"
                        404 -> "Server not found"
                        else -> "Connection failed: ${response.code()}"
                    }
                    _state.update {
                        it.copy(
                            isTestingConnection = false,
                            isConnectionSuccessful = false,
                            connectionError = errorMessage
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isTestingConnection = false,
                        isConnectionSuccessful = false,
                        connectionError = "Connection failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun onQrCodeScanned(data: String) {
        try {
            // QR code format: ["password", "serverUrl"]
            val parsed = data.trim()
            if (parsed.startsWith("[") && parsed.endsWith("]")) {
                val content = parsed.substring(1, parsed.length - 1)
                val parts = content.split(",").map { it.trim().removeSurrounding("\"") }
                if (parts.size >= 2) {
                    val password = parts[0]
                    val serverUrl = parts[1]
                    _state.update {
                        it.copy(
                            password = password,
                            serverUrl = serverUrl,
                            showQrScanner = false
                        )
                    }
                    // Auto-test after scanning
                    testConnection()
                    return
                }
            }
            _state.update { it.copy(connectionError = "Invalid QR code format") }
        } catch (e: Exception) {
            _state.update { it.copy(connectionError = "Failed to parse QR code") }
        }
    }

    fun showQrScanner() {
        _state.update { it.copy(showQrScanner = true) }
    }

    fun hideQrScanner() {
        _state.update { it.copy(showQrScanner = false) }
    }

    fun skipServerSetup() {
        scope.launch {
            // Clear server settings when skipping
            settingsDataStore.setServerAddress("")
            settingsDataStore.setGuidAuthKey("")
            _state.update {
                it.copy(
                    serverUrl = "",
                    password = "",
                    isConnectionSuccessful = false,
                    connectionError = null
                )
            }
        }
    }
}
