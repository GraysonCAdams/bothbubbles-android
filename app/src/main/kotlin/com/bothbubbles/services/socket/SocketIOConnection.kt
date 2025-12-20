package com.bothbubbles.services.socket
import com.bothbubbles.core.data.ConnectionState

import timber.log.Timber
import com.bothbubbles.BuildConfig
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.developer.DeveloperEventLog
import dagger.Lazy
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLEncoder

/**
 * Manages the low-level Socket.IO connection including connect, disconnect, and reconnection.
 * Handles connection state tracking and event listener registration.
 */
class SocketIOConnection(
    private val settingsDataStore: SettingsDataStore,
    private val okHttpClient: OkHttpClient,
    private val developerEventLog: Lazy<DeveloperEventLog>,
    private val eventParser: SocketEventParser,
    private val connectionState: MutableStateFlow<ConnectionState>,
    private val retryAttempt: MutableStateFlow<Int>,
    private val events: MutableSharedFlow<SocketEvent>,
    private val coroutineScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        // Socket.IO event names from BlueBubbles server
        const val EVENT_NEW_MESSAGE = "new-message"
        const val EVENT_MESSAGE_UPDATED = "updated-message"
        const val EVENT_MESSAGE_DELETED = "message-deleted"
        const val EVENT_MESSAGE_SEND_ERROR = "message-send-error"
        const val EVENT_TYPING_INDICATOR = "typing-indicator"
        const val EVENT_CHAT_READ = "chat-read-status-changed"
        const val EVENT_PARTICIPANT_ADDED = "participant-added"
        const val EVENT_PARTICIPANT_REMOVED = "participant-removed"
        const val EVENT_PARTICIPANT_LEFT = "participant-left"
        const val EVENT_GROUP_NAME_CHANGED = "group-name-change"
        const val EVENT_GROUP_ICON_CHANGED = "group-icon-changed"
        const val EVENT_GROUP_ICON_REMOVED = "group-icon-removed"
        const val EVENT_SERVER_UPDATE = "server-update"
        const val EVENT_INCOMING_FACETIME = "incoming-facetime"
        const val EVENT_FACETIME_CALL = "ft-call-status-changed"
        const val EVENT_ICLOUD_ACCOUNT = "icloud-account"
        const val EVENT_SCHEDULED_MESSAGE_CREATED = "scheduled-message-created"
        const val EVENT_SCHEDULED_MESSAGE_SENT = "scheduled-message-sent"
        const val EVENT_SCHEDULED_MESSAGE_ERROR = "scheduled-message-error"
        const val EVENT_SCHEDULED_MESSAGE_DELETED = "scheduled-message-deleted"
    }

    private var socket: Socket? = null
    private var retryCount = 0
    private var retryJob: kotlinx.coroutines.Job? = null
    private var isConnecting = false

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
        Timber.i("connect() called - attempting to connect to server")

        if (socket?.connected() == true) {
            Timber.d("Already connected")
            return
        }

        // Prevent duplicate connection attempts
        if (isConnecting) {
            Timber.d("Connection already in progress, skipping duplicate attempt")
            return
        }

        // Cancel any pending retry
        retryJob?.cancel()

        coroutineScope.launch(ioDispatcher) {
            try {
                isConnecting = true
                connectionState.value = ConnectionState.CONNECTING

                val serverAddress = settingsDataStore.serverAddress.first()
                val password = settingsDataStore.serverPassword.first()

                if (serverAddress.isBlank() || password.isBlank()) {
                    Timber.e("Server address or password not configured")
                    connectionState.value = ConnectionState.NOT_CONFIGURED
                    return@launch
                }

                // Log sanitized server address (hide credentials/full URL for security)
                val uri = URI.create(serverAddress)
                Timber.i("Connecting to server: ${uri.scheme}://${uri.host}:${uri.port}")
                Timber.d("Password configured (${password.length} chars)")

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
                    callFactory = okHttpClient
                    webSocketFactory = okHttpClient
                }

                Timber.d("Creating socket with options: transports=${options.transports?.joinToString()}, timeout=${options.timeout}")

                socket = IO.socket(URI.create(serverAddress), options).apply {
                    on(Socket.EVENT_CONNECT, onConnect)
                    on(Socket.EVENT_DISCONNECT, onDisconnect)
                    on(Socket.EVENT_CONNECT_ERROR, onConnectError)

                    // Debug: Log ALL incoming events to see what the server sends (only in debug builds)
                    if (BuildConfig.DEBUG) {
                        onAnyIncoming { args: Array<Any?> ->
                            val eventName = args.getOrNull(0)?.toString() ?: "unknown"
                            Timber.i(">>> SOCKET EVENT: '$eventName' with ${args.size - 1} args")
                            args.drop(1).forEachIndexed { index: Int, arg: Any? ->
                                val preview = arg?.toString()?.take(200) ?: "null"
                                Timber.d("    arg[$index]: $preview")
                            }
                        }
                    }

                    // Register all event handlers from the parser
                    on(EVENT_NEW_MESSAGE, eventParser.onNewMessage)
                    on(EVENT_MESSAGE_UPDATED, eventParser.onMessageUpdated)
                    on(EVENT_MESSAGE_DELETED, eventParser.onMessageDeleted)
                    on(EVENT_MESSAGE_SEND_ERROR, eventParser.onMessageSendError)
                    on(EVENT_TYPING_INDICATOR, eventParser.onTypingIndicator)
                    on(EVENT_CHAT_READ, eventParser.onChatRead)
                    on(EVENT_PARTICIPANT_ADDED, eventParser.onParticipantAdded)
                    on(EVENT_PARTICIPANT_REMOVED, eventParser.onParticipantRemoved)
                    on(EVENT_PARTICIPANT_LEFT, eventParser.onParticipantLeft)
                    on(EVENT_GROUP_NAME_CHANGED, eventParser.onGroupNameChanged)
                    on(EVENT_GROUP_ICON_CHANGED, eventParser.onGroupIconChanged)
                    on(EVENT_GROUP_ICON_REMOVED, eventParser.onGroupIconRemoved)
                    on(EVENT_SERVER_UPDATE, eventParser.onServerUpdate)
                    on(EVENT_INCOMING_FACETIME, eventParser.onIncomingFaceTime)
                    on(EVENT_FACETIME_CALL, eventParser.onFaceTimeCall)
                    on(EVENT_ICLOUD_ACCOUNT, eventParser.onICloudAccountStatus)
                    on(EVENT_SCHEDULED_MESSAGE_CREATED, eventParser.onScheduledMessageCreated)
                    on(EVENT_SCHEDULED_MESSAGE_SENT, eventParser.onScheduledMessageSent)
                    on(EVENT_SCHEDULED_MESSAGE_ERROR, eventParser.onScheduledMessageError)
                    on(EVENT_SCHEDULED_MESSAGE_DELETED, eventParser.onScheduledMessageDeleted)

                    Timber.i("Socket created, calling connect()...")
                    connect()
                    Timber.d("Socket.connect() called, waiting for connection events...")
                }
                // Note: isConnecting will be reset by onConnect or onConnectError handlers
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect")
                isConnecting = false
                connectionState.value = ConnectionState.ERROR
                events.tryEmit(SocketEvent.Error(e.message ?: "Connection failed"))
                // Socket.IO handles reconnection automatically with exponential backoff
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
        connectionState.value = ConnectionState.DISCONNECTED
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

    /**
     * Get the underlying socket for emitting events
     */
    fun getSocket(): Socket? = socket

    /**
     * Reset retry state (called on successful connection)
     */
    private fun resetRetryState() {
        retryCount = 0
        retryAttempt.value = 0
    }

    // ===== Socket Event Handlers =====

    private val onConnect = Emitter.Listener {
        Timber.d("Connected to server")
        isConnecting = false
        resetRetryState()
        connectionState.value = ConnectionState.CONNECTED
        developerEventLog.get().logSocketEvent("CONNECTED", "Real-time connection established")
    }

    private val onDisconnect = Emitter.Listener { args ->
        Timber.d("Disconnected from server: ${args.firstOrNull()}")
        isConnecting = false
        connectionState.value = ConnectionState.DISCONNECTED
        developerEventLog.get().logSocketEvent("DISCONNECTED", args.firstOrNull()?.toString())
        // Socket.IO handles reconnection automatically with exponential backoff
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.firstOrNull()
        Timber.e("Connection error: $error")
        isConnecting = false
        connectionState.value = ConnectionState.ERROR
        events.tryEmit(SocketEvent.Error(error?.toString() ?: "Connection error"))
        developerEventLog.get().logSocketEvent("ERROR", error?.toString())
        // Socket.IO handles reconnection automatically with exponential backoff
    }
}
