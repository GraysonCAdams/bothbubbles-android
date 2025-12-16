package com.bothbubbles.ui.setup.delegates

import android.content.Intent
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.sms.SmsPermissionHelper
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
 * Handles SMS setup configuration and permissions.
 *
 * Phase 9: Uses AssistedInject to receive CoroutineScope at construction.
 * Exposes StateFlow<SmsSetupState> instead of mutating external state.
 */
class SmsSetupDelegate @AssistedInject constructor(
    private val smsPermissionHelper: SmsPermissionHelper,
    private val settingsDataStore: SettingsDataStore,
    private val smsRepository: SmsRepository,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): SmsSetupDelegate
    }

    private val _state = MutableStateFlow(SmsSetupState())
    val state: StateFlow<SmsSetupState> = _state.asStateFlow()

    init {
        loadSmsStatus()
    }

    fun loadSmsStatus() {
        val status = smsPermissionHelper.getSmsCapabilityStatus()
        _state.update { it.copy(smsCapabilityStatus = status) }
    }

    fun updateSmsEnabled(enabled: Boolean) {
        _state.update { it.copy(smsEnabled = enabled) }
    }

    fun getMissingSmsPermissions(): Array<String> {
        return smsPermissionHelper.getMissingSmsPermissions().toTypedArray()
    }

    fun getDefaultSmsAppIntent(): Intent {
        return smsPermissionHelper.createDefaultSmsAppIntent()
    }

    fun onSmsPermissionsResult() {
        loadSmsStatus()
    }

    fun onDefaultSmsAppResult() {
        loadSmsStatus()
        // If we're now the default SMS app, auto-enable SMS
        if (smsPermissionHelper.isDefaultSmsApp()) {
            scope.launch {
                settingsDataStore.setSmsEnabled(true)
            }
            _state.update { it.copy(smsEnabled = true) }
        }
    }

    fun finalizeSmsSettings() {
        scope.launch {
            settingsDataStore.setSmsEnabled(_state.value.smsEnabled)
        }
    }

    fun loadUserPhoneNumber() {
        val phone = smsRepository.getAvailableSims().firstOrNull()?.number
            ?.takeIf { it.isNotBlank() }
            ?.let { formatPhone(it) }
        _state.update { it.copy(userPhoneNumber = phone) }
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
