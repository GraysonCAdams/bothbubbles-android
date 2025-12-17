package com.bothbubbles.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bothbubbles.core.model.SoundTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Handles UI, theme, chat, gesture, and sound preferences.
 */
class UiPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
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

    /**
     * Whether the user has completed the SMS/iMessage toggle tutorial.
     * The tutorial is shown once when first opening an eligible chat.
     */
    val hasCompletedSendModeTutorial: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HAS_COMPLETED_SEND_MODE_TUTORIAL] ?: false
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
        prefs[Keys.SWIPE_SENSITIVITY] ?: 0.4f
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

    val soundTheme: Flow<SoundTheme> = dataStore.data.map { prefs ->
        val themeName = prefs[Keys.SOUND_THEME] ?: SoundTheme.DEFAULT.name
        try {
            SoundTheme.valueOf(themeName)
        } catch (e: IllegalArgumentException) {
            SoundTheme.DEFAULT
        }
    }

    // ===== Haptic Feedback Settings =====

    /**
     * Whether haptic feedback is enabled globally.
     * When disabled, no haptic feedback will be triggered.
     */
    val hapticsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HAPTICS_ENABLED] ?: true
    }

    /**
     * Whether to sync haptic patterns with sound effects.
     * When enabled, uses rich VibrationEffect.Composition patterns.
     */
    val audioHapticSyncEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUDIO_HAPTIC_SYNC_ENABLED] ?: true
    }

    // ===== Conversation Filter Settings =====

    /**
     * Default conversation filter for the conversations list.
     * Values: "all", "unread", "spam", "unknown_senders", "known_senders"
     */
    val conversationFilter: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.CONVERSATION_FILTER] ?: "all"
    }

    /**
     * Default category filter for the conversations list.
     * Values: null (no filter), or MessageCategory name (e.g., "TRANSACTIONS", "DELIVERIES")
     */
    val categoryFilter: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.CATEGORY_FILTER]
    }

    // ===== Setters =====

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

    /**
     * Mark the SMS/iMessage toggle tutorial as completed.
     * Once completed, the tutorial will never show again.
     */
    suspend fun setHasCompletedSendModeTutorial(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_COMPLETED_SEND_MODE_TUTORIAL] = completed
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

    suspend fun setSoundTheme(theme: SoundTheme) {
        dataStore.edit { prefs ->
            prefs[Keys.SOUND_THEME] = theme.name
        }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.HAPTICS_ENABLED] = enabled
        }
    }

    suspend fun setAudioHapticSyncEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUDIO_HAPTIC_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setConversationFilter(filter: String) {
        dataStore.edit { prefs ->
            prefs[Keys.CONVERSATION_FILTER] = filter
        }
    }

    suspend fun setCategoryFilter(category: String?) {
        dataStore.edit { prefs ->
            if (category != null) {
                prefs[Keys.CATEGORY_FILTER] = category
            } else {
                prefs.remove(Keys.CATEGORY_FILTER)
            }
        }
    }

    private object Keys {
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
        val HAS_COMPLETED_SEND_MODE_TUTORIAL = booleanPreferencesKey("has_completed_send_mode_tutorial")

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
        val SOUND_THEME = stringPreferencesKey("sound_theme")

        // Haptic Feedback Settings
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val AUDIO_HAPTIC_SYNC_ENABLED = booleanPreferencesKey("audio_haptic_sync_enabled")

        // Conversation Filter Settings
        val CONVERSATION_FILTER = stringPreferencesKey("conversation_filter")
        val CATEGORY_FILTER = stringPreferencesKey("category_filter")
    }
}
