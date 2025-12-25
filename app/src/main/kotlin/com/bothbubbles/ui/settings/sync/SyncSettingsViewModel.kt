package com.bothbubbles.ui.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.services.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val syncService: SyncService,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncSettingsUiState())
    val uiState: StateFlow<SyncSettingsUiState> = _uiState.asStateFlow()

    init {
        observeSyncState()
        observeLastSyncTime()
        observeAutoDownloadSetting()
        observeSyncOnCellularSetting()
    }

    private fun observeAutoDownloadSetting() {
        viewModelScope.launch {
            settingsDataStore.autoDownloadAttachments.collect { enabled ->
                _uiState.update { it.copy(autoDownloadEnabled = enabled) }
            }
        }
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAutoDownloadAttachments(enabled)
        }
    }

    private fun observeSyncOnCellularSetting() {
        viewModelScope.launch {
            settingsDataStore.syncOnCellular.collect { enabled ->
                _uiState.update { it.copy(syncOnCellular = enabled) }
            }
        }
    }

    fun setSyncOnCellular(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSyncOnCellular(enabled)
        }
    }

    /**
     * One-time sync override - syncs now even if on cellular.
     * Used for "Sync Anyway" button when sync is paused due to cellular restriction.
     */
    fun syncAnywayOneTime() {
        syncService.syncAnywayOneTime()
    }

    private fun observeSyncState() {
        viewModelScope.launch {
            syncService.syncState.collect { state ->
                _uiState.update { it.copy(syncState = state) }
            }
        }
    }

    private fun observeLastSyncTime() {
        viewModelScope.launch {
            settingsDataStore.lastSyncTime.collect { time ->
                _uiState.update {
                    it.copy(
                        lastSyncTime = time,
                        lastSyncFormatted = formatLastSyncTime(time)
                    )
                }
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            syncService.performIncrementalSync()
        }
    }

    fun fullSync() {
        viewModelScope.launch {
            syncService.performInitialSync()
        }
    }

    fun cleanSync() {
        Timber.tag("SyncSettings").i("cleanSync() called from ViewModel")
        viewModelScope.launch {
            Timber.tag("SyncSettings").i("cleanSync() launching coroutine")
            val result = syncService.performCleanSync()
            Timber.tag("SyncSettings").i("cleanSync() completed with result: $result")
        }
    }

    fun disconnectServer() {
        viewModelScope.launch {
            syncService.cleanupServerData()
        }
    }

    fun resetSyncState() {
        syncService.resetState()
    }

    private fun formatLastSyncTime(timestamp: Long): String {
        if (timestamp == 0L) return "Never"

        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> {
                val hours = diff / 3600_000
                "$hours hour${if (hours > 1) "s" else ""} ago"
            }
            else -> {
                val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
                dateFormat.format(Date(timestamp))
            }
        }
    }
}

data class SyncSettingsUiState(
    val syncState: SyncState = SyncState.Idle,
    val lastSyncTime: Long = 0L,
    val lastSyncFormatted: String = "Never",
    val autoDownloadEnabled: Boolean = true,
    val syncOnCellular: Boolean = false
)
