package com.bothbubbles.data.local.db.dao

import androidx.room.Dao

/**
 * Notification settings operations for chats.
 *
 * DEPRECATED: Notification settings (notifications_enabled, snooze_until) have moved
 * to [UnifiedChatEntity]. Use [UnifiedChatDao] for notification settings.
 *
 * This interface is kept for backwards compatibility with [ChatDao] composition.
 */
@Dao
interface ChatNotificationDao {
    // All notification settings now managed through UnifiedChatDao
}
