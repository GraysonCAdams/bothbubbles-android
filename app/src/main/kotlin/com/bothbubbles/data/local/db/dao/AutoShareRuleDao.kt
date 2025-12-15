package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.AutoShareRecipientEntity
import com.bothbubbles.data.local.db.entity.AutoShareRuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * POJO for loading a rule with its recipients using Room's @Relation.
 */
data class AutoShareRuleWithRecipients(
    @Embedded
    val rule: AutoShareRuleEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "rule_id"
    )
    val recipients: List<AutoShareRecipientEntity>
)

@Dao
interface AutoShareRuleDao {

    // ===== Rules Queries =====

    /**
     * Observe all rules with their recipients.
     */
    @Transaction
    @Query("SELECT * FROM auto_share_rules ORDER BY created_at DESC")
    fun observeAllRules(): Flow<List<AutoShareRuleWithRecipients>>

    /**
     * Get all enabled rules with their recipients (for matching).
     */
    @Transaction
    @Query("SELECT * FROM auto_share_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<AutoShareRuleWithRecipients>

    /**
     * Get a rule by ID with recipients.
     */
    @Transaction
    @Query("SELECT * FROM auto_share_rules WHERE id = :ruleId")
    suspend fun getRuleById(ruleId: Long): AutoShareRuleWithRecipients?

    /**
     * Get a rule by ID (without recipients).
     */
    @Query("SELECT * FROM auto_share_rules WHERE id = :ruleId")
    suspend fun getRuleEntityById(ruleId: Long): AutoShareRuleEntity?

    /**
     * Check if a destination name already exists.
     */
    @Query("SELECT COUNT(*) FROM auto_share_rules WHERE LOWER(destination_name) = LOWER(:name)")
    suspend fun countByDestinationName(name: String): Int

    // ===== Rules Mutations =====

    /**
     * Insert a new rule.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AutoShareRuleEntity): Long

    /**
     * Update an existing rule.
     */
    @Update
    suspend fun updateRule(rule: AutoShareRuleEntity)

    /**
     * Update rule enabled status.
     */
    @Query("UPDATE auto_share_rules SET enabled = :enabled WHERE id = :ruleId")
    suspend fun updateRuleEnabled(ruleId: Long, enabled: Boolean)

    /**
     * Update rule trigger tracking (for rate limiting and privacy reminders).
     */
    @Query("""
        UPDATE auto_share_rules
        SET last_triggered_at = :timestamp,
            last_trigger_date = :dateInt,
            consecutive_trigger_days = CASE
                WHEN last_trigger_date = :yesterdayInt THEN consecutive_trigger_days + 1
                WHEN last_trigger_date = :dateInt THEN consecutive_trigger_days
                ELSE 1
            END
        WHERE id = :ruleId
    """)
    suspend fun updateTriggerTracking(
        ruleId: Long,
        timestamp: Long,
        dateInt: Int,
        yesterdayInt: Int
    )

    /**
     * Reset consecutive trigger days.
     */
    @Query("UPDATE auto_share_rules SET consecutive_trigger_days = 0 WHERE id = :ruleId")
    suspend fun resetConsecutiveDays(ruleId: Long)

    /**
     * Delete a rule (recipients are cascade deleted).
     */
    @Query("DELETE FROM auto_share_rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Delete
    suspend fun deleteRuleEntity(rule: AutoShareRuleEntity)

    // ===== Recipients Queries =====

    /**
     * Get all recipients for a rule.
     */
    @Query("SELECT * FROM auto_share_recipients WHERE rule_id = :ruleId")
    suspend fun getRecipients(ruleId: Long): List<AutoShareRecipientEntity>

    /**
     * Check if a chat is already a recipient for a rule.
     */
    @Query("SELECT COUNT(*) FROM auto_share_recipients WHERE rule_id = :ruleId AND chat_guid = :chatGuid")
    suspend fun isRecipient(ruleId: Long, chatGuid: String): Int

    // ===== Recipients Mutations =====

    /**
     * Insert a recipient.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipient(recipient: AutoShareRecipientEntity): Long

    /**
     * Insert multiple recipients.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipients(recipients: List<AutoShareRecipientEntity>)

    /**
     * Delete a recipient.
     */
    @Query("DELETE FROM auto_share_recipients WHERE rule_id = :ruleId AND chat_guid = :chatGuid")
    suspend fun deleteRecipient(ruleId: Long, chatGuid: String)

    /**
     * Delete all recipients for a rule.
     */
    @Query("DELETE FROM auto_share_recipients WHERE rule_id = :ruleId")
    suspend fun deleteAllRecipients(ruleId: Long)

    /**
     * Replace all recipients for a rule.
     */
    @Transaction
    suspend fun replaceRecipients(ruleId: Long, recipients: List<AutoShareRecipientEntity>) {
        deleteAllRecipients(ruleId)
        insertRecipients(recipients)
    }
}
