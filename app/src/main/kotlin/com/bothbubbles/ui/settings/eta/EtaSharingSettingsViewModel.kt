package com.bothbubbles.ui.settings.eta

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.AutoShareRecipient
import com.bothbubbles.data.repository.AutoShareRule
import com.bothbubbles.data.repository.AutoShareRuleRepository
import com.bothbubbles.data.repository.LocationType
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.contacts.PhoneContact
import com.bothbubbles.services.eta.EtaSharingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EtaSharingSettingsUiState(
    val enabled: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val changeThresholdMinutes: Int = 5,
    val isNavigationActive: Boolean = false,
    val isCurrentlySharing: Boolean = false,
    val currentEtaMinutes: Int = 0,
    val destination: String? = null,
    val isDeveloperMode: Boolean = false,
    val autoShareRules: List<AutoShareRule> = emptyList()
)

@HiltViewModel
class EtaSharingSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val etaSharingManager: EtaSharingManager,
    private val autoShareRuleRepository: AutoShareRuleRepository,
    private val contactsService: AndroidContactsService
) : ViewModel() {

    private val _hasNotificationAccess = MutableStateFlow(checkNotificationAccess())

    // Available contacts for auto-share recipient selection
    private val _availableContacts = MutableStateFlow<List<PhoneContact>>(emptyList())
    val availableContacts: StateFlow<List<PhoneContact>> = _availableContacts.asStateFlow()

    private val _isLoadingContacts = MutableStateFlow(false)
    val isLoadingContacts: StateFlow<Boolean> = _isLoadingContacts.asStateFlow()

    val uiState: StateFlow<EtaSharingSettingsUiState> = combine(
        settingsDataStore.etaSharingEnabled,
        settingsDataStore.etaChangeThreshold,
        _hasNotificationAccess,
        etaSharingManager.isNavigationActive,
        etaSharingManager.state,
        settingsDataStore.developerModeEnabled,
        autoShareRuleRepository.observeAllRules()
    ) { values: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val etaState = values[4] as? com.bothbubbles.services.eta.EtaState
        val rules = values[6] as? List<AutoShareRule> ?: emptyList()
        EtaSharingSettingsUiState(
            enabled = values[0] as? Boolean ?: false,
            changeThresholdMinutes = values[1] as? Int ?: 5,
            hasNotificationAccess = values[2] as? Boolean ?: false,
            isNavigationActive = values[3] as? Boolean ?: false,
            isCurrentlySharing = etaState?.isSharing ?: false,
            currentEtaMinutes = etaState?.currentEta?.etaMinutes ?: 0,
            destination = etaState?.currentEta?.destination,
            isDeveloperMode = values[5] as? Boolean ?: false,
            autoShareRules = rules
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EtaSharingSettingsUiState()
    )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setEtaSharingEnabled(enabled)
        }
    }

    fun setChangeThreshold(minutes: Int) {
        viewModelScope.launch {
            settingsDataStore.setEtaChangeThreshold(minutes)
        }
    }

    fun refreshNotificationAccess() {
        Log.d("EtaSettings", "refreshNotificationAccess() called")
        _hasNotificationAccess.value = checkNotificationAccess()
    }

    private fun checkNotificationAccess(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        val hasAccess = enabledPackages.contains(context.packageName)
        Log.d("EtaSettings", "checkNotificationAccess: enabledPackages = $enabledPackages")
        Log.d("EtaSettings", "checkNotificationAccess: looking for '${context.packageName}', hasAccess = $hasAccess")
        return hasAccess
    }

    fun getNotificationAccessSettingsIntent(): android.content.Intent {
        return android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    // ===== Auto-Share Rules =====

    /**
     * Load available contacts for the recipient picker.
     * Only loads contacts with valid phone numbers or emails.
     */
    fun loadAvailableContacts() {
        viewModelScope.launch {
            _isLoadingContacts.value = true
            try {
                val contacts = contactsService.getAllContacts()
                // Filter to contacts with at least one phone number or email
                _availableContacts.value = contacts.filter { contact ->
                    contact.phoneNumbers.isNotEmpty() || contact.emails.isNotEmpty()
                }.sortedBy { it.displayName.lowercase() }
            } catch (e: Exception) {
                Log.e("EtaSettings", "Failed to load contacts", e)
            } finally {
                _isLoadingContacts.value = false
            }
        }
    }

    /**
     * Create a new auto-share rule.
     */
    fun createAutoShareRule(
        destinationName: String,
        keywords: List<String>,
        locationType: LocationType,
        recipients: List<AutoShareRecipient>
    ) {
        viewModelScope.launch {
            val ruleId = autoShareRuleRepository.createRule(
                destinationName = destinationName,
                keywords = keywords,
                locationType = locationType,
                recipients = recipients
            )
            if (ruleId != null) {
                Log.d("EtaSettings", "Created auto-share rule: $ruleId")
            } else {
                Log.w("EtaSettings", "Failed to create auto-share rule")
            }
        }
    }

    /**
     * Update an existing auto-share rule.
     */
    fun updateAutoShareRule(
        ruleId: Long,
        destinationName: String,
        keywords: List<String>,
        locationType: LocationType,
        recipients: List<AutoShareRecipient>
    ) {
        viewModelScope.launch {
            val success = autoShareRuleRepository.updateRule(
                ruleId = ruleId,
                destinationName = destinationName,
                keywords = keywords,
                locationType = locationType,
                recipients = recipients
            )
            Log.d("EtaSettings", "Updated auto-share rule $ruleId: $success")
        }
    }

    /**
     * Toggle enabled state for an auto-share rule.
     */
    fun toggleAutoShareRule(rule: AutoShareRule, enabled: Boolean) {
        viewModelScope.launch {
            autoShareRuleRepository.setRuleEnabled(rule.id, enabled)
        }
    }

    /**
     * Delete an auto-share rule.
     */
    fun deleteAutoShareRule(rule: AutoShareRule) {
        viewModelScope.launch {
            autoShareRuleRepository.deleteRule(rule.id)
            Log.d("EtaSettings", "Deleted auto-share rule: ${rule.id}")
        }
    }

    // ===== Debug/Testing Methods =====

    /**
     * Simulate starting navigation (for testing)
     */
    fun debugSimulateNavigation(etaMinutes: Int, destination: String) {
        etaSharingManager.simulateNavigation(etaMinutes, destination)
    }

    /**
     * Simulate updating ETA (for testing)
     */
    fun debugUpdateEta(etaMinutes: Int) {
        val currentDestination = uiState.value.destination ?: "Test Location"
        etaSharingManager.simulateNavigation(etaMinutes, currentDestination)
    }

    /**
     * Simulate stopping navigation (for testing)
     */
    fun debugStopNavigation() {
        etaSharingManager.simulateNavigationStop()
    }

    /**
     * Reset terminal state (for testing)
     */
    fun debugResetTerminalState() {
        etaSharingManager.debugResetTerminalState()
    }
}
