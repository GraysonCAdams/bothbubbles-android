package com.bothbubbles.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Handles SMS/MMS preferences.
 */
class SmsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // ===== SMS Settings =====

    val smsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SMS_ENABLED] ?: false
    }

    val smsOnlyMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SMS_ONLY_MODE] ?: false
    }

    val preferSmsOverIMessage: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.PREFER_SMS_OVER_IMESSAGE] ?: false
    }

    val selectedSimSlot: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_SIM_SLOT] ?: -1
    }

    val hasCompletedInitialSmsImport: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HAS_COMPLETED_INITIAL_SMS_IMPORT] ?: false
    }

    /**
     * App version code when SMS re-sync was last performed.
     * Used to trigger one-time re-sync on app updates to recover historical external SMS.
     */
    val lastSmsResyncVersion: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SMS_RESYNC_VERSION] ?: 0
    }

    /**
     * Block messages from unknown senders (numbers not in contacts).
     * When enabled, SMS/MMS from unknown numbers are silently ignored.
     */
    val blockUnknownSenders: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.BLOCK_UNKNOWN_SENDERS] ?: false
    }

    /**
     * Automatically switch between iMessage and SMS based on availability.
     * When enabled (default), the app automatically selects the best delivery method.
     * When disabled, users manually control send mode per-conversation via the chat menu.
     */
    val autoSwitchSendMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_SWITCH_SEND_MODE] ?: true
    }

    // ===== Setters =====

    suspend fun setSmsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SMS_ENABLED] = enabled
        }
    }

    suspend fun setSmsOnlyMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SMS_ONLY_MODE] = enabled
        }
    }

    suspend fun setPreferSmsOverIMessage(prefer: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.PREFER_SMS_OVER_IMESSAGE] = prefer
        }
    }

    suspend fun setSelectedSimSlot(slot: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.SELECTED_SIM_SLOT] = slot
        }
    }

    suspend fun setHasCompletedInitialSmsImport(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_COMPLETED_INITIAL_SMS_IMPORT] = completed
        }
    }

    suspend fun setLastSmsResyncVersion(versionCode: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SMS_RESYNC_VERSION] = versionCode
        }
    }

    suspend fun setBlockUnknownSenders(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.BLOCK_UNKNOWN_SENDERS] = enabled
        }
    }

    suspend fun setAutoSwitchSendMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_SWITCH_SEND_MODE] = enabled
        }
    }

    private object Keys {
        val SMS_ENABLED = booleanPreferencesKey("sms_enabled")
        val SMS_ONLY_MODE = booleanPreferencesKey("sms_only_mode")
        val PREFER_SMS_OVER_IMESSAGE = booleanPreferencesKey("prefer_sms_over_imessage")
        val SELECTED_SIM_SLOT = intPreferencesKey("selected_sim_slot")
        val HAS_COMPLETED_INITIAL_SMS_IMPORT = booleanPreferencesKey("has_completed_initial_sms_import")
        val BLOCK_UNKNOWN_SENDERS = booleanPreferencesKey("block_unknown_senders")
        val LAST_SMS_RESYNC_VERSION = intPreferencesKey("last_sms_resync_version")
        val AUTO_SWITCH_SEND_MODE = booleanPreferencesKey("auto_switch_send_mode")
    }
}
