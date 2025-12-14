package com.bothbubbles.ui.setup.delegates

import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.ui.setup.SetupUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles server connection testing and QR code scanning for setup.
 */
class ServerConnectionDelegate(
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi
) {
    suspend fun loadSavedSettings(uiState: MutableStateFlow<SetupUiState>) {
        val serverAddress = settingsDataStore.serverAddress.first()
        val password = settingsDataStore.guidAuthKey.first()
        uiState.value = uiState.value.copy(
            serverUrl = serverAddress,
            password = password
        )
    }

    fun updateServerUrl(uiState: MutableStateFlow<SetupUiState>, url: String) {
        uiState.value = uiState.value.copy(serverUrl = url, connectionError = null)
    }

    fun updatePassword(uiState: MutableStateFlow<SetupUiState>, password: String) {
        uiState.value = uiState.value.copy(password = password, connectionError = null)
    }

    fun testConnection(scope: CoroutineScope, uiState: MutableStateFlow<SetupUiState>) {
        val serverUrl = uiState.value.serverUrl.trim()
        val password = uiState.value.password.trim()

        if (serverUrl.isBlank()) {
            uiState.value = uiState.value.copy(connectionError = "Please enter a server URL")
            return
        }

        if (password.isBlank()) {
            uiState.value = uiState.value.copy(connectionError = "Please enter a password")
            return
        }

        scope.launch {
            uiState.value = uiState.value.copy(isTestingConnection = true, connectionError = null)

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

                    uiState.value = uiState.value.copy(
                        isTestingConnection = false,
                        isConnectionSuccessful = true,
                        connectionError = null
                    )
                } else {
                    val errorMessage = when (response.code()) {
                        401 -> "Invalid password"
                        404 -> "Server not found"
                        else -> "Connection failed: ${response.code()}"
                    }
                    uiState.value = uiState.value.copy(
                        isTestingConnection = false,
                        isConnectionSuccessful = false,
                        connectionError = errorMessage
                    )
                }
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    isTestingConnection = false,
                    isConnectionSuccessful = false,
                    connectionError = "Connection failed: ${e.message}"
                )
            }
        }
    }

    fun onQrCodeScanned(
        scope: CoroutineScope,
        uiState: MutableStateFlow<SetupUiState>,
        data: String
    ) {
        try {
            // QR code format: ["password", "serverUrl"]
            val parsed = data.trim()
            if (parsed.startsWith("[") && parsed.endsWith("]")) {
                val content = parsed.substring(1, parsed.length - 1)
                val parts = content.split(",").map { it.trim().removeSurrounding("\"") }
                if (parts.size >= 2) {
                    val password = parts[0]
                    val serverUrl = parts[1]
                    uiState.value = uiState.value.copy(
                        password = password,
                        serverUrl = serverUrl,
                        showQrScanner = false
                    )
                    // Auto-test after scanning
                    testConnection(scope, uiState)
                    return
                }
            }
            uiState.value = uiState.value.copy(connectionError = "Invalid QR code format")
        } catch (e: Exception) {
            uiState.value = uiState.value.copy(connectionError = "Failed to parse QR code")
        }
    }

    fun showQrScanner(uiState: MutableStateFlow<SetupUiState>) {
        uiState.value = uiState.value.copy(showQrScanner = true)
    }

    fun hideQrScanner(uiState: MutableStateFlow<SetupUiState>) {
        uiState.value = uiState.value.copy(showQrScanner = false)
    }

    suspend fun skipServerSetup(uiState: MutableStateFlow<SetupUiState>) {
        // Clear server settings when skipping
        settingsDataStore.setServerAddress("")
        settingsDataStore.setGuidAuthKey("")
        uiState.value = uiState.value.copy(
            serverUrl = "",
            password = "",
            isConnectionSuccessful = false,
            connectionError = null
        )
    }
}
