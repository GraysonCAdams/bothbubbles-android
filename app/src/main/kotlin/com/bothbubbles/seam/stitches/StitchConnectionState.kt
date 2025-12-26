package com.bothbubbles.seam.stitches

sealed class StitchConnectionState {
    object NotConfigured : StitchConnectionState()
    object Disconnected : StitchConnectionState()
    object Connecting : StitchConnectionState()
    object Connected : StitchConnectionState()
    data class Error(val message: String) : StitchConnectionState()
}
