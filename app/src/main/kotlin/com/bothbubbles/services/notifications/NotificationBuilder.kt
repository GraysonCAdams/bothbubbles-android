package com.bothbubbles.services.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.MainActivity
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.repository.QuickReplyTemplateRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.ui.call.IncomingCallActivity
import com.bothbubbles.util.AvatarGenerator
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds various types of notifications for the app.
 * Handles construction of message, service, sync, and FaceTime notifications.
 */
@Singleton
class NotificationBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bubbleMetadataHelper: BubbleMetadataHelper,
    private val quickReplyTemplateRepository: QuickReplyTemplateRepository,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val activeConversationManager: ActiveConversationManager,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    // Cached values to avoid blocking calls
    @Volatile
    private var cachedTemplateChoices: Array<CharSequence> = emptyArray()

    // Cache unified group mappings (chatGuid -> merged guids string)
    private val unifiedGroupCache = mutableMapOf<String, String?>()
    private val unifiedGroupCacheMutex = Mutex()

    init {
        // Initialize cached values in background
        applicationScope.launch(ioDispatcher) {
            cachedTemplateChoices = quickReplyTemplateRepository.getNotificationChoices(maxCount = 3)
        }
        // Keep cached values updated
        applicationScope.launch(ioDispatcher) {
            quickReplyTemplateRepository.observeAllTemplates().collect {
                cachedTemplateChoices = quickReplyTemplateRepository.getNotificationChoices(maxCount = 3)
            }
        }
        // Pre-populate unified group cache
        applicationScope.launch(ioDispatcher) {
            refreshUnifiedGroupCache()
        }
    }

    /**
     * Refresh the unified group cache in the background.
     * Called on initialization and whenever unified groups change.
     */
    private suspend fun refreshUnifiedGroupCache() {
        try {
            val allGroups = unifiedChatGroupDao.getActiveGroupsPaginated(limit = 1000, offset = 0)
            val groupIds = allGroups.map { it.id }
            val allMembers = unifiedChatGroupDao.getChatGuidsForGroups(groupIds)

            // Build map of groupId -> list of chat guids
            val groupMembersMap = allMembers.groupBy { it.groupId }

            // Build map of chatGuid -> merged guids string
            val newCache = mutableMapOf<String, String?>()
            for ((groupId, members) in groupMembersMap) {
                val chatGuids = members.map { it.chatGuid }
                if (chatGuids.size > 1) {
                    val mergedGuidsString = chatGuids.joinToString(",")
                    // Add entry for each chat in the group
                    chatGuids.forEach { chatGuid ->
                        newCache[chatGuid] = mergedGuidsString
                    }
                }
            }

            unifiedGroupCacheMutex.withLock {
                unifiedGroupCache.clear()
                unifiedGroupCache.putAll(newCache)
            }

            Timber.d("Refreshed unified group cache with ${newCache.size} chat mappings")
        } catch (e: Exception) {
            Timber.w(e, "Failed to refresh unified group cache")
        }
    }

    /**
     * Get merged guids for a chat from cache.
     * Returns null if chat is not part of a unified group or cache miss.
     */
    private suspend fun getCachedMergedGuids(chatGuid: String): String? {
        return unifiedGroupCacheMutex.withLock {
            unifiedGroupCache[chatGuid]
        }
    }

    /**
     * Invalidate the unified group cache and refresh in the background.
     * Call this when unified groups are created, updated, or deleted.
     */
    fun invalidateUnifiedGroupCache() {
        applicationScope.launch(ioDispatcher) {
            refreshUnifiedGroupCache()
        }
    }

    /**
     * Build a notification for a new message.
     *
     * @param channelId The notification channel ID (per-conversation channel)
     * @param senderAddress The sender's address (phone/email) used for bubble filtering
     * @param participantNames List of participant names for group chats (used for group avatar collage)
     * @param participantAvatarPaths List of avatar paths for group participants (corresponding to participantNames)
     * @param subject Optional message subject. When present, shows ONLY the subject (not the body).
     * @param attachmentUri Optional content:// URI to an attachment image/video for inline preview
     * @param attachmentMimeType MIME type of the attachment (required if attachmentUri is provided)
     */
    fun buildMessageNotification(
        channelId: String,
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
        participantAvatarPaths: List<String?> = emptyList(),
        subject: String? = null,
        totalUnreadCount: Int,
        attachmentUri: android.net.Uri? = null,
        attachmentMimeType: String? = null
    ): android.app.Notification {
        val notificationId = chatGuid.hashCode()

        // Look up if this chat is part of a unified group (for merged iMessage/SMS navigation)
        // Use cached value to avoid blocking the notification thread
        val mergedGuids: String? = unifiedGroupCache[chatGuid]

        // Create intent to open the chat (with message GUID for deep-link scrolling)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationChannelManager.EXTRA_CHAT_GUID, chatGuid)
            putExtra(NotificationChannelManager.EXTRA_MESSAGE_GUID, messageGuid)
            if (mergedGuids != null) {
                putExtra(NotificationChannelManager.EXTRA_MERGED_GUIDS, mergedGuids)
            }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Check for verification code in message (needed before building reply action)
        val detectedCode = PhoneAndCodeParsingUtils.detectFirstCode(messageText)

        // Reply action with quick reply template chips (using cached choices)
        val replyRemoteInput = RemoteInput.Builder(NotificationChannelManager.EXTRA_REPLY_TEXT)
            .setLabel(context.getString(R.string.message_placeholder))
            .apply {
                if (cachedTemplateChoices.isNotEmpty()) {
                    setChoices(cachedTemplateChoices)
                }
            }
            .build()

        val replyIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationChannelManager.ACTION_REPLY
            putExtra(NotificationChannelManager.EXTRA_CHAT_GUID, chatGuid)
            putExtra(NotificationChannelManager.EXTRA_MESSAGE_GUID, messageGuid)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

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
            action = NotificationChannelManager.ACTION_MARK_READ
            putExtra(NotificationChannelManager.EXTRA_CHAT_GUID, chatGuid)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Format display text - subject takes priority, then link preview, then message body
        // When subject is present, show ONLY the subject (like iOS does)
        val displayText = when {
            subject != null -> if (subject.isBlank()) "(no subject)" else subject
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
            // Use circleCrop=false for adaptive icon support
            val photoBitmap = AvatarGenerator.loadContactPhotoBitmap(context, avatarUri, 128, circleCrop = false)
            if (photoBitmap != null) {
                IconCompat.createWithAdaptiveBitmap(photoBitmap)
            } else {
                // Photo load failed, generate fallback avatar
                try {
                    val displayName = senderName ?: chatTitle
                    AvatarGenerator.generateAdaptiveIconCompat(context, displayName, 128)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to generate avatar bitmap")
                    null
                }
            }
        } else {
            // No contact photo available, generate avatar
            try {
                val displayName = senderName ?: chatTitle
                AvatarGenerator.generateAdaptiveIconCompat(context, displayName, 128)
            } catch (e: Exception) {
                Timber.w(e, "Failed to generate avatar bitmap")
                null
            }
        }

        avatarIcon?.let { senderBuilder.setIcon(it) }

        val sender = senderBuilder.build()

        val deviceUser = Person.Builder()
            .setName("Me")
            .setKey("me")
            .build()

        // Create message with optional inline media attachment
        val message = NotificationCompat.MessagingStyle.Message(
            displayText,
            System.currentTimeMillis(),
            sender
        ).apply {
            // Add inline image/video preview if attachment is available
            if (attachmentUri != null && attachmentMimeType != null) {
                setData(attachmentMimeType, attachmentUri)
                Timber.d("Set notification attachment data: $attachmentMimeType, $attachmentUri")
            }
        }

        val messagingStyle = NotificationCompat.MessagingStyle(deviceUser)
            .setConversationTitle(if (isGroup) chatTitle else null)
            .setGroupConversation(isGroup)
            .addMessage(message)

        // Create conversation shortcut for bubble support
        // Pass participant names and avatar paths for group collages with actual contact photos
        val shortcutId = bubbleMetadataHelper.createConversationShortcut(
            chatGuid = chatGuid,
            chatTitle = chatTitle,
            isGroup = isGroup,
            participantNames = participantNames,
            chatAvatarPath = null, // No custom chat avatar from notification
            senderAvatarPath = if (!isGroup) avatarUri else null,
            participantAvatarPaths = participantAvatarPaths
        )

        // Create bubble metadata if bubbles are enabled for this conversation
        // AND the app is not in the foreground (to avoid "interface inception")
        val shouldBubble = bubbleMetadataHelper.shouldShowBubble(chatGuid, senderAddress)
        val isAppForeground = activeConversationManager.isAppForeground
        
        Timber.d("Bubble check: shouldBubble=$shouldBubble, isAppForeground=$isAppForeground, chatGuid=$chatGuid")
        
        val bubbleMetadata = if (shouldBubble && !isAppForeground) {
            bubbleMetadataHelper.createBubbleMetadata(
                chatGuid = chatGuid,
                chatTitle = chatTitle,
                isGroup = isGroup,
                participantNames = participantNames,
                chatAvatarPath = null, // No custom chat avatar from notification
                senderAvatarPath = if (!isGroup) avatarUri else null,
                participantAvatarPaths = participantAvatarPaths,
                mergedGuids = mergedGuids // Pass unified chat guids for proper bubble navigation
            )
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

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setNumber(totalUnreadCount)
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
            Timber.d("Attached bubble metadata to notification for chat: $chatGuid")
        } else {
            Timber.d("No bubble metadata for notification (chatGuid=$chatGuid)")
        }

        // Add copy code action if a verification code was detected
        if (detectedCode != null) {
            val copyCodeIntent = Intent(context, NotificationReceiver::class.java).apply {
                action = NotificationChannelManager.ACTION_COPY_CODE
                putExtra(NotificationChannelManager.EXTRA_CHAT_GUID, chatGuid)
                putExtra(NotificationChannelManager.EXTRA_CODE_TO_COPY, detectedCode.code)
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

        return notificationBuilder
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(NotificationChannelManager.GROUP_MESSAGES)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
    }

    /**
     * Build a group summary notification that controls the app badge count.
     *
     * On Android 8.0+, the app badge count is derived from notifications.
     * This silent summary notification sets the correct badge count via setNumber().
     * It groups all message notifications and displays the total unread count.
     *
     * @param totalUnreadCount The total number of unread messages across all chats
     * @return The summary notification, or null if count is 0 (to clear badge)
     */
    fun buildBadgeSummaryNotification(totalUnreadCount: Int): android.app.Notification? {
        // When count is 0, return null to signal that summary should be cancelled
        if (totalUnreadCount <= 0) return null

        // Create intent to open the app (conversations list)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NotificationChannelManager.SUMMARY_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            // This is the key for badge count on most launchers
            .setNumber(totalUnreadCount)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("$totalUnreadCount unread message${if (totalUnreadCount != 1) "s" else ""}")
            .setContentIntent(contentPendingIntent)
            // Mark as group summary - this makes it the "parent" of grouped notifications
            .setGroup(NotificationChannelManager.GROUP_MESSAGES)
            .setGroupSummary(true)
            // Use inbox style to show a summary of conversations
            .setStyle(NotificationCompat.InboxStyle()
                .setSummaryText("$totalUnreadCount unread"))
            // Low priority so it doesn't make sound/vibrate, but still shows badge
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Don't alert for summary updates
            .setOnlyAlertOnce(true)
            // Auto-cancel when tapped
            .setAutoCancel(true)
            // Show in lock screen but don't reveal content
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    /**
     * Build a foreground service notification.
     */
    fun buildServiceNotification(): android.app.Notification {
        return NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Connected to server")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * Build notification for BlueBubbles sync completion.
     */
    fun buildSyncCompleteNotification(messageCount: Int): android.app.Notification {
        // Create intent to open the app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NotificationChannelManager.SYNC_COMPLETE_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("BlueBubbles import complete")
            .setContentText("Imported $messageCount messages from your BlueBubbles server")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * Build notification for SMS import completion.
     */
    fun buildSmsImportCompleteNotification(): android.app.Notification {
        // Create intent to open the app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NotificationChannelManager.SMS_IMPORT_COMPLETE_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SMS import complete")
            .setContentText("Your SMS messages have been imported successfully")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * Build notification for server update availability.
     */
    fun buildServerUpdateNotification(version: String): android.app.Notification {
        // Create intent to open server settings
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "server_settings")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NotificationChannelManager.SERVER_UPDATE_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("BlueBubbles Server Update Available")
            .setContentText("Version $version is now available")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Build notification for iCloud account status changes.
     */
    fun buildICloudAccountNotification(active: Boolean, alias: String?): android.app.Notification? {
        // Only show notification when account becomes inactive
        if (active) return null

        // Create intent to open server settings
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "server_settings")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NotificationChannelManager.ICLOUD_ACCOUNT_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "iCloud Account Signed Out"
        val text = if (alias != null) {
            "The iCloud account ($alias) is no longer signed in on your Mac. iMessage won't work until you sign back in."
        } else {
            "The iCloud account is no longer signed in on your Mac. iMessage won't work until you sign back in."
        }

        return NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_SYNC_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    /**
     * Build notification for incoming FaceTime call.
     */
    fun buildFaceTimeCallNotification(
        callUuid: String,
        callerName: String,
        callerAddress: String?
    ): android.app.Notification {
        val notificationId = NotificationChannelManager.FACETIME_NOTIFICATION_ID_PREFIX + callUuid.hashCode()

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
            action = NotificationChannelManager.ACTION_ANSWER_FACETIME
            putExtra(NotificationChannelManager.EXTRA_CALL_UUID, callUuid)
        }
        val answerPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline action
        val declineIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationChannelManager.ACTION_DECLINE_FACETIME
            putExtra(NotificationChannelManager.EXTRA_CALL_UUID, callUuid)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_FACETIME)
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
    }

    /**
     * Build notification for a failed message delivery.
     */
    fun buildMessageFailedNotification(
        channelId: String,
        chatGuid: String,
        chatTitle: String,
        messagePreview: String?,
        errorMessage: String
    ): android.app.Notification {
        // Look up if this chat is part of a unified group (for merged iMessage/SMS navigation)
        val mergedGuids: String? = unifiedGroupCache[chatGuid]

        // Create intent to open the chat
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationChannelManager.EXTRA_CHAT_GUID, chatGuid)
            if (mergedGuids != null) {
                putExtra(NotificationChannelManager.EXTRA_MERGED_GUIDS, mergedGuids)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            chatGuid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (messagePreview.isNullOrBlank()) {
            "Message failed to send"
        } else {
            "Failed: ${messagePreview.take(50)}${if (messagePreview.length > 50) "..." else ""}"
        }

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Message not delivered to $chatTitle")
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$contentText\n\n$errorMessage")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
    }
}
