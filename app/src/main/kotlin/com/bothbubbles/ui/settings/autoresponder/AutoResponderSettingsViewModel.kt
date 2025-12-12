package com.bothbubbles.ui.settings.autoresponder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.autoresponder.AutoResponderService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AutoResponderSettingsUiState(
    val enabled: Boolean = false,
    val filterMode: String = "known_senders",
    val rateLimit: Int = 10,
    val phoneNumber: String? = null,
    val messagePreview: String = ""
)

@HiltViewModel
class AutoResponderSettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val smsRepository: SmsRepository,
    private val autoResponderService: AutoResponderService
) : ViewModel() {

    val uiState: StateFlow<AutoResponderSettingsUiState> = combine(
        settingsDataStore.autoResponderEnabled,
        settingsDataStore.autoResponderFilter,
        settingsDataStore.autoResponderRateLimit
    ) { enabled, filter, rateLimit ->
        val phoneNumber = smsRepository.getAvailableSims().firstOrNull()?.number
            ?.takeIf { it.isNotBlank() }
        AutoResponderSettingsUiState(
            enabled = enabled,
            filterMode = filter,
            rateLimit = rateLimit,
            phoneNumber = phoneNumber,
            messagePreview = autoResponderService.buildMessage()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AutoResponderSettingsUiState(
            messagePreview = autoResponderService.buildMessage()
        )
    )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAutoResponderEnabled(enabled)
        }
    }

    fun setFilterMode(mode: String) {
        viewModelScope.launch {
            settingsDataStore.setAutoResponderFilter(mode)
        }
    }

    fun setRateLimit(limit: Int) {
        viewModelScope.launch {
            settingsDataStore.setAutoResponderRateLimit(limit)
        }
    }
}
