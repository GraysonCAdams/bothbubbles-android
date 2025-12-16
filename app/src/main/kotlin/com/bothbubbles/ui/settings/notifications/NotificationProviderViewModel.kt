package com.bothbubbles.ui.settings.notifications

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.foreground.SocketForegroundService
import com.bothbubbles.services.fcm.FcmTokenManager
import com.bothbubbles.services.fcm.FcmTokenState
import com.bothbubbles.services.fcm.FirebaseConfigManager
import com.bothbubbles.services.fcm.FirebaseConfigState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationProviderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val firebaseConfigManager: FirebaseConfigManager,
    private val fcmTokenManager: FcmTokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationProviderUiState())
    val uiState: StateFlow<NotificationProviderUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        observeFcmState()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.notificationProvider,
                settingsDataStore.fcmTokenRegistered
            ) { provider, registered ->
                Pair(provider, registered)
            }.collect { (provider, registered) ->
                _uiState.update {
                    it.copy(
                        selectedProvider = provider,
                        fcmTokenRegistered = registered
                    )
                }
            }
        }
    }

    private fun observeFcmState() {
        viewModelScope.launch {
            try {
                combine(
                    firebaseConfigManager.state,
                    fcmTokenManager.tokenState
                ) { configState, tokenState ->
                    Pair(configState, tokenState)
                }.collect { (configState, tokenState) ->
                    val isGooglePlayAvailable = try {
                        firebaseConfigManager.isGooglePlayServicesAvailable()
                    } catch (e: Exception) {
                        android.util.Timber.tag("NotificationProviderVM").e(e, "Error checking Google Play Services")
                        false
                    }

                    _uiState.update {
                        it.copy(
                            firebaseConfigState = configState,
                            fcmTokenState = tokenState,
                            isGooglePlayServicesAvailable = isGooglePlayAvailable
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Timber.tag("NotificationProviderVM").e(e, "Error observing FCM state")
            }
        }
    }

    fun setNotificationProvider(provider: String) {
        viewModelScope.launch {
            val previousProvider = _uiState.value.selectedProvider

            // Update setting
            settingsDataStore.setNotificationProvider(provider)

            // Handle provider change
            when (provider) {
                "fcm" -> {
                    // Stop foreground service if running
                    if (previousProvider == "foreground") {
                        SocketForegroundService.stop(context)
                    }

                    // Initialize FCM if not already
                    if (!firebaseConfigManager.isInitialized()) {
                        firebaseConfigManager.initializeFromServer()
                    }
                    fcmTokenManager.refreshToken()
                }
                "foreground" -> {
                    // Start foreground service
                    SocketForegroundService.start(context)
                }
            }
        }
    }

    fun reRegisterFcm() {
        viewModelScope.launch {
            _uiState.update { it.copy(isReRegistering = true) }
            try {
                // Reset and re-fetch config
                firebaseConfigManager.reset()
                val result = firebaseConfigManager.initializeFromServer()
                if (result.isSuccess) {
                    fcmTokenManager.refreshToken()
                }
            } finally {
                _uiState.update { it.copy(isReRegistering = false) }
            }
        }
    }

    fun refreshFcmToken() {
        viewModelScope.launch {
            fcmTokenManager.refreshToken()
        }
    }
}

data class NotificationProviderUiState(
    val selectedProvider: String = "fcm",
    val isGooglePlayServicesAvailable: Boolean = true,
    val firebaseConfigState: FirebaseConfigState = FirebaseConfigState.NotInitialized,
    val fcmTokenState: FcmTokenState = FcmTokenState.Unknown,
    val fcmTokenRegistered: Boolean = false,
    val isReRegistering: Boolean = false
)
