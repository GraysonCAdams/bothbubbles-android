package com.bothbubbles.seam.hems

import kotlinx.coroutines.flow.StateFlow

interface Feature {
    val id: String
    val displayName: String
    val description: String
    val featureFlagKey: String

    val isEnabled: StateFlow<Boolean>
    val settingsRoute: String?

    suspend fun onEnable()
    suspend fun onDisable()
}
