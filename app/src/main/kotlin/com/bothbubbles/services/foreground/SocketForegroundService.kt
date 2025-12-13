package com.bothbubbles.services.foreground

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bothbubbles.MainActivity
import com.bothbubbles.R
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.notifications.NotificationChannelManager
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that maintains a persistent Socket.IO connection.
 *
 * This is an alternative to FCM push notifications for devices without
 * Google Play Services or for users who prefer to keep the app alive
 * in the background.
 *
 * Features:
 * - Persistent notification showing connection status
 * - START_STICKY for automatic restart
 * - Maintains socket connection even when app is in background
 */
@AndroidEntryPoint
class SocketForegroundService : Service() {

    companion object {
        private const val TAG = "SocketForegroundService"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_STOP = "com.bothbubbles.action.STOP_SERVICE"

        /**
         * Start the foreground service.
         */
        fun start(context: Context) {
            val intent = Intent(context, SocketForegroundService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, SocketForegroundService::class.java)
            context.stopService(intent)
        }
    }

    @Inject
    lateinit var socketService: SocketService

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    // Service-scoped job for notification updates - cancelled when service is destroyed
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping service via notification action")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Start foreground with initial notification
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))

        // Cancel any existing job before starting a new one (prevents duplicates on restart)
        notificationJob?.cancel()

        // Connect socket and observe status
        // Store job so we can cancel it when service is destroyed
        notificationJob = applicationScope.launch(ioDispatcher) {
            try {
                // Connect to socket
                socketService.connect()

                // Observe connection status and update notification
                socketService.connectionState.collectLatest { state ->
                    val statusText = when (state) {
                        ConnectionState.CONNECTED -> "Connected"
                        ConnectionState.CONNECTING -> "Connecting..."
                        ConnectionState.DISCONNECTED -> "Disconnected"
                        ConnectionState.ERROR -> "Error"
                        ConnectionState.NOT_CONFIGURED -> "Not configured"
                    }
                    updateNotification(statusText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in socket connection", e)
                updateNotification("Error: ${e.message}")
            }
        }

        // START_STICKY ensures service restarts if killed
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        // Cancel the notification update job - prevents zombie collectors
        notificationJob?.cancel()
        notificationJob = null
        // Don't disconnect socket here - let SocketConnectionManager handle it
        // Don't cancel applicationScope - it's application-wide
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(statusText: String): Notification {
        // Intent to open main activity
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop service
        val stopIntent = Intent(this, SocketForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationChannelManager.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notification = createNotification(statusText)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
