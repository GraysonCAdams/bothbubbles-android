package com.bothbubbles.data.repository

import com.bothbubbles.data.local.db.dao.QuickReplyTemplateDao
import com.bothbubbles.data.local.db.entity.QuickReplyTemplateEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing quick reply templates.
 *
 * Handles:
 * - CRUD operations for templates
 * - Usage tracking for "most used" sorting
 * - Providing templates for notification quick replies
 */
@Singleton
class QuickReplyTemplateRepository @Inject constructor(
    private val quickReplyTemplateDao: QuickReplyTemplateDao
) {

    // ===== Observation =====

    /**
     * Observe all templates, sorted by favorites then usage count.
     */
    fun observeAllTemplates(): Flow<List<QuickReplyTemplateEntity>> =
        quickReplyTemplateDao.observeAllTemplates()

    /**
     * Observe the most-used templates for suggestion chips.
     */
    fun observeMostUsedTemplates(limit: Int = 3): Flow<List<QuickReplyTemplateEntity>> =
        quickReplyTemplateDao.observeMostUsedTemplates(limit)

    /**
     * Search templates by title or text.
     */
    fun searchTemplates(query: String): Flow<List<QuickReplyTemplateEntity>> =
        quickReplyTemplateDao.searchTemplates(query)

    // ===== Getters =====

    /**
     * Get templates for notification quick reply chips.
     * Returns title strings for use with RemoteInput.setChoices().
     */
    suspend fun getNotificationChoices(maxCount: Int = 3): Array<CharSequence> {
        return quickReplyTemplateDao.getMostUsedTemplates(maxCount)
            .map { it.title as CharSequence }
            .toTypedArray()
    }

    /**
     * Get the most-used templates (non-reactive).
     */
    suspend fun getMostUsedTemplates(limit: Int = 3): List<QuickReplyTemplateEntity> =
        quickReplyTemplateDao.getMostUsedTemplates(limit)

    /**
     * Get a template by ID.
     */
    suspend fun getById(id: Long): QuickReplyTemplateEntity? =
        quickReplyTemplateDao.getById(id)

    /**
     * Get the template text for a given title (for notification replies).
     */
    suspend fun getTextByTitle(title: String): String? {
        return quickReplyTemplateDao.getAllTemplates()
            .firstOrNull { it.title == title }
            ?.text
    }

    /**
     * Get template count.
     */
    suspend fun getTemplateCount(): Int =
        quickReplyTemplateDao.getTemplateCount()

    // ===== CRUD =====

    /**
     * Create a new template.
     */
    suspend fun createTemplate(
        title: String,
        text: String = title // Default to title if text not provided
    ): Long {
        val template = QuickReplyTemplateEntity(
            title = title.trim(),
            text = text.trim()
        )
        return quickReplyTemplateDao.insert(template)
    }

    /**
     * Update an existing template.
     */
    suspend fun updateTemplate(template: QuickReplyTemplateEntity) =
        quickReplyTemplateDao.update(template)

    /**
     * Delete a template by ID.
     */
    suspend fun deleteTemplate(id: Long) =
        quickReplyTemplateDao.deleteById(id)

    /**
     * Delete all templates.
     */
    suspend fun deleteAllTemplates() =
        quickReplyTemplateDao.deleteAll()

    // ===== Usage Tracking =====

    /**
     * Record that a template was used.
     * Increments usage count and updates last used timestamp.
     */
    suspend fun recordUsage(id: Long) =
        quickReplyTemplateDao.incrementUsage(id)

    /**
     * Toggle favorite status for a template.
     */
    suspend fun toggleFavorite(id: Long) {
        val template = quickReplyTemplateDao.getById(id) ?: return
        quickReplyTemplateDao.updateFavoriteStatus(id, !template.isFavorite)
    }

    /**
     * Set favorite status for a template.
     */
    suspend fun setFavorite(id: Long, isFavorite: Boolean) =
        quickReplyTemplateDao.updateFavoriteStatus(id, isFavorite)
}
