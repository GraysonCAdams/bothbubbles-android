package com.bothbubbles.ui.settings.eta

import android.content.Context
import android.provider.Settings
import timber.log.Timber
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.FeaturePreferences
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.AutoShareContact
import com.bothbubbles.data.repository.AutoShareContactRepository
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
    val minimumEtaMinutes: Int = 5,
    val isNavigationActive: Boolean = false,
    val isCurrentlySharing: Boolean = false,
    val currentEtaMinutes: Int = 0,
    val isDeveloperMode: Boolean = false,
    val autoShareContacts: List<AutoShareContact> = emptyList(),
    val canAddMoreContacts: Boolean = true
)

@HiltViewModel
class EtaSharingSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val featurePreferences: FeaturePreferences,
    private val etaSharingManager: EtaSharingManager,
    private val autoShareContactRepository: AutoShareContactRepository,
    private val contactsService: AndroidContactsService
) : ViewModel() {

    private val _hasNotificationAccess = MutableStateFlow(checkNotificationAccess())

    // Available contacts for auto-share selection
    private val _availableContacts = MutableStateFlow<List<PhoneContact>>(emptyList())
    val availableContacts: StateFlow<List<PhoneContact>> = _availableContacts.asStateFlow()

    private val _isLoadingContacts = MutableStateFlow(false)
    val isLoadingContacts: StateFlow<Boolean> = _isLoadingContacts.asStateFlow()

    val uiState: StateFlow<EtaSharingSettingsUiState> = combine(
        settingsDataStore.etaSharingEnabled,
        settingsDataStore.etaChangeThreshold,
        featurePreferences.autoShareMinimumEtaMinutes,
        _hasNotificationAccess,
        etaSharingManager.isNavigationActive,
        etaSharingManager.state,
        settingsDataStore.developerModeEnabled,
        autoShareContactRepository.observeAll()
    ) { values: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val etaState = values[5] as? com.bothbubbles.services.eta.EtaState
        val contacts = values[7] as? List<AutoShareContact> ?: emptyList()
        EtaSharingSettingsUiState(
            enabled = values[0] as? Boolean ?: false,
            changeThresholdMinutes = values[1] as? Int ?: 5,
            minimumEtaMinutes = values[2] as? Int ?: 5,
            hasNotificationAccess = values[3] as? Boolean ?: false,
            isNavigationActive = values[4] as? Boolean ?: false,
            isCurrentlySharing = etaState?.isSharing ?: false,
            currentEtaMinutes = etaState?.currentEta?.etaMinutes ?: 0,
            isDeveloperMode = values[6] as? Boolean ?: false,
            autoShareContacts = contacts,
            canAddMoreContacts = contacts.size < AutoShareContactRepository.MAX_CONTACTS
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

    fun setMinimumEtaMinutes(minutes: Int) {
        viewModelScope.launch {
            featurePreferences.setAutoShareMinimumEtaMinutes(minutes)
        }
    }

    fun refreshNotificationAccess() {
        Timber.tag("EtaSettings").d("refreshNotificationAccess() called")
        _hasNotificationAccess.value = checkNotificationAccess()
    }

    private fun checkNotificationAccess(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        val hasAccess = enabledPackages.contains(context.packageName)
        Timber.tag("EtaSettings").d("checkNotificationAccess: enabledPackages = $enabledPackages")
        Timber.tag("EtaSettings").d("checkNotificationAccess: looking for '${context.packageName}', hasAccess = $hasAccess")
        return hasAccess
    }

    fun getNotificationAccessSettingsIntent(): android.content.Intent {
        return android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    // ===== Auto-Share Contacts =====

    /**
     * Load available contacts for the contact picker.
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
                Timber.tag("EtaSettings").e(e, "Failed to load contacts")
            } finally {
                _isLoadingContacts.value = false
            }
        }
    }

    /**
     * Add a contact to auto-share list.
     * Returns false if the contact already exists or max contacts reached.
     */
    fun addAutoShareContact(chatGuid: String, displayName: String) {
        viewModelScope.launch {
            val success = autoShareContactRepository.add(chatGuid, displayName)
            if (success) {
                Timber.tag("EtaSettings").d("Added auto-share contact: $displayName")
            } else {
                Timber.tag("EtaSettings").w("Failed to add auto-share contact (max reached or duplicate)")
            }
        }
    }

    /**
     * Toggle enabled state for an auto-share contact.
     */
    fun toggleAutoShareContact(contact: AutoShareContact, enabled: Boolean) {
        viewModelScope.launch {
            autoShareContactRepository.setEnabled(contact.chatGuid, enabled)
        }
    }

    /**
     * Remove an auto-share contact.
     */
    fun removeAutoShareContact(contact: AutoShareContact) {
        viewModelScope.launch {
            autoShareContactRepository.remove(contact.chatGuid)
            Timber.tag("EtaSettings").d("Removed auto-share contact: ${contact.displayName}")
        }
    }

    // ===== Debug/Testing Methods =====

    /**
     * Simulate starting navigation (for testing)
     */
    fun debugSimulateNavigation(etaMinutes: Int) {
        etaSharingManager.simulateNavigation(etaMinutes)
    }

    /**
     * Simulate updating ETA (for testing)
     */
    fun debugUpdateEta(etaMinutes: Int) {
        etaSharingManager.simulateNavigation(etaMinutes)
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
