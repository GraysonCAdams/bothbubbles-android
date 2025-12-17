package com.bothbubbles.services.socket

import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.core.network.api.dto.MessageDto
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.sound.SoundManager
import com.squareup.moshi.Moshi
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

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

/**
 * Main Socket.IO service that implements the SocketConnection interface.
 * Delegates to specialized components for connection management, event parsing, and event emission.
 */
@Singleton
class SocketService @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val moshi: Moshi,
    private val soundManager: Lazy<SoundManager>,
    private val okHttpClient: OkHttpClient,
    private val developerEventLog: Lazy<DeveloperEventLog>,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : SocketConnection {

    // State flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _retryAttempt = MutableStateFlow(0)
    override val retryAttempt: StateFlow<Int> = _retryAttempt.asStateFlow()

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 100)
    override val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val _serverVersion = MutableStateFlow<String?>(null)
    override val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    init {
        // Listen for server version updates from socket events
        applicationScope.launch {
            events.collect { event ->
                if (event is SocketEvent.ServerUpdate) {
                    _serverVersion.value = event.version
                }
            }
        }
    }

    // Delegated components
    private val eventParser = SocketEventParser(
        moshi = moshi,
        events = _events,
        soundManager = soundManager,
        developerEventLog = developerEventLog
    )

    private val socketConnection = SocketIOConnection(
        settingsDataStore = settingsDataStore,
        okHttpClient = okHttpClient,
        developerEventLog = developerEventLog,
        eventParser = eventParser,
        connectionState = _connectionState,
        retryAttempt = _retryAttempt,
        events = _events,
        coroutineScope = applicationScope,
        ioDispatcher = ioDispatcher
    )

    private val eventEmitter = SocketEventEmitter(
        getSocket = { socketConnection.getSocket() },
        isConnected = { socketConnection.isConnected() }
    )

    // ===== SocketConnection Interface Implementation =====
    // All methods delegate to specialized components

    override suspend fun isServerConfigured(): Boolean =
        socketConnection.isServerConfigured()

    override fun connect() =
        socketConnection.connect()

    override fun disconnect() =
        socketConnection.disconnect()

    override fun reconnect() =
        socketConnection.reconnect()

    override fun retryNow() =
        socketConnection.retryNow()

    override fun isConnected(): Boolean =
        socketConnection.isConnected()

    // ===== Typing Indicators (Outbound) =====
    // Delegated to SocketEventEmitter

    override fun sendStartedTyping(chatGuid: String) =
        eventEmitter.sendStartedTyping(chatGuid)

    override fun sendStoppedTyping(chatGuid: String) =
        eventEmitter.sendStoppedTyping(chatGuid)

    // ===== Chat State Events =====
    // Delegated to SocketEventEmitter

    override fun sendOpenChat(chatGuid: String) =
        eventEmitter.sendOpenChat(chatGuid)

    override fun sendCloseChat(chatGuid: String) =
        eventEmitter.sendCloseChat(chatGuid)
}
