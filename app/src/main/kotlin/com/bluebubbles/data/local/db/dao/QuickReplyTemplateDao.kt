package com.bluebubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bluebubbles.data.local.db.entity.QuickReplyTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuickReplyTemplateDao {

    // ===== Queries =====

    /**
     * Observe all templates, sorted by favorites first, then by usage count.
     */
    @Query("""
        SELECT * FROM quick_reply_templates
        ORDER BY is_favorite DESC, usage_count DESC, display_order ASC
    """)
    fun observeAllTemplates(): Flow<List<QuickReplyTemplateEntity>>

    /**
     * Get all templates (non-reactive).
     */
    @Query("""
        SELECT * FROM quick_reply_templates
        ORDER BY is_favorite DESC, usage_count DESC, display_order ASC
    """)
    suspend fun getAllTemplates(): List<QuickReplyTemplateEntity>

    /**
     * Get the most-used templates for display in suggestion chips.
     */
    @Query("""
        SELECT * FROM quick_reply_templates
        ORDER BY is_favorite DESC, usage_count DESC
        LIMIT :limit
    """)
    fun observeMostUsedTemplates(limit: Int = 3): Flow<List<QuickReplyTemplateEntity>>

    /**
     * Get templates for notification quick reply chips (blocking for use in NotificationService).
     */
    @Query("""
        SELECT * FROM quick_reply_templates
        ORDER BY is_favorite DESC, usage_count DESC
        LIMIT :limit
    """)
    suspend fun getMostUsedTemplates(limit: Int = 3): List<QuickReplyTemplateEntity>

    /**
     * Get a template by ID.
     */
    @Query("SELECT * FROM quick_reply_templates WHERE id = :id")
    suspend fun getById(id: Long): QuickReplyTemplateEntity?

    /**
     * Search templates by title or text.
     */
    @Query("""
        SELECT * FROM quick_reply_templates
        WHERE title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%'
        ORDER BY usage_count DESC
        LIMIT :limit
    """)
    fun searchTemplates(query: String, limit: Int = 20): Flow<List<QuickReplyTemplateEntity>>

    /**
     * Get the count of templates.
     */
    @Query("SELECT COUNT(*) FROM quick_reply_templates")
    suspend fun getTemplateCount(): Int

    // ===== Inserts/Updates =====

    /**
     * Insert a new template.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: QuickReplyTemplateEntity): Long

    /**
     * Insert multiple templates.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(templates: List<QuickReplyTemplateEntity>)

    /**
     * Update an existing template.
     */
    @Update
    suspend fun update(template: QuickReplyTemplateEntity)

    /**
     * Increment the usage count and update last used timestamp.
     */
    @Query("""
        UPDATE quick_reply_templates
        SET usage_count = usage_count + 1, last_used_at = :timestamp
        WHERE id = :id
    """)
    suspend fun incrementUsage(id: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * Update favorite status for a template.
     */
    @Query("UPDATE quick_reply_templates SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean)

    /**
     * Update the display order for a template (for manual reordering).
     */
    @Query("UPDATE quick_reply_templates SET display_order = :order WHERE id = :id")
    suspend fun updateDisplayOrder(id: Long, order: Int)

    // ===== Deletes =====

    /**
     * Delete a template.
     */
    @Delete
    suspend fun delete(template: QuickReplyTemplateEntity)

    /**
     * Delete a template by ID.
     */
    @Query("DELETE FROM quick_reply_templates WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete all templates.
     */
    @Query("DELETE FROM quick_reply_templates")
    suspend fun deleteAll()
}
