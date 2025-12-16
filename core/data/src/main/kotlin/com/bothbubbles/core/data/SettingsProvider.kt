package com.bothbubbles.core.data

import kotlinx.coroutines.flow.Flow

/**
 * Interface providing access to app settings.
 *
 * Feature modules depend on this interface rather than the concrete SettingsDataStore
 * implementation, allowing for better decoupling and testability.
 *
 * The full SettingsDataStore in the app module implements this interface.
 * Additional setting categories can be added as needed for feature migration.
 */
interface SettingsProvider {
    // ===== Server Settings =====
    val serverAddress: Flow<String>
    val isSetupComplete: Flow<Boolean>
    val serverPrivateApiEnabled: Flow<Boolean>

    // ===== UI Settings =====
    val developerModeEnabled: Flow<Boolean>
    val enablePrivateApi: Flow<Boolean>
    val hasShownPrivateApiPrompt: Flow<Boolean>

    // ===== Notification Settings =====
    val notificationsEnabled: Flow<Boolean>
    val keepAlive: Flow<Boolean>

    // ===== SMS Settings =====
    val smsEnabled: Flow<Boolean>
    val smsOnlyMode: Flow<Boolean>

    // ===== Mutators =====
    suspend fun setDeveloperModeEnabled(enabled: Boolean)
    suspend fun setEnablePrivateApi(enabled: Boolean)
    suspend fun setHasShownPrivateApiPrompt(shown: Boolean)
    suspend fun setServerCapabilities(
        osVersion: String?,
        serverVersion: String?,
        privateApiEnabled: Boolean,
        helperConnected: Boolean
    )
}
