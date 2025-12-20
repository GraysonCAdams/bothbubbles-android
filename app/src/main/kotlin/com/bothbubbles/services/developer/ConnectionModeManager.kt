package com.bothbubbles.services.developer

import android.app.NotificationManager
import android.content.Context
import timber.log.Timber
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bothbubbles.BothBubblesApp
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.fcm.FcmTokenManager
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.MainDispatcher
import com.bothbubbles.services.fcm.FirebaseConfigManager
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The current connection mode
 */
enum class ConnectionMode {
    /** Using Socket.IO for real-time events (app in foreground) */
    SOCKET,
    /** Using FCM for push notifications (app in background) */
    FCM,
    /** Not connected (server not configured or error) */
    DISCONNECTED
}

/**
 * Manages automatic switching between Socket.IO (foreground) and FCM (background).
 *
 * Strategy:
 * - When app is in foreground: Use Socket.IO for real-time, low-latency events
 * - When app goes to background: Disconnect socket, rely on FCM for push notifications
 * - FCM is always registered regardless of mode (for wake-up purposes)
 */
@Singleton
class ConnectionModeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socketService: SocketService,
    private val fcmTokenManager: FcmTokenManager,
    private val firebaseConfigManager: FirebaseConfigManager,
    private val developerEventLog: DeveloperEventLog,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "ConnectionModeManager"
        // Delay before disconnecting socket when backgrounding (allows for quick app switches)
        private const val BACKGROUND_DISCONNECT_DELAY_MS = 5000L
        private const val FCM_STATUS_NOTIFICATION_ID = 9999
    }

    private var isInitialized = false
    private var backgroundDisconnectJob: kotlinx.coroutines.Job? = null
    private var socketStateObserverJob: kotlinx.coroutines.Job? = null

    private val _currentMode = MutableStateFlow(ConnectionMode.DISCONNECTED)
    val currentMode: StateFlow<ConnectionMode> = _currentMode.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    /**
     * Initialize the connection mode manager.
     * Should be called once from Application.onCreate()
     */
    fun initialize() {
        if (isInitialized) {
            Timber.d("Already initialized")
            return
        }
        isInitialized = true
        Timber.d("Initializing ConnectionModeManager")

        // Register for app lifecycle events
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Observe socket connection state
        socketStateObserverJob = applicationScope.launch(mainDispatcher) {
            socketService.connectionState.collect { state ->
                updateMode(state)
                developerEventLog.logConnectionChange(
                    source = EventSource.SOCKET,
                    state = state.name,
                    details = when (state) {
                        ConnectionState.ERROR -> "Will retry..."
                        ConnectionState.CONNECTED -> "Real-time events active"
                        ConnectionState.DISCONNECTED -> "Socket closed"
                        else -> null
                    }
                )
            }
        }

        // Ensure FCM is always initialized (for background wake-up)
        initializeFcm()
    }

    private fun initializeFcm() {
        applicationScope.launch(mainDispatcher) {
            try {
                if (!firebaseConfigManager.isInitialized()) {
                    Timber.d("Initializing Firebase for FCM")
                    firebaseConfigManager.initializeFromServer()
                }
                fcmTokenManager.initialize()
                developerEventLog.logFcmEvent("INIT", "FCM initialized for background notifications")
            } catch (e: Exception) {
                Timber.w(e, "Failed to initialize FCM")
                developerEventLog.logFcmEvent("INIT_ERROR", e.message)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // App came to foreground
        Timber.d("App foregrounded - switching to Socket mode")
        _isAppInForeground.value = true

        // Cancel any pending background disconnect
        backgroundDisconnectJob?.cancel()
        backgroundDisconnectJob = null

        // Connect socket for real-time events
        applicationScope.launch(mainDispatcher) {
            if (socketService.isServerConfigured()) {
                socketService.connect()
                developerEventLog.logConnectionChange(
                    source = EventSource.SOCKET,
                    state = "CONNECTING",
                    details = "App foregrounded"
                )
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background
        Timber.d("onStop: App backgrounded - scheduling switch to FCM mode in ${BACKGROUND_DISCONNECT_DELAY_MS}ms")
        _isAppInForeground.value = false

        // Delay disconnect to handle quick app switches
        backgroundDisconnectJob?.cancel()
        backgroundDisconnectJob = applicationScope.launch(mainDispatcher) {
            Timber.d("onStop: Waiting ${BACKGROUND_DISCONNECT_DELAY_MS}ms before switching to FCM...")
            delay(BACKGROUND_DISCONNECT_DELAY_MS)

            Timber.d("onStop: Delay complete, disconnecting socket and switching to FCM mode")
            socketService.disconnect()
            _currentMode.value = ConnectionMode.FCM
            developerEventLog.logConnectionChange(
                source = EventSource.FCM,
                state = "ACTIVE",
                details = "App backgrounded, using FCM for notifications"
            )
        }
    }

    /**
     * Show a notification when FCM mode is activated (developer mode only)
     */
    private suspend fun showFcmStatusNotification(success: Boolean) {
        val developerMode = settingsDataStore.developerModeEnabled.first()
        Timber.d("showFcmStatusNotification: success=$success, developerMode=$developerMode")

        // Only show in developer mode
        if (!developerMode) {
            Timber.d("Developer mode not enabled, skipping notification")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, BothBubblesApp.CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(if (success) "FCM Mode Active" else "FCM Mode Failed")
            .setContentText(
                if (success) "Background push notifications enabled"
                else "Failed to switch to FCM mode"
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        Timber.d("Posting FCM status notification")
        notificationManager.notify(FCM_STATUS_NOTIFICATION_ID, notification)
    }

    private fun updateMode(socketState: ConnectionState) {
        _currentMode.value = when {
            socketState == ConnectionState.CONNECTED -> ConnectionMode.SOCKET
            socketState == ConnectionState.NOT_CONFIGURED -> ConnectionMode.DISCONNECTED
            !_isAppInForeground.value -> ConnectionMode.FCM
            else -> ConnectionMode.DISCONNECTED
        }
    }

    /**
     * Force reconnect (e.g., after server config change)
     */
    fun forceReconnect() {
        Timber.d("Force reconnecting")
        if (_isAppInForeground.value) {
            socketService.reconnect()
        }
        // Re-register FCM in case config changed
        initializeFcm()
    }

    /**
     * Get a human-readable description of the current mode
     */
    fun getModeDescription(): String {
        return when (_currentMode.value) {
            ConnectionMode.SOCKET -> "Socket (real-time)"
            ConnectionMode.FCM -> "FCM (push)"
            ConnectionMode.DISCONNECTED -> "Disconnected"
        }
    }

    /**
     * Cleanup method for testing - cancels all active collectors.
     * Should only be called in test scenarios.
     */
    fun cleanup() {
        socketStateObserverJob?.cancel()
        backgroundDisconnectJob?.cancel()
        socketStateObserverJob = null
        backgroundDisconnectJob = null
    }
}
