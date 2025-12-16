package com.bothbubbles.ui.setup.delegates

import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.fcm.FirebaseConfigManager
import com.bothbubbles.services.fcm.FcmTokenManager
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.ui.setup.SetupUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Handles initial sync and setup completion.
 */
class SyncDelegate(
    private val settingsDataStore: SettingsDataStore,
    private val socketConnection: SocketConnection,
    private val syncService: SyncService,
    private val firebaseConfigManager: FirebaseConfigManager,
    private val fcmTokenManager: FcmTokenManager
) {
    fun updateSkipEmptyChats(uiState: MutableStateFlow<SetupUiState>, skip: Boolean) {
        uiState.value = uiState.value.copy(skipEmptyChats = skip)
    }

    suspend fun completeSetupWithoutSync(uiState: MutableStateFlow<SetupUiState>) {
        settingsDataStore.setSetupComplete(true)
        uiState.value = uiState.value.copy(isSyncComplete = true)
    }

    fun startSync(scope: CoroutineScope, uiState: MutableStateFlow<SetupUiState>) {
        scope.launch {
            uiState.value = uiState.value.copy(isSyncing = true, syncProgress = 0f, syncError = null)

            try {
                // Connect socket first
                socketConnection.connect()

                // Initialize FCM for push notifications (non-blocking)
                launch {
                    try {
                        firebaseConfigManager.initializeFromServer()
                        fcmTokenManager.refreshToken()
                    } catch (e: Exception) {
                        Log.w("SetupViewModel", "FCM init failed", e)
                    }
                }

                // Mark setup complete IMMEDIATELY so user can use the app
                // Sync will continue in background
                settingsDataStore.setSetupComplete(true)
                uiState.value = uiState.value.copy(
                    isSyncing = false,
                    isSyncComplete = true
                )

                // Start initial sync in SyncService's own scope (survives ViewModel destruction)
                // Categorization runs automatically after sync completes in SyncService
                syncService.startInitialSync(
                    messagesPerChat = uiState.value.messagesPerChat
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    isSyncing = false,
                    syncError = "Failed to start sync: ${e.message}"
                )
            }
        }
    }
}
