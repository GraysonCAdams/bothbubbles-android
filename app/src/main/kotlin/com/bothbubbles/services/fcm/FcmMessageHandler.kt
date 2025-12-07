package com.bothbubbles.services.fcm

import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.util.PhoneNumberFormatter
import com.google.firebase.messaging.RemoteMessage
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
    private val chatDao: ChatDao
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

        if (type.isNullOrBlank()) {
            Log.w(TAG, "FCM message has no type, ignoring")
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
            }
            else -> {
                Log.d(TAG, "Unhandled FCM message type: $type")
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
        val senderName = data["senderName"] ?: data["handle"]
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

        // Get chat info from local database
        val chat = chatDao.getChatByGuid(chatGuid)

        // Check if chat is snoozed
        if (chat?.isSnoozed == true) {
            Log.d(TAG, "Chat $chatGuid is snoozed, skipping FCM notification")
            triggerSocketReconnect()
            return
        }

        val chatTitle = chat?.displayName ?: chat?.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: senderName ?: ""

        // Show notification
        notificationService.showMessageNotification(
            chatGuid = chatGuid,
            chatTitle = chatTitle,
            messageText = messageText,
            messageGuid = messageGuid,
            senderName = senderName,
            senderAddress = data["handle"]
        )

        // Trigger socket reconnect to sync full data
        triggerSocketReconnect()
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
}
