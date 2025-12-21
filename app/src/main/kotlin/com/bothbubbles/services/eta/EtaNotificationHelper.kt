package com.bothbubbles.services.eta

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bothbubbles.MainActivity

/**
 * Shared helper for building and showing ETA sharing notifications.
 * Used by both NavigationListenerService and EtaSharingReceiver to avoid code duplication.
 */
object EtaNotificationHelper {

    /**
     * Show or update the "sharing active" notification with Android Auto support.
     */
    fun showSharingNotification(
        context: Context,
        recipientName: String,
        etaMinutes: Int
    ) {
        val stopIntent = Intent(EtaSharingReceiver.ACTION_STOP_SHARING).apply {
            setPackage(context.packageName)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val etaText = if (etaMinutes > 0) " (${formatEtaMinutes(etaMinutes)})" else ""
        val contentTitle = "Sharing ETA with $recipientName"
        val contentText = "You're sharing your arrival time$etaText"

        val notification = NotificationCompat.Builder(context, NavigationListenerService.CHANNEL_ETA_SHARING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Sharing",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Android Auto support
            .extend(
                NotificationCompat.CarExtender()
                    .setUnreadConversation(
                        NotificationCompat.CarExtender.UnreadConversation.Builder(contentTitle)
                            .addMessage(contentText)
                            .setLatestTimestamp(System.currentTimeMillis())
                            .setReplyAction(stopPendingIntent, null)
                            .build()
                    )
            )
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NavigationListenerService.NOTIFICATION_ID_ETA_SHARING, notification)

        // Cancel the prompt notification when sharing starts
        notificationManager.cancel(NavigationListenerService.NOTIFICATION_ID_ETA_PROMPT)
    }

    /**
     * Cancel the sharing notification.
     */
    fun cancelSharingNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NavigationListenerService.NOTIFICATION_ID_ETA_SHARING)
    }

    /**
     * Show the destination fetching notification when opening nav app to scrape destination.
     */
    fun showDestinationFetchNotification(context: Context, navAppName: String) {
        val notification = NotificationCompat.Builder(context, NavigationListenerService.CHANNEL_ETA_SHARING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Fetching destination details")
            .setContentText("Getting destination from $navAppName...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(false)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NavigationListenerService.NOTIFICATION_ID_DESTINATION_FETCH, notification)
    }

    /**
     * Cancel the destination fetch notification.
     */
    fun cancelDestinationFetchNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NavigationListenerService.NOTIFICATION_ID_DESTINATION_FETCH)
    }

    /**
     * Format ETA minutes for display.
     */
    fun formatEtaMinutes(minutes: Int): String {
        return when {
            minutes < 1 -> "Arriving"
            minutes < 60 -> "$minutes min"
            else -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
            }
        }
    }
}
