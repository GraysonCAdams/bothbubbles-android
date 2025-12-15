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

    // ===== Link Previews =====

    val linkPreviewsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.LINK_PREVIEWS_ENABLED] ?: false  // Default false until pagination is implemented
    }

    // ===== ETA Sharing =====

    val etaSharingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ETA_SHARING_ENABLED] ?: false
    }

    /**
     * How often to send ETA updates (minimum minutes between updates)
     */
    val etaUpdateInterval: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.ETA_UPDATE_INTERVAL] ?: 15
    }

    /**
     * Minimum ETA change (in minutes) to trigger an update
     */
    val etaChangeThreshold: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.ETA_CHANGE_THRESHOLD] ?: 5
    }

    /**
     * Minimum ETA (in minutes) to trigger auto-sharing.
     * Prevents auto-sharing for very short trips (e.g., driving around the block).
     * Default is 5 minutes.
     */
    val autoShareMinimumEtaMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_SHARE_MINIMUM_ETA_MINUTES] ?: 5
    }

    // ===== Android Auto Settings =====

    /**
     * Privacy mode for Android Auto.
     * When enabled, hides message content on the car display,
     * showing only "New Message" instead of actual text.
     */
    val androidAutoPrivacyMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ANDROID_AUTO_PRIVACY_MODE] ?: false
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

    /**
     * The iMessage alias (phone number or email) to recommend in auto-response messages.
     * Empty string means let the user choose from available aliases.
     */
    val autoResponderRecommendedAlias: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_RESPONDER_RECOMMENDED_ALIAS] ?: ""
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

    suspend fun setLinkPreviewsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.LINK_PREVIEWS_ENABLED] = enabled
        }
    }

    suspend fun setEtaSharingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ETA_SHARING_ENABLED] = enabled
        }
    }

    suspend fun setEtaUpdateInterval(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.ETA_UPDATE_INTERVAL] = minutes.coerceIn(5, 30)
        }
    }

    suspend fun setEtaChangeThreshold(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.ETA_CHANGE_THRESHOLD] = minutes.coerceIn(2, 15)
        }
    }

    suspend fun setAutoShareMinimumEtaMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_SHARE_MINIMUM_ETA_MINUTES] = minutes.coerceIn(1, 30)
        }
    }

    suspend fun setAndroidAutoPrivacyMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ANDROID_AUTO_PRIVACY_MODE] = enabled
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

    suspend fun setAutoResponderRecommendedAlias(alias: String) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_RESPONDER_RECOMMENDED_ALIAS] = alias
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

        // Link Previews
        val LINK_PREVIEWS_ENABLED = booleanPreferencesKey("link_previews_enabled")

        // Auto-Responder
        val AUTO_RESPONDER_ENABLED = booleanPreferencesKey("auto_responder_enabled")
        val AUTO_RESPONDER_FILTER = stringPreferencesKey("auto_responder_filter")
        val AUTO_RESPONDER_RATE_LIMIT = intPreferencesKey("auto_responder_rate_limit")
        val AUTO_RESPONDER_RECOMMENDED_ALIAS = stringPreferencesKey("auto_responder_recommended_alias")

        // ETA Sharing
        val ETA_SHARING_ENABLED = booleanPreferencesKey("eta_sharing_enabled")
        val ETA_UPDATE_INTERVAL = intPreferencesKey("eta_update_interval")
        val ETA_CHANGE_THRESHOLD = intPreferencesKey("eta_change_threshold")
        val AUTO_SHARE_MINIMUM_ETA_MINUTES = intPreferencesKey("auto_share_minimum_eta_minutes")

        // Android Auto
        val ANDROID_AUTO_PRIVACY_MODE = booleanPreferencesKey("android_auto_privacy_mode")
    }
}
