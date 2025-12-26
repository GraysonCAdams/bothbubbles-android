package com.bothbubbles.seam.hems.autoresponder

import com.bothbubbles.core.model.entity.AutoResponderRuleEntity
import com.bothbubbles.data.local.db.dao.AutoResponderRuleDao
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine for evaluating auto-responder rules against incoming messages.
 *
 * Rules are evaluated in priority order (lower priority number = higher precedence).
 * The first matching rule is returned - subsequent rules are not evaluated.
 *
 * This follows a "first match wins" strategy, allowing users to order rules
 * from most specific to most general.
 */
@Singleton
class AutoResponderRuleEngine @Inject constructor(
    private val ruleDao: AutoResponderRuleDao,
    private val conditionEvaluator: AutoResponderConditionEvaluator
) {
    companion object {
        private const val TAG = "AutoResponderRuleEngine"
    }

    /**
     * Find the first matching rule for the given message context.
     *
     * Rules are evaluated in priority order (lower = higher priority).
     * Returns the first rule where ALL conditions are satisfied.
     *
     * @param context Information about the incoming message
     * @return The matching rule, or null if no rules match
     */
    suspend fun findMatchingRule(context: MessageContext): AutoResponderRuleEntity? {
        val enabledRules = ruleDao.getEnabledRulesByPriority()

        if (enabledRules.isEmpty()) {
            Timber.d("$TAG: No enabled rules found")
            return null
        }

        Timber.d("$TAG: Evaluating ${enabledRules.size} enabled rules for sender=${context.senderAddress}, stitch=${context.stitchId}")

        for (rule in enabledRules) {
            if (conditionEvaluator.allConditionsMet(rule, context)) {
                Timber.i("$TAG: Rule '${rule.name}' (id=${rule.id}) matched")
                return rule
            }
        }

        Timber.d("$TAG: No rules matched")
        return null
    }

    /**
     * Check if any rule could potentially match (ignoring message-specific conditions).
     *
     * Useful for quick pre-check before doing expensive operations like
     * checking iMessage availability.
     *
     * @return true if at least one rule is enabled
     */
    suspend fun hasEnabledRules(): Boolean {
        return ruleDao.getCount() > 0
    }
}
