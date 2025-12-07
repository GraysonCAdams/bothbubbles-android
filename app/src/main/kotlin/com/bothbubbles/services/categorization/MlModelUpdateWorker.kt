package com.bothbubbles.services.categorization

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bothbubbles.data.local.prefs.SettingsDataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically checks for and downloads ML model updates.
 *
 * Behavior:
 * - Runs periodically (every 7 days by default)
 * - Only downloads on WiFi unless cellular updates are enabled in settings
 * - Respects user preference for cellular auto-updates
 */
@HiltWorker
class MlModelUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val entityExtractionService: EntityExtractionService,
    private val settingsDataStore: SettingsDataStore
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "MlModelUpdateWorker"
        private const val WORK_NAME = "ml_model_update"

        // Check for updates every 7 days
        private const val UPDATE_INTERVAL_DAYS = 7L

        /**
         * Schedule periodic ML model update checks.
         * Call this from Application.onCreate() or after setup completes.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val updateRequest = PeriodicWorkRequestBuilder<MlModelUpdateWorker>(
                UPDATE_INTERVAL_DAYS, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                updateRequest
            )

            Log.d(TAG, "Scheduled ML model update worker")
        }

        /**
         * Cancel scheduled updates.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled ML model update worker")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting ML model update check")

        // Check if categorization is enabled
        val categorizationEnabled = settingsDataStore.categorizationEnabled.first()
        if (!categorizationEnabled) {
            Log.d(TAG, "Categorization disabled, skipping update")
            return Result.success()
        }

        // Check network conditions
        val isOnWifi = isOnWifi()
        val allowCellular = settingsDataStore.mlAutoUpdateOnCellular.first()

        if (!isOnWifi && !allowCellular) {
            Log.d(TAG, "Not on WiFi and cellular updates disabled, skipping")
            return Result.success()
        }

        return try {
            // Attempt to download/update the model
            val success = entityExtractionService.downloadModel(allowCellular = allowCellular)

            if (success) {
                settingsDataStore.setMlModelDownloaded(true)
                Log.i(TAG, "ML model updated successfully")
                Result.success()
            } else {
                Log.w(TAG, "ML model update failed, will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating ML model", e)
            Result.retry()
        }
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
