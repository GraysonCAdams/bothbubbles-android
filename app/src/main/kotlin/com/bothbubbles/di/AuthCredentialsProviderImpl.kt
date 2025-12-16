package com.bothbubbles.di

import com.bothbubbles.core.network.AuthCredentialsProvider
import com.bothbubbles.data.local.prefs.SettingsDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of AuthCredentialsProvider that reads credentials from SettingsDataStore.
 * This bridges the :core:network module with the app's data layer.
 */
@Singleton
class AuthCredentialsProviderImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : AuthCredentialsProvider {

    override suspend fun getServerAddress(): String {
        return settingsDataStore.serverAddress.first()
    }

    override suspend fun getAuthKey(): String {
        return settingsDataStore.guidAuthKey.first()
    }

    override suspend fun getCustomHeaders(): Map<String, String> {
        return settingsDataStore.customHeaders.first()
    }
}
