package com.bothbubbles.services.socket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bothbubbles.data.local.prefs.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Socket.IO connection lifecycle.
 * Handles automatic connection on app startup, foreground/background transitions,
 * and network connectivity changes.
 */
@Singleton
class SocketConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socketService: SocketService,
    private val socketEventHandler: SocketEventHandler,
    private val settingsDataStore: SettingsDataStore
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "SocketConnectionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isInitialized = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Initialize the connection manager.
     * Should be called once from Application.onCreate()
     */
    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        isInitialized = true

        Log.d(TAG, "Initializing SocketConnectionManager")

        // Register for app lifecycle events (foreground/background)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Register for network connectivity changes
        registerNetworkCallback()

        // Start listening for socket events (idempotent)
        socketEventHandler.startListening()

        // Attempt initial connection
        attemptConnection()
    }

    override fun onStart(owner: LifecycleOwner) {
        // App came to foreground
        Log.d(TAG, "App foregrounded - attempting connection")
        attemptConnection()
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background
        // Check if foreground service mode is active - if so, let the service manage the socket
        scope.launch {
            val provider = try {
                settingsDataStore.notificationProvider.first()
            } catch (e: Exception) {
                "fcm" // Default to FCM mode if we can't read settings
            }

            if (provider == "foreground") {
                // Foreground service mode - let SocketForegroundService manage the socket
                Log.d(TAG, "App backgrounded - foreground service mode active, keeping socket connected")
            } else {
                // FCM mode - disconnect socket to save battery
                Log.d(TAG, "App backgrounded - FCM mode, disconnecting socket")
                socketService.disconnect()
            }
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available - attempting connection")
                attemptConnection()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                // Socket.IO will handle reconnection internally when network returns
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun attemptConnection() {
        scope.launch {
            // Check if server is configured
            if (!socketService.isServerConfigured()) {
                Log.d(TAG, "Server not configured - skipping connection")
                return@launch
            }

            // Check if already connected
            if (socketService.isConnected()) {
                Log.d(TAG, "Already connected - skipping")
                return@launch
            }

            Log.d(TAG, "Initiating socket connection")
            socketService.connect()
        }
    }

    /**
     * Call this when server settings change to trigger reconnection
     */
    fun onServerConfigurationChanged() {
        Log.d(TAG, "Server configuration changed - reconnecting")
        socketService.reconnect()
    }
}
