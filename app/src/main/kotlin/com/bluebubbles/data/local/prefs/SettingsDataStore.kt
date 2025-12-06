package com.bluebubbles.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

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

    // ===== UI Preferences =====

    val useDynamicColor: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.USE_DYNAMIC_COLOR] ?: true
    }

    val useSimpleAppTitle: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.USE_SIMPLE_APP_TITLE] ?: false
    }

    val denseChatTiles: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DENSE_CHAT_TILES] ?: false
    }

    val use24HourFormat: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.USE_24_HOUR_FORMAT] ?: false
    }

    val showDeliveryTimestamps: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SHOW_DELIVERY_TIMESTAMPS] ?: true
    }

    // ===== Chat Preferences =====

    val sendWithReturn: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SEND_WITH_RETURN] ?: false
    }

    val autoOpenKeyboard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_OPEN_KEYBOARD] ?: true
    }

    val enablePrivateApi: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ENABLE_PRIVATE_API] ?: false
    }

    val sendTypingIndicators: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SEND_TYPING_INDICATORS] ?: true
    }

    val hasShownPrivateApiPrompt: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HAS_SHOWN_PRIVATE_API_PROMPT] ?: false
    }

    // ===== SMS Settings =====

    val smsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SMS_ENABLED] ?: false
    }

    val smsOnlyMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SMS_ONLY_MODE] ?: false
    }

    val autoRetryAsSms: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_RETRY_AS_SMS] ?: true  // Default to auto-retry as SMS when iMessage fails
    }

    val preferSmsOverIMessage: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.PREFER_SMS_OVER_IMESSAGE] ?: false
    }

    val selectedSimSlot: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_SIM_SLOT] ?: -1
    }

    // ===== Notification Settings =====

    val notificationsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NOTIFICATIONS_ENABLED] ?: true
    }

    val notifyOnChatList: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NOTIFY_ON_CHAT_LIST] ?: false
    }

    // ===== Background Service =====

    val keepAlive: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.KEEP_ALIVE] ?: true
    }

    // ===== Swipe Gesture Settings =====

    val swipeGesturesEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SWIPE_GESTURES_ENABLED] ?: true
    }

    val swipeLeftAction: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SWIPE_LEFT_ACTION] ?: "archive"
    }

    val swipeRightAction: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SWIPE_RIGHT_ACTION] ?: "pin"
    }

    val swipeSensitivity: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.SWIPE_SENSITIVITY] ?: 0.25f
    }

    // ===== Call Settings =====

    val preferredCallMethod: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.PREFERRED_CALL_METHOD] ?: "google_meet"
    }

    // ===== Dismissed Banners =====

    val dismissedSaveContactBanners: Flow<Set<String>> = dataStore.data.map { prefs ->
        (prefs[Keys.DISMISSED_SAVE_CONTACT_BANNERS] ?: "")
            .split(",")
            .filter { it.isNotEmpty() }
            .toSet()
    }

    val dismissedSetupBanner: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DISMISSED_SETUP_BANNER] ?: false
    }

    val dismissedSmsBanner: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DISMISSED_SMS_BANNER] ?: false
    }

    // ===== Message Effects =====

    val autoPlayEffects: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_PLAY_EFFECTS] ?: true
    }

    val replayEffectsOnScroll: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.REPLAY_EFFECTS_ON_SCROLL] ?: false
    }

    val reduceMotion: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.REDUCE_MOTION] ?: false
    }

    // ===== Sound Settings =====

    val messageSoundsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.MESSAGE_SOUNDS_ENABLED] ?: true
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

    suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.USE_DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setUseSimpleAppTitle(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.USE_SIMPLE_APP_TITLE] = enabled
        }
    }

    suspend fun setDenseChatTiles(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DENSE_CHAT_TILES] = enabled
        }
    }

    suspend fun setUse24HourFormat(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.USE_24_HOUR_FORMAT] = enabled
        }
    }

    suspend fun setShowDeliveryTimestamps(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_DELIVERY_TIMESTAMPS] = enabled
        }
    }

    suspend fun setSendWithReturn(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SEND_WITH_RETURN] = enabled
        }
    }

    suspend fun setAutoOpenKeyboard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_OPEN_KEYBOARD] = enabled
        }
    }

    suspend fun setEnablePrivateApi(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ENABLE_PRIVATE_API] = enabled
        }
    }

    suspend fun setSendTypingIndicators(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SEND_TYPING_INDICATORS] = enabled
        }
    }

    suspend fun setHasShownPrivateApiPrompt(shown: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_SHOWN_PRIVATE_API_PROMPT] = shown
        }
    }

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

    suspend fun setAutoRetryAsSms(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_RETRY_AS_SMS] = enabled
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

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.NOTIFICATIONS_ENABLED] = enabled
        }
    }

    suspend fun setNotifyOnChatList(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.NOTIFY_ON_CHAT_LIST] = enabled
        }
    }

    suspend fun setKeepAlive(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.KEEP_ALIVE] = enabled
        }
    }

    suspend fun setSwipeGesturesEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SWIPE_GESTURES_ENABLED] = enabled
        }
    }

    suspend fun setSwipeLeftAction(action: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SWIPE_LEFT_ACTION] = action
        }
    }

    suspend fun setSwipeRightAction(action: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SWIPE_RIGHT_ACTION] = action
        }
    }

    suspend fun setSwipeSensitivity(sensitivity: Float) {
        dataStore.edit { prefs ->
            prefs[Keys.SWIPE_SENSITIVITY] = sensitivity
        }
    }

    suspend fun setPreferredCallMethod(method: String) {
        dataStore.edit { prefs ->
            prefs[Keys.PREFERRED_CALL_METHOD] = method
        }
    }

    suspend fun dismissSaveContactBanner(address: String) {
        dataStore.edit { prefs ->
            val current = (prefs[Keys.DISMISSED_SAVE_CONTACT_BANNERS] ?: "")
                .split(",")
                .filter { it.isNotEmpty() }
                .toMutableSet()
            current.add(address)
            prefs[Keys.DISMISSED_SAVE_CONTACT_BANNERS] = current.joinToString(",")
        }
    }

    suspend fun setDismissedSetupBanner(dismissed: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DISMISSED_SETUP_BANNER] = dismissed
        }
    }

    suspend fun resetSetupBannerDismissal() {
        dataStore.edit { prefs ->
            prefs[Keys.DISMISSED_SETUP_BANNER] = false
        }
    }

    suspend fun setDismissedSmsBanner(dismissed: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DISMISSED_SMS_BANNER] = dismissed
        }
    }

    suspend fun resetSmsBannerDismissal() {
        dataStore.edit { prefs ->
            prefs[Keys.DISMISSED_SMS_BANNER] = false
        }
    }

    suspend fun setAutoPlayEffects(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_PLAY_EFFECTS] = enabled
        }
    }

    suspend fun setReplayEffectsOnScroll(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.REPLAY_EFFECTS_ON_SCROLL] = enabled
        }
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.REDUCE_MOTION] = enabled
        }
    }

    suspend fun setMessageSoundsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.MESSAGE_SOUNDS_ENABLED] = enabled
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
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
        // Server
        val SERVER_ADDRESS = stringPreferencesKey("server_address")
        val GUID_AUTH_KEY = stringPreferencesKey("guid_auth_key")
        val CUSTOM_HEADERS = stringPreferencesKey("custom_headers")
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")

        // UI
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val USE_SIMPLE_APP_TITLE = booleanPreferencesKey("use_simple_app_title")
        val DENSE_CHAT_TILES = booleanPreferencesKey("dense_chat_tiles")
        val USE_24_HOUR_FORMAT = booleanPreferencesKey("use_24_hour_format")
        val SHOW_DELIVERY_TIMESTAMPS = booleanPreferencesKey("show_delivery_timestamps")

        // Chat
        val SEND_WITH_RETURN = booleanPreferencesKey("send_with_return")
        val AUTO_OPEN_KEYBOARD = booleanPreferencesKey("auto_open_keyboard")
        val ENABLE_PRIVATE_API = booleanPreferencesKey("enable_private_api")
        val SEND_TYPING_INDICATORS = booleanPreferencesKey("send_typing_indicators")
        val HAS_SHOWN_PRIVATE_API_PROMPT = booleanPreferencesKey("has_shown_private_api_prompt")

        // SMS
        val SMS_ENABLED = booleanPreferencesKey("sms_enabled")
        val SMS_ONLY_MODE = booleanPreferencesKey("sms_only_mode")
        val AUTO_RETRY_AS_SMS = booleanPreferencesKey("auto_retry_as_sms")
        val PREFER_SMS_OVER_IMESSAGE = booleanPreferencesKey("prefer_sms_over_imessage")
        val SELECTED_SIM_SLOT = intPreferencesKey("selected_sim_slot")

        // Notifications
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFY_ON_CHAT_LIST = booleanPreferencesKey("notify_on_chat_list")

        // Background
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")

        // Swipe Gestures
        val SWIPE_GESTURES_ENABLED = booleanPreferencesKey("swipe_gestures_enabled")
        val SWIPE_LEFT_ACTION = stringPreferencesKey("swipe_left_action")
        val SWIPE_RIGHT_ACTION = stringPreferencesKey("swipe_right_action")
        val SWIPE_SENSITIVITY = floatPreferencesKey("swipe_sensitivity")

        // Call Settings
        val PREFERRED_CALL_METHOD = stringPreferencesKey("preferred_call_method")

        // Dismissed Banners
        val DISMISSED_SAVE_CONTACT_BANNERS = stringPreferencesKey("dismissed_save_contact_banners")
        val DISMISSED_SETUP_BANNER = booleanPreferencesKey("dismissed_setup_banner")
        val DISMISSED_SMS_BANNER = booleanPreferencesKey("dismissed_sms_banner")

        // Message Effects
        val AUTO_PLAY_EFFECTS = booleanPreferencesKey("auto_play_effects")
        val REPLAY_EFFECTS_ON_SCROLL = booleanPreferencesKey("replay_effects_on_scroll")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")

        // Sound Settings
        val MESSAGE_SOUNDS_ENABLED = booleanPreferencesKey("message_sounds_enabled")
    }
}
