package com.bothbubbles.services.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.bothbubbles.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages notification channels and related constants.
 * Responsible for creating and maintaining notification channels.
 */
@Singleton
class NotificationChannelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Channel IDs
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_FACETIME = "facetime"
        const val CHANNEL_SYNC_STATUS = "sync_status"

        // Actions
        const val ACTION_REPLY = "com.bothbubbles.action.REPLY"
        const val ACTION_MARK_READ = "com.bothbubbles.action.MARK_READ"
        const val ACTION_COPY_CODE = "com.bothbubbles.action.COPY_CODE"
        const val ACTION_ANSWER_FACETIME = "com.bothbubbles.action.ANSWER_FACETIME"
        const val ACTION_DECLINE_FACETIME = "com.bothbubbles.action.DECLINE_FACETIME"

        // Intent extras
        const val EXTRA_CHAT_GUID = "chat_guid"
        const val EXTRA_MESSAGE_GUID = "message_guid"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_CODE_TO_COPY = "code_to_copy"
        const val EXTRA_CALL_UUID = "call_uuid"

        // Notification IDs
        const val GROUP_MESSAGES = "messages_group"
        const val FACETIME_NOTIFICATION_ID_PREFIX = 1000000
        const val SYNC_COMPLETE_NOTIFICATION_ID = 2000001
        const val SMS_IMPORT_COMPLETE_NOTIFICATION_ID = 2000002
        const val SERVER_UPDATE_NOTIFICATION_ID = 2000003
        const val ICLOUD_ACCOUNT_NOTIFICATION_ID = 2000004
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_messages_desc)
            enableVibration(true)
            enableLights(true)
            // Enable conversation bubbles for this channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(true)
            }
        }

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_service_desc)
            setShowBadge(false)
        }

        val faceTimeChannel = NotificationChannel(
            CHANNEL_FACETIME,
            "FaceTime Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming FaceTime call notifications"
            enableVibration(true)
            enableLights(true)
        }

        val syncStatusChannel = NotificationChannel(
            CHANNEL_SYNC_STATUS,
            "Sync Status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about message import and sync completion"
            setShowBadge(false)
        }

        notificationManager.createNotificationChannels(
            listOf(messagesChannel, serviceChannel, faceTimeChannel, syncStatusChannel)
        )
    }
}
