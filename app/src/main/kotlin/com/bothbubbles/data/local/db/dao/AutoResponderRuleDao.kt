package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bothbubbles.core.model.entity.AutoResponderRuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for auto-responder rules.
 *
 * Rules are evaluated in priority order - lower priority number means higher precedence.
 * The first matching rule is used to send the auto-response.
 */
@Dao
interface AutoResponderRuleDao {

    /**
     * Get all rules ordered by priority (for rule list UI).
     */
    @Query("SELECT * FROM auto_responder_rules ORDER BY priority ASC")
    fun observeAllRulesByPriority(): Flow<List<AutoResponderRuleEntity>>

    /**
     * Get enabled rules ordered by priority (for rule engine evaluation).
     */
    @Query("SELECT * FROM auto_responder_rules WHERE is_enabled = 1 ORDER BY priority ASC")
    suspend fun getEnabledRulesByPriority(): List<AutoResponderRuleEntity>

    /**
     * Get a single rule by ID.
     */
    @Query("SELECT * FROM auto_responder_rules WHERE id = :id")
    suspend fun getById(id: Long): AutoResponderRuleEntity?

    /**
     * Get a single rule by ID as Flow (for editor screen).
     */
    @Query("SELECT * FROM auto_responder_rules WHERE id = :id")
    fun observeById(id: Long): Flow<AutoResponderRuleEntity?>

    /**
     * Insert a new rule. Returns the inserted row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: AutoResponderRuleEntity): Long

    /**
     * Update an existing rule.
     */
    @Update
    suspend fun update(rule: AutoResponderRuleEntity)

    /**
     * Delete a rule.
     */
    @Delete
    suspend fun delete(rule: AutoResponderRuleEntity)

    /**
     * Delete a rule by ID.
     */
    @Query("DELETE FROM auto_responder_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Get the count of all rules.
     */
    @Query("SELECT COUNT(*) FROM auto_responder_rules")
    suspend fun getCount(): Int

    /**
     * Get the highest priority value currently in use.
     * New rules should use this + 1 as their priority.
     */
    @Query("SELECT MAX(priority) FROM auto_responder_rules")
    suspend fun getMaxPriority(): Int?

    /**
     * Update the enabled state of a rule.
     */
    @Query("UPDATE auto_responder_rules SET is_enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    /**
     * Swap priorities between two rules (for drag-and-drop reordering).
     */
    @Query("UPDATE auto_responder_rules SET priority = :newPriority, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePriority(id: Long, newPriority: Int, updatedAt: Long = System.currentTimeMillis())
}
