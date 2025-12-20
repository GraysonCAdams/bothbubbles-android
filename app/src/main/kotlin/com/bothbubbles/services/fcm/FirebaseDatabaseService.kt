package com.bothbubbles.services.fcm

import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.socket.SocketService
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to Firebase Database for dynamic server URL updates.
 *
 * When the BlueBubbles server's URL changes (e.g., due to dynamic DNS or new tunnel),
 * it writes the new URL to Firebase. This service listens for those changes and
 * automatically updates the local server address, then reconnects the socket.
 *
 * This solves the "server moved" problem where users would otherwise need to
 * manually enter the new server URL.
 *
 * Firebase Database paths:
 * - Realtime Database: /config/serverUrl
 * - Firestore (fallback): server/config.serverUrl
 */
@Singleton
class FirebaseDatabaseService @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val firebaseConfigManager: FirebaseConfigManager,
    private val socketService: Lazy<SocketService>,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "FirebaseDatabaseService"

        // Realtime Database path
        private const val REALTIME_DB_PATH = "config"
        private const val REALTIME_DB_FIELD = "serverUrl"

        // Firestore path (fallback when Realtime Database URL is not configured)
        private const val FIRESTORE_COLLECTION = "server"
        private const val FIRESTORE_DOCUMENT = "config"
        private const val FIRESTORE_FIELD = "serverUrl"
    }

    private val _state = MutableStateFlow<FirebaseDatabaseState>(FirebaseDatabaseState.NotStarted)
    val state: StateFlow<FirebaseDatabaseState> = _state.asStateFlow()

    private var realtimeDbListener: ValueEventListener? = null
    private var firestoreListener: ListenerRegistration? = null

    // Cache the database URL to avoid runBlocking in stopListening
    private var cachedDatabaseUrl: String? = null

    /**
     * Start listening for server URL changes.
     *
     * This should be called after Firebase has been initialized and setup is complete.
     * It will attempt to listen to Realtime Database first, falling back to Firestore
     * if no database URL is configured.
     */
    fun startListening() {
        applicationScope.launch(ioDispatcher) {
            try {
                // Check if setup is complete
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) {
                    Timber.d("Setup not complete, skipping Firebase Database listener")
                    _state.value = FirebaseDatabaseState.NotStarted
                    return@launch
                }

                // Check if Firebase is initialized
                if (!firebaseConfigManager.isInitialized()) {
                    Timber.d("Firebase not initialized, skipping Database listener")
                    _state.value = FirebaseDatabaseState.NotStarted
                    return@launch
                }

                val databaseUrl = settingsDataStore.firebaseDatabaseUrl.first()
                cachedDatabaseUrl = databaseUrl.takeIf { it.isNotBlank() }

                if (databaseUrl.isNotBlank()) {
                    // Use Realtime Database
                    startRealtimeDatabaseListener(databaseUrl)
                } else {
                    // Fall back to Firestore
                    startFirestoreListener()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error starting Firebase Database listener")
                _state.value = FirebaseDatabaseState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Stop listening for server URL changes.
     */
    fun stopListening() {
        // Remove Realtime Database listener
        realtimeDbListener?.let { listener ->
            try {
                // Use cached database URL to avoid blocking
                if (!cachedDatabaseUrl.isNullOrBlank()) {
                    val database = FirebaseDatabase.getInstance(cachedDatabaseUrl!!)
                    database.getReference(REALTIME_DB_PATH).removeEventListener(listener)
                }
            } catch (e: Exception) {
                Timber.w(e, "Error removing Realtime Database listener")
            }
            realtimeDbListener = null
        }

        // Remove Firestore listener
        firestoreListener?.remove()
        firestoreListener = null

        // Clear cached URL
        cachedDatabaseUrl = null

        _state.value = FirebaseDatabaseState.NotStarted
        Timber.d("Firebase Database listeners stopped")
    }

    /**
     * Manually fetch and update the server URL from Firebase Database.
     * Useful for one-time sync or when the listener may have missed updates.
     */
    suspend fun fetchServerUrl(): Result<String?> {
        return try {
            val databaseUrl = settingsDataStore.firebaseDatabaseUrl.first()

            if (databaseUrl.isNotBlank()) {
                fetchFromRealtimeDatabase(databaseUrl)
            } else {
                fetchFromFirestore()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching server URL from Firebase")
            Result.failure(e)
        }
    }

    private fun startRealtimeDatabaseListener(databaseUrl: String) {
        try {
            val database = FirebaseDatabase.getInstance(databaseUrl)
            val ref = database.getReference(REALTIME_DB_PATH)

            realtimeDbListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val serverUrl = snapshot.child(REALTIME_DB_FIELD).getValue(String::class.java)
                    if (serverUrl != null) {
                        Timber.i("Received server URL update from Realtime Database: ${serverUrl.take(30)}...")
                        handleServerUrlUpdate(serverUrl)
                    } else {
                        Timber.d("Realtime Database snapshot had no serverUrl field")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Timber.e(error.toException(), "Realtime Database listener cancelled: ${error.message}")
                    _state.value = FirebaseDatabaseState.Error(error.message)
                }
            }

            ref.addValueEventListener(realtimeDbListener!!)
            _state.value = FirebaseDatabaseState.Listening(ListenerType.REALTIME_DATABASE)
            Timber.i("Started Realtime Database listener at $REALTIME_DB_PATH")

        } catch (e: Exception) {
            Timber.e(e, "Error starting Realtime Database listener")
            _state.value = FirebaseDatabaseState.Error(e.message ?: "Failed to start listener")
        }
    }

    private fun startFirestoreListener() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val docRef = firestore.collection(FIRESTORE_COLLECTION).document(FIRESTORE_DOCUMENT)

            firestoreListener = docRef.addSnapshotListener(object : EventListener<DocumentSnapshot> {
                override fun onEvent(snapshot: DocumentSnapshot?, error: FirebaseFirestoreException?) {
                    if (error != null) {
                        Timber.e(error, "Firestore listener error: ${error.message}")
                        _state.value = FirebaseDatabaseState.Error(error.message ?: "Firestore error")
                        return
                    }

                    val serverUrl = snapshot?.getString(FIRESTORE_FIELD)
                    if (serverUrl != null) {
                        Timber.i("Received server URL update from Firestore: ${serverUrl.take(30)}...")
                        handleServerUrlUpdate(serverUrl)
                    } else {
                        Timber.d("Firestore document had no serverUrl field")
                    }
                }
            })

            _state.value = FirebaseDatabaseState.Listening(ListenerType.FIRESTORE)
            Timber.i("Started Firestore listener at $FIRESTORE_COLLECTION/$FIRESTORE_DOCUMENT")

        } catch (e: Exception) {
            Timber.e(e, "Error starting Firestore listener")
            _state.value = FirebaseDatabaseState.Error(e.message ?: "Failed to start Firestore listener")
        }
    }

    private suspend fun fetchFromRealtimeDatabase(databaseUrl: String): Result<String?> {
        return try {
            val database = FirebaseDatabase.getInstance(databaseUrl)
            val ref = database.getReference(REALTIME_DB_PATH).child(REALTIME_DB_FIELD)

            // Use a one-time read
            val snapshot = ref.get().await()
            val serverUrl = snapshot.getValue(String::class.java)

            if (serverUrl != null) {
                handleServerUrlUpdate(serverUrl)
            }

            Result.success(serverUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchFromFirestore(): Result<String?> {
        return try {
            val firestore = FirebaseFirestore.getInstance()
            val docRef = firestore.collection(FIRESTORE_COLLECTION).document(FIRESTORE_DOCUMENT)

            val document = docRef.get().await()
            val serverUrl = document.getString(FIRESTORE_FIELD)

            if (serverUrl != null) {
                handleServerUrlUpdate(serverUrl)
            }

            Result.success(serverUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun handleServerUrlUpdate(newServerUrl: String) {
        applicationScope.launch(ioDispatcher) {
            val sanitizedUrl = sanitizeServerAddress(newServerUrl)
            val currentUrl = settingsDataStore.serverAddress.first()

            if (sanitizedUrl != currentUrl && sanitizedUrl.isNotBlank()) {
                Timber.i("Server URL changed: $currentUrl -> $sanitizedUrl")

                // Update local settings
                settingsDataStore.setServerAddress(sanitizedUrl)

                // Trigger socket reconnect to use new URL
                Timber.i("Triggering socket reconnect for new server URL")
                socketService.get().reconnect()
            } else {
                Timber.d("Server URL unchanged or invalid, skipping update")
            }
        }
    }

    /**
     * Sanitize server address by ensuring proper URL format.
     * Adds https:// if no protocol specified and removes trailing slashes.
     */
    private fun sanitizeServerAddress(address: String): String {
        var sanitized = address.trim()

        // Add https:// if no protocol specified
        if (!sanitized.startsWith("http://") && !sanitized.startsWith("https://")) {
            sanitized = "https://$sanitized"
        }

        // Remove trailing slashes
        while (sanitized.endsWith("/")) {
            sanitized = sanitized.dropLast(1)
        }

        return sanitized
    }
}

/**
 * State of the Firebase Database listener.
 */
sealed class FirebaseDatabaseState {
    data object NotStarted : FirebaseDatabaseState()
    data class Listening(val type: ListenerType) : FirebaseDatabaseState()
    data class Error(val message: String) : FirebaseDatabaseState()
}

/**
 * Type of Firebase listener being used.
 */
enum class ListenerType {
    REALTIME_DATABASE,
    FIRESTORE
}
