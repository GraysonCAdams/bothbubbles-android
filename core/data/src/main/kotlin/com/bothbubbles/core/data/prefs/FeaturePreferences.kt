package com.bothbubbles.core.data.prefs

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

    // Per-category enabled settings
    val transactionsCategoryEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.TRANSACTIONS_CATEGORY_ENABLED] ?: true
    }

    val deliveriesCategoryEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DELIVERIES_CATEGORY_ENABLED] ?: true
    }

    val promotionsCategoryEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.PROMOTIONS_CATEGORY_ENABLED] ?: true
    }

    val remindersCategoryEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.REMINDERS_CATEGORY_ENABLED] ?: true
    }

    // ===== Developer Mode =====

    val developerModeEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DEVELOPER_MODE_ENABLED] ?: false
    }

    // ===== Link Previews =====

    val linkPreviewsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.LINK_PREVIEWS_ENABLED] ?: true
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
     * Whether to send ETA change notifications when arrival time shifts significantly.
     * When enabled, sends updates if arrival time changes by ≥5 minutes (with 10 min cooldown).
     */
    val etaChangeNotificationsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ETA_CHANGE_NOTIFICATIONS_ENABLED] ?: true
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

    // ===== Life360 Integration =====

    /**
     * Whether Life360 integration is enabled.
     */
    val life360Enabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.LIFE360_ENABLED] ?: false
    }

    /**
     * How often to poll for location updates (in minutes).
     * Default is 10 minutes.
     */
    val life360PollIntervalMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.LIFE360_POLL_INTERVAL] ?: 10
    }

    /**
     * Ghost mode - pause all Life360 syncing without logging out.
     */
    val life360PauseSyncing: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.LIFE360_PAUSE_SYNCING] ?: false
    }

    // ===== Social Media Downloading =====

    /**
     * Whether TikTok video downloading is enabled.
     * When enabled, TikTok links will fetch the video stream instead of opening in browser.
     */
    val tiktokDownloaderEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.TIKTOK_DOWNLOADER_ENABLED] ?: false
    }

    /**
     * Whether Instagram video downloading is enabled.
     * When enabled, Instagram Reels/video links will fetch the video stream instead of opening in browser.
     */
    val instagramDownloaderEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.INSTAGRAM_DOWNLOADER_ENABLED] ?: false
    }

    /**
     * Whether to automatically download social media videos in the background when messages are received.
     * When disabled, videos are only fetched when the user opens the chat and views the message.
     */
    val socialMediaBackgroundDownloadEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SOCIAL_MEDIA_BACKGROUND_DOWNLOAD_ENABLED] ?: false
    }

    /**
     * Whether to allow social media video downloads over cellular data.
     * When disabled, downloads will only occur on Wi-Fi.
     */
    val socialMediaDownloadOnCellularEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SOCIAL_MEDIA_DOWNLOAD_ON_CELLULAR_ENABLED] ?: false
    }

    /**
     * Video quality preference for TikTok downloads.
     * Options: "sd" (standard), "hd" (high definition)
     */
    val tiktokVideoQuality: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.TIKTOK_VIDEO_QUALITY] ?: "hd"
    }

    /**
     * Whether the vertical swipe Reels feed is enabled.
     * Only functional when background downloading is also enabled.
     */
    val reelsFeedEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.REELS_FEED_ENABLED] ?: false
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

    suspend fun setTransactionsCategoryEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.TRANSACTIONS_CATEGORY_ENABLED] = enabled
        }
    }

    suspend fun setDeliveriesCategoryEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DELIVERIES_CATEGORY_ENABLED] = enabled
        }
    }

    suspend fun setPromotionsCategoryEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.PROMOTIONS_CATEGORY_ENABLED] = enabled
        }
    }

    suspend fun setRemindersCategoryEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.REMINDERS_CATEGORY_ENABLED] = enabled
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

    suspend fun setEtaChangeNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ETA_CHANGE_NOTIFICATIONS_ENABLED] = enabled
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

    suspend fun setLife360Enabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.LIFE360_ENABLED] = enabled
        }
    }

    suspend fun setLife360PollInterval(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.LIFE360_POLL_INTERVAL] = minutes.coerceIn(5, 30)
        }
    }

    suspend fun setLife360PauseSyncing(paused: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.LIFE360_PAUSE_SYNCING] = paused
        }
    }

    suspend fun setTiktokDownloaderEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.TIKTOK_DOWNLOADER_ENABLED] = enabled
        }
    }

    suspend fun setInstagramDownloaderEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.INSTAGRAM_DOWNLOADER_ENABLED] = enabled
        }
    }

    suspend fun setSocialMediaBackgroundDownloadEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SOCIAL_MEDIA_BACKGROUND_DOWNLOAD_ENABLED] = enabled
        }
    }

    suspend fun setSocialMediaDownloadOnCellularEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SOCIAL_MEDIA_DOWNLOAD_ON_CELLULAR_ENABLED] = enabled
        }
    }

    suspend fun setTiktokVideoQuality(quality: String) {
        dataStore.edit { prefs ->
            prefs[Keys.TIKTOK_VIDEO_QUALITY] = quality
        }
    }

    suspend fun setReelsFeedEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.REELS_FEED_ENABLED] = enabled
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
        val TRANSACTIONS_CATEGORY_ENABLED = booleanPreferencesKey("transactions_category_enabled")
        val DELIVERIES_CATEGORY_ENABLED = booleanPreferencesKey("deliveries_category_enabled")
        val PROMOTIONS_CATEGORY_ENABLED = booleanPreferencesKey("promotions_category_enabled")
        val REMINDERS_CATEGORY_ENABLED = booleanPreferencesKey("reminders_category_enabled")

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
        val ETA_CHANGE_NOTIFICATIONS_ENABLED = booleanPreferencesKey("eta_change_notifications_enabled")
        val AUTO_SHARE_MINIMUM_ETA_MINUTES = intPreferencesKey("auto_share_minimum_eta_minutes")

        // Android Auto
        val ANDROID_AUTO_PRIVACY_MODE = booleanPreferencesKey("android_auto_privacy_mode")

        // Life360 Integration
        val LIFE360_ENABLED = booleanPreferencesKey("life360_enabled")
        val LIFE360_POLL_INTERVAL = intPreferencesKey("life360_poll_interval")
        val LIFE360_PAUSE_SYNCING = booleanPreferencesKey("life360_pause_syncing")

        // Social Media Downloading
        val TIKTOK_DOWNLOADER_ENABLED = booleanPreferencesKey("tiktok_downloader_enabled")
        val INSTAGRAM_DOWNLOADER_ENABLED = booleanPreferencesKey("instagram_downloader_enabled")
        val SOCIAL_MEDIA_BACKGROUND_DOWNLOAD_ENABLED = booleanPreferencesKey("social_media_background_download_enabled")
        val SOCIAL_MEDIA_DOWNLOAD_ON_CELLULAR_ENABLED = booleanPreferencesKey("social_media_download_on_cellular_enabled")
        val TIKTOK_VIDEO_QUALITY = stringPreferencesKey("tiktok_video_quality")
        val REELS_FEED_ENABLED = booleanPreferencesKey("reels_feed_enabled")
    }
}
