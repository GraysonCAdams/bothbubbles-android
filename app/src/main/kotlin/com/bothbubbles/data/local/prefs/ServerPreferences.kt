package com.bothbubbles.data.local.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bothbubbles.data.ServerCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Handles server connection and capabilities preferences.
 */
class ServerPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // ===== Server Connection =====

    val serverAddress: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SERVER_ADDRESS] ?: ""
    }

    val guidAuthKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.GUID_AUTH_KEY] ?: ""
    }

    val customHeaders: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        val headersString = prefs[Keys.CUSTOM_HEADERS] ?: ""
        parseHeaders(headersString)
    }

    val isSetupComplete: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SETUP_COMPLETE] ?: false
    }

    val lastSyncTimestamp: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SYNC_TIMESTAMP] ?: 0L
    }

    // Aliases for service compatibility
    val serverPassword: Flow<String> get() = guidAuthKey
    val lastSyncTime: Flow<Long> get() = lastSyncTimestamp

    // ===== Server Capabilities =====

    val serverOsVersion: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SERVER_OS_VERSION] ?: ""
    }

    val serverVersionStored: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SERVER_VERSION] ?: ""
    }

    val serverPrivateApiEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SERVER_PRIVATE_API_ENABLED] ?: false
    }

    val serverHelperConnected: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SERVER_HELPER_CONNECTED] ?: false
    }

    /**
     * Combined flow that provides server capabilities based on stored server info.
     * Updates automatically when any underlying server info changes.
     */
    val serverCapabilities: Flow<ServerCapabilities> = combine(
        serverOsVersion,
        serverVersionStored,
        serverPrivateApiEnabled,
        serverHelperConnected
    ) { osVersion, serverVersion, privateApi, helperConnected ->
        ServerCapabilities.fromServerInfo(
            osVersion = osVersion.takeIf { it.isNotBlank() },
            serverVersion = serverVersion.takeIf { it.isNotBlank() },
            privateApiEnabled = privateApi,
            helperConnected = helperConnected
        )
    }

    // ===== Setters =====

    suspend fun setServerAddress(address: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_ADDRESS] = address
        }
    }

    suspend fun setGuidAuthKey(key: String) {
        dataStore.edit { prefs ->
            prefs[Keys.GUID_AUTH_KEY] = key
        }
    }

    suspend fun setCustomHeaders(headers: Map<String, String>) {
        dataStore.edit { prefs ->
            prefs[Keys.CUSTOM_HEADERS] = serializeHeaders(headers)
        }
    }

    suspend fun setSetupComplete(complete: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SETUP_COMPLETE] = complete
        }
    }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_TIMESTAMP] = timestamp
        }
    }

    // Alias for service compatibility
    suspend fun setLastSyncTime(timestamp: Long) = setLastSyncTimestamp(timestamp)

    /**
     * Update server capabilities from server info.
     * Call this when server info is fetched from the API.
     */
    suspend fun setServerCapabilities(
        osVersion: String?,
        serverVersion: String?,
        privateApiEnabled: Boolean,
        helperConnected: Boolean
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_OS_VERSION] = osVersion ?: ""
            prefs[Keys.SERVER_VERSION] = serverVersion ?: ""
            prefs[Keys.SERVER_PRIVATE_API_ENABLED] = privateApiEnabled
            prefs[Keys.SERVER_HELPER_CONNECTED] = helperConnected
        }
    }

    // ===== Helpers =====

    private fun parseHeaders(headersString: String): Map<String, String> {
        if (headersString.isBlank()) return emptyMap()
        return headersString.split(";")
            .mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    private fun serializeHeaders(headers: Map<String, String>): String {
        return headers.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    private object Keys {
        val SERVER_ADDRESS = stringPreferencesKey("server_address")
        val GUID_AUTH_KEY = stringPreferencesKey("guid_auth_key")
        val CUSTOM_HEADERS = stringPreferencesKey("custom_headers")
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")

        // Server Capabilities (fetched from server)
        val SERVER_OS_VERSION = stringPreferencesKey("server_os_version")
        val SERVER_VERSION = stringPreferencesKey("server_version")
        val SERVER_PRIVATE_API_ENABLED = booleanPreferencesKey("server_private_api_enabled")
        val SERVER_HELPER_CONNECTED = booleanPreferencesKey("server_helper_connected")
    }
}
