package com.bothbubbles.ui.settings.sms

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.services.export.SmsBackupProgress
import com.bothbubbles.services.export.SmsBackupService
import com.bothbubbles.services.export.SmsRestoreProgress
import com.bothbubbles.services.export.SmsRestorer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SmsBackupViewModel @Inject constructor(
    private val backupService: SmsBackupService,
    private val smsRestorer: SmsRestorer
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmsBackupUiState())
    val uiState: StateFlow<SmsBackupUiState> = _uiState.asStateFlow()

    private var backupJob: Job? = null
    private var restoreJob: Job? = null

    init {
        // Observe backup progress
        backupService.backupProgress
            .onEach { progress ->
                _uiState.update { it.copy(backupProgress = progress) }
            }
            .launchIn(viewModelScope)

        // Observe restore progress
        smsRestorer.restoreProgress
            .onEach { progress ->
                _uiState.update { it.copy(restoreProgress = progress) }
            }
            .launchIn(viewModelScope)
    }

    fun startBackup() {
        if (backupJob?.isActive == true) return

        backupJob = backupService.exportBackup()
            .onEach { progress ->
                _uiState.update { it.copy(backupProgress = progress) }
            }
            .launchIn(viewModelScope)
    }

    fun startRestore(fileUri: Uri) {
        if (restoreJob?.isActive == true) return

        restoreJob = smsRestorer.restoreBackup(fileUri)
            .onEach { progress ->
                _uiState.update { it.copy(restoreProgress = progress) }
            }
            .launchIn(viewModelScope)
    }

    fun cancelBackup() {
        backupJob?.cancel()
        backupService.resetProgress()
    }

    fun cancelRestore() {
        restoreJob?.cancel()
        smsRestorer.resetProgress()
    }

    fun resetBackupProgress() {
        backupService.resetProgress()
    }

    fun resetRestoreProgress() {
        smsRestorer.resetProgress()
    }

    fun dismissError() {
        when (_uiState.value.backupProgress) {
            is SmsBackupProgress.Error -> backupService.resetProgress()
            else -> {}
        }
        when (_uiState.value.restoreProgress) {
            is SmsRestoreProgress.Error -> smsRestorer.resetProgress()
            else -> {}
        }
    }
}

data class SmsBackupUiState(
    val backupProgress: SmsBackupProgress = SmsBackupProgress.Idle,
    val restoreProgress: SmsRestoreProgress = SmsRestoreProgress.Idle
)
