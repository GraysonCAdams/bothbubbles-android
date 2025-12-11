package com.bothbubbles.services.socket

import android.util.Log
import com.bothbubbles.BuildConfig
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.dto.MessageDto
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.sound.SoundManager
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
import okhttp3.OkHttpClient
import org.json.JSONObject
import dagger.Lazy
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

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
    /** Server-side message send failure (faster than waiting for REST response) */
    data class MessageSendError(val tempGuid: String, val errorMessage: String) : SocketEvent()
    data class TypingIndicator(val chatGuid: String, val isTyping: Boolean) : SocketEvent()
    data class ChatRead(val chatGuid: String) : SocketEvent()
    data class ParticipantAdded(val chatGuid: String, val handleAddress: String) : SocketEvent()
    data class ParticipantRemoved(val chatGuid: String, val handleAddress: String) : SocketEvent()
    /** User voluntarily left group (distinct from being removed) */
    data class ParticipantLeft(val chatGuid: String, val handleAddress: String) : SocketEvent()
    data class GroupNameChanged(val chatGuid: String, val newName: String) : SocketEvent()
    data class GroupIconChanged(val chatGuid: String) : SocketEvent()
    /** Group icon was removed (distinct from changed) */
    data class GroupIconRemoved(val chatGuid: String) : SocketEvent()
    data class ServerUpdate(val version: String) : SocketEvent()
    /** iCloud account status change (logged in/out) */
    data class ICloudAccountStatus(val alias: String?, val active: Boolean) : SocketEvent()
    /** Standard FaceTime incoming call (non-Private API mode) */
    data class IncomingFaceTime(val caller: String, val timestamp: Long) : SocketEvent()
    /** Advanced FaceTime call status (Private API mode) */
    data class FaceTimeCall(
        val callUuid: String,
        val callerName: String?,
        val callerAddress: String?,
        val status: FaceTimeCallStatus
    ) : SocketEvent()

    // Server-side scheduled message events
    data class ScheduledMessageCreated(val messageId: Long, val chatGuid: String, val text: String?, val scheduledAt: Long) : SocketEvent()
    data class ScheduledMessageSent(val messageId: Long, val chatGuid: String, val sentMessageGuid: String?) : SocketEvent()
    data class ScheduledMessageError(val messageId: Long, val chatGuid: String, val errorMessage: String) : SocketEvent()
    data class ScheduledMessageDeleted(val messageId: Long, val chatGuid: String) : SocketEvent()

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
    private val soundManager: Lazy<SoundManager>,
    private val okHttpClient: OkHttpClient,
    private val developerEventLog: Lazy<DeveloperEventLog>
) {
    companion object {
        private const val TAG = "SocketService"

        // Socket.IO event names from BlueBubbles server
        private const val EVENT_NEW_MESSAGE = "new-message"
        private const val EVENT_MESSAGE_UPDATED = "updated-message"
        private const val EVENT_MESSAGE_DELETED = "message-deleted"
        private const val EVENT_MESSAGE_SEND_ERROR = "message-send-error"
        private const val EVENT_TYPING_INDICATOR = "typing-indicator"
        private const val EVENT_CHAT_READ = "chat-read-status-changed"
        private const val EVENT_PARTICIPANT_ADDED = "participant-added"
        private const val EVENT_PARTICIPANT_REMOVED = "participant-removed"
        private const val EVENT_PARTICIPANT_LEFT = "participant-left"
        private const val EVENT_GROUP_NAME_CHANGED = "group-name-change"
        private const val EVENT_GROUP_ICON_CHANGED = "group-icon-changed"
        private const val EVENT_GROUP_ICON_REMOVED = "group-icon-removed"
        private const val EVENT_SERVER_UPDATE = "server-update"
        private const val EVENT_INCOMING_FACETIME = "incoming-facetime"
        private const val EVENT_FACETIME_CALL = "ft-call-status-changed"
        private const val EVENT_ICLOUD_ACCOUNT = "icloud-account"

        // Server-side scheduled message events
        private const val EVENT_SCHEDULED_MESSAGE_CREATED = "scheduled-message-created"
        private const val EVENT_SCHEDULED_MESSAGE_SENT = "scheduled-message-sent"
        private const val EVENT_SCHEDULED_MESSAGE_ERROR = "scheduled-message-error"
        private const val EVENT_SCHEDULED_MESSAGE_DELETED = "scheduled-message-deleted"

    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: Socket? = null
    private var retryCount = 0
    private var retryJob: kotlinx.coroutines.Job? = null

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

    // Track if we're currently connecting to prevent duplicate attempts
    private var isConnecting = false

    /**
     * Connect to the BlueBubbles server
     */
    fun connect() {
        Log.i(TAG, "connect() called - attempting to connect to server")

        if (socket?.connected() == true) {
            Log.d(TAG, "Already connected")
            return
        }

        // Prevent duplicate connection attempts
        if (isConnecting) {
            Log.d(TAG, "Connection already in progress, skipping duplicate attempt")
            return
        }

        // Cancel any pending retry
        retryJob?.cancel()

        scope.launch {
            try {
                isConnecting = true
                _connectionState.value = ConnectionState.CONNECTING

                val serverAddress = settingsDataStore.serverAddress.first()
                val password = settingsDataStore.serverPassword.first()

                if (serverAddress.isBlank() || password.isBlank()) {
                    Log.e(TAG, "Server address or password not configured")
                    _connectionState.value = ConnectionState.NOT_CONFIGURED
                    return@launch
                }

                // Log sanitized server address (hide credentials/full URL for security)
                val uri = URI.create(serverAddress)
                Log.i(TAG, "Connecting to server: ${uri.scheme}://${uri.host}:${uri.port}")
                Log.d(TAG, "Password length: ${password.length}, first 4 chars: ${password.take(4)}...")

                // URL-encode the password for use in query string
                val encodedPassword = URLEncoder.encode(password, "UTF-8")

                val options = IO.Options().apply {
                    forceNew = true
                    // Use Socket.IO's built-in reconnection with exponential backoff
                    reconnection = true
                    reconnectionAttempts = Int.MAX_VALUE
                    reconnectionDelay = 5000        // Start at 5 seconds
                    reconnectionDelayMax = 60000    // Cap at 60 seconds
                    randomizationFactor = 0.5       // Add jitter to prevent thundering herd
                    timeout = 20000
                    // Pass authentication via query string (like official BlueBubbles app)
                    query = "guid=$encodedPassword"
                    transports = arrayOf("websocket", "polling")
                    // Use our OkHttpClient which trusts self-signed certificates
                    // Note: AuthInterceptor may add guid again - we need to fix that
                    callFactory = okHttpClient
                    webSocketFactory = okHttpClient
                }

                Log.d(TAG, "Creating socket with options: transports=${options.transports?.joinToString()}, timeout=${options.timeout}")

                socket = IO.socket(URI.create(serverAddress), options).apply {
                    on(Socket.EVENT_CONNECT, onConnect)
                    on(Socket.EVENT_DISCONNECT, onDisconnect)
                    on(Socket.EVENT_CONNECT_ERROR, onConnectError)

                    // Debug: Log ALL incoming events to see what the server sends (only in debug builds)
                    if (BuildConfig.DEBUG) {
                        onAnyIncoming { args: Array<Any?> ->
                            val eventName = args.getOrNull(0)?.toString() ?: "unknown"
                            Log.i(TAG, ">>> SOCKET EVENT: '$eventName' with ${args.size - 1} args")
                            args.drop(1).forEachIndexed { index: Int, arg: Any? ->
                                val preview = arg?.toString()?.take(200) ?: "null"
                                Log.d(TAG, "    arg[$index]: $preview")
                            }
                        }
                    }

                    // BlueBubbles events
                    on(EVENT_NEW_MESSAGE, onNewMessage)
                    on(EVENT_MESSAGE_UPDATED, onMessageUpdated)
                    on(EVENT_MESSAGE_DELETED, onMessageDeleted)
                    on(EVENT_MESSAGE_SEND_ERROR, onMessageSendError)
                    on(EVENT_TYPING_INDICATOR, onTypingIndicator)
                    on(EVENT_CHAT_READ, onChatRead)
                    on(EVENT_PARTICIPANT_ADDED, onParticipantAdded)
                    on(EVENT_PARTICIPANT_REMOVED, onParticipantRemoved)
                    on(EVENT_PARTICIPANT_LEFT, onParticipantLeft)
                    on(EVENT_GROUP_NAME_CHANGED, onGroupNameChanged)
                    on(EVENT_GROUP_ICON_CHANGED, onGroupIconChanged)
                    on(EVENT_GROUP_ICON_REMOVED, onGroupIconRemoved)
                    on(EVENT_SERVER_UPDATE, onServerUpdate)
                    on(EVENT_INCOMING_FACETIME, onIncomingFaceTime)
                    on(EVENT_FACETIME_CALL, onFaceTimeCall)
                    on(EVENT_ICLOUD_ACCOUNT, onICloudAccountStatus)

                    // Server-side scheduled message events
                    on(EVENT_SCHEDULED_MESSAGE_CREATED, onScheduledMessageCreated)
                    on(EVENT_SCHEDULED_MESSAGE_SENT, onScheduledMessageSent)
                    on(EVENT_SCHEDULED_MESSAGE_ERROR, onScheduledMessageError)
                    on(EVENT_SCHEDULED_MESSAGE_DELETED, onScheduledMessageDeleted)

                    Log.i(TAG, "Socket created, calling connect()...")
                    connect()
                    Log.d(TAG, "Socket.connect() called, waiting for connection events...")
                }
                // Note: isConnecting will be reset by onConnect or onConnectError handlers
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect", e)
                isConnecting = false
                _connectionState.value = ConnectionState.ERROR
                _events.tryEmit(SocketEvent.Error(e.message ?: "Connection failed"))
                // Socket.IO handles reconnection automatically with exponential backoff
            }
        }
    }

    /**
     * Reset retry state (called on successful connection)
     */
    private fun resetRetryState() {
        retryCount = 0
        _retryAttempt.value = 0
    }

    /**
     * Disconnect from the server
     */
    fun disconnect() {
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
        isConnecting = false
        resetRetryState()
        _connectionState.value = ConnectionState.CONNECTED
        developerEventLog.get().logSocketEvent("CONNECTED", "Real-time connection established")
    }

    private val onDisconnect = Emitter.Listener { args ->
        Log.d(TAG, "Disconnected from server: ${args.firstOrNull()}")
        isConnecting = false
        _connectionState.value = ConnectionState.DISCONNECTED
        developerEventLog.get().logSocketEvent("DISCONNECTED", args.firstOrNull()?.toString())
        // Socket.IO handles reconnection automatically with exponential backoff
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.firstOrNull()
        Log.e(TAG, "Connection error: $error")
        isConnecting = false
        _connectionState.value = ConnectionState.ERROR
        _events.tryEmit(SocketEvent.Error(error?.toString() ?: "Connection error"))
        developerEventLog.get().logSocketEvent("ERROR", error?.toString())
        // Socket.IO handles reconnection automatically with exponential backoff
    }

    private val onNewMessage = Emitter.Listener { args ->
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
            _events.tryEmit(SocketEvent.NewMessage(message, chatGuid))
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

    private val onMessageUpdated = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener

            // Server sends message directly, not wrapped
            val message = messageAdapter.fromJson(data.toString())
            if (message != null) {
                val chatGuid = message.chats?.firstOrNull()?.guid ?: ""
                Log.d(TAG, "Message updated: ${message.guid}")
                _events.tryEmit(SocketEvent.MessageUpdated(message, chatGuid))
                developerEventLog.get().logSocketEvent("updated-message", "guid: ${message.guid?.take(20)}...")
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
                developerEventLog.get().logSocketEvent("message-deleted", "guid: ${messageGuid.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message deletion", e)
        }
    }

    private val onMessageSendError = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val tempGuid = data.optString("tempGuid", data.optString("guid", ""))
            val errorMessage = data.optString("error", data.optString("message", "Send failed"))

            if (tempGuid.isNotBlank()) {
                Log.e(TAG, "Message send error: $tempGuid - $errorMessage")
                _events.tryEmit(SocketEvent.MessageSendError(tempGuid, errorMessage))
                developerEventLog.get().logSocketEvent("message-send-error", "guid: ${tempGuid.take(20)}..., error: $errorMessage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message send error", e)
        }
    }

    private val onTypingIndicator = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            // Server sends "guid" not "chatGuid" for typing indicators
            val chatGuid = data.optString("guid", "").ifBlank {
                data.optString("chatGuid", "")
            }
            val isTyping = data.optBoolean("display", false)

            if (chatGuid.isNotBlank()) {
                _events.tryEmit(SocketEvent.TypingIndicator(chatGuid, isTyping))
                developerEventLog.get().logSocketEvent("typing-indicator", "chat: ${chatGuid.take(20)}..., typing: $isTyping")
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
                developerEventLog.get().logSocketEvent("chat-read-status-changed", "chat: ${chatGuid.take(20)}...")
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
                developerEventLog.get().logSocketEvent("participant-added", "chat: ${chatGuid.take(20)}..., handle: $handleAddress")
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
                developerEventLog.get().logSocketEvent("participant-removed", "chat: ${chatGuid.take(20)}..., handle: $handleAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing participant removed", e)
        }
    }

    private val onParticipantLeft = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val handleAddress = data.optString("handle", "")

            if (chatGuid.isNotBlank() && handleAddress.isNotBlank()) {
                Log.d(TAG, "Participant left: $handleAddress from $chatGuid")
                _events.tryEmit(SocketEvent.ParticipantLeft(chatGuid, handleAddress))
                developerEventLog.get().logSocketEvent("participant-left", "chat: ${chatGuid.take(20)}..., handle: $handleAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing participant left", e)
        }
    }

    private val onGroupNameChanged = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")
            val newName = data.optString("newName", "")

            if (chatGuid.isNotBlank()) {
                _events.tryEmit(SocketEvent.GroupNameChanged(chatGuid, newName))
                developerEventLog.get().logSocketEvent("group-name-change", "chat: ${chatGuid.take(20)}..., name: $newName")
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
                developerEventLog.get().logSocketEvent("group-icon-changed", "chat: ${chatGuid.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing group icon change", e)
        }
    }

    private val onGroupIconRemoved = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val chatGuid = data.optString("chatGuid", "")

            if (chatGuid.isNotBlank()) {
                Log.d(TAG, "Group icon removed: $chatGuid")
                _events.tryEmit(SocketEvent.GroupIconRemoved(chatGuid))
                developerEventLog.get().logSocketEvent("group-icon-removed", "chat: ${chatGuid.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing group icon removed", e)
        }
    }

    private val onServerUpdate = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val version = data.optString("version", "")

            if (version.isNotBlank()) {
                _events.tryEmit(SocketEvent.ServerUpdate(version))
                developerEventLog.get().logSocketEvent("server-update", "version: $version")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server update", e)
        }
    }

    private val onIncomingFaceTime = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val caller = data.optString("caller", data.optString("handle", ""))
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())

            if (caller.isNotBlank()) {
                Log.d(TAG, "Incoming FaceTime from: $caller")
                _events.tryEmit(SocketEvent.IncomingFaceTime(caller, timestamp))
                developerEventLog.get().logSocketEvent("incoming-facetime", "caller: $caller")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming FaceTime", e)
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
                developerEventLog.get().logSocketEvent("ft-call-status-changed", "uuid: ${callUuid.take(8)}..., status: $status")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing FaceTime call event", e)
        }
    }

    private val onICloudAccountStatus = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val alias = data.optString("alias", null)
            val active = data.optBoolean("active", true)

            Log.i(TAG, "iCloud account status: alias=$alias, active=$active")
            _events.tryEmit(SocketEvent.ICloudAccountStatus(alias, active))
            developerEventLog.get().logSocketEvent("icloud-account", "alias: $alias, active: $active")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing iCloud account status", e)
        }
    }

    // ===== Server-side Scheduled Message Events =====

    private val onScheduledMessageCreated = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageId = data.optLong("id", -1)
            val chatGuid = data.optString("chatGuid", "")
            val text = data.optString("text", null)
            val scheduledAt = data.optLong("scheduledFor", 0)

            if (messageId >= 0 && chatGuid.isNotBlank()) {
                Log.d(TAG, "Scheduled message created: $messageId for chat $chatGuid at $scheduledAt")
                _events.tryEmit(SocketEvent.ScheduledMessageCreated(messageId, chatGuid, text, scheduledAt))
                developerEventLog.get().logSocketEvent("scheduled-message-created", "id: $messageId, chat: ${chatGuid.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scheduled message created", e)
        }
    }

    private val onScheduledMessageSent = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageId = data.optLong("id", -1)
            val chatGuid = data.optString("chatGuid", "")
            val sentMessageGuid = data.optString("messageGuid", null)

            if (messageId >= 0) {
                Log.d(TAG, "Scheduled message sent: $messageId -> $sentMessageGuid")
                _events.tryEmit(SocketEvent.ScheduledMessageSent(messageId, chatGuid, sentMessageGuid))
                developerEventLog.get().logSocketEvent("scheduled-message-sent", "id: $messageId, msgGuid: ${sentMessageGuid?.take(20)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scheduled message sent", e)
        }
    }

    private val onScheduledMessageError = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageId = data.optLong("id", -1)
            val chatGuid = data.optString("chatGuid", "")
            val errorMessage = data.optString("error", "Unknown error")

            if (messageId >= 0) {
                Log.e(TAG, "Scheduled message error: $messageId - $errorMessage")
                _events.tryEmit(SocketEvent.ScheduledMessageError(messageId, chatGuid, errorMessage))
                developerEventLog.get().logSocketEvent("scheduled-message-error", "id: $messageId, error: $errorMessage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scheduled message error", e)
        }
    }

    private val onScheduledMessageDeleted = Emitter.Listener { args ->
        try {
            val data = args.firstOrNull() as? JSONObject ?: return@Listener
            val messageId = data.optLong("id", -1)
            val chatGuid = data.optString("chatGuid", "")

            if (messageId >= 0) {
                Log.d(TAG, "Scheduled message deleted: $messageId")
                _events.tryEmit(SocketEvent.ScheduledMessageDeleted(messageId, chatGuid))
                developerEventLog.get().logSocketEvent("scheduled-message-deleted", "id: $messageId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scheduled message deleted", e)
        }
    }

    // ===== Outbound Events =====

    /**
     * Notify server that user opened a conversation.
     * This tells the server which chat is currently active, allowing it to
     * optimize notifications (e.g., skip push for active chat).
     */
    fun sendOpenChat(chatGuid: String) {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send open-chat: not connected")
            return
        }

        val payload = JSONObject().apply {
            put("chatGuid", chatGuid)
        }

        socket?.emit("open-chat", payload)
        Log.d(TAG, "Sent open-chat for: $chatGuid")
    }

    /**
     * Notify server that user closed the conversation.
     * Call this when navigating away from the chat screen.
     */
    fun sendCloseChat(chatGuid: String) {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send close-chat: not connected")
            return
        }

        val payload = JSONObject().apply {
            put("chatGuid", chatGuid)
        }

        socket?.emit("close-chat", payload)
        Log.d(TAG, "Sent close-chat for: $chatGuid")
    }
}
