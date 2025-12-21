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
 *
 * Uses per-conversation notification channels, allowing users to customize
 * sound, vibration, and importance for individual conversations via Android Settings.
 */
@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationBuilder: NotificationBuilder,
    private val notificationChannelManager: NotificationChannelManager,
    private val badgeManager: BadgeManager
) : Notifier {
    private val notificationManager = NotificationManagerCompat.from(context)

    // Track active notification IDs per chat for proper cancellation when stacking
    // Maps chatGuid -> Set of notification IDs
    private val activeNotificationIds = mutableMapOf<String, MutableSet<Int>>()

    /**
     * Show a notification for a new message.
     *
     * Creates a per-conversation notification channel if one doesn't exist,
     * allowing users to customize notification settings per conversation.
     *
     * Uses unique notification IDs per message to enable stacking (multiple
     * notifications per chat that don't replace each other).
     */
    override fun showMessageNotification(params: MessageNotificationParams) {
        if (!hasNotificationPermission()) return

        // Get or create per-conversation channel
        val channelId = notificationChannelManager.getOrCreateConversationChannel(
            chatGuid = params.chatGuid,
            chatTitle = params.chatTitle
        )

        val notification = notificationBuilder.buildMessageNotification(
            channelId = channelId,
            chatGuid = params.chatGuid,
            chatTitle = params.chatTitle,
            messageText = params.messageText,
            messageGuid = params.messageGuid,
            senderName = params.senderName,
            senderAddress = params.senderAddress,
            isGroup = params.isGroup,
            avatarUri = params.avatarUri,
            linkPreviewTitle = params.linkPreviewTitle,
            linkPreviewDomain = params.linkPreviewDomain,
            participantNames = params.participantNames,
            participantAvatarPaths = params.participantAvatarPaths,
            subject = params.subject,
            totalUnreadCount = badgeManager.totalUnread.value,
            attachmentUri = params.attachmentUri,
            attachmentMimeType = params.attachmentMimeType
        )

        // Use messageGuid for unique notification ID (enables stacking)
        val notificationId = params.messageGuid.hashCode()

        // Track this notification ID for the chat
        synchronized(activeNotificationIds) {
            activeNotificationIds.getOrPut(params.chatGuid) { mutableSetOf() }.add(notificationId)
        }

        notificationManager.notify(notificationId, notification)
    }

    /**
     * Cancel all notifications for a chat.
     * Cancels all stacked notifications that belong to this chat.
     */
    override fun cancelNotification(chatGuid: String) {
        synchronized(activeNotificationIds) {
            val ids = activeNotificationIds.remove(chatGuid)
            ids?.forEach { notificationId ->
                notificationManager.cancel(notificationId)
            }
        }
    }

    /**
     * Cancel all message notifications.
     */
    override fun cancelAllNotifications() {
        synchronized(activeNotificationIds) {
            activeNotificationIds.clear()
        }
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

    /**
     * Show notification when a message fails to deliver.
     * Uses a unique notification ID based on chat + timestamp so failures stack.
     */
    override fun showMessageFailedNotification(
        chatGuid: String,
        chatTitle: String,
        messagePreview: String?,
        errorMessage: String
    ) {
        if (!hasNotificationPermission()) return

        // Get or create per-conversation channel
        val channelId = notificationChannelManager.getOrCreateConversationChannel(
            chatGuid = chatGuid,
            chatTitle = chatTitle
        )

        val notification = notificationBuilder.buildMessageFailedNotification(
            channelId = channelId,
            chatGuid = chatGuid,
            chatTitle = chatTitle,
            messagePreview = messagePreview,
            errorMessage = errorMessage
        )

        // Use unique ID based on chat + timestamp so failures stack
        val notificationId = ("failed-$chatGuid-${System.currentTimeMillis()}").hashCode()
        notificationManager.notify(notificationId, notification)
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
     * Update the app badge count.
     *
     * On Android 8.0+, badge counts are derived from notifications.
     * This method posts a group summary notification with setNumber(count)
     * which is the most reliable cross-device way to set badge counts.
     *
     * Also sends manufacturer-specific broadcasts for Samsung/Sony devices
     * as a fallback for launchers that support direct badge APIs.
     *
     * @param count The total unread message count to display on the app badge
     */
    override fun updateAppBadge(count: Int) {
        // Primary approach: Use a group summary notification with setNumber()
        // This works on most Android 8.0+ launchers (Pixel, OnePlus, Xiaomi, etc.)
        if (hasNotificationPermission()) {
            val summaryNotification = notificationBuilder.buildBadgeSummaryNotification(count)
            if (summaryNotification != null) {
                // Post the summary notification to update badge
                notificationManager.notify(
                    NotificationChannelManager.SUMMARY_NOTIFICATION_ID,
                    summaryNotification
                )
            } else {
                // Count is 0, cancel the summary notification to clear badge
                notificationManager.cancel(NotificationChannelManager.SUMMARY_NOTIFICATION_ID)
            }
        }

        // Fallback: Try manufacturer-specific badge APIs
        // These work on Samsung OneUI and Sony launchers even without notifications

        // Samsung badge API
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            try {
                val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE")
                intent.putExtra("badge_count", count)
                intent.putExtra("badge_count_package_name", context.packageName)
                intent.putExtra("badge_count_class_name", "com.bothbubbles.MainActivity")
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                // Samsung badge API not available
            }
        }

        // Sony badge API
        if (Build.MANUFACTURER.equals("sony", ignoreCase = true)) {
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
}
