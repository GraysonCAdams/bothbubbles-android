package com.bothbubbles.data.local.db.dao

import androidx.room.Dao

/**
 * Categorization and spam operations for chats.
 *
 * DEPRECATED: Spam and categorization fields have moved to [UnifiedChatEntity].
 * Use [UnifiedChatDao] for spam and category operations.
 *
 * This interface is kept for backwards compatibility with [ChatDao] composition.
 */
@Dao
interface ChatCategorizationDao {
    // All spam and categorization now managed through UnifiedChatDao
}
