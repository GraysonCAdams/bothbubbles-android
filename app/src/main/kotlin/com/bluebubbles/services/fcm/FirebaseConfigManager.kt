package com.bluebubbles.services.fcm

import android.content.Context
import android.util.Log
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.data.remote.api.BlueBubblesApi
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages dynamic Firebase initialization with server-provided configuration.
 *
 * BlueBubbles does not use a static google-services.json file. Instead, the Firebase
 * configuration is fetched from the BlueBubbles server, allowing each user's server
 * to provide its own Firebase project credentials.
 */
@Singleton
class FirebaseConfigManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: BlueBubblesApi,
    private val settingsDataStore: SettingsDataStore,
    private val googleApiAvailability: GoogleApiAvailability
) {
    companion object {
        private const val TAG = "FirebaseConfigManager"
        private const val FIREBASE_APP_NAME = "bluebubbles"
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow<FirebaseConfigState>(FirebaseConfigState.NotInitialized)
    val state: StateFlow<FirebaseConfigState> = _state.asStateFlow()

    /**
     * Check if Google Play Services is available on this device.
     */
    fun isGooglePlayServicesAvailable(): Boolean {
        val result = googleApiAvailability.isGooglePlayServicesAvailable(context)
        return result == ConnectionResult.SUCCESS
    }

    /**
     * Get the error code if Google Play Services is not available.
     */
    fun getGooglePlayServicesErrorCode(): Int {
        return googleApiAvailability.isGooglePlayServicesAvailable(context)
    }

    /**
     * Check if Firebase has been initialized.
     */
    fun isInitialized(): Boolean {
        return try {
            FirebaseApp.getInstance(FIREBASE_APP_NAME)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    /**
     * Get the Firebase app instance, or null if not initialized.
     */
    fun getFirebaseApp(): FirebaseApp? {
        return try {
            FirebaseApp.getInstance(FIREBASE_APP_NAME)
        } catch (e: IllegalStateException) {
            null
        }
    }

    /**
     * Initialize Firebase from server configuration.
     * This fetches the FCM client config from the server and initializes Firebase.
     *
     * @return Result indicating success or failure
     */
    suspend fun initializeFromServer(): Result<Unit> = mutex.withLock {
        // Check if already initialized
        if (isInitialized()) {
            Log.d(TAG, "Firebase already initialized")
            _state.value = FirebaseConfigState.Initialized
            return@withLock Result.success(Unit)
        }

        // Check Google Play Services
        if (!isGooglePlayServicesAvailable()) {
            val errorCode = getGooglePlayServicesErrorCode()
            Log.w(TAG, "Google Play Services not available: $errorCode")
            _state.value = FirebaseConfigState.Error("Google Play Services not available")
            return@withLock Result.failure(Exception("Google Play Services not available (error: $errorCode)"))
        }

        _state.value = FirebaseConfigState.Initializing

        return@withLock try {
            // Try to use cached config first
            val cachedConfig = getCachedConfig()
            if (cachedConfig != null) {
                Log.d(TAG, "Using cached Firebase config")
                initializeFirebaseApp(cachedConfig)
                _state.value = FirebaseConfigState.Initialized
                return@withLock Result.success(Unit)
            }

            // Fetch config from server
            Log.d(TAG, "Fetching Firebase config from server")
            val response = api.getFcmClient()

            if (!response.isSuccessful) {
                val error = "Failed to fetch FCM config: ${response.code()}"
                Log.e(TAG, error)
                _state.value = FirebaseConfigState.Error(error)
                return@withLock Result.failure(Exception(error))
            }

            val fcmClient = response.body()?.data
            if (fcmClient == null) {
                val error = "FCM config response was empty"
                Log.e(TAG, error)
                _state.value = FirebaseConfigState.Error(error)
                return@withLock Result.failure(Exception(error))
            }

            // Validate required fields
            if (fcmClient.projectNumber.isNullOrBlank() ||
                fcmClient.appId.isNullOrBlank() ||
                fcmClient.apiKey.isNullOrBlank()
            ) {
                val error = "FCM config is incomplete (missing required fields)"
                Log.e(TAG, error)
                _state.value = FirebaseConfigState.Error(error)
                return@withLock Result.failure(Exception(error))
            }

            val config = FirebaseConfig(
                projectNumber = fcmClient.projectNumber,
                appId = fcmClient.appId,
                apiKey = fcmClient.apiKey,
                storageBucket = fcmClient.storageBucket ?: ""
            )

            // Save config for offline use
            settingsDataStore.setFirebaseConfig(
                projectNumber = config.projectNumber,
                appId = config.appId,
                apiKey = config.apiKey,
                storageBucket = config.storageBucket
            )

            // Initialize Firebase
            initializeFirebaseApp(config)
            _state.value = FirebaseConfigState.Initialized
            Log.i(TAG, "Firebase initialized successfully")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase", e)
            _state.value = FirebaseConfigState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Initialize Firebase from cached configuration (for app startup).
     * Returns immediately if config is not cached - use initializeFromServer() to fetch.
     */
    suspend fun initializeFromCache(): Boolean = mutex.withLock {
        if (isInitialized()) {
            _state.value = FirebaseConfigState.Initialized
            return@withLock true
        }

        if (!isGooglePlayServicesAvailable()) {
            _state.value = FirebaseConfigState.Error("Google Play Services not available")
            return@withLock false
        }

        val config = getCachedConfig()
        if (config == null) {
            Log.d(TAG, "No cached Firebase config available")
            return@withLock false
        }

        return@withLock try {
            initializeFirebaseApp(config)
            _state.value = FirebaseConfigState.Initialized
            Log.i(TAG, "Firebase initialized from cache")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase from cache", e)
            _state.value = FirebaseConfigState.Error(e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Reset Firebase configuration. Call this to re-fetch config from server.
     */
    suspend fun reset() = mutex.withLock {
        try {
            FirebaseApp.getInstance(FIREBASE_APP_NAME).delete()
        } catch (e: IllegalStateException) {
            // App not initialized, ignore
        }
        settingsDataStore.clearFirebaseConfig()
        _state.value = FirebaseConfigState.NotInitialized
        Log.d(TAG, "Firebase config reset")
    }

    private suspend fun getCachedConfig(): FirebaseConfig? {
        val projectNumber = settingsDataStore.firebaseProjectNumber.first()
        val appId = settingsDataStore.firebaseAppId.first()
        val apiKey = settingsDataStore.firebaseApiKey.first()
        val storageBucket = settingsDataStore.firebaseStorageBucket.first()

        if (projectNumber.isBlank() || appId.isBlank() || apiKey.isBlank()) {
            return null
        }

        return FirebaseConfig(
            projectNumber = projectNumber,
            appId = appId,
            apiKey = apiKey,
            storageBucket = storageBucket
        )
    }

    private fun initializeFirebaseApp(config: FirebaseConfig) {
        val options = FirebaseOptions.Builder()
            .setGcmSenderId(config.projectNumber)
            .setApplicationId(config.appId)
            .setApiKey(config.apiKey)
            .setStorageBucket(config.storageBucket)
            .build()

        FirebaseApp.initializeApp(context, options, FIREBASE_APP_NAME)
    }
}

/**
 * Firebase configuration data class.
 */
data class FirebaseConfig(
    val projectNumber: String,
    val appId: String,
    val apiKey: String,
    val storageBucket: String
)

/**
 * State of Firebase configuration and initialization.
 */
sealed class FirebaseConfigState {
    data object NotInitialized : FirebaseConfigState()
    data object Initializing : FirebaseConfigState()
    data object Initialized : FirebaseConfigState()
    data class Error(val message: String) : FirebaseConfigState()
}
