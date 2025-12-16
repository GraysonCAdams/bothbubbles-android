package com.bothbubbles.ui.setup.delegates

import timber.log.Timber
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.fcm.FirebaseConfigManager
import com.bothbubbles.services.fcm.FcmTokenManager
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.sync.SyncService
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Handles initial sync and setup completion.
 *
 * Phase 9: Uses AssistedInject to receive CoroutineScope at construction.
 * Exposes StateFlow<SyncState> instead of mutating external state.
 */
class SyncDelegate @AssistedInject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val socketConnection: SocketConnection,
    private val syncService: SyncService,
    private val firebaseConfigManager: FirebaseConfigManager,
    private val fcmTokenManager: FcmTokenManager,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): SyncDelegate
    }

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun updateSkipEmptyChats(skip: Boolean) {
        _state.update { it.copy(skipEmptyChats = skip) }
    }

    fun completeSetupWithoutSync() {
        scope.launch {
            settingsDataStore.setSetupComplete(true)
            _state.update { it.copy(isSyncComplete = true) }
        }
    }

    fun startSync() {
        scope.launch {
            _state.update { it.copy(isSyncing = true, syncProgress = 0f, syncError = null) }

            try {
                // Connect socket first
                socketConnection.connect()

                // Initialize FCM for push notifications (non-blocking)
                launch {
                    try {
                        firebaseConfigManager.initializeFromServer()
                        fcmTokenManager.refreshToken()
                    } catch (e: Exception) {
                        Timber.w(e, "FCM init failed")
                    }
                }

                // Mark setup complete IMMEDIATELY so user can use the app
                // Sync will continue in background
                settingsDataStore.setSetupComplete(true)
                _state.update {
                    it.copy(
                        isSyncing = false,
                        isSyncComplete = true
                    )
                }

                // Start initial sync in SyncService's own scope (survives ViewModel destruction)
                // Categorization runs automatically after sync completes in SyncService
                syncService.startInitialSync(
                    messagesPerChat = _state.value.messagesPerChat
                )
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSyncing = false,
                        syncError = "Failed to start sync: ${e.message}"
                    )
                }
            }
        }
    }
}
