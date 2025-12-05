package com.bluebubbles.ui.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.services.sync.SyncService
import com.bluebubbles.services.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        viewModelScope.launch {
            syncService.performCleanSync()
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
    val lastSyncFormatted: String = "Never"
)
