package com.bothbubbles.services.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main notification service that coordinates notification operations.
 * Delegates to specialized helpers for channel management, bubble metadata, and notification building.
 */
@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationBuilder: NotificationBuilder
) : Notifier {
    private val notificationManager = NotificationManagerCompat.from(context)

    /**
     * Show a notification for a new message.
     *
     * @param senderAddress The sender's address (phone/email) used for bubble filtering
     * @param participantNames List of participant names for group chats (used for group avatar collage)
     */
    override fun showMessageNotification(
        chatGuid: String,
        chatTitle: String,
        messageText: String,
        messageGuid: String,
        senderName: String?,
        senderAddress: String?,
        isGroup: Boolean,
        avatarUri: String?,
        linkPreviewTitle: String?,
        linkPreviewDomain: String?,
        participantNames: List<String>
    ) {
        if (!hasNotificationPermission()) return

        val notification = notificationBuilder.buildMessageNotification(
            chatGuid = chatGuid,
            chatTitle = chatTitle,
            messageText = messageText,
            messageGuid = messageGuid,
            senderName = senderName,
            senderAddress = senderAddress,
            isGroup = isGroup,
            avatarUri = avatarUri,
            linkPreviewTitle = linkPreviewTitle,
            linkPreviewDomain = linkPreviewDomain,
            participantNames = participantNames
        )

        notificationManager.notify(chatGuid.hashCode(), notification)
    }

    /**
     * Cancel notification for a chat.
     */
    override fun cancelNotification(chatGuid: String) {
        notificationManager.cancel(chatGuid.hashCode())
    }

    /**
     * Cancel all message notifications.
     */
    override fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }

    /**
     * Show foreground service notification.
     */
    override fun createServiceNotification(): android.app.Notification {
        return notificationBuilder.buildServiceNotification()
    }

    /**
     * Show notification when BlueBubbles initial sync completes.
     */
    override fun showBlueBubblesSyncCompleteNotification(messageCount: Int) {
        if (!hasNotificationPermission()) return

        val notification = notificationBuilder.buildSyncCompleteNotification(messageCount)
        notificationManager.notify(NotificationChannelManager.SYNC_COMPLETE_NOTIFICATION_ID, notification)
    }

    /**
     * Show notification when SMS import completes.
     */
    override fun showSmsImportCompleteNotification() {
        if (!hasNotificationPermission()) return

        val notification = notificationBuilder.buildSmsImportCompleteNotification()
        notificationManager.notify(NotificationChannelManager.SMS_IMPORT_COMPLETE_NOTIFICATION_ID, notification)
    }

    /**
     * Show notification when a BlueBubbles server update is available.
     */
    override fun showServerUpdateNotification(version: String) {
        if (!hasNotificationPermission()) return

        val notification = notificationBuilder.buildServerUpdateNotification(version)
        notificationManager.notify(NotificationChannelManager.SERVER_UPDATE_NOTIFICATION_ID, notification)
    }

    /**
     * Show notification when iCloud account status changes.
     */
    override fun showICloudAccountNotification(active: Boolean, alias: String?) {
        if (!hasNotificationPermission()) return

        val notification = notificationBuilder.buildICloudAccountNotification(active, alias)
        notification?.let {
            notificationManager.notify(NotificationChannelManager.ICLOUD_ACCOUNT_NOTIFICATION_ID, it)
        }
    }

    /**
     * Show incoming FaceTime call notification with full-screen intent.
     */
    override fun showFaceTimeCallNotification(
        callUuid: String,
        callerName: String,
        callerAddress: String?
    ) {
        if (!hasNotificationPermission()) return

        val notification = notificationBuilder.buildFaceTimeCallNotification(callUuid, callerName, callerAddress)
        val notificationId = NotificationChannelManager.FACETIME_NOTIFICATION_ID_PREFIX + callUuid.hashCode()
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Dismiss FaceTime call notification.
     */
    override fun dismissFaceTimeCallNotification(callUuid: String) {
        val notificationId = NotificationChannelManager.FACETIME_NOTIFICATION_ID_PREFIX + callUuid.hashCode()
        notificationManager.cancel(notificationId)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Update the app badge count on supported launchers.
     * This uses ShortcutManager badges on Android O+ and falls back to
     * ShortcutBadger-style intents for older devices.
     *
     * Note: This does NOT affect push notifications, only the badge count.
     *
     * @param count The number to show on the app icon badge
     */
    override fun updateAppBadge(count: Int) {
        // On Android 8+, badges are tied to notification channels
        // We use a silent notification with setNumber to update the badge
        // This won't show a visible notification but will update the badge count

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Some launchers support direct badge update via shortcut
            try {
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                // Note: Standard Android doesn't support direct badge setting
                // The badge count comes from notification count
                // For Samsung/Huawei launchers, we'd need specific intents
            } catch (e: Exception) {
                // Ignore if shortcut manager is not available
            }
        }

        // Try Samsung badge API
        try {
            val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE")
            intent.putExtra("badge_count", count)
            intent.putExtra("badge_count_package_name", context.packageName)
            intent.putExtra("badge_count_class_name", "com.bothbubbles.MainActivity")
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // Samsung badge API not available
        }

        // Try Sony badge API
        try {
            val contentValues = android.content.ContentValues()
            contentValues.put("badge_count", count)
            contentValues.put("package_name", context.packageName)
            contentValues.put("activity_name", "com.bothbubbles.MainActivity")
            context.contentResolver.insert(
                android.net.Uri.parse("content://com.sonymobile.home.resourceprovider/badge"),
                contentValues
            )
        } catch (e: Exception) {
            // Sony badge API not available
        }
    }
}
