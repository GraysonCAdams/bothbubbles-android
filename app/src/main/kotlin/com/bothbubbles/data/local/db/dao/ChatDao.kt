package com.bothbubbles.data.local.db.dao

import androidx.room.Dao

/**
 * Main ChatDao that combines all chat-related data access operations.
 * Functionality is separated into logical sub-DAOs:
 * - ChatQueryDao: Basic query operations
 * - ChatGroupDao: Group and unified chat queries
 * - ChatParticipantDao: Participant management
 * - ChatUpdateDao: Update operations
 * - ChatNotificationDao: Notification settings
 * - ChatDeleteDao: Delete operations
 * - ChatCategorizationDao: Categorization and spam
 * - ChatTransactionDao: Transaction operations
 */
@Dao
interface ChatDao :
    ChatQueryDao,
    ChatGroupDao,
    ChatParticipantDao,
    ChatUpdateDao,
    ChatNotificationDao,
    ChatDeleteDao,
    ChatCategorizationDao,
    ChatTransactionDao
