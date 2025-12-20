package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Notification settings operations for chats.
 *
 * Note: Sound, vibration, importance, and other notification customizations are handled
 * by Android's per-conversation notification channels. Users can customize these via
 * Android Settings > Apps > BothBubbles > Notifications > [Conversation].
 *
 * This DAO only manages app-level settings that are independent of Android channels:
 * - notifications_enabled: App-level mute toggle
 * - snooze_until: Temporary notification snooze
 */
@Dao
interface ChatNotificationDao {

    @Query("UPDATE chats SET notifications_enabled = :enabled WHERE guid = :guid")
    suspend fun updateNotificationsEnabled(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET snooze_until = :snoozeUntil WHERE guid = :guid")
    suspend fun updateSnoozeUntil(guid: String, snoozeUntil: Long?)
}
