package com.bothbubbles.services.socket

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for Socket.IO connection management.
 * Allows mocking in tests without modifying the concrete implementation.
 *
 * This interface defines the contract for real-time server communication:
 * - Connection lifecycle management
 * - Typing indicator sending
 * - Chat presence notifications
 *
 * Implementation: [SocketService]
 */
interface SocketConnection {

    /**
     * Current connection state.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Current retry attempt number.
     */
    val retryAttempt: StateFlow<Int>

    /**
     * Stream of socket events from the server.
     */
    val events: SharedFlow<SocketEvent>

    /**
     * Check if server is configured (has address and password).
     */
    suspend fun isServerConfigured(): Boolean

    /**
     * Connect to the BlueBubbles server.
     */
    fun connect()

    /**
     * Disconnect from the server.
     */
    fun disconnect()

    /**
     * Reconnect to the server (disconnect then connect).
     */
    fun reconnect()

    /**
     * Force an immediate retry (for user-triggered retry).
     */
    fun retryNow()

    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean

    /**
     * Send started-typing event to the server.
     * Requires Private API to be enabled on the server.
     */
    fun sendStartedTyping(chatGuid: String)

    /**
     * Send stopped-typing event to the server.
     * Requires Private API to be enabled on the server.
     */
    fun sendStoppedTyping(chatGuid: String)

    /**
     * Notify server that user opened a conversation.
     */
    fun sendOpenChat(chatGuid: String)

    /**
     * Notify server that user closed the conversation.
     */
    fun sendCloseChat(chatGuid: String)
}
