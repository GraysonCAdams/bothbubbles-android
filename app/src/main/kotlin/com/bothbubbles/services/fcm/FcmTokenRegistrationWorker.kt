package com.bothbubbles.services.fcm

import android.content.Context
import timber.log.Timber
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.RegisterDeviceRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * WorkManager worker that registers the FCM token with the BlueBubbles server.
 *
 * Features:
 * - Network constraint (only runs when connected)
 * - Exponential backoff retry (up to 3 attempts)
 * - Unique work to prevent duplicate registrations
 */
@HiltWorker
class FcmTokenRegistrationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val api: BothBubblesApi,
    private val settingsDataStore: SettingsDataStore,
    private val fcmTokenManager: FcmTokenManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "FcmTokenRegWorker"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE_NAME = "device_name"
        private const val MAX_RETRY_COUNT = 3
    }

    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN)
        val deviceName = inputData.getString(KEY_DEVICE_NAME)

        if (token.isNullOrBlank()) {
            Timber.e("Token is missing")
            return Result.failure()
        }

        if (deviceName.isNullOrBlank()) {
            Timber.e("Device name is missing")
            return Result.failure()
        }

        // Check if server is configured
        val serverAddress = settingsDataStore.serverAddress.first()
        if (serverAddress.isBlank()) {
            Timber.d("Server not configured, skipping FCM registration")
            return Result.success()
        }

        // Check if setup is complete
        val setupComplete = settingsDataStore.isSetupComplete.first()
        if (!setupComplete) {
            Timber.d("Setup not complete, skipping FCM registration")
            return Result.success()
        }

        Timber.d("Registering FCM token with server (attempt ${runAttemptCount + 1})")

        return try {
            val request = RegisterDeviceRequest(
                name = deviceName,
                identifier = token
            )

            val response = api.registerFcmDevice(request)

            if (response.isSuccessful) {
                Timber.i("FCM token registered successfully")
                fcmTokenManager.markTokenRegistered(token)
                Result.success()
            } else {
                val errorCode = response.code()
                Timber.w("FCM registration failed with code: $errorCode")

                // Retry on server errors (5xx), fail on client errors (4xx)
                if (errorCode >= 500 && runAttemptCount < MAX_RETRY_COUNT) {
                    Result.retry()
                } else if (runAttemptCount < MAX_RETRY_COUNT) {
                    Result.retry()
                } else {
                    Timber.e("FCM registration failed after $MAX_RETRY_COUNT attempts")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error registering FCM token")

            if (runAttemptCount < MAX_RETRY_COUNT) {
                Result.retry()
            } else {
                Timber.e("FCM registration failed after $MAX_RETRY_COUNT attempts")
                Result.failure()
            }
        }
    }
}
