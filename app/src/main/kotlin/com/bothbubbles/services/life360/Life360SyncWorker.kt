package com.bothbubbles.services.life360

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bothbubbles.core.data.prefs.FeaturePreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically syncs Life360 member locations.
 *
 * Features:
 * - Syncs all circles at configured interval (default 10 minutes)
 * - Respects ghost mode (pause syncing) setting
 * - Only runs when network is available
 * - Respects Life360 API rate limits
 */
@HiltWorker
class Life360SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val life360Service: Life360Service,
    private val tokenStorage: Life360TokenStorage,
    private val featurePreferences: FeaturePreferences
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val WORK_NAME = "life360_sync"

        /**
         * Schedule the Life360 sync worker.
         *
         * @param context Application context
         * @param intervalMinutes How often to sync (minutes)
         */
        fun schedule(context: Context, intervalMinutes: Int = 10) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<Life360SyncWorker>(
                intervalMinutes.toLong().coerceAtLeast(15), // Android minimum is 15 min
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    15, TimeUnit.MINUTES
                )
                .addTag("life360_sync")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE, // Update interval if changed
                workRequest
            )

            Timber.i("Life360 sync scheduled (every $intervalMinutes minutes)")
        }

        /**
         * Cancel the Life360 sync worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("Life360 sync cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Timber.d("Starting Life360 sync")

        // Check if authenticated
        if (!tokenStorage.isAuthenticated) {
            Timber.d("Life360 not authenticated, skipping sync")
            return Result.success()
        }

        // Check if feature is enabled
        val enabled = featurePreferences.life360Enabled.first()
        if (!enabled) {
            Timber.d("Life360 disabled, skipping sync")
            return Result.success()
        }

        // Check ghost mode
        val paused = featurePreferences.life360PauseSyncing.first()
        if (paused) {
            Timber.d("Life360 syncing paused (ghost mode), skipping")
            return Result.success()
        }

        return try {
            val result = life360Service.syncAllCircles()
            result.fold(
                onSuccess = { memberCount ->
                    Timber.d("Life360 sync complete: $memberCount members updated")
                    Result.success()
                },
                onFailure = { error ->
                    Timber.w(error, "Life360 sync failed")
                    // Retry on transient errors
                    if (error is com.bothbubbles.util.error.Life360Error.RateLimited ||
                        error is com.bothbubbles.util.error.Life360Error.NetworkFailure
                    ) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Life360 sync failed with exception")
            Result.retry()
        }
    }
}
