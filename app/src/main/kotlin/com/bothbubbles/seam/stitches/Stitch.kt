package com.bothbubbles.seam.stitches

import kotlinx.coroutines.flow.StateFlow

interface Stitch {
    val id: String
    val displayName: String
    val iconResId: Int
    val chatGuidPrefix: String?

    val connectionState: StateFlow<StitchConnectionState>
    val isEnabled: StateFlow<Boolean>
    val capabilities: StitchCapabilities

    suspend fun initialize()
    suspend fun teardown()

    val settingsRoute: String?
}
