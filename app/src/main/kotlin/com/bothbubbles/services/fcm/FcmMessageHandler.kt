package com.bothbubbles.services.fcm

import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.util.MessageDeduplicator
import com.bothbubbles.util.PhoneNumberFormatter
import com.google.firebase.messaging.RemoteMessage
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val developerEventLog: Lazy<DeveloperEventLog>
) {
    companion object {
        private const val TAG = "FcmMessageHandler"

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Handle an incoming FCM message.
     */
    suspend fun handleMessage(message: RemoteMessage) {
        val type = message.data["type"]
        Log.d(TAG, "Received FCM message type: $type")
        developerEventLog.get().logFcmEvent(type ?: "unknown", "FCM push received")

        if (type.isNullOrBlank()) {
            Log.w(TAG, "FCM message has no type, ignoring")
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
                Log.d(TAG, "Ignoring typing indicator from FCM")
                developerEventLog.get().logFcmEvent(type, "Ignored (socket-only)")
            }
            else -> {
                Log.d(TAG, "Unhandled FCM message type: $type")
                developerEventLog.get().logFcmEvent(type, "Unhandled type")
                // Trigger socket reconnect for unhandled events that might need full data
                triggerSocketReconnect()
            }
        }
    }

    private suspend fun handleNewMessage(data: Map<String, String>) {
        // If socket is connected, it will handle the message with full data
        if (socketService.isConnected()) {
            Log.d(TAG, "Socket connected, skipping FCM notification")
            return
        }

        // Extract basic info from FCM payload
        val chatGuid = data["chatGuid"]
        val messageGuid = data["guid"] ?: data["messageGuid"]
        val messageText = data["text"] ?: data["message"] ?: ""
        val senderAddress = data["handle"]
        val isFromMe = data["isFromMe"]?.toBoolean() ?: false

        if (chatGuid.isNullOrBlank() || messageGuid.isNullOrBlank()) {
            Log.w(TAG, "FCM new-message missing required fields")
            triggerSocketReconnect()
            return
        }

        // Skip if message is from me
        if (isFromMe) {
            Log.d(TAG, "Skipping notification for own message")
            return
        }

        // Check for duplicate notification (message may arrive via both FCM and socket)
        if (!messageDeduplicator.shouldNotifyForMessage(messageGuid)) {
            Log.d(TAG, "Message $messageGuid already notified, skipping FCM duplicate notification")
            triggerSocketReconnect()
            return
        }

        // Check if user is currently viewing this conversation
        if (activeConversationManager.isConversationActive(chatGuid)) {
            Log.d(TAG, "Chat $chatGuid is currently active, skipping FCM notification")
            triggerSocketReconnect()
            return
        }

        // Get chat info from local database
        val chat = chatDao.getChatByGuid(chatGuid)

        // Check if notifications are disabled for this chat
        if (chat?.notificationsEnabled == false) {
            Log.d(TAG, "Notifications disabled for chat $chatGuid, skipping FCM notification")
            triggerSocketReconnect()
            return
        }

        // Check if chat is snoozed
        if (chat?.isSnoozed == true) {
            Log.d(TAG, "Chat $chatGuid is snoozed, skipping FCM notification")
            triggerSocketReconnect()
            return
        }

        // Resolve sender name - try contact lookup first
        val senderName = resolveSenderName(senderAddress, data["senderName"])

        val chatTitle = chat?.displayName ?: chat?.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: senderName ?: ""

        // Check for invisible ink effect
        val expressiveSendStyleId = data["expressiveSendStyleId"]
        val isInvisibleInk = MessageEffect.fromStyleId(expressiveSendStyleId) == MessageEffect.Bubble.InvisibleInk
        val hasAttachments = data["hasAttachments"]?.toBoolean() ?: false
        val notificationText = if (isInvisibleInk) {
            if (hasAttachments) "Image sent with Invisible Ink" else "Message sent with Invisible Ink"
        } else {
            messageText
        }

        // For group chats, extract first name for cleaner notification display
        val isGroup = chat?.isGroup ?: false
        val displaySenderName = if (isGroup && senderName != null) {
            extractFirstName(senderName)
        } else {
            senderName
        }

        // Show notification
        notificationService.showMessageNotification(
            chatGuid = chatGuid,
            chatTitle = chatTitle,
            messageText = notificationText,
            messageGuid = messageGuid,
            senderName = displaySenderName,
            senderAddress = senderAddress,
            isGroup = isGroup
        )

        // Trigger socket reconnect to sync full data
        triggerSocketReconnect()
    }

    /**
     * Resolve sender name from address.
     * Priority: device contact > cached contact name > server-provided name > formatted address
     */
    private suspend fun resolveSenderName(address: String?, serverProvidedName: String?): String? {
        if (address.isNullOrBlank()) return serverProvidedName

        // Try live contact lookup first
        val contactName = androidContactsService.getContactDisplayName(address)
        if (contactName != null) {
            // Cache the contact name for future lookups
            val localHandle = handleDao.getHandlesByAddress(address).firstOrNull()
            localHandle?.let { handle ->
                val photoUri = androidContactsService.getContactPhotoUri(address)
                handleDao.updateCachedContactInfo(handle.id, contactName, photoUri)
            }
            return contactName
        }

        // Check for cached contact name in local handle
        val localHandle = handleDao.getHandlesByAddress(address).firstOrNull()
        if (localHandle?.cachedDisplayName != null) {
            return localHandle.cachedDisplayName
        }

        // Fall back to inferred name
        if (localHandle?.inferredName != null) {
            return localHandle.displayName // includes "Maybe:" prefix
        }

        // Fall back to server-provided name or formatted address
        return serverProvidedName ?: PhoneNumberFormatter.format(address)
    }

    private fun handleUpdatedMessage(data: Map<String, String>) {
        Log.d(TAG, "Message updated via FCM: ${data["guid"]}")
        // Trigger socket reconnect to get full update
        triggerSocketReconnect()
    }

    private fun handleFaceTimeCall(data: Map<String, String>) {
        val callUuid = data["callUuid"] ?: data["uuid"]
        val status = data["status"]
        val callerName = data["caller"] ?: data["callerName"]
        val callerAddress = data["handle"] ?: data["callerAddress"]

        if (callUuid.isNullOrBlank()) {
            Log.w(TAG, "FaceTime call missing UUID")
            return
        }

        Log.d(TAG, "FaceTime call: $callUuid, status: $status")

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
                Log.d(TAG, "Unknown FaceTime status: $status")
            }
        }
    }

    private fun handleServerUpdate(data: Map<String, String>) {
        val version = data["version"]
        Log.i(TAG, "Server update notification: $version")
        // Could show a notification about server update available
    }

    private fun handleChatReadStatus(data: Map<String, String>) {
        Log.d(TAG, "Chat read status changed via FCM")
        // Socket will handle the full update
        triggerSocketReconnect()
    }

    /**
     * Trigger socket reconnect to sync any missed data.
     * Called when FCM delivers a notification while socket is disconnected.
     */
    private fun triggerSocketReconnect() {
        if (!socketService.isConnected()) {
            Log.d(TAG, "Triggering socket reconnect")
            scope.launch {
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
