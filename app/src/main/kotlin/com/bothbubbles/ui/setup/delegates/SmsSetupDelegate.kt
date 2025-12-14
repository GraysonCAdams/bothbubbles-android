package com.bothbubbles.ui.setup.delegates

import android.content.Intent
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.ui.setup.SetupUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Handles SMS setup configuration and permissions.
 */
class SmsSetupDelegate(
    private val smsPermissionHelper: SmsPermissionHelper,
    private val settingsDataStore: SettingsDataStore,
    private val smsRepository: SmsRepository
) {
    fun loadSmsStatus(uiState: MutableStateFlow<SetupUiState>) {
        val status = smsPermissionHelper.getSmsCapabilityStatus()
        uiState.value = uiState.value.copy(smsCapabilityStatus = status)
    }

    fun updateSmsEnabled(uiState: MutableStateFlow<SetupUiState>, enabled: Boolean) {
        uiState.value = uiState.value.copy(smsEnabled = enabled)
    }

    fun getMissingSmsPermissions(): Array<String> {
        return smsPermissionHelper.getMissingSmsPermissions().toTypedArray()
    }

    fun getDefaultSmsAppIntent(): Intent {
        return smsPermissionHelper.createDefaultSmsAppIntent()
    }

    fun onSmsPermissionsResult(uiState: MutableStateFlow<SetupUiState>) {
        loadSmsStatus(uiState)
    }

    fun onDefaultSmsAppResult(scope: CoroutineScope, uiState: MutableStateFlow<SetupUiState>) {
        loadSmsStatus(uiState)
        // If we're now the default SMS app, auto-enable SMS
        if (smsPermissionHelper.isDefaultSmsApp()) {
            scope.launch {
                settingsDataStore.setSmsEnabled(true)
            }
            uiState.value = uiState.value.copy(smsEnabled = true)
        }
    }

    suspend fun finalizeSmsSettings(uiState: MutableStateFlow<SetupUiState>) {
        settingsDataStore.setSmsEnabled(uiState.value.smsEnabled)
    }

    fun loadUserPhoneNumber(uiState: MutableStateFlow<SetupUiState>) {
        val phone = smsRepository.getAvailableSims().firstOrNull()?.number
            ?.takeIf { it.isNotBlank() }
            ?.let { formatPhone(it) }
        uiState.value = uiState.value.copy(userPhoneNumber = phone)
    }

    private fun formatPhone(phone: String): String {
        val digits = phone.replace(Regex("[^0-9]"), "")
        return when {
            digits.length == 10 -> "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
            digits.length == 11 && digits.startsWith("1") ->
                "(${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7)}"
            else -> phone
        }
    }
}
