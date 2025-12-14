package com.bothbubbles.ui.setup.delegates

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.categorization.EntityExtractionService
import com.bothbubbles.services.categorization.MlModelUpdateWorker
import com.bothbubbles.ui.setup.SetupUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Handles ML model download and setup.
 */
class MlModelDelegate(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val entityExtractionService: EntityExtractionService
) {
    suspend fun checkMlModelStatus(uiState: MutableStateFlow<SetupUiState>) {
        val isDownloaded = entityExtractionService.checkModelDownloaded()
        uiState.value = uiState.value.copy(
            mlModelDownloaded = isDownloaded,
            isOnWifi = isOnWifi()
        )
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun updateMlCellularAutoUpdate(uiState: MutableStateFlow<SetupUiState>, enabled: Boolean) {
        uiState.value = uiState.value.copy(mlEnableCellularUpdates = enabled)
    }

    fun downloadMlModel(scope: CoroutineScope, uiState: MutableStateFlow<SetupUiState>) {
        scope.launch {
            uiState.value = uiState.value.copy(mlDownloading = true, mlDownloadError = null)

            val allowCellular = !isOnWifi() // If not on WiFi, user has consented to cellular
            val success = entityExtractionService.downloadModel(allowCellular)

            if (success) {
                // Save to settings
                settingsDataStore.setMlModelDownloaded(true)
                // Enable categorization
                settingsDataStore.setCategorizationEnabled(true)
                // If downloaded on cellular and user checked the box, enable cellular updates
                if (!isOnWifi() && uiState.value.mlEnableCellularUpdates) {
                    settingsDataStore.setMlAutoUpdateOnCellular(true)
                }
                // Schedule periodic ML model updates
                MlModelUpdateWorker.schedule(context)
                // Mark ML setup complete (setup finishes after sync)
                uiState.value = uiState.value.copy(
                    mlDownloading = false,
                    mlModelDownloaded = true
                )
            } else {
                uiState.value = uiState.value.copy(
                    mlDownloading = false,
                    mlDownloadError = "Failed to download ML model"
                )
            }
        }
    }

    fun skipMlDownload(uiState: MutableStateFlow<SetupUiState>) {
        uiState.value = uiState.value.copy(mlDownloadSkipped = true)
    }
}
