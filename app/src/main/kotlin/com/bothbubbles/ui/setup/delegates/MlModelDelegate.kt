package com.bothbubbles.ui.setup.delegates

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.categorization.EntityExtractionService
import com.bothbubbles.services.categorization.MlModelUpdateWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Handles ML model download and setup.
 *
 * Phase 9: Uses AssistedInject to receive CoroutineScope at construction.
 * Exposes StateFlow<MlModelState> instead of mutating external state.
 */
class MlModelDelegate @AssistedInject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val entityExtractionService: EntityExtractionService,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): MlModelDelegate
    }

    private val _state = MutableStateFlow(MlModelState())
    val state: StateFlow<MlModelState> = _state.asStateFlow()

    init {
        checkMlModelStatus()
    }

    fun checkMlModelStatus() {
        scope.launch {
            val isDownloaded = entityExtractionService.checkModelDownloaded()
            _state.update {
                it.copy(
                    mlModelDownloaded = isDownloaded,
                    isOnWifi = isOnWifi()
                )
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun updateMlCellularAutoUpdate(enabled: Boolean) {
        _state.update { it.copy(mlEnableCellularUpdates = enabled) }
    }

    fun downloadMlModel() {
        scope.launch {
            _state.update { it.copy(mlDownloading = true, mlDownloadError = null) }

            val allowCellular = !isOnWifi() // If not on WiFi, user has consented to cellular
            val success = entityExtractionService.downloadModel(allowCellular)

            if (success) {
                // Save to settings
                settingsDataStore.setMlModelDownloaded(true)
                // Enable categorization
                settingsDataStore.setCategorizationEnabled(true)
                // If downloaded on cellular and user checked the box, enable cellular updates
                if (!isOnWifi() && _state.value.mlEnableCellularUpdates) {
                    settingsDataStore.setMlAutoUpdateOnCellular(true)
                }
                // Schedule periodic ML model updates
                MlModelUpdateWorker.schedule(context)
                // Mark ML setup complete (setup finishes after sync)
                _state.update {
                    it.copy(
                        mlDownloading = false,
                        mlModelDownloaded = true
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        mlDownloading = false,
                        mlDownloadError = "Failed to download ML model"
                    )
                }
            }
        }
    }

    fun skipMlDownload() {
        _state.update { it.copy(mlDownloadSkipped = true) }
    }
}
