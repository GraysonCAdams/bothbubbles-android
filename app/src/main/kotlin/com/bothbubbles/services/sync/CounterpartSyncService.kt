package com.bothbubbles.services.sync

import android.util.Log
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.dao.VerifiedCounterpartCheckDao
import com.bothbubbles.data.local.db.entity.VerifiedCounterpartCheckEntity
import com.bothbubbles.data.repository.ChatSyncOperations
import com.bothbubbles.util.error.NetworkError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for "lazy repair" of unified chat groups (Tier 2 sync integrity).
 *
 * When a unified group has only one chat member (e.g., SMS only), this service
 * checks if the server has a counterpart chat (e.g., iMessage) and fetches it
 * if found. This repairs sync gaps where one service's chat was never synced.
 *
 * Key features:
 * - **Predictive**: Locally computes counterpart GUID (no discovery query needed)
 * - **Cached**: Records 404 results to avoid repeated checks for Android contacts
 * - **Lazy**: Only runs when user opens a potentially-incomplete conversation
 *
 * @see VerifiedCounterpartCheckEntity for caching strategy
 */
@Singleton
class CounterpartSyncService @Inject constructor(
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val verifiedCheckDao: VerifiedCounterpartCheckDao,
    private val chatSyncOperations: ChatSyncOperations
) {
    companion object {
        private const val TAG = "CounterpartSync"

        // Cache TTL: Re-check after 30 days (contact may have switched phones)
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

        // Service prefixes for GUID prediction
        private const val IMESSAGE_PREFIX = "iMessage;-;"
        private const val SMS_PREFIX = "sms;-;"
    }

    /**
     * Result of a counterpart check operation.
     */
    sealed class CheckResult {
        /** Counterpart was found and synced successfully */
        data class Found(val chatGuid: String) : CheckResult()

        /** No counterpart exists on server (e.g., Android contact) */
        data object NotFound : CheckResult()

        /** Already verified previously (cached result) */
        data class AlreadyVerified(val hasCounterpart: Boolean) : CheckResult()

        /** Check failed due to network error (don't cache) */
        data class Error(val message: String) : CheckResult()

        /** Skipped because group already has multiple members */
        data object Skipped : CheckResult()
    }

    /**
     * Check and repair a unified group's counterpart chat if missing.
     *
     * This is the main entry point for Tier 2 lazy repair. Call this when:
     * - User opens a conversation
     * - Unified group has only one chat member
     *
     * @param groupId The unified group ID to check
     * @return The result of the check operation
     */
    suspend fun checkAndRepairCounterpart(groupId: Long): CheckResult {
        // Get group details
        val group = unifiedChatGroupDao.getGroupById(groupId)
        if (group == null) {
            Log.w(TAG, "Group $groupId not found")
            return CheckResult.Error("Group not found")
        }

        // Get member chats for this group
        val memberGuids = unifiedChatGroupDao.getChatGuidsForGroup(groupId)
        if (memberGuids.size > 1) {
            // Already has both iMessage and SMS - nothing to repair
            Log.d(TAG, "Group $groupId already complete (${memberGuids.size} members)")
            return CheckResult.Skipped
        }

        if (memberGuids.isEmpty()) {
            Log.w(TAG, "Group $groupId has no members")
            return CheckResult.Error("Group has no members")
        }

        val existingChatGuid = memberGuids.first()
        val identifier = group.identifier

        // Check cache first
        val cachedCheck = verifiedCheckDao.get(identifier)
        if (cachedCheck != null && !isCacheExpired(cachedCheck)) {
            Log.d(TAG, "Using cached result for $identifier: hasCounterpart=${cachedCheck.hasCounterpart}")
            return CheckResult.AlreadyVerified(cachedCheck.hasCounterpart)
        }

        // Predict counterpart GUID
        val counterpartGuid = predictCounterpartGuid(existingChatGuid)
        if (counterpartGuid == null) {
            Log.d(TAG, "Cannot predict counterpart for $existingChatGuid")
            return CheckResult.Skipped
        }

        Log.d(TAG, "Checking for counterpart: $counterpartGuid (existing: $existingChatGuid)")

        // Try to fetch counterpart from server
        return try {
            val result = chatSyncOperations.fetchChat(counterpartGuid)
            result.fold(
                onSuccess = { chat ->
                    Log.i(TAG, "Found counterpart $counterpartGuid for group $groupId")

                    // Record positive verification
                    verifiedCheckDao.upsert(
                        VerifiedCounterpartCheckEntity(
                            normalizedAddress = identifier,
                            hasCounterpart = true
                        )
                    )

                    CheckResult.Found(chat.guid)
                },
                onFailure = { error ->
                    handleFetchError(error, identifier, counterpartGuid)
                }
            )
        } catch (e: Exception) {
            handleFetchError(e, identifier, counterpartGuid)
        }
    }

    /**
     * Check and repair counterpart for a unified group by its identifier.
     *
     * Convenience method when you have the identifier (phone number) instead of group ID.
     */
    suspend fun checkAndRepairCounterpartByIdentifier(identifier: String): CheckResult {
        val group = unifiedChatGroupDao.getGroupByIdentifier(identifier)
        if (group == null) {
            Log.d(TAG, "No unified group found for identifier $identifier")
            return CheckResult.Error("No group for identifier")
        }
        return checkAndRepairCounterpart(group.id)
    }

    /**
     * Predict the counterpart chat GUID based on the existing chat.
     *
     * For SMS chat `sms;-;+1234567890` → predicts `iMessage;-;+1234567890`
     * For iMessage chat `iMessage;-;+1234567890` → predicts `sms;-;+1234567890`
     */
    private fun predictCounterpartGuid(existingGuid: String): String? {
        return when {
            existingGuid.startsWith(SMS_PREFIX, ignoreCase = true) -> {
                val address = existingGuid.removePrefix(SMS_PREFIX)
                    .removePrefix("sms;-;") // Handle case variations
                "$IMESSAGE_PREFIX$address"
            }
            existingGuid.startsWith("mms;-;", ignoreCase = true) -> {
                val address = existingGuid.substringAfter(";-;")
                "$IMESSAGE_PREFIX$address"
            }
            existingGuid.startsWith(IMESSAGE_PREFIX, ignoreCase = true) -> {
                val address = existingGuid.removePrefix(IMESSAGE_PREFIX)
                    .removePrefix("iMessage;-;") // Handle case variations
                "$SMS_PREFIX$address"
            }
            else -> {
                Log.d(TAG, "Unknown chat GUID format: $existingGuid")
                null
            }
        }
    }

    /**
     * Handle errors from fetch attempt.
     */
    private suspend fun handleFetchError(
        error: Throwable,
        identifier: String,
        counterpartGuid: String
    ): CheckResult {
        return when (error) {
            is NetworkError.ServerError -> {
                if (error.statusCode == 404) {
                    // Chat doesn't exist on server - cache negative result
                    Log.d(TAG, "No counterpart $counterpartGuid exists (404)")
                    verifiedCheckDao.upsert(
                        VerifiedCounterpartCheckEntity(
                            normalizedAddress = identifier,
                            hasCounterpart = false
                        )
                    )
                    CheckResult.NotFound
                } else {
                    // Server error - don't cache, might be transient
                    Log.w(TAG, "Server error checking counterpart: ${error.statusCode}")
                    CheckResult.Error("Server error: ${error.statusCode}")
                }
            }
            else -> {
                // Network error - don't cache, will retry later
                Log.w(TAG, "Error checking counterpart $counterpartGuid: ${error.message}")
                CheckResult.Error(error.message ?: "Unknown error")
            }
        }
    }

    /**
     * Check if a cached verification has expired.
     */
    private fun isCacheExpired(check: VerifiedCounterpartCheckEntity): Boolean {
        val age = System.currentTimeMillis() - check.verifiedAt
        return age > CACHE_TTL_MS
    }

    /**
     * Invalidate cache for a specific address.
     * Use when user manually triggers a re-check.
     */
    suspend fun invalidateCache(identifier: String) {
        verifiedCheckDao.delete(identifier)
        Log.d(TAG, "Invalidated cache for $identifier")
    }

    /**
     * Clear all cached verifications.
     * Use for full sync reset.
     */
    suspend fun clearAllCache() {
        verifiedCheckDao.deleteAll()
        Log.d(TAG, "Cleared all counterpart verification cache")
    }

    /**
     * Clean up expired cache entries.
     * Can be called periodically (e.g., on app startup).
     */
    suspend fun cleanupExpiredCache() {
        val expiredBefore = System.currentTimeMillis() - CACHE_TTL_MS
        verifiedCheckDao.deleteOlderThan(expiredBefore)
        Log.d(TAG, "Cleaned up cache entries older than 30 days")
    }
}
