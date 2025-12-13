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
 * Handles sync and state restoration preferences.
 */
class SyncPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // ===== Initial Sync Progress (Resumable) =====

    /**
     * Whether initial sync has been started (but may not be complete).
     * Used to detect interrupted syncs that need to resume.
     */
    val initialSyncStarted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.INITIAL_SYNC_STARTED] ?: false
    }

    /**
     * Whether initial sync has completed successfully.
     * When true, the app won't attempt to resume sync on startup.
     */
    val initialSyncComplete: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.INITIAL_SYNC_COMPLETE] ?: false
    }

    /**
     * Set of chat GUIDs that have been successfully synced.
     * Used to skip already-synced chats when resuming an interrupted sync.
     */
    val syncedChatGuids: Flow<Set<String>> = dataStore.data.map { prefs ->
        (prefs[Keys.SYNCED_CHAT_GUIDS] ?: "")
            .split(",")
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Messages per chat setting used for initial sync (stored for resume).
     */
    val initialSyncMessagesPerChat: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.INITIAL_SYNC_MESSAGES_PER_CHAT] ?: 25
    }

    // ===== State Restoration =====

    val lastOpenChatGuid: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_OPEN_CHAT_GUID]
    }

    val lastOpenChatMergedGuids: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_OPEN_CHAT_MERGED_GUIDS]
    }

    val lastScrollPosition: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SCROLL_POSITION] ?: 0
    }

    val lastScrollOffset: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SCROLL_OFFSET] ?: 0
    }

    val appLaunchTimestamps: Flow<List<Long>> = dataStore.data.map { prefs ->
        (prefs[Keys.APP_LAUNCH_TIMESTAMPS] ?: "")
            .split(",")
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toLongOrNull() }
    }

    // ===== Setters =====

    suspend fun setInitialSyncStarted(started: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.INITIAL_SYNC_STARTED] = started
        }
    }

    suspend fun setInitialSyncComplete(complete: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.INITIAL_SYNC_COMPLETE] = complete
        }
    }

    suspend fun markChatSynced(chatGuid: String) {
        dataStore.edit { prefs ->
            val current = (prefs[Keys.SYNCED_CHAT_GUIDS] ?: "")
                .split(",")
                .filter { it.isNotEmpty() }
                .toMutableSet()
            current.add(chatGuid)
            prefs[Keys.SYNCED_CHAT_GUIDS] = current.joinToString(",")
        }
    }

    suspend fun setInitialSyncMessagesPerChat(messagesPerChat: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.INITIAL_SYNC_MESSAGES_PER_CHAT] = messagesPerChat
        }
    }

    /**
     * Clear all sync progress. Called when starting a fresh sync or after reset.
     */
    suspend fun clearSyncProgress() {
        dataStore.edit { prefs ->
            prefs[Keys.INITIAL_SYNC_STARTED] = false
            prefs[Keys.INITIAL_SYNC_COMPLETE] = false
            prefs[Keys.SYNCED_CHAT_GUIDS] = ""
        }
    }

    suspend fun setLastOpenChat(chatGuid: String?, mergedGuids: String?) {
        dataStore.edit { prefs ->
            if (chatGuid != null) {
                prefs[Keys.LAST_OPEN_CHAT_GUID] = chatGuid
                if (mergedGuids != null) {
                    prefs[Keys.LAST_OPEN_CHAT_MERGED_GUIDS] = mergedGuids
                } else {
                    prefs.remove(Keys.LAST_OPEN_CHAT_MERGED_GUIDS)
                }
            } else {
                prefs.remove(Keys.LAST_OPEN_CHAT_GUID)
                prefs.remove(Keys.LAST_OPEN_CHAT_MERGED_GUIDS)
            }
        }
    }

    suspend fun setLastScrollPosition(position: Int, offset: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SCROLL_POSITION] = position
            prefs[Keys.LAST_SCROLL_OFFSET] = offset
        }
    }

    suspend fun clearLastOpenChat() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.LAST_OPEN_CHAT_GUID)
            prefs.remove(Keys.LAST_OPEN_CHAT_MERGED_GUIDS)
            prefs.remove(Keys.LAST_SCROLL_POSITION)
            prefs.remove(Keys.LAST_SCROLL_OFFSET)
        }
    }

    /**
     * Record an app launch timestamp and check if we should skip state restoration
     * due to repeated crashes (2+ launches within 1 minute).
     * Returns true if state restoration should be skipped.
     */
    suspend fun recordLaunchAndCheckCrashProtection(): Boolean {
        val currentTime = System.currentTimeMillis()
        val oneMinuteAgo = currentTime - 60_000L

        var shouldSkipRestore = false

        dataStore.edit { prefs ->
            val existingTimestamps = (prefs[Keys.APP_LAUNCH_TIMESTAMPS] ?: "")
                .split(",")
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toLongOrNull() }
                .filter { it > oneMinuteAgo } // Only keep timestamps within the last minute

            // If there are already 1+ launches in the last minute, this makes 2+
            shouldSkipRestore = existingTimestamps.isNotEmpty()

            // Add current timestamp and keep only recent ones
            val newTimestamps = (existingTimestamps + currentTime)
                .filter { it > oneMinuteAgo }
                .takeLast(5) // Keep max 5 timestamps

            prefs[Keys.APP_LAUNCH_TIMESTAMPS] = newTimestamps.joinToString(",")
        }

        return shouldSkipRestore
    }

    /**
     * Clear launch timestamps after successful app use (e.g., after being open for a while).
     * Call this to reset crash protection after the app has been stable.
     */
    suspend fun clearLaunchTimestamps() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.APP_LAUNCH_TIMESTAMPS)
        }
    }

    private object Keys {
        // Initial Sync Progress (Resumable)
        val INITIAL_SYNC_STARTED = booleanPreferencesKey("initial_sync_started")
        val INITIAL_SYNC_COMPLETE = booleanPreferencesKey("initial_sync_complete")
        val SYNCED_CHAT_GUIDS = stringPreferencesKey("synced_chat_guids")
        val INITIAL_SYNC_MESSAGES_PER_CHAT = intPreferencesKey("initial_sync_messages_per_chat")

        // State Restoration
        val LAST_OPEN_CHAT_GUID = stringPreferencesKey("last_open_chat_guid")
        val LAST_OPEN_CHAT_MERGED_GUIDS = stringPreferencesKey("last_open_chat_merged_guids")
        val LAST_SCROLL_POSITION = intPreferencesKey("last_scroll_position")
        val LAST_SCROLL_OFFSET = intPreferencesKey("last_scroll_offset")
        val APP_LAUNCH_TIMESTAMPS = stringPreferencesKey("app_launch_timestamps")
    }
}
