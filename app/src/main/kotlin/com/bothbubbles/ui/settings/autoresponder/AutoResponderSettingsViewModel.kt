package com.bothbubbles.ui.settings.autoresponder

import timber.log.Timber
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.autoresponder.AutoResponderService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
    val messagePreview: String = "",
    val availableAliases: List<String> = emptyList(),
    val selectedAlias: String = "",
    val isLoadingAliases: Boolean = false
)

@HiltViewModel
class AutoResponderSettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val smsRepository: SmsRepository,
    private val autoResponderService: AutoResponderService,
    private val api: BothBubblesApi
) : ViewModel() {

    private val _availableAliases = MutableStateFlow<List<String>>(emptyList())
    private val _isLoadingAliases = MutableStateFlow(false)

    val uiState: StateFlow<AutoResponderSettingsUiState> = combine(
        settingsDataStore.autoResponderEnabled,
        settingsDataStore.autoResponderFilter,
        settingsDataStore.autoResponderRateLimit,
        settingsDataStore.autoResponderRecommendedAlias,
        _availableAliases,
        _isLoadingAliases
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val enabled = values[0] as? Boolean ?: false
        val filter = values[1] as? String ?: "known_senders"
        val rateLimit = values[2] as? Int ?: 10
        val selectedAlias = values[3] as? String ?: ""
        val aliases = values[4] as? List<String> ?: emptyList()
        val isLoading = values[5] as? Boolean ?: false

        val phoneNumber = smsRepository.getAvailableSims().firstOrNull()?.number
            ?.takeIf { it.isNotBlank() }
        AutoResponderSettingsUiState(
            enabled = enabled,
            filterMode = filter,
            rateLimit = rateLimit,
            phoneNumber = phoneNumber,
            messagePreview = autoResponderService.buildMessage(selectedAlias.takeIf { it.isNotBlank() }),
            availableAliases = aliases,
            selectedAlias = selectedAlias,
            isLoadingAliases = isLoading
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AutoResponderSettingsUiState(
            messagePreview = autoResponderService.buildMessage(null)
        )
    )

    init {
        loadAliases()
    }

    private fun loadAliases() {
        viewModelScope.launch {
            _isLoadingAliases.value = true
            try {
                val response = api.getICloudAccountInfo()
                if (response.isSuccessful) {
                    val aliases = response.body()?.data?.vettedAliases
                        ?.map { it.alias }
                        ?: emptyList()
                    _availableAliases.value = aliases
                    Timber.d("Loaded ${aliases.size} aliases: $aliases")
                } else {
                    Timber.w("Failed to load aliases: ${response.code()}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading aliases")
            } finally {
                _isLoadingAliases.value = false
            }
        }
    }

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

    fun setRecommendedAlias(alias: String) {
        viewModelScope.launch {
            settingsDataStore.setAutoResponderRecommendedAlias(alias)
        }
    }

    fun refreshAliases() {
        loadAliases()
    }
}
