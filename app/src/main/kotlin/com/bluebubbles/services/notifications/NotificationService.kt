package com.bluebubbles.services.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bluebubbles.MainActivity
import com.bluebubbles.R
import com.bluebubbles.ui.bubble.BubbleActivity
import com.bluebubbles.ui.call.IncomingCallActivity
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.data.repository.QuickReplyTemplateRepository
import com.bluebubbles.services.contacts.AndroidContactsService
import com.bluebubbles.ui.components.PhoneAndCodeParsingUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val androidContactsService: AndroidContactsService,
    private val chatDao: ChatDao,
    private val quickReplyTemplateRepository: QuickReplyTemplateRepository
) {
    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_FACETIME = "facetime"

        const val ACTION_REPLY = "com.bluebubbles.action.REPLY"
        const val ACTION_MARK_READ = "com.bluebubbles.action.MARK_READ"
        const val ACTION_COPY_CODE = "com.bluebubbles.action.COPY_CODE"
        const val ACTION_ANSWER_FACETIME = "com.bluebubbles.action.ANSWER_FACETIME"
        const val ACTION_DECLINE_FACETIME = "com.bluebubbles.action.DECLINE_FACETIME"

        const val EXTRA_CHAT_GUID = "chat_guid"
        const val EXTRA_MESSAGE_GUID = "message_guid"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_CODE_TO_COPY = "code_to_copy"
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

        notificationManager.createNotificationChannels(listOf(messagesChannel, serviceChannel, faceTimeChannel))
    }

    /**
     * Creates or updates a dynamic shortcut for a conversation.
     * This is required for bubble notifications to work properly.
     *
     * @param chatGuid Unique identifier for the chat
     * @param chatTitle Display name of the conversation
     * @param isGroup Whether this is a group conversation
     * @return The shortcut ID
     */
    private fun createConversationShortcut(
        chatGuid: String,
        chatTitle: String,
        isGroup: Boolean
    ): String {
        val shortcutId = "chat_$chatGuid"

        // Create intent for the shortcut
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_CHAT_GUID, chatGuid)
        }

        // Create person for the conversation
        val person = Person.Builder()
            .setName(chatTitle)
            .setKey(chatGuid)
            .build()

        // Build the shortcut
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(chatTitle)
            .setLongLabel(chatTitle)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .setLongLived(true)
            .setPerson(person)
            .setCategories(setOf("com.bluebubbles.category.SHARE_TARGET"))
            .build()

        // Push the shortcut
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        return shortcutId
    }

    /**
     * Determines if bubbles should be shown for a conversation based on the filter mode.
     *
     * @param chatGuid The chat GUID
     * @param senderAddress The sender's address (phone number or email)
     * @return true if bubbles should be shown
     */
    private fun shouldShowBubble(chatGuid: String, senderAddress: String?): Boolean {
        return runBlocking {
            when (settingsDataStore.bubbleFilterMode.first()) {
                "none" -> false
                "all" -> true
                "selected" -> chatDao.getChatByGuid(chatGuid)?.bubbleEnabled ?: false
                "favorites" -> {
                    if (senderAddress.isNullOrEmpty()) {
                        false
                    } else {
                        androidContactsService.isContactStarred(senderAddress)
                    }
                }
                else -> true
            }
        }
    }

    /**
     * Creates bubble metadata for a conversation notification.
     * This enables the notification to be displayed as a floating bubble.
     *
     * @param chatGuid Unique identifier for the chat
     * @param chatTitle Display name of the conversation
     * @return BubbleMetadata for the notification, or null if bubbles aren't supported
     */
    private fun createBubbleMetadata(
        chatGuid: String,
        chatTitle: String
    ): NotificationCompat.BubbleMetadata? {
        // Bubbles require Android Q (API 29) or higher
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        // Create intent for the bubble activity
        val bubbleIntent = BubbleActivity.createIntent(context, chatGuid, chatTitle)
        val bubblePendingIntent = PendingIntent.getActivity(
            context,
            chatGuid.hashCode() + 100, // Different request code than main intent
            bubbleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Build bubble metadata
        return NotificationCompat.BubbleMetadata.Builder(
            bubblePendingIntent,
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        )
            .setDesiredHeight(600)
            .setAutoExpandBubble(false)
            .setSuppressNotification(false)
            .build()
    }

    /**
     * Show a notification for a new message
     *
     * @param senderAddress The sender's address (phone/email) used for bubble filtering
     */
    fun showMessageNotification(
        chatGuid: String,
        chatTitle: String,
        messageText: String,
        messageGuid: String,
        senderName: String?,
        senderAddress: String? = null,
        isGroup: Boolean = false,
        avatarUri: String? = null,
        linkPreviewTitle: String? = null,
        linkPreviewDomain: String? = null
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

        // Get quick reply template choices for notification chips
        val templateChoices = runBlocking {
            quickReplyTemplateRepository.getNotificationChoices(maxCount = 3)
        }

        // Reply action with quick reply template chips
        val replyRemoteInput = RemoteInput.Builder(EXTRA_REPLY_TEXT)
            .setLabel(context.getString(R.string.message_placeholder))
            .apply {
                if (templateChoices.isNotEmpty()) {
                    setChoices(templateChoices)
                }
            }
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

        // Check for verification code in message (needed before building reply action)
        val detectedCode = PhoneAndCodeParsingUtils.detectFirstCode(messageText)

        // Reply action - disable smart reply suggestions if OTP detected
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(R.string.action_reply),
            replyPendingIntent
        )
            .addRemoteInput(replyRemoteInput)
            .setAllowGeneratedReplies(detectedCode == null)
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

        // Format display text with link preview if available
        val displayText = when {
            linkPreviewTitle != null && linkPreviewDomain != null -> "$linkPreviewTitle ($linkPreviewDomain)"
            linkPreviewTitle != null -> linkPreviewTitle
            linkPreviewDomain != null -> linkPreviewDomain
            else -> messageText
        }

        // Create messaging style for conversation-like appearance
        val sender = Person.Builder()
            .setName(senderName ?: chatTitle)
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(
            Person.Builder().setName("Me").build()
        )
            .setConversationTitle(if (isGroup) chatTitle else null)
            .addMessage(displayText, System.currentTimeMillis(), sender)

        // Create conversation shortcut for bubble support
        val shortcutId = createConversationShortcut(chatGuid, chatTitle, isGroup)

        // Create bubble metadata if bubbles are enabled for this conversation
        val bubbleMetadata = if (shouldShowBubble(chatGuid, senderAddress)) {
            createBubbleMetadata(chatGuid, chatTitle)
        } else {
            null
        }

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(chatTitle)
            .setContentText(displayText)
            .setStyle(messagingStyle)
            .setContentIntent(contentPendingIntent)
            .addAction(replyAction)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Mark as Read",
                markReadPendingIntent
            )
            // Enable bubble support
            .setShortcutId(shortcutId)

        // Add bubble metadata if available (Android Q+)
        if (bubbleMetadata != null) {
            notificationBuilder.setBubbleMetadata(bubbleMetadata)
        }

        // Add copy code action if a verification code was detected
        if (detectedCode != null) {
            val copyCodeIntent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_COPY_CODE
                putExtra(EXTRA_CHAT_GUID, chatGuid)
                putExtra(EXTRA_CODE_TO_COPY, detectedCode.code)
            }
            val copyCodePendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 3,
                copyCodeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            notificationBuilder.addAction(
                android.R.drawable.ic_menu_edit,
                "Copy Code: ${detectedCode.code}",
                copyCodePendingIntent
            )
        }

        val notification = notificationBuilder
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
     * Show incoming FaceTime call notification with full-screen intent
     */
    fun showFaceTimeCallNotification(
        callUuid: String,
        callerName: String,
        callerAddress: String?
    ) {
        if (!hasNotificationPermission()) return

        val notificationId = FACETIME_NOTIFICATION_ID_PREFIX + callUuid.hashCode()

        // Full-screen intent for locked device
        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_CALL_UUID, callUuid)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
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
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(60000) // Auto-dismiss after 60 seconds
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
