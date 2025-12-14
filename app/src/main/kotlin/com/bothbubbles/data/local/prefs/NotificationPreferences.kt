package com.bothbubbles.data.local.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Handles notification and FCM push notification preferences.
 */
class NotificationPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // ===== Notification Settings =====

    val notificationsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NOTIFICATIONS_ENABLED] ?: true
    }

    val notifyOnChatList: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NOTIFY_ON_CHAT_LIST] ?: false
    }

    /**
     * Bubble filter mode for chat bubbles.
     * - "all": Show bubbles for all conversations
     * - "favorites": Only show bubbles for starred contacts (Android favorites)
     * - "selected": Only show bubbles for conversations in selectedBubbleChats
     * - "none": Disable bubbles entirely
     */
    val bubbleFilterMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.BUBBLE_FILTER_MODE] ?: "all"
    }

    /**
     * Set of chat GUIDs that should show bubbles when bubbleFilterMode is "selected".
     */
    val selectedBubbleChats: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_BUBBLE_CHATS] ?: emptySet()
    }

    // ===== FCM Push Notifications =====

    /**
     * Notification provider mode.
     * - "socket": Use socket connection for real-time events (default - FCM config is for official app only)
     * - "fcm": Use Firebase Cloud Messaging (requires compatible Firebase config)
     * - "foreground": Use foreground service to keep socket connection alive
     */
    val notificationProvider: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.NOTIFICATION_PROVIDER] ?: "socket"
    }

    val fcmToken: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.FCM_TOKEN] ?: ""
    }

    val fcmTokenRegistered: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.FCM_TOKEN_REGISTERED] ?: false
    }

    // Firebase dynamic config (fetched from server)
    val firebaseProjectNumber: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.FIREBASE_PROJECT_NUMBER] ?: ""
    }

    val firebaseProjectId: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.FIREBASE_PROJECT_ID] ?: ""
    }

    val firebaseAppId: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.FIREBASE_APP_ID] ?: ""
    }

    val firebaseApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.FIREBASE_API_KEY] ?: ""
    }

    val firebaseStorageBucket: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.FIREBASE_STORAGE_BUCKET] ?: ""
    }

    // ===== Background Service =====

    val keepAlive: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.KEEP_ALIVE] ?: true
    }

    // ===== Setters =====

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

    suspend fun setBubbleFilterMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[Keys.BUBBLE_FILTER_MODE] = mode
        }
    }

    suspend fun setSelectedBubbleChats(chatGuids: Set<String>) {
        dataStore.edit { prefs ->
            prefs[Keys.SELECTED_BUBBLE_CHATS] = chatGuids
        }
    }

    suspend fun addSelectedBubbleChat(chatGuid: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.SELECTED_BUBBLE_CHATS] ?: emptySet()
            prefs[Keys.SELECTED_BUBBLE_CHATS] = current + chatGuid
        }
    }

    suspend fun removeSelectedBubbleChat(chatGuid: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.SELECTED_BUBBLE_CHATS] ?: emptySet()
            prefs[Keys.SELECTED_BUBBLE_CHATS] = current - chatGuid
        }
    }

    suspend fun setNotificationProvider(provider: String) {
        dataStore.edit { prefs ->
            prefs[Keys.NOTIFICATION_PROVIDER] = provider
        }
    }

    suspend fun setFcmToken(token: String) {
        dataStore.edit { prefs ->
            prefs[Keys.FCM_TOKEN] = token
        }
    }

    suspend fun setFcmTokenRegistered(registered: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.FCM_TOKEN_REGISTERED] = registered
        }
    }

    suspend fun setFirebaseConfig(
        projectNumber: String,
        projectId: String,
        appId: String,
        apiKey: String,
        storageBucket: String
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.FIREBASE_PROJECT_NUMBER] = projectNumber
            prefs[Keys.FIREBASE_PROJECT_ID] = projectId
            prefs[Keys.FIREBASE_APP_ID] = appId
            prefs[Keys.FIREBASE_API_KEY] = apiKey
            prefs[Keys.FIREBASE_STORAGE_BUCKET] = storageBucket
        }
    }

    suspend fun clearFirebaseConfig() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.FIREBASE_PROJECT_NUMBER)
            prefs.remove(Keys.FIREBASE_PROJECT_ID)
            prefs.remove(Keys.FIREBASE_APP_ID)
            prefs.remove(Keys.FIREBASE_API_KEY)
            prefs.remove(Keys.FIREBASE_STORAGE_BUCKET)
            prefs.remove(Keys.FCM_TOKEN)
            prefs[Keys.FCM_TOKEN_REGISTERED] = false
        }
    }

    suspend fun setKeepAlive(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.KEEP_ALIVE] = enabled
        }
    }

    private object Keys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFY_ON_CHAT_LIST = booleanPreferencesKey("notify_on_chat_list")
        val BUBBLE_FILTER_MODE = stringPreferencesKey("bubble_filter_mode")
        val SELECTED_BUBBLE_CHATS = stringSetPreferencesKey("selected_bubble_chats")

        // FCM Push Notifications
        val NOTIFICATION_PROVIDER = stringPreferencesKey("notification_provider")
        val FCM_TOKEN = stringPreferencesKey("fcm_token")
        val FCM_TOKEN_REGISTERED = booleanPreferencesKey("fcm_token_registered")
        val FIREBASE_PROJECT_NUMBER = stringPreferencesKey("firebase_project_number")
        val FIREBASE_PROJECT_ID = stringPreferencesKey("firebase_project_id")
        val FIREBASE_APP_ID = stringPreferencesKey("firebase_app_id")
        val FIREBASE_API_KEY = stringPreferencesKey("firebase_api_key")
        val FIREBASE_STORAGE_BUCKET = stringPreferencesKey("firebase_storage_bucket")

        // Background
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
    }
}
