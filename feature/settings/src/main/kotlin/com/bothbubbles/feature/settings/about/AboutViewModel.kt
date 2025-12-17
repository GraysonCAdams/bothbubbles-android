package com.bothbubbles.feature.settings.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.core.data.DeveloperModeTracker
import com.bothbubbles.core.data.ServerConnectionProvider
import com.bothbubbles.core.data.SettingsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverConnection: ServerConnectionProvider,
    private val settingsProvider: SettingsProvider,
    private val developerModeTracker: DeveloperModeTracker
) : ViewModel() {

    companion object {
        private const val TAPS_TO_ENABLE_DEVELOPER_MODE = 10
        private const val TAP_TIMEOUT_MS = 2000L
    }

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    private var tapCount = 0
    private var lastTapTime = 0L

    init {
        loadAppVersion()
        observeServerVersion()
        observeDeveloperMode()
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
            serverConnection.serverVersion.collect { version ->
                _uiState.update { it.copy(serverVersion = version) }
            }
        }
    }

    private fun observeDeveloperMode() {
        viewModelScope.launch {
            settingsProvider.developerModeEnabled.collect { enabled ->
                _uiState.update { it.copy(developerModeEnabled = enabled) }
                developerModeTracker.setEnabled(enabled)
            }
        }
    }

    /**
     * Called when the version text is tapped.
     * After 10 taps within the timeout window, developer mode is enabled.
     */
    fun onVersionTapped(): DeveloperModeTapResult {
        val currentTime = System.currentTimeMillis()

        // Reset tap count if timeout exceeded
        if (currentTime - lastTapTime > TAP_TIMEOUT_MS) {
            tapCount = 0
        }

        lastTapTime = currentTime
        tapCount++

        val remaining = TAPS_TO_ENABLE_DEVELOPER_MODE - tapCount

        return when {
            _uiState.value.developerModeEnabled -> {
                // Already enabled
                tapCount = 0
                DeveloperModeTapResult.AlreadyEnabled
            }
            tapCount >= TAPS_TO_ENABLE_DEVELOPER_MODE -> {
                // Enable developer mode
                tapCount = 0
                viewModelScope.launch {
                    settingsProvider.setDeveloperModeEnabled(true)
                }
                DeveloperModeTapResult.JustEnabled
            }
            remaining <= 3 -> {
                // Show countdown
                DeveloperModeTapResult.TapsRemaining(remaining)
            }
            else -> {
                DeveloperModeTapResult.NoFeedback
            }
        }
    }
}

sealed class DeveloperModeTapResult {
    data object NoFeedback : DeveloperModeTapResult()
    data class TapsRemaining(val count: Int) : DeveloperModeTapResult()
    data object JustEnabled : DeveloperModeTapResult()
    data object AlreadyEnabled : DeveloperModeTapResult()
}

data class AboutUiState(
    val appVersion: String = "Unknown",
    val serverVersion: String? = null,
    val developerModeEnabled: Boolean = false
)
