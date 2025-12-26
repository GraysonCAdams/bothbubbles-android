package com.bothbubbles.seam.stitches

/**
 * Represents the connection state of a Stitch.
 *
 * States:
 * - [NotConfigured]: The stitch has not been set up (e.g., no server address)
 * - [Disconnected]: Configured but not currently connected
 * - [Connecting]: Actively attempting to connect
 * - [Connected]: Successfully connected and ready for use
 * - [Error]: Connection failed with an error message
 */
sealed class StitchConnectionState {
    /** The stitch has not been configured (e.g., no server address set). */
    data object NotConfigured : StitchConnectionState()

    /** The stitch is configured but not currently connected. */
    data object Disconnected : StitchConnectionState()

    /** The stitch is actively attempting to connect. */
    data object Connecting : StitchConnectionState()

    /** The stitch is connected and ready for use. */
    data object Connected : StitchConnectionState()

    /** The stitch encountered an error while connecting. */
    data class Error(val message: String) : StitchConnectionState()

    /** Returns true if this state represents a connected stitch. */
    val isConnected: Boolean get() = this == Connected

    /** Returns true if this state represents an error. */
    val isError: Boolean get() = this is Error

    /** Returns true if this state represents a usable (connected or can be connected) stitch. */
    val isUsable: Boolean get() = this == Connected || this == Disconnected || this == Connecting
}
