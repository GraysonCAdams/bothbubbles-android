package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Notification settings operations for chats.
 * Separated from main ChatDao for better organization.
 */
@Dao
interface ChatNotificationDao {

    @Query("UPDATE chats SET notifications_enabled = :enabled WHERE guid = :guid")
    suspend fun updateNotificationsEnabled(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET notification_priority = :priority WHERE guid = :guid")
    suspend fun updateNotificationPriority(guid: String, priority: String)

    @Query("UPDATE chats SET bubble_enabled = :enabled WHERE guid = :guid")
    suspend fun updateBubbleEnabled(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET pop_on_screen = :enabled WHERE guid = :guid")
    suspend fun updatePopOnScreen(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET custom_notification_sound = :sound WHERE guid = :guid")
    suspend fun updateNotificationSound(guid: String, sound: String?)

    @Query("UPDATE chats SET lock_screen_visibility = :visibility WHERE guid = :guid")
    suspend fun updateLockScreenVisibility(guid: String, visibility: String)

    @Query("UPDATE chats SET show_notification_dot = :enabled WHERE guid = :guid")
    suspend fun updateShowNotificationDot(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET vibration_enabled = :enabled WHERE guid = :guid")
    suspend fun updateVibrationEnabled(guid: String, enabled: Boolean)

    @Query("UPDATE chats SET snooze_until = :snoozeUntil WHERE guid = :guid")
    suspend fun updateSnoozeUntil(guid: String, snoozeUntil: Long?)
}
