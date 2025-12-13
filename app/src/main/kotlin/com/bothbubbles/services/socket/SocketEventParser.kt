package com.bothbubbles.services.socket

import android.util.Log
import com.bothbubbles.data.remote.api.dto.MessageDto
import com.squareup.moshi.Moshi
import io.socket.emitter.Emitter
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import dagger.Lazy
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.sound.SoundManager

/**
 * Parses incoming Socket.IO events from the BlueBubbles server and emits SocketEvent objects.
 * This class handles all event parsing logic, keeping the main SocketService focused on
 * connection management.
 */
class SocketEventParser(
    private val moshi: Moshi,
    private val events: MutableSharedFlow<SocketEvent>,
    private val soundManager: Lazy<SoundManager>,
    private val developerEventLog: Lazy<DeveloperEventLog>
) {
    companion object {
        private const val TAG = "SocketEventParser"
    }

    private val messageAdapter by lazy {
        moshi.adapter(MessageDto::class.java)
    }

    // ===== Message Events =====

    val onNewMessage = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: run {
                Log.w(TAG, "new-message: first arg is not JSONObject: ${args.firstOrNull()?.javaClass?.name}")
                return@Listener
            }

            // Server sends message directly, not wrapped in {"message": {...}}
            // The message itself contains a "chats" array with the chat info
            val message = messageAdapter.fromJson(data.toString())
            if (message == null) {
                Log.w(TAG, "new-message: Failed to parse message from: ${data.toString().take(200)}")
                return@Listener
            }

            // Get chatGuid from the message's chats array
            val chatGuid = message.chats?.firstOrNull()?.guid ?: ""

            Log.d(TAG, "New message received: ${message.guid} for chat: $chatGuid")
            events.tryEmit(SocketEvent.NewMessage(message, chatGuid))
            developerEventLog.get().logSocketEvent("new-message", "guid: ${message.guid?.take(20)}...")

            // Play receive sound for messages from others (not from me)
            // Only plays if user is viewing this conversation; otherwise notification handles sound
            if (message.isFromMe != true) {
                soundManager.get().playReceiveSound(chatGuid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing new message", e)
        }
    }

    val onMessageUpdated = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener

            // Server sends message directly, not wrapped
            val message = messageAdapter.fromJson(data.toString())
            if (message != null) {
                val chatGuid = message.chats?.firstOrNull()?.guid ?: ""
                Log.d(TAG, "Message updated: ${message.guid}")
                events.tryEmit(SocketEvent.MessageUpdated(message, chatGuid))
                developerEventLog.get().logSocketEvent("updated-message", "guid: ${message.guid?.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message update", e)
        }
    }

    val onMessageDeleted = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageGuid = data.optString("messageGuid", "")
            val chatGuid = data.optString("chatGuid", "")

            if (messageGuid.isNotBlank()) {
                Log.d(TAG, "Message deleted: $messageGuid")
                events.tryEmit(SocketEvent.MessageDeleted(messageGuid, chatGuid))
                developerEventLog.get().logSocketEvent("message-deleted", "guid: ${messageGuid.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message deletion", e)
        }
    }

    val onMessageSendError = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val tempGuid = data.optString("tempGuid", data.optString("guid", ""))
            val errorMessage = data.optString("error", data.optString("message", "Send failed"))

            if (tempGuid.isNotBlank()) {
                Log.e(TAG, "Message send error: $tempGuid - $errorMessage")
                events.tryEmit(SocketEvent.MessageSendError(tempGuid, errorMessage))
                developerEventLog.get().logSocketEvent("message-send-error", "guid: ${tempGuid.take(20)}..., error: $errorMessage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message send error", e)
        }
    }

    // ===== Chat Events =====

    val onTypingIndicator = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            // Server sends "guid" not "chatGuid" for typing indicators
            val chatGuid = data.optString("guid", "").ifBlank {
                data.optString("chatGuid", "")
            }
            val isTyping = data.optBoolean("display", false)

            if (chatGuid.isNotBlank()) {
                events.tryEmit(SocketEvent.TypingIndicator(chatGuid, isTyping))
                developerEventLog.get().logSocketEvent("typing-indicator", "chat: ${chatGuid.take(20)}..., typing: $isTyping")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing typing indicator", e)
        }
    }

    val onChatRead = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")

            if (chatGuid.isNotBlank()) {
                events.tryEmit(SocketEvent.ChatRead(chatGuid))
                developerEventLog.get().logSocketEvent("chat-read-status-changed", "chat: ${chatGuid.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chat read status", e)
        }
    }

    // ===== Group Events =====

    val onParticipantAdded = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val handleAddress = data.optString("handle", "")

            if (chatGuid.isNotBlank() && handleAddress.isNotBlank()) {
                events.tryEmit(SocketEvent.ParticipantAdded(chatGuid, handleAddress))
                developerEventLog.get().logSocketEvent("participant-added", "chat: ${chatGuid.take(20)}..., handle: $handleAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing participant added", e)
        }
    }

    val onParticipantRemoved = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val handleAddress = data.optString("handle", "")

            if (chatGuid.isNotBlank() && handleAddress.isNotBlank()) {
                events.tryEmit(SocketEvent.ParticipantRemoved(chatGuid, handleAddress))
                developerEventLog.get().logSocketEvent("participant-removed", "chat: ${chatGuid.take(20)}..., handle: $handleAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing participant removed", e)
        }
    }

    val onParticipantLeft = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val handleAddress = data.optString("handle", "")

            if (chatGuid.isNotBlank() && handleAddress.isNotBlank()) {
                Log.d(TAG, "Participant left: $handleAddress from $chatGuid")
                events.tryEmit(SocketEvent.ParticipantLeft(chatGuid, handleAddress))
                developerEventLog.get().logSocketEvent("participant-left", "chat: ${chatGuid.take(20)}..., handle: $handleAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing participant left", e)
        }
    }

    val onGroupNameChanged = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val newName = data.optString("newName", "")

            if (chatGuid.isNotBlank()) {
                events.tryEmit(SocketEvent.GroupNameChanged(chatGuid, newName))
                developerEventLog.get().logSocketEvent("group-name-change", "chat: ${chatGuid.take(20)}..., name: $newName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing group name change", e)
        }
    }

    val onGroupIconChanged = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")

            if (chatGuid.isNotBlank()) {
                events.tryEmit(SocketEvent.GroupIconChanged(chatGuid))
                developerEventLog.get().logSocketEvent("group-icon-changed", "chat: ${chatGuid.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing group icon change", e)
        }
    }

    val onGroupIconRemoved = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")

            if (chatGuid.isNotBlank()) {
                Log.d(TAG, "Group icon removed: $chatGuid")
                events.tryEmit(SocketEvent.GroupIconRemoved(chatGuid))
                developerEventLog.get().logSocketEvent("group-icon-removed", "chat: ${chatGuid.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing group icon removed", e)
        }
    }

    // ===== System Events =====

    val onServerUpdate = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val version = data.optString("version", "")

            if (version.isNotBlank()) {
                events.tryEmit(SocketEvent.ServerUpdate(version))
                developerEventLog.get().logSocketEvent("server-update", "version: $version")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server update", e)
        }
    }

    val onICloudAccountStatus = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val alias = data.optString("alias", null)
            val active = data.optBoolean("active", true)

            Log.i(TAG, "iCloud account status: alias=$alias, active=$active")
            events.tryEmit(SocketEvent.ICloudAccountStatus(alias, active))
            developerEventLog.get().logSocketEvent("icloud-account", "alias: $alias, active: $active")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing iCloud account status", e)
        }
    }

    // ===== FaceTime Events =====

    val onIncomingFaceTime = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val caller = data.optString("caller", data.optString("handle", ""))
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())

            if (caller.isNotBlank()) {
                Log.d(TAG, "Incoming FaceTime from: $caller")
                events.tryEmit(SocketEvent.IncomingFaceTime(caller, timestamp))
                developerEventLog.get().logSocketEvent("incoming-facetime", "caller: $caller")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming FaceTime", e)
        }
    }

    val onFaceTimeCall = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val callUuid = data.optString("callUuid", "")
            val callerName = data.optString("caller", null)
            val callerAddress = data.optString("handle", null)
            val statusString = data.optString("status", "").lowercase()

            val status = when (statusString) {
                "incoming" -> FaceTimeCallStatus.INCOMING
                "connected" -> FaceTimeCallStatus.CONNECTED
                "disconnected" -> FaceTimeCallStatus.DISCONNECTED
                "ringing" -> FaceTimeCallStatus.RINGING
                else -> FaceTimeCallStatus.UNKNOWN
            }

            if (callUuid.isNotBlank()) {
                Log.d(TAG, "FaceTime call: $callUuid, status: $status")
                events.tryEmit(SocketEvent.FaceTimeCall(callUuid, callerName, callerAddress, status))
                developerEventLog.get().logSocketEvent("ft-call-status-changed", "uuid: ${callUuid.take(8)}..., status: $status")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing FaceTime call event", e)
        }
    }

    // ===== Scheduled Message Events =====

    val onScheduledMessageCreated = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageId = data.optLong("id", -1)
            val chatGuid = data.optString("chatGuid", "")
            val text = data.optString("text", null)
            val scheduledAt = data.optLong("scheduledFor", 0)

            if (messageId >= 0 && chatGuid.isNotBlank()) {
                Log.d(TAG, "Scheduled message created: $messageId for chat $chatGuid at $scheduledAt")
                events.tryEmit(SocketEvent.ScheduledMessageCreated(messageId, chatGuid, text, scheduledAt))
                developerEventLog.get().logSocketEvent("scheduled-message-created", "id: $messageId, chat: ${chatGuid.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scheduled message created", e)
        }
    }

    val onScheduledMessageSent = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageId = data.optLong("id", -1)
            val chatGuid = data.optString("chatGuid", "")
            val sentMessageGuid = data.optString("messageGuid", null)

            if (messageId >= 0) {
                Log.d(TAG, "Scheduled message sent: $messageId -> $sentMessageGuid")
                events.tryEmit(SocketEvent.ScheduledMessageSent(messageId, chatGuid, sentMessageGuid))
                developerEventLog.get().logSocketEvent("scheduled-message-sent", "id: $messageId, msgGuid: ${sentMessageGuid?.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scheduled message sent", e)
        }
    }

    val onScheduledMessageError = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageId = data.optLong("id", -1)
            val chatGuid = data.optString("chatGuid", "")
            val errorMessage = data.optString("error", "Unknown error")

            if (messageId >= 0) {
                Log.e(TAG, "Scheduled message error: $messageId - $errorMessage")
                events.tryEmit(SocketEvent.ScheduledMessageError(messageId, chatGuid, errorMessage))
                developerEventLog.get().logSocketEvent("scheduled-message-error", "id: $messageId, error: $errorMessage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scheduled message error", e)
        }
    }

    val onScheduledMessageDeleted = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageId = data.optLong("id", -1)
            val chatGuid = data.optString("chatGuid", "")

            if (messageId >= 0) {
                Log.d(TAG, "Scheduled message deleted: $messageId")
                events.tryEmit(SocketEvent.ScheduledMessageDeleted(messageId, chatGuid))
                developerEventLog.get().logSocketEvent("scheduled-message-deleted", "id: $messageId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scheduled message deleted", e)
        }
    }
}
