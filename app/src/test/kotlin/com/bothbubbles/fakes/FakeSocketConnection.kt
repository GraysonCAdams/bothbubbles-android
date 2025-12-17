package com.bothbubbles.fakes

import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.services.socket.SocketEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake implementation of [SocketConnection] for unit testing.
 *
 * This fake records all method calls and allows tests to configure
 * connection state and simulate events.
 *
 * Usage:
 * ```kotlin
 * val fakeSocket = FakeSocketConnection()
 *
 * // Configure state
 * fakeSocket.setConnected(true)
 *
 * // Use in test
 * val delegate = ChatSendDelegate(socketService = fakeSocket, ...)
 * delegate.startTyping(isPrivateApiEnabled = true, isTypingIndicatorsEnabled = true)
 *
 * // Verify
 * assertEquals(1, fakeSocket.startedTypingCalls.size)
 * ```
 */
class FakeSocketConnection : SocketConnection {

    // ===== State =====

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _retryAttempt = MutableStateFlow(0)
    override val retryAttempt: StateFlow<Int> = _retryAttempt

    private val _serverVersion = MutableStateFlow<String?>(null)
    override val serverVersion: StateFlow<String?> = _serverVersion

    private val _events = MutableSharedFlow<SocketEvent>(replay = 1)
    override val events: SharedFlow<SocketEvent> = _events

    private var _isConnected = false

    // ===== Configurable Results =====

    var isServerConfiguredResult: Boolean = true

    // ===== Call Recording =====

    val connectCalls = mutableListOf<Unit>()
    val disconnectCalls = mutableListOf<Unit>()
    val reconnectCalls = mutableListOf<Unit>()
    val retryNowCalls = mutableListOf<Unit>()
    val startedTypingCalls = mutableListOf<String>()
    val stoppedTypingCalls = mutableListOf<String>()
    val openChatCalls = mutableListOf<String>()
    val closeChatCalls = mutableListOf<String>()

    // ===== Interface Implementations =====

    override suspend fun isServerConfigured(): Boolean = isServerConfiguredResult

    override fun connect() {
        connectCalls.add(Unit)
        _isConnected = true
        _connectionState.value = ConnectionState.CONNECTED
    }

    override fun disconnect() {
        disconnectCalls.add(Unit)
        _isConnected = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun reconnect() {
        reconnectCalls.add(Unit)
        _isConnected = true
        _connectionState.value = ConnectionState.CONNECTED
    }

    override fun retryNow() {
        retryNowCalls.add(Unit)
    }

    override fun isConnected(): Boolean = _isConnected

    override fun sendStartedTyping(chatGuid: String) {
        startedTypingCalls.add(chatGuid)
    }

    override fun sendStoppedTyping(chatGuid: String) {
        stoppedTypingCalls.add(chatGuid)
    }

    override fun sendOpenChat(chatGuid: String) {
        openChatCalls.add(chatGuid)
    }

    override fun sendCloseChat(chatGuid: String) {
        closeChatCalls.add(chatGuid)
    }

    // ===== Test Helpers =====

    fun reset() {
        connectCalls.clear()
        disconnectCalls.clear()
        reconnectCalls.clear()
        retryNowCalls.clear()
        startedTypingCalls.clear()
        stoppedTypingCalls.clear()
        openChatCalls.clear()
        closeChatCalls.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
        _retryAttempt.value = 0
        _isConnected = false
        isServerConfiguredResult = true
    }

    /**
     * Set the connection state directly.
     */
    fun setConnected(connected: Boolean) {
        _isConnected = connected
        _connectionState.value = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
    }

    /**
     * Simulate a socket event.
     */
    suspend fun emitEvent(event: SocketEvent) {
        _events.emit(event)
    }

    /**
     * Set the retry attempt count.
     */
    fun setRetryAttempt(attempt: Int) {
        _retryAttempt.value = attempt
    }

    /**
     * Set the server version.
     */
    fun setServerVersion(version: String?) {
        _serverVersion.value = version
    }
}
