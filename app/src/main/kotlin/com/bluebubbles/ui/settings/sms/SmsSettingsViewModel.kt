package com.bluebubbles.ui.settings.sms

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.data.repository.SmsRepository
import com.bluebubbles.services.sms.SimInfo
import com.bluebubbles.services.sms.SmsCapabilityStatus
import com.bluebubbles.services.sms.SmsPermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SmsSettingsViewModel @Inject constructor(
    private val smsRepository: SmsRepository,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmsSettingsUiState())
    val uiState: StateFlow<SmsSettingsUiState> = _uiState.asStateFlow()

    init {
        loadSmsStatus()
        loadSettings()
    }

    fun loadSmsStatus() {
        val status = smsPermissionHelper.getSmsCapabilityStatus()
        val sims = smsRepository.getAvailableSims()
        val defaultSimId = smsRepository.getDefaultSimId()

        _uiState.update {
            it.copy(
                capabilityStatus = status,
                availableSims = sims,
                defaultSimId = defaultSimId
            )
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                settingsDataStore.smsEnabled,
                settingsDataStore.preferSmsOverIMessage,
                settingsDataStore.selectedSimSlot
            ) { enabled, preferSms, simSlot ->
                Triple(enabled, preferSms, simSlot)
            }.collect { (enabled, preferSms, simSlot) ->
                _uiState.update {
                    it.copy(
                        smsEnabled = enabled,
                        preferSmsOverIMessage = preferSms,
                        selectedSimSlot = simSlot
                    )
                }
            }
        }
    }

    fun setSmsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setSmsEnabled(enabled)
            if (enabled) {
                smsRepository.startObserving()
            } else {
                smsRepository.stopObserving()
            }
        }
    }

    fun setPreferSmsOverIMessage(prefer: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setPreferSmsOverIMessage(prefer)
        }
    }

    fun setSelectedSimSlot(slot: Int) {
        viewModelScope.launch {
            settingsDataStore.setSelectedSimSlot(slot)
        }
    }

    /**
     * Get intent to request default SMS app status
     */
    fun getDefaultSmsAppIntent(): Intent {
        return smsPermissionHelper.createDefaultSmsAppIntent()
    }

    /**
     * Get list of missing permissions
     */
    fun getMissingPermissions(): Array<String> {
        return smsPermissionHelper.getMissingSmsPermissions().toTypedArray()
    }

    /**
     * Called after permissions are granted/denied
     */
    fun onPermissionsResult() {
        loadSmsStatus()
    }

    /**
     * Called after default SMS app request completes
     */
    fun onDefaultSmsAppResult() {
        loadSmsStatus()
    }

    /**
     * Start SMS import process
     */
    fun startSmsImport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importProgress = 0f) }

            smsRepository.importAllThreads(
                limit = 500,
                onProgress = { current, total ->
                    _uiState.update {
                        it.copy(importProgress = current.toFloat() / total.toFloat())
                    }
                }
            ).fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importProgress = 1f,
                            lastImportCount = count
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            error = e.message
                        )
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class SmsSettingsUiState(
    val capabilityStatus: SmsCapabilityStatus? = null,
    val availableSims: List<SimInfo> = emptyList(),
    val defaultSimId: Int = -1,
    val smsEnabled: Boolean = false,
    val preferSmsOverIMessage: Boolean = false,
    val selectedSimSlot: Int = -1,
    val isImporting: Boolean = false,
    val importProgress: Float = 0f,
    val lastImportCount: Int? = null,
    val error: String? = null
)
