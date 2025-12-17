package com.bothbubbles.services.socket

import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.core.data.ServerConnectionProvider
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for Socket.IO connection management.
 * Allows mocking in tests without modifying the concrete implementation.
 *
 * This interface extends [ServerConnectionProvider] to provide the base connection
 * management contract, and adds socket-specific features:
 * - Typing indicator sending
 * - Chat presence notifications
 * - Socket event stream
 *
 * Implementation: [SocketService]
 */
interface SocketConnection : ServerConnectionProvider {

    /**
     * Stream of socket events from the server.
     */
    val events: SharedFlow<SocketEvent>

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
