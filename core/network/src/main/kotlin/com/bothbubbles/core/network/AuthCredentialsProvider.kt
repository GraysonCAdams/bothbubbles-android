package com.bothbubbles.core.network

/**
 * Interface for providing authentication credentials to the network layer.
 * This abstraction allows :core:network to remain independent of the data layer.
 *
 * The app module provides an implementation that reads from SettingsDataStore.
 */
interface AuthCredentialsProvider {
    /**
     * Get the current server address (e.g., "https://192.168.1.100:1234")
     */
    suspend fun getServerAddress(): String

    /**
     * Get the current authentication key (GUID)
     */
    suspend fun getAuthKey(): String

    /**
     * Get custom headers for tunnel services (ngrok, zrok, etc.)
     */
    suspend fun getCustomHeaders(): Map<String, String>
}
