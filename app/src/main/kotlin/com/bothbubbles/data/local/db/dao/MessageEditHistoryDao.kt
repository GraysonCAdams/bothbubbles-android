package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bothbubbles.core.model.entity.MessageEditHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for storing and retrieving message edit history.
 * Used to track previous versions of edited messages for display in the UI.
 */
@Dao
interface MessageEditHistoryDao {

    /**
     * Insert a new edit history entry.
     * Called before updating a message with a new edit to preserve the old text.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MessageEditHistoryEntity)

    /**
     * Get all edit history entries for a message, ordered by edit time (newest first).
     * @return Flow of edit history entries for reactive UI updates
     */
    @Query("""
        SELECT * FROM message_edit_history
        WHERE message_guid = :messageGuid
        ORDER BY edited_at DESC
    """)
    fun getEditHistory(messageGuid: String): Flow<List<MessageEditHistoryEntity>>

    /**
     * Get all edit history entries for a message synchronously.
     * @return List of edit history entries
     */
    @Query("""
        SELECT * FROM message_edit_history
        WHERE message_guid = :messageGuid
        ORDER BY edited_at DESC
    """)
    suspend fun getEditHistorySync(messageGuid: String): List<MessageEditHistoryEntity>

    /**
     * Check if a message has any edit history.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM message_edit_history WHERE message_guid = :messageGuid)")
    suspend fun hasEditHistory(messageGuid: String): Boolean

    /**
     * Delete edit history for a specific message.
     * Called when a message is deleted (also handled by foreign key cascade).
     */
    @Query("DELETE FROM message_edit_history WHERE message_guid = :messageGuid")
    suspend fun deleteForMessage(messageGuid: String)

    /**
     * Clear all edit history (useful for reset scenarios).
     */
    @Query("DELETE FROM message_edit_history")
    suspend fun clear()

    /**
     * Get count of tracked edit history entries (for debugging).
     */
    @Query("SELECT COUNT(*) FROM message_edit_history")
    suspend fun count(): Int
}
