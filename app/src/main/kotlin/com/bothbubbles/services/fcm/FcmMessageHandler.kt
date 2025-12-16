package com.bothbubbles.services.fcm

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.local.db.entity.rawDisplayName
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.util.MessageDeduplicator
import com.bothbubbles.util.PhoneNumberFormatter
import com.google.firebase.messaging.RemoteMessage
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
 * If the Socket.IO connection is active, notifications are skipped since
 * the socket will handle message delivery with full data.
 *
 * FCM serves as a backup wake mechanism when the socket is disconnected.
 */
@Singleton
class FcmMessageHandler @Inject constructor(
    private val socketService: SocketService,
    private val notificationService: NotificationService,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val androidContactsService: AndroidContactsService,
    private val messageDeduplicator: MessageDeduplicator,
    private val activeConversationManager: ActiveConversationManager,
    private val developerEventLog: Lazy<DeveloperEventLog>,
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
        // BlueBubbles server sends message data as a JSON string in the "data" field
        val dataJsonString = data["data"]
        if (dataJsonString.isNullOrBlank()) {
            Timber.w("FCM new-message missing 'data' field")
            triggerSocketReconnect()
            return
        }

        // Parse the JSON data
        val messageJson: JSONObject
        try {
            messageJson = JSONObject(dataJsonString)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse FCM message JSON")
            triggerSocketReconnect()
            return
        }

        // Extract basic info from parsed JSON
        val messageGuid = messageJson.optString("guid", "")
        val messageText = messageJson.optString("text", "")
        val isFromMe = messageJson.optBoolean("isFromMe", false)
        val expressiveSendStyleId = messageJson.optString("expressiveSendStyleId", null)
        val hasAttachments = messageJson.optJSONArray("attachments")?.length()?.let { it > 0 } ?: false

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

        // If socket is connected, it will handle the message with full data
        if (socketService.isConnected()) {
            Timber.d("Socket connected, skipping FCM notification (socket will handle)")
            return
        }

        // Skip if message is from me
        if (isFromMe) {
            Timber.d("Skipping notification for own message")
            return
        }

        // Check for duplicate notification (message may arrive via both FCM and socket)
        if (!messageDeduplicator.shouldNotifyForMessage(messageGuid)) {
            Timber.d("Message $messageGuid already notified, skipping FCM duplicate notification")
            triggerSocketReconnect()
            return
        }

        // Check if user is currently viewing this conversation
        if (activeConversationManager.isConversationActive(chatGuid)) {
            Timber.d("Chat $chatGuid is currently active, skipping FCM notification")
            triggerSocketReconnect()
            return
        }

        // Get chat info from local database
        val chat = chatDao.getChatByGuid(chatGuid)

        // Check if notifications are disabled for this chat
        if (chat?.notificationsEnabled == false) {
            Timber.d("Notifications disabled for chat $chatGuid, skipping FCM notification")
            triggerSocketReconnect()
            return
        }

        // Check if chat is snoozed
        if (chat?.isSnoozed == true) {
            Timber.d("Chat $chatGuid is snoozed, skipping FCM notification")
            triggerSocketReconnect()
            return
        }

        // Resolve sender name and avatar - try contact lookup first
        val (senderName, senderAvatarUri) = resolveSenderNameAndAvatar(senderAddress, null)

        // For 1:1 chats, use sender's contact name as title; for groups, use group name
        val isGroup = chat?.isGroup ?: false
        val chatTitle = if (isGroup) {
            chat?.displayName ?: chat?.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: ""
        } else {
            senderName
                ?: chat?.displayName
                ?: chat?.chatIdentifier?.let { PhoneNumberFormatter.format(it) }
                ?: ""
        }

        // Check for invisible ink effect (expressiveSendStyleId and hasAttachments already extracted above)
        val isInvisibleInk = MessageEffect.fromStyleId(expressiveSendStyleId) == MessageEffect.Bubble.InvisibleInk
        val notificationText = if (isInvisibleInk) {
            if (hasAttachments) "Image sent with Invisible Ink" else "Message sent with Invisible Ink"
        } else {
            messageText
        }

        // For group chats, extract first name for cleaner notification display
        val displaySenderName = if (isGroup && senderName != null) {
            extractFirstName(senderName)
        } else {
            senderName
        }

        // For group chats, fetch participant names for the group avatar collage
        val participantNames = if (isGroup) {
            chatDao.getParticipantsForChat(chatGuid).map { it.rawDisplayName }
        } else {
            emptyList()
        }

        // Show notification
        Timber.d("DEBUG: Showing notification - chatTitle=$chatTitle, notificationText=$notificationText, displaySenderName=$displaySenderName, isGroup=$isGroup")
        notificationService.showMessageNotification(
            chatGuid = chatGuid,
            chatTitle = chatTitle,
            messageText = notificationText,
            messageGuid = messageGuid,
            senderName = displaySenderName,
            senderAddress = senderAddress,
            isGroup = isGroup,
            avatarUri = senderAvatarUri,
            participantNames = participantNames
        )
        Timber.d("DEBUG: Notification shown successfully!")

        // Trigger socket reconnect to sync full data
        triggerSocketReconnect()
    }

    /**
     * Resolve sender name and avatar from address.
     * Priority: device contact > cached contact name > server-provided name > formatted address
     * Returns Pair of (name, avatarUri)
     */
    private suspend fun resolveSenderNameAndAvatar(address: String?, serverProvidedName: String?): Pair<String?, String?> {
        if (address.isNullOrBlank()) return serverProvidedName to null

        // Try live contact lookup first
        val contactName = androidContactsService.getContactDisplayName(address)
        if (contactName != null) {
            val photoUri = androidContactsService.getContactPhotoUri(address)
            // Cache the contact name for future lookups
            val localHandle = handleDao.getHandlesByAddress(address).firstOrNull()
            localHandle?.let { handle ->
                handleDao.updateCachedContactInfo(handle.id, contactName, photoUri)
            }
            return contactName to photoUri
        }

        // Check for cached contact info in local handle
        val localHandle = handleDao.getHandlesByAddress(address).firstOrNull()
        if (localHandle?.cachedDisplayName != null) {
            return localHandle.cachedDisplayName to localHandle.cachedAvatarPath
        }

        // Fall back to inferred name
        if (localHandle?.inferredName != null) {
            return localHandle.displayName to localHandle.cachedAvatarPath // includes "Maybe:" prefix
        }

        // Fall back to server-provided name or formatted address
        return (serverProvidedName ?: PhoneNumberFormatter.format(address)) to null
    }

    private fun handleUpdatedMessage(data: Map<String, String>) {
        Timber.d("Message updated via FCM: ${data["guid"]}")
        // Trigger socket reconnect to get full update
        triggerSocketReconnect()
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
     * Extract the first name from a full name, excluding emojis and non-letter characters.
     */
    private fun extractFirstName(fullName: String): String {
        val words = fullName.trim().split(Regex("\\s+"))
        for (word in words) {
            val cleaned = word.filter { it.isLetterOrDigit() }
            if (cleaned.isNotEmpty() && cleaned.any { it.isLetter() }) {
                return cleaned
            }
        }
        return words.firstOrNull()?.filter { it.isLetterOrDigit() } ?: fullName
    }
}
