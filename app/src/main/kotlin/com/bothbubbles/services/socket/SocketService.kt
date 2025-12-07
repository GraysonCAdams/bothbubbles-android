package com.bothbubbles.services.socket

import android.util.Log
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.dto.MessageDto
import com.bothbubbles.services.sound.SoundManager
import com.squareup.moshi.Moshi
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import dagger.Lazy
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Socket.IO connection states
 */
enum class ConnectionState {
    /** Not connected and not attempting to connect */
    DISCONNECTED,
    /** Actively attempting to connect */
    CONNECTING,
    /** Successfully connected to server */
    CONNECTED,
    /** Connection failed with error, will attempt retry */
    ERROR,
    /** Server not configured (no address/password) */
    NOT_CONFIGURED
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
    private val moshi: Moshi,
    private val soundManager: Lazy<SoundManager>
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

        // Retry configuration
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val RETRY_DELAY_MULTIPLIER = 1.5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: Socket? = null
    private var retryJob: Job? = null
    private var currentRetryDelay = INITIAL_RETRY_DELAY_MS
    private var retryCount = 0

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _retryAttempt = MutableStateFlow(0)
    val retryAttempt: StateFlow<Int> = _retryAttempt.asStateFlow()

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val messageAdapter by lazy {
        moshi.adapter(MessageDto::class.java)
    }

    /**
     * Check if server is configured (has address and password)
     */
    suspend fun isServerConfigured(): Boolean {
        val serverAddress = settingsDataStore.serverAddress.first()
        val password = settingsDataStore.serverPassword.first()
        return serverAddress.isNotBlank() && password.isNotBlank()
    }

    /**
     * Connect to the BlueBubbles server
     */
    fun connect() {
        if (socket?.connected() == true) {
            Log.d(TAG, "Already connected")
            return
        }

        // Cancel any pending retry
        retryJob?.cancel()

        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING

                val serverAddress = settingsDataStore.serverAddress.first()
                val password = settingsDataStore.serverPassword.first()

                if (serverAddress.isBlank() || password.isBlank()) {
                    Log.e(TAG, "Server address or password not configured")
                    _connectionState.value = ConnectionState.NOT_CONFIGURED
                    return@launch
                }

                val options = IO.Options().apply {
                    forceNew = true
                    reconnection = true
                    reconnectionAttempts = Int.MAX_VALUE
                    reconnectionDelay = 1000
                    reconnectionDelayMax = 5000
                    timeout = 20000
                    query = "guid=$password"
                    transports = arrayOf("websocket", "polling")
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
                scheduleRetry()
            }
        }
    }

    /**
     * Schedule a retry with exponential backoff
     */
    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            retryCount++
            _retryAttempt.value = retryCount
            Log.d(TAG, "Scheduling retry #$retryCount in ${currentRetryDelay}ms")

            delay(currentRetryDelay)

            // Increase delay for next retry (exponential backoff)
            currentRetryDelay = (currentRetryDelay * RETRY_DELAY_MULTIPLIER)
                .toLong()
                .coerceAtMost(MAX_RETRY_DELAY_MS)

            // Attempt reconnection
            connect()
        }
    }

    /**
     * Reset retry state (called on successful connection)
     */
    private fun resetRetryState() {
        retryJob?.cancel()
        retryCount = 0
        currentRetryDelay = INITIAL_RETRY_DELAY_MS
        _retryAttempt.value = 0
    }

    /**
     * Disconnect from the server
     */
    fun disconnect() {
        retryJob?.cancel()
        socket?.disconnect()
        socket?.off()
        socket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        resetRetryState()
    }

    /**
     * Reconnect to the server
     */
    fun reconnect() {
        resetRetryState()
        disconnect()
        connect()
    }

    /**
     * Force an immediate retry (for user-triggered retry)
     */
    fun retryNow() {
        resetRetryState()
        connect()
    }

    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean = socket?.connected() == true

    // ===== Typing Indicators (Outbound) =====

    /**
     * Send started-typing event to the server.
     * This notifies the other party that we are typing.
     * Requires Private API to be enabled on the server.
     */
    fun sendStartedTyping(chatGuid: String) {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send typing indicator: not connected")
            return
        }

        val payload = JSONObject().apply {
            put("chatGuid", chatGuid)
        }

        socket?.emit("started-typing", payload)
        Log.d(TAG, "Sent started-typing for chat: $chatGuid")
    }

    /**
     * Send stopped-typing event to the server.
     * This notifies the other party that we stopped typing.
     * Requires Private API to be enabled on the server.
     */
    fun sendStoppedTyping(chatGuid: String) {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send typing indicator: not connected")
            return
        }

        val payload = JSONObject().apply {
            put("chatGuid", chatGuid)
        }

        socket?.emit("stopped-typing", payload)
        Log.d(TAG, "Sent stopped-typing for chat: $chatGuid")
    }

    // ===== Socket Event Handlers =====

    private val onConnect = Emitter.Listener {
        Log.d(TAG, "Connected to server")
        resetRetryState()
        _connectionState.value = ConnectionState.CONNECTED
    }

    private val onDisconnect = Emitter.Listener { args ->
        Log.d(TAG, "Disconnected from server: ${args.firstOrNull()}")
        _connectionState.value = ConnectionState.DISCONNECTED
        // Schedule retry on unexpected disconnect
        scheduleRetry()
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.firstOrNull()
        Log.e(TAG, "Connection error: $error")
        _connectionState.value = ConnectionState.ERROR
        _events.tryEmit(SocketEvent.Error(error?.toString() ?: "Connection error"))
        // Schedule retry on connection error
        scheduleRetry()
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

                // Play receive sound for messages from others (not from me)
                if (message.isFromMe != true) {
                    soundManager.get().playReceiveSound()
                }
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
