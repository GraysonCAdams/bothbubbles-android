package com.bothbubbles.services.sync

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
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
    private val unifiedChatDao: UnifiedChatDao,
    private val chatDao: ChatDao,
    private val verifiedCheckDao: VerifiedCounterpartCheckDao,
    private val chatSyncOperations: ChatSyncOperations
) {
    companion object {
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
     * Check and repair a unified chat's counterpart chat if missing.
     *
     * This is the main entry point for Tier 2 lazy repair. Call this when:
     * - User opens a conversation
     * - Unified chat has only one linked chat
     *
     * @param unifiedChatId The unified chat ID to check
     * @return The result of the check operation
     */
    suspend fun checkAndRepairCounterpart(unifiedChatId: String): CheckResult {
        // Get unified chat details
        val unifiedChat = unifiedChatDao.getById(unifiedChatId)
        if (unifiedChat == null) {
            Timber.w("Unified chat $unifiedChatId not found")
            return CheckResult.Error("Unified chat not found")
        }

        // Get linked chats for this unified chat
        val linkedChatGuids = chatDao.getChatGuidsForUnifiedChat(unifiedChatId)
        if (linkedChatGuids.size > 1) {
            // Already has both iMessage and SMS - nothing to repair
            Timber.d("Unified chat $unifiedChatId already complete (${linkedChatGuids.size} linked chats)")
            return CheckResult.Skipped
        }

        if (linkedChatGuids.isEmpty()) {
            Timber.w("Unified chat $unifiedChatId has no linked chats")
            return CheckResult.Error("Unified chat has no linked chats")
        }

        val existingChatGuid = linkedChatGuids.first()
        val identifier = unifiedChat.normalizedAddress

        // Check cache first
        val cachedCheck = verifiedCheckDao.get(identifier)
        if (cachedCheck != null && !isCacheExpired(cachedCheck)) {
            Timber.d("Using cached result for $identifier: hasCounterpart=${cachedCheck.hasCounterpart}")
            return CheckResult.AlreadyVerified(cachedCheck.hasCounterpart)
        }

        // Predict counterpart GUID
        val counterpartGuid = predictCounterpartGuid(existingChatGuid)
        if (counterpartGuid == null) {
            Timber.d("Cannot predict counterpart for $existingChatGuid")
            return CheckResult.Skipped
        }

        Timber.d("Checking for counterpart: $counterpartGuid (existing: $existingChatGuid)")

        // Try to fetch counterpart from server
        return try {
            val result = chatSyncOperations.fetchChat(counterpartGuid)
            result.fold(
                onSuccess = { chat ->
                    Timber.i("Found counterpart $counterpartGuid for unified chat $unifiedChatId")

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
     * Check and repair counterpart for a unified chat by its identifier.
     *
     * Convenience method when you have the identifier (phone number) instead of unified chat ID.
     */
    suspend fun checkAndRepairCounterpartByIdentifier(identifier: String): CheckResult {
        val unifiedChat = unifiedChatDao.getByNormalizedAddress(identifier)
        if (unifiedChat == null) {
            Timber.d("No unified chat found for identifier $identifier")
            return CheckResult.Error("No unified chat for identifier")
        }
        return checkAndRepairCounterpart(unifiedChat.id)
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
                Timber.d("Unknown chat GUID format: $existingGuid")
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
                    Timber.d("No counterpart $counterpartGuid exists (404)")
                    verifiedCheckDao.upsert(
                        VerifiedCounterpartCheckEntity(
                            normalizedAddress = identifier,
                            hasCounterpart = false
                        )
                    )
                    CheckResult.NotFound
                } else {
                    // Server error - don't cache, might be transient
                    Timber.w("Server error checking counterpart: ${error.statusCode}")
                    CheckResult.Error("Server error: ${error.statusCode}")
                }
            }
            else -> {
                // Network error - don't cache, will retry later
                Timber.w("Error checking counterpart $counterpartGuid: ${error.message}")
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
        Timber.d("Invalidated cache for $identifier")
    }

    /**
     * Clear all cached verifications.
     * Use for full sync reset.
     */
    suspend fun clearAllCache() {
        verifiedCheckDao.deleteAll()
        Timber.d("Cleared all counterpart verification cache")
    }

    /**
     * Clean up expired cache entries.
     * Can be called periodically (e.g., on app startup).
     */
    suspend fun cleanupExpiredCache() {
        val expiredBefore = System.currentTimeMillis() - CACHE_TTL_MS
        verifiedCheckDao.deleteOlderThan(expiredBefore)
        Timber.d("Cleaned up cache entries older than 30 days")
    }
}
