package com.bothbubbles.core.data

/**
 * Socket.IO connection states.
 *
 * Defined in :core:data so feature modules can observe connection state
 * without depending on the services layer.
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
