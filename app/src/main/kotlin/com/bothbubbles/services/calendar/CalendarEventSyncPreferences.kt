package com.bothbubbles.services.calendar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences for tracking calendar event sync state per contact.
 *
 * Used to determine when retroactive sync is needed:
 * - If a contact hasn't been synced in 48+ hours, fetch past events on conversation open
 * - Tracks last sync time per contact address
 */
@Singleton
class CalendarEventSyncPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "calendar_event_sync"
        private const val KEY_PREFIX_LAST_SYNC = "last_sync_"

        /** 48 hours in milliseconds */
        const val STALE_THRESHOLD_MS = 48 * 60 * 60 * 1000L
    }

    /**
     * Get the last sync time for a contact address.
     * Returns 0 if never synced.
     */
    fun getLastSyncTime(address: String): Long {
        return prefs.getLong(KEY_PREFIX_LAST_SYNC + address, 0L)
    }

    /**
     * Record sync time for a contact address.
     * @param address The normalized contact address
     * @param time The sync time (defaults to current time)
     */
    fun setLastSyncTime(address: String, time: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_PREFIX_LAST_SYNC + address, time).apply()
    }

    /**
     * Check if a contact needs retroactive sync.
     * Returns true if:
     * - Contact has never been synced (lastSync == 0)
     * - Last sync was 48+ hours ago
     */
    fun needsRetroactiveSync(address: String): Boolean {
        val lastSync = getLastSyncTime(address)
        if (lastSync == 0L) return true
        return (System.currentTimeMillis() - lastSync) >= STALE_THRESHOLD_MS
    }

    /**
     * Clear sync time for a contact address.
     * Called when calendar association is removed.
     */
    fun clearSyncTime(address: String) {
        prefs.edit().remove(KEY_PREFIX_LAST_SYNC + address).apply()
    }

    /**
     * Clear all sync times.
     * Called when all calendar associations are removed.
     */
    fun clearAllSyncTimes() {
        prefs.edit().clear().apply()
    }
}
