package com.bothbubbles.services.notifications

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
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import android.util.Log
import com.bothbubbles.MainActivity
import com.bothbubbles.R
import com.bothbubbles.ui.bubble.BubbleActivity
import com.bothbubbles.ui.call.IncomingCallActivity
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.QuickReplyTemplateRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import com.bothbubbles.util.AvatarGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val androidContactsService: AndroidContactsService,
    private val chatDao: ChatDao,
    private val quickReplyTemplateRepository: QuickReplyTemplateRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "NotificationService"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_FACETIME = "facetime"
        const val CHANNEL_SYNC_STATUS = "sync_status"

        const val ACTION_REPLY = "com.bothbubbles.action.REPLY"
        const val ACTION_MARK_READ = "com.bothbubbles.action.MARK_READ"
        const val ACTION_COPY_CODE = "com.bothbubbles.action.COPY_CODE"
        const val ACTION_ANSWER_FACETIME = "com.bothbubbles.action.ANSWER_FACETIME"
        const val ACTION_DECLINE_FACETIME = "com.bothbubbles.action.DECLINE_FACETIME"

        const val EXTRA_CHAT_GUID = "chat_guid"
        const val EXTRA_MESSAGE_GUID = "message_guid"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_CODE_TO_COPY = "code_to_copy"
        const val EXTRA_CALL_UUID = "call_uuid"

        private const val GROUP_MESSAGES = "messages_group"
        private const val FACETIME_NOTIFICATION_ID_PREFIX = 1000000
        private const val SYNC_COMPLETE_NOTIFICATION_ID = 2000001
        private const val SMS_IMPORT_COMPLETE_NOTIFICATION_ID = 2000002
        private const val SERVER_UPDATE_NOTIFICATION_ID = 2000003
        private const val ICLOUD_ACCOUNT_NOTIFICATION_ID = 2000004
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    // Cached values to avoid blocking calls
    @Volatile private var cachedBubbleFilterMode: String = "all"
    @Volatile private var cachedTemplateChoices: Array<CharSequence> = emptyArray()

    init {
        createNotificationChannels()
        // Initialize cached values in background
        applicationScope.launch(ioDispatcher) {
            cachedBubbleFilterMode = settingsDataStore.bubbleFilterMode.first()
            cachedTemplateChoices = quickReplyTemplateRepository.getNotificationChoices(maxCount = 3)
        }
        // Keep cached values updated
        applicationScope.launch(ioDispatcher) {
            settingsDataStore.bubbleFilterMode.collect { mode ->
                cachedBubbleFilterMode = mode
            }
        }
        applicationScope.launch(ioDispatcher) {
            quickReplyTemplateRepository.observeAllTemplates().collect {
                cachedTemplateChoices = quickReplyTemplateRepository.getNotificationChoices(maxCount = 3)
            }
        }
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

        notificationManager.createNotificationChannels(listOf(messagesChannel, serviceChannel, faceTimeChannel, syncStatusChannel))
    }

    /**
     * Creates or updates a dynamic shortcut for a conversation.
     * This is required for bubble notifications to work properly.
     *
     * @param chatGuid Unique identifier for the chat
     * @param chatTitle Display name of the conversation
     * @param isGroup Whether this is a group conversation
     * @param participantNames List of participant names for group collage (optional)
     * @return The shortcut ID
     */
    private fun createConversationShortcut(
        chatGuid: String,
        chatTitle: String,
        isGroup: Boolean,
        participantNames: List<String> = emptyList()
    ): String {
        val shortcutId = "chat_$chatGuid"

        // Create intent for the shortcut
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_CHAT_GUID, chatGuid)
        }

        // Generate avatar icon - use group collage for groups, single avatar otherwise
        // Uses transparent background so no white circle appears behind the collage
        val avatarIcon: IconCompat = if (isGroup && participantNames.size > 1) {
            AvatarGenerator.generateGroupIconCompat(participantNames, 128)
        } else {
            AvatarGenerator.generateIconCompat(chatTitle, 128)
        }

        // Create person for the conversation
        val person = Person.Builder()
            .setName(chatTitle)
            .setKey(chatGuid)
            .setIcon(avatarIcon)
            .build()

        // Build the shortcut with LocusId for bubble support
        val locusId = LocusIdCompat(chatGuid)
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(chatTitle)
            .setLongLabel(chatTitle)
            .setIcon(avatarIcon)
            .setIntent(intent)
            .setLongLived(true)
            .setIsConversation()
            .setLocusId(locusId)
            .setPerson(person)
            .setCategories(setOf("com.bothbubbles.category.SHARE_TARGET"))
            .build()

        // Push the shortcut
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        return shortcutId
    }

    /**
     * Determines if bubbles should be shown for a conversation based on the filter mode.
     * Uses cached bubble filter mode to avoid blocking the calling thread.
     *
     * @param chatGuid The chat GUID
     * @param senderAddress The sender's address (phone number or email)
     * @return true if bubbles should be shown
     */
    private fun shouldShowBubble(chatGuid: String, senderAddress: String?): Boolean {
        return when (cachedBubbleFilterMode) {
            "none" -> false
            "all" -> true
            // For modes requiring async lookup, default to true and let the system handle it
            // The cache is updated asynchronously when settings change
            "selected", "favorites" -> true
            else -> true
        }
    }

    /**
     * Creates bubble metadata for a conversation notification.
     * This enables the notification to be displayed as a floating bubble.
     *
     * @param chatGuid Unique identifier for the chat
     * @param chatTitle Display name of the conversation
     * @param isGroup Whether this is a group conversation
     * @param participantNames List of participant names for group collage (optional)
     * @return BubbleMetadata for the notification, or null if bubbles aren't supported
     */
    private fun createBubbleMetadata(
        chatGuid: String,
        chatTitle: String,
        isGroup: Boolean = false,
        participantNames: List<String> = emptyList()
    ): NotificationCompat.BubbleMetadata? {
        // Bubbles require Android Q (API 29) or higher
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "Bubbles not supported: API level ${Build.VERSION.SDK_INT} < 29")
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

        // Generate avatar icon - use group collage for groups, single avatar otherwise
        // Uses transparent background so no white circle appears behind the collage
        val bubbleIcon: IconCompat = if (isGroup && participantNames.size > 1) {
            AvatarGenerator.generateGroupIconCompat(participantNames, 128)
        } else {
            AvatarGenerator.generateIconCompat(chatTitle, 128)
        }

        // Build bubble metadata
        val metadata = NotificationCompat.BubbleMetadata.Builder(
            bubblePendingIntent,
            bubbleIcon
        )
            .setDesiredHeight(600)
            .setAutoExpandBubble(false)
            .setSuppressNotification(false)
            .build()

        Log.d(TAG, "Created bubble metadata for chat: $chatGuid (title: $chatTitle)")
        return metadata
    }

    /**
     * Show a notification for a new message
     *
     * @param senderAddress The sender's address (phone/email) used for bubble filtering
     * @param participantNames List of participant names for group chats (used for group avatar collage)
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
        linkPreviewDomain: String? = null,
        participantNames: List<String> = emptyList()
    ) {
        if (!hasNotificationPermission()) return

        val notificationId = chatGuid.hashCode()

        // Create intent to open the chat (with message GUID for deep-link scrolling)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_GUID, chatGuid)
            putExtra(EXTRA_MESSAGE_GUID, messageGuid)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Reply action with quick reply template chips (using cached choices)
        val replyRemoteInput = RemoteInput.Builder(EXTRA_REPLY_TEXT)
            .setLabel(context.getString(R.string.message_placeholder))
            .apply {
                if (cachedTemplateChoices.isNotEmpty()) {
                    setChoices(cachedTemplateChoices)
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
        // Uses semantic action for Android Auto compatibility
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(R.string.action_reply),
            replyPendingIntent
        )
            .addRemoteInput(replyRemoteInput)
            .setAllowGeneratedReplies(detectedCode == null)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
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
        // Android Auto requires MessagingStyle with proper sender info
        val senderBuilder = Person.Builder()
            .setName(senderName ?: chatTitle)
            .setKey(senderAddress ?: chatGuid)

        // Add avatar to sender Person - load contact photo as bitmap or generate one
        // Note: content:// URIs can't be passed directly to notifications because the
        // notification system doesn't have permission to read contact photos. We must
        // load the photo as a bitmap first.
        val avatarIcon: IconCompat? = if (avatarUri != null) {
            // Try to load contact photo as bitmap
            val photoBitmap = AvatarGenerator.loadContactPhotoBitmap(context, avatarUri, 128)
            if (photoBitmap != null) {
                IconCompat.createWithBitmap(photoBitmap)
            } else {
                // Photo load failed, generate fallback avatar
                try {
                    val displayName = senderName ?: chatTitle
                    AvatarGenerator.generateIconCompat(displayName, 128)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to generate avatar bitmap", e)
                    null
                }
            }
        } else {
            // No contact photo available, generate avatar
            try {
                val displayName = senderName ?: chatTitle
                AvatarGenerator.generateIconCompat(displayName, 128)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to generate avatar bitmap", e)
                null
            }
        }

        avatarIcon?.let { senderBuilder.setIcon(it) }

        val sender = senderBuilder.build()

        val deviceUser = Person.Builder()
            .setName("Me")
            .setKey("me")
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(deviceUser)
            .setConversationTitle(if (isGroup) chatTitle else null)
            .setGroupConversation(isGroup)
            .addMessage(displayText, System.currentTimeMillis(), sender)

        // Create conversation shortcut for bubble support
        // Pass participant names for group collages with transparent backgrounds
        val shortcutId = createConversationShortcut(chatGuid, chatTitle, isGroup, participantNames)

        // Create bubble metadata if bubbles are enabled for this conversation
        val shouldBubble = shouldShowBubble(chatGuid, senderAddress)
        Log.d(TAG, "Bubble check: shouldBubble=$shouldBubble, filterMode=$cachedBubbleFilterMode, chatGuid=$chatGuid")
        val bubbleMetadata = if (shouldBubble) {
            createBubbleMetadata(chatGuid, chatTitle, isGroup, participantNames)
        } else {
            null
        }

        // Mark as read action with semantic action for Android Auto
        val markReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Mark as Read",
            markReadPendingIntent
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(chatTitle)
            .setContentText(displayText)
            .setStyle(messagingStyle)
            .setContentIntent(contentPendingIntent)
            .addAction(replyAction)
            .addAction(markReadAction)
            // Enable bubble support - LocusId links notification to shortcut for bubbles
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(chatGuid))

        // Add bubble metadata if available (Android Q+)
        if (bubbleMetadata != null) {
            notificationBuilder.setBubbleMetadata(bubbleMetadata)
            Log.d(TAG, "Attached bubble metadata to notification for chat: $chatGuid")
        } else {
            Log.d(TAG, "No bubble metadata for notification (chatGuid=$chatGuid)")
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
     * Show notification when BlueBubbles initial sync completes
     */
    fun showBlueBubblesSyncCompleteNotification(messageCount: Int) {
        if (!hasNotificationPermission()) return

        // Create intent to open the app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            SYNC_COMPLETE_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("BlueBubbles import complete")
            .setContentText("Imported $messageCount messages from your BlueBubbles server")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(SYNC_COMPLETE_NOTIFICATION_ID, notification)
    }

    /**
     * Show notification when SMS import completes
     */
    fun showSmsImportCompleteNotification() {
        if (!hasNotificationPermission()) return

        // Create intent to open the app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            SMS_IMPORT_COMPLETE_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SMS import complete")
            .setContentText("Your SMS messages have been imported successfully")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(SMS_IMPORT_COMPLETE_NOTIFICATION_ID, notification)
    }

    /**
     * Show notification when a BlueBubbles server update is available.
     * Tapping opens the app's server settings where they can see more info.
     */
    fun showServerUpdateNotification(version: String) {
        if (!hasNotificationPermission()) return

        // Create intent to open server settings
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "server_settings")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            SERVER_UPDATE_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("BlueBubbles Server Update Available")
            .setContentText("Version $version is now available")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(SERVER_UPDATE_NOTIFICATION_ID, notification)
    }

    /**
     * Show notification when iCloud account status changes.
     * This is important to notify users when their iCloud account is logged out,
     * which would prevent iMessage from working.
     */
    fun showICloudAccountNotification(active: Boolean, alias: String?) {
        if (!hasNotificationPermission()) return

        // Only show notification when account becomes inactive
        if (active) return

        // Create intent to open server settings
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "server_settings")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            ICLOUD_ACCOUNT_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "iCloud Account Signed Out"
        val text = if (alias != null) {
            "The iCloud account ($alias) is no longer signed in on your Mac. iMessage won't work until you sign back in."
        } else {
            "The iCloud account is no longer signed in on your Mac. iMessage won't work until you sign back in."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(ICLOUD_ACCOUNT_NOTIFICATION_ID, notification)
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

    /**
     * Update the app badge count on supported launchers.
     * This uses ShortcutManager badges on Android O+ and falls back to
     * ShortcutBadger-style intents for older devices.
     *
     * Note: This does NOT affect push notifications, only the badge count.
     *
     * @param count The number to show on the app icon badge
     */
    fun updateAppBadge(count: Int) {
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
