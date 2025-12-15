package com.bothbubbles.data.repository

import android.util.Log
import com.bothbubbles.data.local.db.dao.AutoShareRuleDao
import com.bothbubbles.data.local.db.dao.AutoShareRuleWithRecipients
import com.bothbubbles.data.local.db.entity.AutoShareRecipientEntity
import com.bothbubbles.data.local.db.entity.AutoShareRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain model for an auto-share rule with recipients.
 */
data class AutoShareRule(
    val id: Long,
    val destinationName: String,
    val keywords: List<String>,
    val locationType: LocationType,
    val recipients: List<AutoShareRecipient>,
    val enabled: Boolean,
    val lastTriggeredAt: Long?,
    val consecutiveTriggerDays: Int
)

data class AutoShareRecipient(
    val chatGuid: String,
    val displayName: String
)

enum class LocationType {
    HOME,
    WORK,
    SCHOOL,
    GYM,
    CUSTOM;

    companion object {
        fun fromString(value: String): LocationType = when (value.lowercase()) {
            "home" -> HOME
            "work" -> WORK
            "school" -> SCHOOL
            "gym" -> GYM
            else -> CUSTOM
        }
    }

    fun toDbValue(): String = name.lowercase()
}

/**
 * Repository for managing ETA auto-share rules.
 *
 * Handles:
 * - CRUD operations for rules and recipients
 * - Destination matching for auto-triggering
 * - Rate limiting and privacy tracking
 */
@Singleton
class AutoShareRuleRepository @Inject constructor(
    private val autoShareRuleDao: AutoShareRuleDao
) {
    companion object {
        private const val TAG = "AutoShareRuleRepo"
        private const val MAX_RECIPIENTS_PER_RULE = 5
        private const val RATE_LIMIT_MS = 5 * 60 * 1000L // 5 minutes between triggers
    }

    // ===== Observation =====

    /**
     * Observe all rules with their recipients.
     */
    fun observeAllRules(): Flow<List<AutoShareRule>> =
        autoShareRuleDao.observeAllRules().map { rules ->
            rules.map { it.toDomainModel() }
        }

    // ===== Getters =====

    /**
     * Get all enabled rules for matching.
     */
    suspend fun getEnabledRules(): List<AutoShareRule> =
        autoShareRuleDao.getEnabledRules().map { it.toDomainModel() }

    /**
     * Get a rule by ID.
     */
    suspend fun getRuleById(ruleId: Long): AutoShareRule? =
        autoShareRuleDao.getRuleById(ruleId)?.toDomainModel()

    /**
     * Find a matching rule for a navigation destination.
     * Uses normalized keyword matching.
     *
     * @return The matching rule, or null if no match and not rate-limited
     */
    suspend fun findMatchingRule(destination: String): AutoShareRule? {
        val normalizedDest = normalizeDestination(destination)
        val enabledRules = getEnabledRules()

        for (rule in enabledRules) {
            // Check rate limiting
            if (isRateLimited(rule)) {
                Log.d(TAG, "Rule ${rule.id} is rate-limited, skipping")
                continue
            }

            // Check keyword matching
            for (keyword in rule.keywords) {
                val normalizedKeyword = normalizeDestination(keyword)
                if (matchesKeyword(normalizedDest, normalizedKeyword)) {
                    Log.d(TAG, "Matched rule ${rule.destinationName} with keyword: $keyword")
                    return rule
                }
            }
        }

        return null
    }

    private fun isRateLimited(rule: AutoShareRule): Boolean {
        val lastTriggered = rule.lastTriggeredAt ?: return false
        return System.currentTimeMillis() - lastTriggered < RATE_LIMIT_MS
    }

    /**
     * Check if keyword matches destination using flexible matching.
     */
    private fun matchesKeyword(normalizedDest: String, normalizedKeyword: String): Boolean {
        // Exact match
        if (normalizedDest == normalizedKeyword) return true

        // Contains match (keyword found in destination)
        if (normalizedDest.contains(normalizedKeyword)) return true

        // Word-based similarity (60% overlap)
        val destWords = normalizedDest.split(" ").filter { it.isNotBlank() }.toSet()
        val keywordWords = normalizedKeyword.split(" ").filter { it.isNotBlank() }.toSet()

        if (keywordWords.isEmpty()) return false

        val intersection = destWords.intersect(keywordWords)
        val similarity = intersection.size.toFloat() / keywordWords.size
        return similarity >= 0.6f
    }

    /**
     * Normalize destination string for matching.
     */
    private fun normalizeDestination(dest: String): String {
        return dest.lowercase()
            .replace("street", "st")
            .replace("avenue", "ave")
            .replace("boulevard", "blvd")
            .replace("drive", "dr")
            .replace("road", "rd")
            .replace("lane", "ln")
            .replace("court", "ct")
            .replace("place", "pl")
            .replace(Regex("[.,#]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ===== CRUD =====

    /**
     * Create a new rule with recipients.
     *
     * @return The ID of the created rule, or null if validation failed
     */
    suspend fun createRule(
        destinationName: String,
        keywords: List<String>,
        locationType: LocationType,
        recipients: List<AutoShareRecipient>
    ): Long? {
        // Validation
        if (destinationName.isBlank()) {
            Log.w(TAG, "Cannot create rule: destination name is blank")
            return null
        }

        if (keywords.isEmpty() || keywords.all { it.isBlank() }) {
            Log.w(TAG, "Cannot create rule: no keywords provided")
            return null
        }

        if (recipients.isEmpty()) {
            Log.w(TAG, "Cannot create rule: no recipients provided")
            return null
        }

        if (recipients.size > MAX_RECIPIENTS_PER_RULE) {
            Log.w(TAG, "Cannot create rule: too many recipients (max $MAX_RECIPIENTS_PER_RULE)")
            return null
        }

        // Check for duplicate destination name
        if (autoShareRuleDao.countByDestinationName(destinationName) > 0) {
            Log.w(TAG, "Cannot create rule: destination '$destinationName' already exists")
            return null
        }

        // Create rule
        val ruleEntity = AutoShareRuleEntity(
            destinationName = destinationName.trim(),
            keywords = keywords.filter { it.isNotBlank() }.joinToString(",") { it.trim() },
            locationType = locationType.toDbValue()
        )

        val ruleId = autoShareRuleDao.insertRule(ruleEntity)

        // Create recipients
        val recipientEntities = recipients.map { recipient ->
            AutoShareRecipientEntity(
                ruleId = ruleId,
                chatGuid = recipient.chatGuid,
                displayName = recipient.displayName
            )
        }
        autoShareRuleDao.insertRecipients(recipientEntities)

        Log.d(TAG, "Created rule $ruleId for '$destinationName' with ${recipients.size} recipients")
        return ruleId
    }

    /**
     * Update an existing rule.
     */
    suspend fun updateRule(
        ruleId: Long,
        destinationName: String,
        keywords: List<String>,
        locationType: LocationType,
        recipients: List<AutoShareRecipient>
    ): Boolean {
        val existingRule = autoShareRuleDao.getRuleEntityById(ruleId) ?: return false

        // Update rule entity
        val updatedRule = existingRule.copy(
            destinationName = destinationName.trim(),
            keywords = keywords.filter { it.isNotBlank() }.joinToString(",") { it.trim() },
            locationType = locationType.toDbValue()
        )
        autoShareRuleDao.updateRule(updatedRule)

        // Replace recipients
        val recipientEntities = recipients.map { recipient ->
            AutoShareRecipientEntity(
                ruleId = ruleId,
                chatGuid = recipient.chatGuid,
                displayName = recipient.displayName
            )
        }
        autoShareRuleDao.replaceRecipients(ruleId, recipientEntities)

        Log.d(TAG, "Updated rule $ruleId")
        return true
    }

    /**
     * Toggle rule enabled status.
     */
    suspend fun setRuleEnabled(ruleId: Long, enabled: Boolean) {
        autoShareRuleDao.updateRuleEnabled(ruleId, enabled)
    }

    /**
     * Delete a rule (recipients are cascade deleted).
     */
    suspend fun deleteRule(ruleId: Long) {
        autoShareRuleDao.deleteRule(ruleId)
        Log.d(TAG, "Deleted rule $ruleId")
    }

    // ===== Trigger Tracking =====

    /**
     * Record that a rule was triggered.
     * Updates timestamp and consecutive day tracking for privacy reminders.
     */
    suspend fun recordRuleTrigger(ruleId: Long) {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val today = dateFormat.format(Date()).toInt()
        val yesterday = dateFormat.format(Date(System.currentTimeMillis() - 86400000)).toInt()

        autoShareRuleDao.updateTriggerTracking(
            ruleId = ruleId,
            timestamp = System.currentTimeMillis(),
            dateInt = today,
            yesterdayInt = yesterday
        )
    }

    /**
     * Check if we should show a privacy reminder for this rule.
     * Returns true if rule has been triggered 5+ consecutive days.
     */
    suspend fun shouldShowPrivacyReminder(ruleId: Long): Boolean {
        val rule = getRuleById(ruleId) ?: return false
        return rule.consecutiveTriggerDays >= 5
    }

    /**
     * Reset the consecutive trigger day counter.
     */
    suspend fun resetPrivacyReminder(ruleId: Long) {
        autoShareRuleDao.resetConsecutiveDays(ruleId)
    }

    // ===== Mapping =====

    private fun AutoShareRuleWithRecipients.toDomainModel(): AutoShareRule {
        return AutoShareRule(
            id = rule.id,
            destinationName = rule.destinationName,
            keywords = rule.keywords.split(",").map { it.trim() }.filter { it.isNotBlank() },
            locationType = LocationType.fromString(rule.locationType),
            recipients = recipients.map { recipient ->
                AutoShareRecipient(
                    chatGuid = recipient.chatGuid,
                    displayName = recipient.displayName
                )
            },
            enabled = rule.enabled,
            lastTriggeredAt = rule.lastTriggeredAt,
            consecutiveTriggerDays = rule.consecutiveTriggerDays
        )
    }
}
