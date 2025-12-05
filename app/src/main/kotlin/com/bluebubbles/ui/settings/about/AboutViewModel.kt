package com.bluebubbles.ui.settings.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.services.socket.SocketEvent
import com.bluebubbles.services.socket.SocketService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socketService: SocketService
) : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    init {
        loadAppVersion()
        observeServerVersion()
    }

    private fun loadAppVersion() {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            _uiState.update { it.copy(appVersion = packageInfo.versionName ?: "Unknown") }
        } catch (e: Exception) {
            _uiState.update { it.copy(appVersion = "Unknown") }
        }
    }

    private fun observeServerVersion() {
        viewModelScope.launch {
            socketService.events.collect { event ->
                if (event is SocketEvent.ServerUpdate) {
                    _uiState.update { it.copy(serverVersion = event.version) }
                }
            }
        }
    }
}

data class AboutUiState(
    val appVersion: String = "Unknown",
    val serverVersion: String? = null
)
