package com.bothbubbles.ui.settings.sms

import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.data.repository.StitchSettingsRepository
import com.bothbubbles.seam.stitches.sms.SmsStitch
import com.bothbubbles.services.sms.SimInfo
import com.bothbubbles.services.sms.SmsCapabilityStatus
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.ui.theme.StitchDefaultColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SmsSettingsViewModel @Inject constructor(
    private val smsRepository: SmsRepository,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val settingsDataStore: SettingsDataStore,
    private val stitchSettingsRepository: StitchSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmsSettingsUiState())
    val uiState: StateFlow<SmsSettingsUiState> = _uiState.asStateFlow()

    init {
        loadSmsStatus()
        loadSettings()
        observeCustomColor()
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
                settingsDataStore.selectedSimSlot,
                settingsDataStore.autoSwitchSendMode
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                val enabled = values[0] as? Boolean ?: false
                val preferSms = values[1] as? Boolean ?: false
                val simSlot = values[2] as? Int ?: -1
                val autoSwitch = values[3] as? Boolean ?: true
                arrayOf(enabled, preferSms, simSlot, autoSwitch)
            }.collect { values ->
                val enabled = values.getOrNull(0) as? Boolean ?: false
                val preferSms = values.getOrNull(1) as? Boolean ?: false
                val simSlot = values.getOrNull(2) as? Int ?: -1
                val autoSwitch = values.getOrNull(3) as? Boolean ?: true
                _uiState.update {
                    it.copy(
                        smsEnabled = enabled,
                        preferSmsOverIMessage = preferSms,
                        selectedSimSlot = simSlot,
                        autoSwitchSendMode = autoSwitch
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

    fun setAutoSwitchSendMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAutoSwitchSendMode(enabled)
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear the resync result message
     */
    fun clearResyncResult() {
        _uiState.update { it.copy(resyncResult = null) }
    }

    // ===== Custom Color =====

    private fun observeCustomColor() {
        viewModelScope.launch {
            // For simplicity, we'll use light theme default here
            // In production, you'd observe the theme state
            stitchSettingsRepository.observeEffectiveColor(SmsStitch.ID, isDarkTheme = false)
                .collect { color ->
                    _uiState.update { it.copy(currentBubbleColor = color) }
                }
        }

        viewModelScope.launch {
            stitchSettingsRepository.observeAllCustomColors().collect { customColors ->
                val hasCustom = customColors.containsKey(SmsStitch.ID)
                _uiState.update { it.copy(isUsingDefaultColor = !hasCustom) }
            }
        }
    }

    /**
     * Set a custom bubble color for SMS messages.
     */
    fun setCustomColor(color: Color) {
        viewModelScope.launch {
            stitchSettingsRepository.setCustomColor(SmsStitch.ID, color)
        }
    }

    /**
     * Reset the bubble color to the default SMS green.
     */
    fun resetColorToDefault() {
        viewModelScope.launch {
            stitchSettingsRepository.resetColorToDefault(SmsStitch.ID)
        }
    }

    /**
     * Get the default bubble color for SMS.
     */
    fun getDefaultColor(): Color {
        return StitchDefaultColors.getDefaultBubbleColor(SmsStitch.ID, isDarkTheme = false)
    }

    /**
     * Manually re-sync SMS messages from the system SMS provider.
     * This imports any SMS messages that may have been missed
     * (e.g., from Android Auto, other SMS apps, etc.)
     */
    fun resyncSms() {
        if (_uiState.value.isResyncing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isResyncing = true, resyncResult = null) }

            try {
                val result = smsRepository.importAllThreads(limit = 500)
                result.onSuccess { imported ->
                    _uiState.update {
                        it.copy(
                            isResyncing = false,
                            resyncResult = if (imported > 0) {
                                "Imported $imported SMS threads"
                            } else {
                                "No new messages found"
                            }
                        )
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isResyncing = false,
                            error = "Re-sync failed: ${error.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isResyncing = false,
                        error = "Re-sync failed: ${e.message}"
                    )
                }
            }
        }
    }
}

data class SmsSettingsUiState(
    val capabilityStatus: SmsCapabilityStatus? = null,
    val availableSims: List<SimInfo> = emptyList(),
    val defaultSimId: Int = -1,
    val smsEnabled: Boolean = false,
    val preferSmsOverIMessage: Boolean = false,
    val autoSwitchSendMode: Boolean = true,
    val selectedSimSlot: Int = -1,
    val error: String? = null,
    val isResyncing: Boolean = false,
    val resyncResult: String? = null,
    // Bubble color customization
    val currentBubbleColor: Color = StitchDefaultColors.getDefaultBubbleColor(SmsStitch.ID, false),
    val isUsingDefaultColor: Boolean = true
)
