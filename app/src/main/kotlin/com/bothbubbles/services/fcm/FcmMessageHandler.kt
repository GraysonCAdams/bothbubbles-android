package com.bothbubbles.services.fcm

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.media.AttachmentDownloadQueue
import com.bothbubbles.services.messaging.IncomingMessageHandler
import com.bothbubbles.services.notifications.NotificationParamsBuilder
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.services.messaging.MessageDeduplicator
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.core.network.api.dto.MessageDto
import com.google.firebase.messaging.RemoteMessage
import com.squareup.moshi.Moshi
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles incoming FCM push notification messages.
 *
 * This handler processes FCM data payloads from the BlueBubbles server.
 * FCM serves as a reliable sync trigger - when a push arrives, we show
 * the notification immediately (optimistic UI) and trigger a chat sync
 * to ensure the message is saved to the database.
 *
 * This approach handles the "stale socket" problem where the socket reports
 * connected but isn't receiving events. MessageDeduplicator prevents duplicate
 * notifications when both FCM and socket deliver the same message.
 */
@Singleton
class FcmMessageHandler @Inject constructor(
    private val socketService: SocketService,
    private val notificationService: NotificationService,
    private val notificationParamsBuilder: NotificationParamsBuilder,
    private val messageRepository: MessageRepository,
    private val chatDao: ChatDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val messageDeduplicator: MessageDeduplicator,
    private val activeConversationManager: ActiveConversationManager,
    private val developerEventLog: Lazy<DeveloperEventLog>,
    private val attachmentDownloadQueue: AttachmentDownloadQueue,
    private val incomingMessageHandler: IncomingMessageHandler,
    private val moshi: Moshi,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        // Debug flag: set to true to allow notifications for own messages (for testing)
        private const val DEBUG_ALLOW_OWN_MESSAGE_NOTIFICATIONS = false

        // Debug flag: set to true to skip the active conversation check (for testing)
        private const val DEBUG_SKIP_ACTIVE_CONVERSATION_CHECK = false

        // FCM message types from BlueBubbles server
        private const val TYPE_NEW_MESSAGE = "new-message"
        private const val TYPE_UPDATED_MESSAGE = "updated-message"
        private const val TYPE_GROUP_NAME_CHANGE = "group-name-change"
        private const val TYPE_PARTICIPANT_ADDED = "participant-added"
        private const val TYPE_PARTICIPANT_REMOVED = "participant-removed"
        private const val TYPE_PARTICIPANT_LEFT = "participant-left"
        private const val TYPE_CHAT_READ_STATUS_CHANGED = "chat-read-status-changed"
        private const val TYPE_TYPING_INDICATOR = "typing-indicator"
        private const val TYPE_SERVER_UPDATE = "server-update"
        private const val TYPE_FT_CALL_STATUS_CHANGED = "ft-call-status-changed"
    }

    /**
     * Handle an incoming FCM message.
     */
    suspend fun handleMessage(message: RemoteMessage) {
        val type = message.data["type"]
        Timber.d("Received FCM message type: $type")
        developerEventLog.get().logFcmEvent(type ?: "unknown", "FCM push received")

        if (type.isNullOrBlank()) {
            Timber.w("FCM message has no type, ignoring")
            developerEventLog.get().logFcmEvent("IGNORED", "No type field")
            return
        }

        when (type) {
            TYPE_NEW_MESSAGE -> handleNewMessage(message.data)
            TYPE_UPDATED_MESSAGE -> handleUpdatedMessage(message.data)
            TYPE_FT_CALL_STATUS_CHANGED -> handleFaceTimeCall(message.data)
            TYPE_SERVER_UPDATE -> handleServerUpdate(message.data)
            TYPE_CHAT_READ_STATUS_CHANGED -> handleChatReadStatus(message.data)
            TYPE_TYPING_INDICATOR -> {
                // Typing indicators are handled via socket only
                Timber.d("Ignoring typing indicator from FCM")
                developerEventLog.get().logFcmEvent(type, "Ignored (socket-only)")
            }
            else -> {
                Timber.d("Unhandled FCM message type: $type")
                developerEventLog.get().logFcmEvent(type, "Unhandled type")
                // Trigger socket reconnect for unhandled events that might need full data
                triggerSocketReconnect()
            }
        }
    }

    private suspend fun handleNewMessage(data: Map<String, String>) {
        // Log raw FCM data for debugging
        Timber.d("FCM_DEBUG: Raw data keys=${data.keys}, values=${data.entries.joinToString { "${it.key}=${it.value.take(200)}" }}")

        // BlueBubbles server sends message data as a JSON string in the "data" field
        val dataJsonString = data["data"]
        if (dataJsonString.isNullOrBlank()) {
            Timber.w("FCM new-message missing 'data' field")
            triggerSocketReconnect()
            return
        }

        Timber.d("FCM_DEBUG: dataJsonString (first 500 chars)=${dataJsonString.take(500)}")

        // Parse the JSON data
        val messageJson: JSONObject
        try {
            messageJson = JSONObject(dataJsonString)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse FCM message JSON")
            triggerSocketReconnect()
            return
        }

        Timber.d("FCM_DEBUG: Parsed JSON keys=${messageJson.keys().asSequence().toList()}")

        // Extract basic info from parsed JSON
        // Note: optString returns "null" string when value is JSON null, so we must handle that
        val messageGuid = messageJson.optString("guid", "")
        val rawText = messageJson.optString("text", "")
        val messageText = rawText.takeIf { it != "null" } ?: ""
        val messageSubject = messageJson.optString("subject", null)?.takeIf { it.isNotEmpty() && it != "null" }
        val isFromMe = messageJson.optBoolean("isFromMe", false)
        val expressiveSendStyleId = messageJson.optString("expressiveSendStyleId", null)
        val hasAttachments = messageJson.optJSONArray("attachments")?.length()?.let { it > 0 } ?: false

        Timber.d("FCM_DEBUG: Extracted - rawText='$rawText', messageText='$messageText', guid=$messageGuid, hasAttachments=$hasAttachments")

        // Extract chat GUID from chats array
        val chatsArray = messageJson.optJSONArray("chats")
        val chatGuid = chatsArray?.optJSONObject(0)?.optString("guid", "")

        // Extract sender address from handle object
        val handleObj = messageJson.optJSONObject("handle")
        val senderAddress = handleObj?.optString("address", null)

        // Always log FCM message details for debugging (even if socket handles it)
        Timber.d("FCM new-message: guid=$messageGuid, text='$messageText', isFromMe=$isFromMe, chatGuid=$chatGuid")

        if (chatGuid.isNullOrBlank() || messageGuid.isBlank()) {
            Timber.w("FCM new-message missing required fields: chatGuid=$chatGuid, messageGuid=$messageGuid")
            triggerSocketReconnect()
            return
        }

        // Skip notification for own messages but still sync (sent from laptop/other device)
        if (isFromMe) {
            Timber.d("Skipping notification for own message, but syncing")
            triggerChatSync(chatGuid)
            return
        }

        // Check for duplicate notification (message may arrive via both FCM and socket)
        if (!messageDeduplicator.shouldNotifyForMessage(messageGuid)) {
            Timber.d("Message $messageGuid already notified, skipping FCM duplicate notification")
            // Still sync to ensure message is saved (socket may have notified but not saved due to race)
            triggerChatSync(chatGuid)
            return
        }

        // Check if user is currently viewing this conversation
        if (activeConversationManager.isConversationActive(chatGuid)) {
            Timber.d("Chat $chatGuid is currently active, skipping FCM notification")
            // Still sync - ChatViewModel polling may not have caught it yet
            triggerChatSync(chatGuid)
            return
        }

        // Get chat info from local database for notification settings check
        val chat = chatDao.getChatByGuid(chatGuid)
        val unifiedChat = chat?.unifiedChatId?.let { unifiedChatDao.getById(it) }

        // Check if notifications are disabled for this chat
        if (unifiedChat?.notificationsEnabled == false) {
            Timber.d("Notifications disabled for chat $chatGuid, skipping FCM notification")
            // Must still sync to save the message even if we don't notify
            triggerChatSync(chatGuid)
            return
        }

        // Check if chat is snoozed
        if (unifiedChat?.isSnoozed == true) {
            Timber.d("Chat $chatGuid is snoozed, skipping FCM notification")
            // Must still sync to save the message even if we don't notify
            triggerChatSync(chatGuid)
            return
        }

        // Check for invisible ink effect (expressiveSendStyleId and hasAttachments already extracted above)
        val isInvisibleInk = MessageEffect.fromStyleId(expressiveSendStyleId) == MessageEffect.Bubble.InvisibleInk
        val notificationText = when {
            isInvisibleInk && hasAttachments -> "Image sent with Invisible Ink"
            isInvisibleInk -> "Message sent with Invisible Ink"
            messageText.isNotBlank() -> messageText
            hasAttachments -> "Photo"
            else -> "New message"
        }

        // Build notification params using shared builder (ensures consistent contact caching and link preview)
        val params = notificationParamsBuilder.buildParams(
            messageGuid = messageGuid,
            chatGuid = chatGuid,
            messageText = notificationText,
            subject = messageSubject,
            senderAddress = senderAddress,
            fetchLinkPreview = !isInvisibleInk, // Now FCM gets link previews too
            isInvisibleInk = isInvisibleInk
        )

        if (params != null) {
            Timber.d("DEBUG: Showing FCM notification - chatTitle=${params.chatTitle}, notificationText=${params.messageText}")
            notificationService.showMessageNotification(params)
            Timber.d("DEBUG: Notification shown successfully!")
        }

        // Sync this chat first to ensure message and attachments are saved to database
        // We must await this before downloading attachments, otherwise the attachment record won't exist
        syncChatAndDownloadAttachment(chatGuid, messageJson)
    }

    /**
     * Handle message update events from FCM.
     * This includes edits, read receipts, delivery status changes, etc.
     *
     * For message edits specifically, we:
     * 1. Parse the updated message data
     * 2. Save the previous text to edit history
     * 3. Update the notification with the new text
     */
    private suspend fun handleUpdatedMessage(data: Map<String, String>) {
        Timber.d("Message updated via FCM: ${data["guid"]}")

        // Parse the JSON data
        val dataJsonString = data["data"]
        if (dataJsonString.isNullOrBlank()) {
            Timber.w("FCM updated-message missing 'data' field, triggering socket sync")
            triggerSocketReconnect()
            return
        }

        // Parse JSON and convert to MessageDto
        val messageDto: MessageDto
        try {
            val adapter = moshi.adapter(MessageDto::class.java)
            messageDto = adapter.fromJson(dataJsonString)
                ?: throw Exception("Failed to parse MessageDto")
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse FCM updated-message JSON, triggering socket sync")
            triggerSocketReconnect()
            return
        }

        // Extract chat GUID
        val chatGuid = messageDto.chats?.firstOrNull()?.guid
        if (chatGuid.isNullOrBlank()) {
            Timber.w("FCM updated-message missing chatGuid, triggering socket sync")
            triggerSocketReconnect()
            return
        }

        // Handle the message update (this saves edit history if it's an edit)
        incomingMessageHandler.handleMessageUpdate(messageDto, chatGuid)

        // Check if this is a message edit that needs notification update
        if (messageDto.dateEdited != null && !messageDto.isFromMe) {
            updateNotificationForEdit(messageDto, chatGuid)
        }

        // Also sync to ensure we catch any related updates
        triggerChatSync(chatGuid)
    }

    /**
     * Update the notification for an edited message.
     * Similar to MessageEventHandler.updateNotificationIfEdited() but for FCM.
     */
    private suspend fun updateNotificationForEdit(messageDto: MessageDto, chatGuid: String) {
        // Skip if conversation is active
        if (activeConversationManager.isConversationActive(chatGuid)) return

        val chat = chatDao.getChatByGuid(chatGuid)
        val unifiedChat = chat?.unifiedChatId?.let { unifiedChatDao.getById(it) }

        // Check notification settings
        if (unifiedChat?.notificationsEnabled == false) return
        if (unifiedChat?.isSnoozed == true) return

        val messageText = messageDto.text ?: return

        Timber.d("Updating notification for edited message via FCM: ${messageDto.guid}")

        // Build notification params using shared builder (ensures consistent contact caching)
        val params = notificationParamsBuilder.buildParams(
            messageGuid = messageDto.guid,
            chatGuid = chatGuid,
            messageText = messageText,
            subject = messageDto.subject,
            senderAddress = messageDto.handle?.address,
            fetchLinkPreview = false // Edit notifications don't need link preview
        )

        if (params != null) {
            notificationService.showMessageNotification(params)
        }
    }

    private fun handleFaceTimeCall(data: Map<String, String>) {
        val callUuid = data["callUuid"] ?: data["uuid"]
        val status = data["status"]
        val callerName = data["caller"] ?: data["callerName"]
        val callerAddress = data["handle"] ?: data["callerAddress"]

        if (callUuid.isNullOrBlank()) {
            Timber.w("FaceTime call missing UUID")
            return
        }

        Timber.d("FaceTime call: $callUuid, status: $status")

        when (status?.lowercase()) {
            "incoming", "ringing" -> {
                notificationService.showFaceTimeCallNotification(
                    callUuid = callUuid,
                    callerName = callerName ?: callerAddress?.let { PhoneNumberFormatter.format(it) } ?: "",
                    callerAddress = callerAddress
                )
            }
            "disconnected", "ended" -> {
                notificationService.dismissFaceTimeCallNotification(callUuid)
            }
            else -> {
                Timber.d("Unknown FaceTime status: $status")
            }
        }
    }

    private fun handleServerUpdate(data: Map<String, String>) {
        val version = data["version"]
        Timber.i("Server update notification: $version")
        // Could show a notification about server update available
    }

    private fun handleChatReadStatus(data: Map<String, String>) {
        Timber.d("Chat read status changed via FCM")
        // Socket will handle the full update
        triggerSocketReconnect()
    }

    /**
     * Trigger socket reconnect to sync any missed data.
     * Called when FCM delivers a notification while socket is disconnected.
     */
    private fun triggerSocketReconnect() {
        if (!socketService.isConnected()) {
            Timber.d("Triggering socket reconnect")
            applicationScope.launch(ioDispatcher) {
                // Small delay to avoid immediate reconnect spam
                delay(1000)
                socketService.connect()
            }
        }
    }

    /**
     * Trigger a sync for a specific chat to ensure messages are saved.
     * This heals any gaps caused by stale socket connections - fetching the last N messages
     * ensures we catch not just this message but any others that may have been missed.
     */
    private fun triggerChatSync(chatGuid: String) {
        applicationScope.launch(ioDispatcher) {
            Timber.tag("FcmMessageHandler\$triggerChatSync").d("Triggering chat sync for $chatGuid after FCM")
            messageRepository.syncMessagesForChat(chatGuid, limit = 10)
                .onFailure { Timber.e(it, "Failed to sync chat $chatGuid after FCM") }
        }
    }

    /**
     * Sync a chat and then download the first image attachment for notification preview.
     * The sync must complete first so the attachment record exists in the database.
     */
    private fun syncChatAndDownloadAttachment(chatGuid: String, messageJson: JSONObject) {
        applicationScope.launch(ioDispatcher) {
            Timber.d("Syncing chat $chatGuid before downloading attachment")

            // First, sync to ensure attachment is in the database
            messageRepository.syncMessagesForChat(chatGuid, limit = 10)
                .onFailure {
                    Timber.e(it, "Failed to sync chat $chatGuid after FCM")
                    return@launch
                }

            // Now that sync is complete, enqueue attachment download
            enqueueFirstImageAttachment(messageJson, chatGuid)
        }
    }

    /**
     * Enqueue first image/video attachment for download so notification can update with inline preview.
     * This allows NotificationMediaUpdater to add the media to the notification once downloaded.
     */
    private fun enqueueFirstImageAttachment(messageJson: JSONObject, chatGuid: String) {
        try {
            val attachments = messageJson.optJSONArray("attachments") ?: return
            if (attachments.length() == 0) return

            // Find first image/video attachment (not sticker)
            for (i in 0 until attachments.length()) {
                val attachment = attachments.optJSONObject(i) ?: continue
                val mimeType = attachment.optString("mimeType", "").lowercase()
                val isSticker = attachment.optBoolean("isSticker", false)

                if ((mimeType.startsWith("image/") || mimeType.startsWith("video/")) && !isSticker) {
                    val guid = attachment.optString("guid", "")
                    if (guid.isNotBlank()) {
                        Timber.d("Enqueuing FCM notification attachment download: $guid")
                        attachmentDownloadQueue.enqueue(
                            attachmentGuid = guid,
                            chatGuid = chatGuid,
                            priority = AttachmentDownloadQueue.Priority.IMMEDIATE
                        )
                    }
                    return // Only enqueue first media attachment
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error enqueuing notification attachment")
        }
    }
}
