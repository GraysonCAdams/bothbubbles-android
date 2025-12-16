package com.bothbubbles.services.eta

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import androidx.core.app.NotificationCompat
import com.bothbubbles.MainActivity
import com.bothbubbles.data.local.db.dao.ChatQueryDao
import com.bothbubbles.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver for ETA sharing notification actions.
 * Handles:
 * - Starting ETA sharing from the "Navigation Detected" notification
 * - Stopping ETA sharing from the "Sharing Active" notification
 */
@AndroidEntryPoint
class EtaSharingReceiver : BroadcastReceiver() {

    companion object {
        // Actions
        const val ACTION_START_SHARING = "com.bothbubbles.action.START_ETA_SHARING"
        const val ACTION_STOP_SHARING = "com.bothbubbles.action.STOP_ETA_SHARING"

        // Extras
        const val EXTRA_CHAT_GUID = "chat_guid"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EtaSharingReceiverEntryPoint {
        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    @Inject
    lateinit var etaSharingManager: EtaSharingManager

    @Inject
    lateinit var chatQueryDao: ChatQueryDao

    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("Received action: ${intent.action}")

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            EtaSharingReceiverEntryPoint::class.java
        )
        val scope = entryPoint.applicationScope()

        when (intent.action) {
            ACTION_START_SHARING -> handleStartSharing(context, intent, scope)
            ACTION_STOP_SHARING -> handleStopSharing(context)
            NavigationListenerService.ACTION_STOP_SHARING -> handleStopSharing(context) // Legacy action
        }
    }

    private fun handleStartSharing(context: Context, intent: Intent, scope: CoroutineScope) {
        val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID)
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)

        if (chatGuid != null && displayName != null) {
            // Start sharing with the specified chat
            Timber.d("Starting ETA sharing with $displayName (guid: $chatGuid)")
            val currentEta = etaSharingManager.state.value.currentEta
            etaSharingManager.startSharing(chatGuid, displayName, currentEta)
            showSharingNotification(context, displayName, currentEta?.etaMinutes ?: 0)
        } else {
            // Try to use recent chat as fallback
            scope.launch {
                val recentChat = chatQueryDao.getRecentChats(1).firstOrNull()
                if (recentChat != null) {
                    val name = recentChat.displayName ?: recentChat.chatIdentifier ?: "Unknown"
                    Timber.d("Starting ETA sharing with recent chat: $name")
                    val currentEta = etaSharingManager.state.value.currentEta
                    etaSharingManager.startSharing(recentChat.guid, name, currentEta)
                    showSharingNotification(context, name, currentEta?.etaMinutes ?: 0)
                } else {
                    Timber.w("No chat available for ETA sharing")
                }
            }
        }
    }

    private fun handleStopSharing(context: Context) {
        Timber.d("Stopping ETA sharing")
        etaSharingManager.stopSharing(sendFinalMessage = true)
        cancelSharingNotification(context)
    }

    /**
     * Show the "sharing active" notification
     */
    private fun showSharingNotification(context: Context, recipientName: String, etaMinutes: Int) {
        val stopIntent = Intent(ACTION_STOP_SHARING).apply {
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

    private fun cancelSharingNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NavigationListenerService.NOTIFICATION_ID_ETA_SHARING)
    }

    private fun formatEtaMinutes(minutes: Int): String {
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
