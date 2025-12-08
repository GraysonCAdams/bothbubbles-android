package com.bothbubbles.ui.settings.categorization

import android.util.Log
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

    companion object {
        private const val TAG = "CategorizationSettingsVM"
    }

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

            _uiState.update {
                it.copy(
                    categorizationEnabled = categorizationEnabled,
                    mlAutoUpdateOnCellular = mlAutoUpdateOnCellular
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
                Log.d(TAG, "Triggering retroactive categorization...")
                val categorized = categorizationRepository.categorizeAllChats()
                Log.d(TAG, "Retroactive categorization complete: $categorized chats")
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
                    Log.d(TAG, "ML model downloaded, triggering retroactive categorization...")
                    val categorized = categorizationRepository.categorizeAllChats()
                    Log.d(TAG, "Retroactive categorization complete: $categorized chats")
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
    val downloadError: String? = null
)
