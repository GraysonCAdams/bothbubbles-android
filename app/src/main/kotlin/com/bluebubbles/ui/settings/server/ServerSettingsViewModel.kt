package com.bluebubbles.ui.settings.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.data.remote.api.BlueBubblesApi
import com.bluebubbles.services.socket.ConnectionState
import com.bluebubbles.services.socket.SocketService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val socketService: SocketService,
    private val settingsDataStore: SettingsDataStore,
    private val api: BlueBubblesApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSettingsUiState())
    val uiState: StateFlow<ServerSettingsUiState> = _uiState.asStateFlow()

    init {
        observeConnectionState()
        observeServerSettings()
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

    fun reconnect() {
        _uiState.update { it.copy(isReconnecting = true) }
        socketService.reconnect()

        viewModelScope.launch {
            // Wait a bit then update the state
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(isReconnecting = false) }
        }
    }

    fun disconnect() {
        socketService.disconnect()
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
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = ConnectionTestResult(
                                success = false,
                                error = "Server returned ${response.code()}"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        testResult = ConnectionTestResult(
                            success = false,
                            error = e.message
                        )
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearTestResult() {
        _uiState.update { it.copy(testResult = null) }
    }
}

data class ServerSettingsUiState(
    val serverUrl: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val serverVersion: String? = null,
    val isReconnecting: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val error: String? = null
)

data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long? = null,
    val error: String? = null
)
