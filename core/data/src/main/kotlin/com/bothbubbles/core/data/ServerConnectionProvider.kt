package com.bothbubbles.core.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface providing access to server connection state and operations.
 *
 * Feature modules depend on this interface rather than the concrete SocketConnection
 * implementation, allowing for better decoupling and testability.
 *
 * The SocketConnection interface in the app module extends or is adapted to this interface.
 */
interface ServerConnectionProvider {

    /**
     * Current connection state.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Current server version (e.g., "1.9.6").
     * Null if not connected or version not yet received.
     */
    val serverVersion: StateFlow<String?>

    /**
     * Current retry attempt number.
     */
    val retryAttempt: StateFlow<Int>

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
}
