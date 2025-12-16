package com.bothbubbles.core.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Caches iMessage availability check results for contacts.
 *
 * ## Cache Strategy
 * - AVAILABLE/NOT_AVAILABLE: Cached for 24 hours, re-checked on app restart for active chat
 * - UNREACHABLE: Never expires, re-checked when server reconnects
 * - ERROR: Cached for 5 minutes to allow retry
 *
 * ## Address Normalization
 * Addresses are stored in normalized format (E.164 for phones, lowercase for emails)
 * to ensure cache hits across different input formats.
 */
@Entity(
    tableName = "imessage_availability_cache",
    indices = [
        Index(value = ["check_result"]),
        Index(value = ["expires_at"])
    ]
)
data class IMessageAvailabilityCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "normalized_address")
    val normalizedAddress: String,

    /**
     * Result of the iMessage availability check.
     * - AVAILABLE: Contact supports iMessage
     * - NOT_AVAILABLE: Contact does not support iMessage (SMS only)
     * - UNREACHABLE: Server was disconnected during check
     * - ERROR: API error occurred during check
     */
    @ColumnInfo(name = "check_result")
    val checkResult: String,

    /**
     * Timestamp when the check was performed.
     */
    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,

    /**
     * Timestamp when this cache entry expires.
     * - 0 for UNREACHABLE (never expires, re-checked on reconnect)
     * - checkedAt + 24h for AVAILABLE/NOT_AVAILABLE
     * - checkedAt + 5min for ERROR
     */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,

    /**
     * App session ID when this cache entry was created.
     * Used to detect if cache is from a previous app session.
     */
    @ColumnInfo(name = "session_id")
    val sessionId: String
) {
    /**
     * Whether iMessage is available for this contact.
     */
    val isIMessageAvailable: Boolean
        get() = checkResult == CheckResult.AVAILABLE.name

    /**
     * Whether this cache entry has expired.
     */
    fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
        // UNREACHABLE never expires based on time (only on server reconnect)
        if (expiresAt == 0L) return false
        return currentTime >= expiresAt
    }

    /**
     * Whether this entry needs re-check due to server reconnection.
     */
    val needsRecheckOnReconnect: Boolean
        get() = checkResult == CheckResult.UNREACHABLE.name

    companion object {
        const val TTL_AVAILABLE_MS = 24 * 60 * 60 * 1000L    // 24 hours
        const val TTL_NOT_AVAILABLE_MS = 24 * 60 * 60 * 1000L // 24 hours
        const val TTL_ERROR_MS = 5 * 60 * 1000L              // 5 minutes

        fun createAvailable(
            normalizedAddress: String,
            sessionId: String,
            checkedAt: Long = System.currentTimeMillis()
        ) = IMessageAvailabilityCacheEntity(
            normalizedAddress = normalizedAddress,
            checkResult = CheckResult.AVAILABLE.name,
            checkedAt = checkedAt,
            expiresAt = checkedAt + TTL_AVAILABLE_MS,
            sessionId = sessionId
        )

        fun createNotAvailable(
            normalizedAddress: String,
            sessionId: String,
            checkedAt: Long = System.currentTimeMillis()
        ) = IMessageAvailabilityCacheEntity(
            normalizedAddress = normalizedAddress,
            checkResult = CheckResult.NOT_AVAILABLE.name,
            checkedAt = checkedAt,
            expiresAt = checkedAt + TTL_NOT_AVAILABLE_MS,
            sessionId = sessionId
        )

        fun createUnreachable(
            normalizedAddress: String,
            sessionId: String,
            checkedAt: Long = System.currentTimeMillis()
        ) = IMessageAvailabilityCacheEntity(
            normalizedAddress = normalizedAddress,
            checkResult = CheckResult.UNREACHABLE.name,
            checkedAt = checkedAt,
            expiresAt = 0, // Never expires based on time
            sessionId = sessionId
        )

        fun createError(
            normalizedAddress: String,
            sessionId: String,
            checkedAt: Long = System.currentTimeMillis()
        ) = IMessageAvailabilityCacheEntity(
            normalizedAddress = normalizedAddress,
            checkResult = CheckResult.ERROR.name,
            checkedAt = checkedAt,
            expiresAt = checkedAt + TTL_ERROR_MS,
            sessionId = sessionId
        )
    }

    enum class CheckResult {
        AVAILABLE,
        NOT_AVAILABLE,
        UNREACHABLE,
        ERROR
    }
}
