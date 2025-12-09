package com.bothbubbles.services.fcm

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages FCM token lifecycle: retrieval, storage, and server registration.
 *
 * This manager:
 * - Retrieves FCM tokens from FirebaseMessaging
 * - Stores tokens in SettingsDataStore
 * - Enqueues WorkManager jobs for reliable server registration
 * - Handles token refresh events
 */
@Singleton
class FcmTokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val firebaseConfigManager: FirebaseConfigManager
) {
    companion object {
        private const val TAG = "FcmTokenManager"
        private const val WORK_NAME = "fcm_token_registration"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tokenState = MutableStateFlow<FcmTokenState>(FcmTokenState.Unknown)
    val tokenState: StateFlow<FcmTokenState> = _tokenState.asStateFlow()

    /**
     * Initialize the token manager. Call this on app startup.
     * This will attempt to get the current token if Firebase is initialized.
     *
     * FCM is always initialized regardless of connection mode so it's ready
     * for background push notifications when the app is backgrounded.
     */
    fun initialize() {
        scope.launch {
            // Check if setup is complete before trying to initialize Firebase
            val setupComplete = settingsDataStore.isSetupComplete.first()
            if (!setupComplete) {
                Log.d(TAG, "Setup not complete, skipping FCM initialization")
                _tokenState.value = FcmTokenState.NotConfigured
                return@launch
            }

            // Try to initialize Firebase
            if (!firebaseConfigManager.isInitialized()) {
                // First try from cache (fast path)
                var initialized = firebaseConfigManager.initializeFromCache()

                if (!initialized) {
                    // No cache - try to fetch from server
                    Log.d(TAG, "No cached Firebase config, fetching from server...")
                    val result = firebaseConfigManager.initializeFromServer()
                    initialized = result.isSuccess

                    if (!initialized) {
                        Log.w(TAG, "Failed to initialize Firebase from server: ${result.exceptionOrNull()?.message}")
                        _tokenState.value = FcmTokenState.NotConfigured
                        return@launch
                    }
                }
            }

            // Check if we have a stored token that's registered
            val storedToken = settingsDataStore.fcmToken.first()
            val isRegistered = settingsDataStore.fcmTokenRegistered.first()

            if (storedToken.isNotBlank() && isRegistered) {
                Log.d(TAG, "Using stored FCM token")
                _tokenState.value = FcmTokenState.Registered(storedToken)
            } else if (storedToken.isNotBlank()) {
                // Token exists but not registered, re-register
                Log.d(TAG, "Token exists but not registered, enqueueing registration")
                _tokenState.value = FcmTokenState.Available(storedToken)
                enqueueTokenRegistration(storedToken)
            } else {
                // No token, need to fetch
                refreshToken()
            }
        }
    }

    /**
     * Refresh the FCM token. Call this after setup completes or to force a new token.
     * FCM is always kept ready for background mode switching.
     */
    suspend fun refreshToken() {
        if (!firebaseConfigManager.isInitialized()) {
            Log.w(TAG, "Firebase not initialized, cannot refresh token")
            _tokenState.value = FcmTokenState.NotConfigured
            return
        }

        _tokenState.value = FcmTokenState.Loading

        try {
            val firebaseApp = firebaseConfigManager.getFirebaseApp()
            if (firebaseApp == null) {
                Log.e(TAG, "Firebase app is null")
                _tokenState.value = FcmTokenState.Error("Firebase not initialized")
                return
            }

            val messaging = FirebaseMessaging.getInstance()
            val token = messaging.token.await()

            Log.d(TAG, "Got FCM token: ${token.take(10)}...")

            // Store the token
            val storedToken = settingsDataStore.fcmToken.first()
            if (token != storedToken) {
                settingsDataStore.setFcmToken(token)
                settingsDataStore.setFcmTokenRegistered(false)
            }

            _tokenState.value = FcmTokenState.Available(token)

            // Enqueue registration with server
            enqueueTokenRegistration(token)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting FCM token", e)
            _tokenState.value = FcmTokenState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Called when FirebaseMessagingService receives a new token.
     */
    fun onTokenRefreshed(newToken: String) {
        Log.d(TAG, "Token refreshed: ${newToken.take(10)}...")
        scope.launch {
            settingsDataStore.setFcmToken(newToken)
            settingsDataStore.setFcmTokenRegistered(false)
            _tokenState.value = FcmTokenState.Available(newToken)
            enqueueTokenRegistration(newToken)
        }
    }

    /**
     * Mark the token as registered with the server.
     * Called by FcmTokenRegistrationWorker on success.
     */
    suspend fun markTokenRegistered(token: String) {
        val currentToken = settingsDataStore.fcmToken.first()
        if (token == currentToken) {
            settingsDataStore.setFcmTokenRegistered(true)
            _tokenState.value = FcmTokenState.Registered(token)
            Log.i(TAG, "Token marked as registered")
        }
    }

    /**
     * Get a unique device identifier for FCM registration.
     * Combines device model with Android ID to prevent registration collisions
     * when multiple devices of the same model are registered.
     */
    fun getDeviceName(): String {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""
        // Format: "Model (xxxx)" where xxxx is last 4 chars of Android ID
        val suffix = if (androidId.length >= 4) androidId.takeLast(4) else androidId
        return "${Build.MODEL} ($suffix)"
    }

    private fun enqueueTokenRegistration(token: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<FcmTokenRegistrationWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .setInputData(workDataOf(
                FcmTokenRegistrationWorker.KEY_TOKEN to token,
                FcmTokenRegistrationWorker.KEY_DEVICE_NAME to getDeviceName()
            ))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Log.d(TAG, "Enqueued token registration work")
    }
}

/**
 * State of the FCM token.
 */
sealed class FcmTokenState {
    /** Initial state, haven't checked yet */
    data object Unknown : FcmTokenState()

    /** FCM is disabled (using foreground service instead) */
    data object Disabled : FcmTokenState()

    /** Firebase not configured (no server config) */
    data object NotConfigured : FcmTokenState()

    /** Loading/fetching token */
    data object Loading : FcmTokenState()

    /** Token available but not yet registered with server */
    data class Available(val token: String) : FcmTokenState()

    /** Token registered with server */
    data class Registered(val token: String) : FcmTokenState()

    /** Error getting token */
    data class Error(val message: String) : FcmTokenState()
}
