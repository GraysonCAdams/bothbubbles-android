package com.bothbubbles.data.local.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Handles feature preferences including ML categorization, spam detection, auto-responder, and developer mode.
 */
class FeaturePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // ===== Spam Settings =====

    val spamDetectionEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SPAM_DETECTION_ENABLED] ?: true
    }

    val spamThreshold: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.SPAM_THRESHOLD] ?: 70
    }

    // ===== Message Categorization (ML) Settings =====

    val mlModelDownloaded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ML_MODEL_DOWNLOADED] ?: false
    }

    val mlAutoUpdateOnCellular: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ML_AUTO_UPDATE_ON_CELLULAR] ?: false
    }

    val categorizationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.CATEGORIZATION_ENABLED] ?: true
    }

    // ===== Developer Mode =====

    val developerModeEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DEVELOPER_MODE_ENABLED] ?: false
    }

    // ===== Auto-Responder =====

    val autoResponderEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_RESPONDER_ENABLED] ?: false
    }

    /**
     * Auto-responder filter mode.
     * - "everyone": Respond to all iMessage users
     * - "known_senders": Only respond to contacts in address book
     * - "favorites": Only respond to starred contacts
     */
    val autoResponderFilter: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_RESPONDER_FILTER] ?: "known_senders"
    }

    /**
     * Rate limit for auto-responses per hour.
     * Default is 10 to prevent spam in case of issues.
     */
    val autoResponderRateLimit: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_RESPONDER_RATE_LIMIT] ?: 10
    }

    // ===== Setters =====

    suspend fun setSpamDetectionEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SPAM_DETECTION_ENABLED] = enabled
        }
    }

    suspend fun setSpamThreshold(threshold: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.SPAM_THRESHOLD] = threshold.coerceIn(30, 100)
        }
    }

    suspend fun setMlModelDownloaded(downloaded: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ML_MODEL_DOWNLOADED] = downloaded
        }
    }

    suspend fun setMlAutoUpdateOnCellular(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ML_AUTO_UPDATE_ON_CELLULAR] = enabled
        }
    }

    suspend fun setCategorizationEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.CATEGORIZATION_ENABLED] = enabled
        }
    }

    suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DEVELOPER_MODE_ENABLED] = enabled
        }
    }

    suspend fun setAutoResponderEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_RESPONDER_ENABLED] = enabled
        }
    }

    suspend fun setAutoResponderFilter(filter: String) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_RESPONDER_FILTER] = filter
        }
    }

    suspend fun setAutoResponderRateLimit(limit: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_RESPONDER_RATE_LIMIT] = limit.coerceIn(1, 50)
        }
    }

    private object Keys {
        // Spam Settings
        val SPAM_DETECTION_ENABLED = booleanPreferencesKey("spam_detection_enabled")
        val SPAM_THRESHOLD = intPreferencesKey("spam_threshold")

        // Message Categorization (ML) Settings
        val ML_MODEL_DOWNLOADED = booleanPreferencesKey("ml_model_downloaded")
        val ML_AUTO_UPDATE_ON_CELLULAR = booleanPreferencesKey("ml_auto_update_on_cellular")
        val CATEGORIZATION_ENABLED = booleanPreferencesKey("categorization_enabled")

        // Developer Mode
        val DEVELOPER_MODE_ENABLED = booleanPreferencesKey("developer_mode_enabled")

        // Auto-Responder
        val AUTO_RESPONDER_ENABLED = booleanPreferencesKey("auto_responder_enabled")
        val AUTO_RESPONDER_FILTER = stringPreferencesKey("auto_responder_filter")
        val AUTO_RESPONDER_RATE_LIMIT = intPreferencesKey("auto_responder_rate_limit")
    }
}
