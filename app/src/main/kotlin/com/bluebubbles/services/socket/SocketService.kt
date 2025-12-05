package com.bluebubbles.services.socket

import android.util.Log
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.data.remote.api.dto.MessageDto
import com.squareup.moshi.Moshi
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Socket.IO connection states
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Events that can be received from the server
 */
sealed class SocketEvent {
    data class NewMessage(val message: MessageDto, val chatGuid: String) : SocketEvent()
    data class MessageUpdated(val message: MessageDto, val chatGuid: String) : SocketEvent()
    data class MessageDeleted(val messageGuid: String, val chatGuid: String) : SocketEvent()
    data class TypingIndicator(val chatGuid: String, val isTyping: Boolean) : SocketEvent()
    data class ChatRead(val chatGuid: String) : SocketEvent()
    data class ParticipantAdded(val chatGuid: String, val handleAddress: String) : SocketEvent()
    data class ParticipantRemoved(val chatGuid: String, val handleAddress: String) : SocketEvent()
    data class GroupNameChanged(val chatGuid: String, val newName: String) : SocketEvent()
    data class GroupIconChanged(val chatGuid: String) : SocketEvent()
    data class ServerUpdate(val version: String) : SocketEvent()
    data class FaceTimeCall(
        val callUuid: String,
        val callerName: String?,
        val callerAddress: String?,
        val status: FaceTimeCallStatus
    ) : SocketEvent()
    data class Error(val message: String) : SocketEvent()
}

/**
 * FaceTime call status values
 */
enum class FaceTimeCallStatus {
    INCOMING,
    CONNECTED,
    DISCONNECTED,
    RINGING,
    UNKNOWN
}

@Singleton
class SocketService @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val moshi: Moshi
) {
    companion object {
        private const val TAG = "SocketService"

        // Socket.IO event names from BlueBubbles server
        private const val EVENT_NEW_MESSAGE = "new-message"
        private const val EVENT_MESSAGE_UPDATED = "updated-message"
        private const val EVENT_MESSAGE_DELETED = "message-deleted"
        private const val EVENT_TYPING_INDICATOR = "typing-indicator"
        private const val EVENT_CHAT_READ = "chat-read-status-changed"
        private const val EVENT_PARTICIPANT_ADDED = "participant-added"
        private const val EVENT_PARTICIPANT_REMOVED = "participant-removed"
        private const val EVENT_GROUP_NAME_CHANGED = "group-name-change"
        private const val EVENT_GROUP_ICON_CHANGED = "group-icon-changed"
        private const val EVENT_SERVER_UPDATE = "server-update"
        private const val EVENT_FACETIME_CALL = "ft-call-status-changed"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var socket: Socket? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val messageAdapter by lazy {
        moshi.adapter(MessageDto::class.java)
    }

    /**
     * Connect to the BlueBubbles server
     */
    fun connect() {
        if (socket?.connected() == true) {
            Log.d(TAG, "Already connected")
            return
        }

        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING

                val serverAddress = settingsDataStore.serverAddress.first()
                val password = settingsDataStore.serverPassword.first()

                if (serverAddress.isBlank() || password.isBlank()) {
                    Log.e(TAG, "Server address or password not configured")
                    _connectionState.value = ConnectionState.ERROR
                    return@launch
                }

                val options = IO.Options().apply {
                    forceNew = true
                    reconnection = true
                    reconnectionAttempts = Int.MAX_VALUE
                    reconnectionDelay = 1000
                    reconnectionDelayMax = 5000
                    timeout = 20000
                    auth = mapOf("password" to password)
                }

                socket = IO.socket(URI.create(serverAddress), options).apply {
                    on(Socket.EVENT_CONNECT, onConnect)
                    on(Socket.EVENT_DISCONNECT, onDisconnect)
                    on(Socket.EVENT_CONNECT_ERROR, onConnectError)

                    // BlueBubbles events
                    on(EVENT_NEW_MESSAGE, onNewMessage)
                    on(EVENT_MESSAGE_UPDATED, onMessageUpdated)
                    on(EVENT_MESSAGE_DELETED, onMessageDeleted)
                    on(EVENT_TYPING_INDICATOR, onTypingIndicator)
                    on(EVENT_CHAT_READ, onChatRead)
                    on(EVENT_PARTICIPANT_ADDED, onParticipantAdded)
                    on(EVENT_PARTICIPANT_REMOVED, onParticipantRemoved)
                    on(EVENT_GROUP_NAME_CHANGED, onGroupNameChanged)
                    on(EVENT_GROUP_ICON_CHANGED, onGroupIconChanged)
                    on(EVENT_SERVER_UPDATE, onServerUpdate)
                    on(EVENT_FACETIME_CALL, onFaceTimeCall)

                    connect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect", e)
                _connectionState.value = ConnectionState.ERROR
                _events.tryEmit(SocketEvent.Error(e.message ?: "Connection failed"))
            }
        }
    }

    /**
     * Disconnect from the server
     */
    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Reconnect to the server
     */
    fun reconnect() {
        disconnect()
        connect()
    }

    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean = socket?.connected() == true

    // ===== Socket Event Handlers =====

    private val onConnect = Emitter.Listener {
        Log.d(TAG, "Connected to server")
        _connectionState.value = ConnectionState.CONNECTED
    }

    private val onDisconnect = Emitter.Listener { args ->
        Log.d(TAG, "Disconnected from server: ${args.firstOrNull()}")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.firstOrNull()
        Log.e(TAG, "Connection error: $error")
        _connectionState.value = ConnectionState.ERROR
        _events.tryEmit(SocketEvent.Error(error?.toString() ?: "Connection error"))
    }

    private val onNewMessage = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageJson = data.optJSONObject("message") ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")

            val message = messageAdapter.fromJson(messageJson.toString())
            if (message != null) {
                Log.d(TAG, "New message received: ${message.guid}")
                _events.tryEmit(SocketEvent.NewMessage(message, chatGuid))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing new message", e)
        }
    }

    private val onMessageUpdated = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageJson = data.optJSONObject("message") ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")

            val message = messageAdapter.fromJson(messageJson.toString())
            if (message != null) {
                Log.d(TAG, "Message updated: ${message.guid}")
                _events.tryEmit(SocketEvent.MessageUpdated(message, chatGuid))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message update", e)
        }
    }

    private val onMessageDeleted = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageGuid = data.optString("messageGuid", "")
            val chatGuid = data.optString("chatGuid", "")

            if (messageGuid.isNotBlank()) {
                Log.d(TAG, "Message deleted: $messageGuid")
                _events.tryEmit(SocketEvent.MessageDeleted(messageGuid, chatGuid))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message deletion", e)
        }
    }

    private val onTypingIndicator = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val isTyping = data.optBoolean("display", false)

            if (chatGuid.isNotBlank()) {
                _events.tryEmit(SocketEvent.TypingIndicator(chatGuid, isTyping))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing typing indicator", e)
        }
    }

    private val onChatRead = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")

            if (chatGuid.isNotBlank()) {
                _events.tryEmit(SocketEvent.ChatRead(chatGuid))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chat read status", e)
        }
    }

    private val onParticipantAdded = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val handleAddress = data.optString("handle", "")

            if (chatGuid.isNotBlank() && handleAddress.isNotBlank()) {
                _events.tryEmit(SocketEvent.ParticipantAdded(chatGuid, handleAddress))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing participant added", e)
        }
    }

    private val onParticipantRemoved = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val handleAddress = data.optString("handle", "")

            if (chatGuid.isNotBlank() && handleAddress.isNotBlank()) {
                _events.tryEmit(SocketEvent.ParticipantRemoved(chatGuid, handleAddress))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing participant removed", e)
        }
    }

    private val onGroupNameChanged = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val newName = data.optString("newName", "")

            if (chatGuid.isNotBlank()) {
                _events.tryEmit(SocketEvent.GroupNameChanged(chatGuid, newName))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing group name change", e)
        }
    }

    private val onGroupIconChanged = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")

            if (chatGuid.isNotBlank()) {
                _events.tryEmit(SocketEvent.GroupIconChanged(chatGuid))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing group icon change", e)
        }
    }

    private val onServerUpdate = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val version = data.optString("version", "")

            if (version.isNotBlank()) {
                _events.tryEmit(SocketEvent.ServerUpdate(version))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server update", e)
        }
    }

    private val onFaceTimeCall = Emitter.Listener { args ->
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
                _events.tryEmit(SocketEvent.FaceTimeCall(callUuid, callerName, callerAddress, status))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing FaceTime call event", e)
        }
    }
}
