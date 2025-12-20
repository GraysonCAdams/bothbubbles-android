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
    private val notificationBuilder: NotificationBuilder,
    private val badgeManager: BadgeManager
) : Notifier {
    private val notificationManager = NotificationManagerCompat.from(context)

    /**
     * Show a notification for a new message.
     *
     * @param senderAddress The sender's address (phone/email) used for bubble filtering
     * @param participantNames List of participant names for group chats (used for group avatar collage)
     * @param participantAvatarPaths List of avatar paths for group participants (corresponding to participantNames)
     * @param subject Optional message subject. When present, shows ONLY the subject (not the body).
     * @param attachmentUri Optional content:// URI to an attachment image/video for inline preview
     * @param attachmentMimeType MIME type of the attachment (required if attachmentUri is provided)
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
        participantNames: List<String>,
        participantAvatarPaths: List<String?>,
        subject: String?,
        attachmentUri: android.net.Uri?,
        attachmentMimeType: String?
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
            participantNames = participantNames,
            participantAvatarPaths = participantAvatarPaths,
            subject = subject,
            totalUnreadCount = badgeManager.totalUnread.value,
            attachmentUri = attachmentUri,
            attachmentMimeType = attachmentMimeType
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
