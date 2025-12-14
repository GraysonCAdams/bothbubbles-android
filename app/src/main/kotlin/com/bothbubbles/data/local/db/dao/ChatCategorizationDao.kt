package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.bothbubbles.data.local.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

/**
 * Categorization and spam operations for chats.
 * Separated from main ChatDao for better organization.
 */
@Dao
interface ChatCategorizationDao {

    // ===== Spam =====

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND is_spam = 1
        ORDER BY latest_message_date DESC
    """)
    fun getSpamChats(): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND is_spam = 1")
    fun getSpamChatCount(): Flow<Int>

    @Query("UPDATE chats SET is_spam = :isSpam, spam_score = :score WHERE guid = :guid")
    suspend fun updateSpamStatus(guid: String, isSpam: Boolean, score: Int)

    @Query("UPDATE chats SET spam_reported_to_carrier = 1 WHERE guid = :guid")
    suspend fun markAsReportedToCarrier(guid: String)

    @Query("UPDATE chats SET is_spam = 0, spam_score = 0 WHERE guid = :guid")
    suspend fun clearSpamStatus(guid: String)

    // ===== Message Categorization =====

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND category = :category
        ORDER BY latest_message_date DESC
    """)
    fun getChatsByCategory(category: String): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats WHERE date_deleted IS NULL AND category = :category")
    fun getChatCountByCategory(category: String): Flow<Int>

    @Query("""
        SELECT * FROM chats
        WHERE date_deleted IS NULL AND category IS NULL
        ORDER BY latest_message_date DESC
    """)
    suspend fun getUncategorizedChats(): List<ChatEntity>

    @Query("""
        UPDATE chats
        SET category = :category,
            category_confidence = :confidence,
            category_last_updated = :timestamp
        WHERE guid = :guid
    """)
    suspend fun updateCategory(guid: String, category: String?, confidence: Int, timestamp: Long)

    @Query("UPDATE chats SET category = NULL, category_confidence = 0, category_last_updated = NULL WHERE guid = :guid")
    suspend fun clearCategory(guid: String)
}
