package com.bothbubbles.ui.settings.categorization

import timber.log.Timber
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.categorization.CategorizationRepository
import com.bothbubbles.services.categorization.EntityExtractionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategorizationSettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val entityExtractionService: EntityExtractionService,
    private val categorizationRepository: CategorizationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategorizationSettingsUiState())
    val uiState: StateFlow<CategorizationSettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        checkMlModelStatus()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val categorizationEnabled = settingsDataStore.categorizationEnabled.first()
            val mlAutoUpdateOnCellular = settingsDataStore.mlAutoUpdateOnCellular.first()
            val transactionsEnabled = settingsDataStore.transactionsCategoryEnabled.first()
            val deliveriesEnabled = settingsDataStore.deliveriesCategoryEnabled.first()
            val promotionsEnabled = settingsDataStore.promotionsCategoryEnabled.first()
            val remindersEnabled = settingsDataStore.remindersCategoryEnabled.first()

            _uiState.update {
                it.copy(
                    categorizationEnabled = categorizationEnabled,
                    mlAutoUpdateOnCellular = mlAutoUpdateOnCellular,
                    transactionsEnabled = transactionsEnabled,
                    deliveriesEnabled = deliveriesEnabled,
                    promotionsEnabled = promotionsEnabled,
                    remindersEnabled = remindersEnabled
                )
            }
        }
    }

    private fun checkMlModelStatus() {
        viewModelScope.launch {
            val isDownloaded = entityExtractionService.checkModelDownloaded()
            _uiState.update { it.copy(mlModelDownloaded = isDownloaded) }
        }
    }

    fun setCategorizationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setCategorizationEnabled(enabled)
            _uiState.update { it.copy(categorizationEnabled = enabled) }

            // Trigger retroactive categorization when enabling
            if (enabled) {
                _uiState.update { it.copy(isCategorizing = true) }
                Timber.d("Triggering retroactive categorization...")
                val categorized = categorizationRepository.categorizeAllChats()
                Timber.d("Retroactive categorization complete: $categorized chats")
                _uiState.update { it.copy(isCategorizing = false) }
            }
        }
    }

    fun setMlAutoUpdateOnCellular(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setMlAutoUpdateOnCellular(enabled)
            _uiState.update { it.copy(mlAutoUpdateOnCellular = enabled) }
        }
    }

    fun setTransactionsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setTransactionsCategoryEnabled(enabled)
            _uiState.update { it.copy(transactionsEnabled = enabled) }
        }
    }

    fun setDeliveriesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setDeliveriesCategoryEnabled(enabled)
            _uiState.update { it.copy(deliveriesEnabled = enabled) }
        }
    }

    fun setPromotionsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setPromotionsCategoryEnabled(enabled)
            _uiState.update { it.copy(promotionsEnabled = enabled) }
        }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setRemindersCategoryEnabled(enabled)
            _uiState.update { it.copy(remindersEnabled = enabled) }
        }
    }

    fun downloadMlModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadError = null) }

            val success = entityExtractionService.downloadModel(allowCellular = true)

            if (success) {
                settingsDataStore.setMlModelDownloaded(true)
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        mlModelDownloaded = true
                    )
                }

                // If categorization is enabled, trigger retroactive categorization with new ML model
                if (_uiState.value.categorizationEnabled) {
                    _uiState.update { it.copy(isCategorizing = true) }
                    Timber.d("ML model downloaded, triggering retroactive categorization...")
                    val categorized = categorizationRepository.categorizeAllChats()
                    Timber.d("Retroactive categorization complete: $categorized chats")
                    _uiState.update { it.copy(isCategorizing = false) }
                }
            } else {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        downloadError = "Failed to download ML model. Please try again."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(downloadError = null) }
    }
}

data class CategorizationSettingsUiState(
    val categorizationEnabled: Boolean = true,
    val mlModelDownloaded: Boolean = false,
    val mlAutoUpdateOnCellular: Boolean = false,
    val isDownloading: Boolean = false,
    val isCategorizing: Boolean = false,
    val downloadError: String? = null,
    // Per-category enabled states
    val transactionsEnabled: Boolean = true,
    val deliveriesEnabled: Boolean = true,
    val promotionsEnabled: Boolean = true,
    val remindersEnabled: Boolean = true
)
