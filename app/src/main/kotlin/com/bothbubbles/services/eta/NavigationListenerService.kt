package com.bothbubbles.services.eta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bothbubbles.MainActivity
import com.bothbubbles.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * NotificationListenerService that monitors navigation app notifications (Google Maps, Waze)
 * to extract ETA data for sharing with contacts.
 *
 * This service runs when notification access is granted and listens for navigation
 * notifications even when Android Auto is active.
 */
@AndroidEntryPoint
class NavigationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NavigationListenerSvc"
        const val CHANNEL_ETA_SHARING = "eta_sharing"
        const val NOTIFICATION_ID_ETA_SHARING = 3000001

        const val ACTION_STOP_SHARING = "com.bothbubbles.action.STOP_ETA_SHARING"
    }

    @Inject
    lateinit var etaParser: NavigationEtaParser

    @Inject
    lateinit var etaSharingManager: EtaSharingManager

    // Track which navigation notifications we're monitoring
    private val activeNavigationNotifications = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NavigationListenerService created")
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NavigationListenerService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")

        // Check for any existing navigation notifications
        try {
            activeNotifications?.forEach { sbn ->
                checkNavigationNotification(sbn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking existing notifications", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        checkNavigationNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key

        if (activeNavigationNotifications.contains(key)) {
            activeNavigationNotifications.remove(key)
            Log.d(TAG, "Navigation notification removed: $key")

            // If no more navigation notifications, navigation has stopped
            if (activeNavigationNotifications.isEmpty()) {
                etaSharingManager.onNavigationStopped()
                cancelSharingNotification()
            }
        }
    }

    /**
     * Check if this is a navigation notification and process it
     */
    private fun checkNavigationNotification(sbn: StatusBarNotification) {
        val app = NavigationApp.fromPackage(sbn.packageName) ?: return

        // Skip non-navigation categories (Maps has many notification types)
        val category = sbn.notification?.category
        if (app == NavigationApp.GOOGLE_MAPS && category != Notification.CATEGORY_NAVIGATION) {
            return
        }

        Log.d(TAG, "Navigation notification from ${app.name}: ${sbn.key}")
        activeNavigationNotifications.add(sbn.key)

        // Parse ETA data
        val etaData = etaParser.parse(sbn)
        if (etaData != null) {
            Log.d(TAG, "Parsed ETA: ${etaData.etaMinutes} min to ${etaData.destination}")
            etaSharingManager.onEtaUpdate(etaData)
            updateSharingNotificationIfActive(etaData)
        } else {
            Log.d(TAG, "Could not parse ETA from notification")
        }
    }

    /**
     * Create notification channel for ETA sharing status
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ETA_SHARING,
            "ETA Sharing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when you're sharing your ETA with someone"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Show or update the "sharing active" notification
     */
    fun showSharingNotification(recipientName: String, etaMinutes: Int) {
        val stopIntent = Intent(ACTION_STOP_SHARING).apply {
            setPackage(packageName)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val etaText = if (etaMinutes > 0) " ($etaMinutes min away)" else ""
        val notification = NotificationCompat.Builder(this, CHANNEL_ETA_SHARING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing ETA with $recipientName")
            .setContentText("You're sharing your arrival time$etaText")
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Sharing",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_ETA_SHARING, notification)
    }

    /**
     * Update sharing notification if currently sharing
     */
    private fun updateSharingNotificationIfActive(etaData: ParsedEtaData) {
        val session = etaSharingManager.state.value.session ?: return
        showSharingNotification(session.recipientDisplayName, etaData.etaMinutes)
    }

    /**
     * Cancel the sharing notification
     */
    fun cancelSharingNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID_ETA_SHARING)
    }
}
