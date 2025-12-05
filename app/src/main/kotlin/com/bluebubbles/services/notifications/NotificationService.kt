package com.bluebubbles.services.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.bluebubbles.MainActivity
import com.bluebubbles.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_FACETIME = "facetime"

        const val ACTION_REPLY = "com.bluebubbles.action.REPLY"
        const val ACTION_MARK_READ = "com.bluebubbles.action.MARK_READ"
        const val ACTION_ANSWER_FACETIME = "com.bluebubbles.action.ANSWER_FACETIME"
        const val ACTION_DECLINE_FACETIME = "com.bluebubbles.action.DECLINE_FACETIME"

        const val EXTRA_CHAT_GUID = "chat_guid"
        const val EXTRA_MESSAGE_GUID = "message_guid"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_CALL_UUID = "call_uuid"

        private const val GROUP_MESSAGES = "messages_group"
        private const val FACETIME_NOTIFICATION_ID_PREFIX = 1000000
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

        notificationManager.createNotificationChannels(listOf(messagesChannel, serviceChannel, faceTimeChannel))
    }

    /**
     * Show a notification for a new message
     */
    fun showMessageNotification(
        chatGuid: String,
        chatTitle: String,
        messageText: String,
        messageGuid: String,
        senderName: String?,
        isGroup: Boolean = false,
        avatarUri: String? = null
    ) {
        if (!hasNotificationPermission()) return

        val notificationId = chatGuid.hashCode()

        // Create intent to open the chat
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_GUID, chatGuid)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Reply action
        val replyRemoteInput = RemoteInput.Builder(EXTRA_REPLY_TEXT)
            .setLabel(context.getString(R.string.message_placeholder))
            .build()

        val replyIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_CHAT_GUID, chatGuid)
            putExtra(EXTRA_MESSAGE_GUID, messageGuid)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(R.string.action_reply),
            replyPendingIntent
        )
            .addRemoteInput(replyRemoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // Mark as read action
        val markReadIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_CHAT_GUID, chatGuid)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create messaging style for conversation-like appearance
        val sender = Person.Builder()
            .setName(senderName ?: chatTitle)
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(
            Person.Builder().setName("Me").build()
        )
            .setConversationTitle(if (isGroup) chatTitle else null)
            .addMessage(messageText, System.currentTimeMillis(), sender)

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(chatTitle)
            .setContentText(messageText)
            .setStyle(messagingStyle)
            .setContentIntent(contentPendingIntent)
            .addAction(replyAction)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Mark as Read",
                markReadPendingIntent
            )
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_MESSAGES)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    /**
     * Cancel notification for a chat
     */
    fun cancelNotification(chatGuid: String) {
        notificationManager.cancel(chatGuid.hashCode())
    }

    /**
     * Cancel all message notifications
     */
    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }

    /**
     * Show foreground service notification
     */
    fun createServiceNotification(): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Connected to server")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * Show incoming FaceTime call notification
     */
    fun showFaceTimeCallNotification(
        callUuid: String,
        callerName: String,
        callerAddress: String?
    ) {
        if (!hasNotificationPermission()) return

        val notificationId = FACETIME_NOTIFICATION_ID_PREFIX + callUuid.hashCode()

        // Answer action - opens browser with FaceTime link
        val answerIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_ANSWER_FACETIME
            putExtra(EXTRA_CALL_UUID, callUuid)
        }
        val answerPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline action
        val declineIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_DECLINE_FACETIME
            putExtra(EXTRA_CALL_UUID, callUuid)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_FACETIME)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming FaceTime Call")
            .setContentText("$callerName is calling...")
            .addAction(
                android.R.drawable.ic_menu_call,
                "Answer",
                answerPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Decline",
                declinePendingIntent
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    /**
     * Dismiss FaceTime call notification
     */
    fun dismissFaceTimeCallNotification(callUuid: String) {
        val notificationId = FACETIME_NOTIFICATION_ID_PREFIX + callUuid.hashCode()
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
}
