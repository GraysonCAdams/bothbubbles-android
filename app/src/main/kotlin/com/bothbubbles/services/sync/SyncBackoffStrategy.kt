package com.bothbubbles.services.sync

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff strategy for sync operations.
 *
 * Prevents server hammering when network is flaky by implementing
 * exponential backoff on consecutive failures.
 *
 * Backoff formula: delay = min(INITIAL_DELAY * 2^(failures-1), MAX_DELAY)
 * - After 1 failure: 1 second
 * - After 2 failures: 2 seconds
 * - After 3 failures: 4 seconds
 * - After 4 failures: 8 seconds
 * - After 5+ failures: caps at MAX_DELAY (5 minutes)
 *
 * Also implements debouncing to prevent rapid sync attempts within
 * MIN_SYNC_INTERVAL of each other.
 */
@Singleton
class SyncBackoffStrategy @Inject constructor() {

    companion object {
        private const val TAG = "SyncBackoffStrategy"

        /** Initial backoff delay in milliseconds (1 second) */
        private const val INITIAL_DELAY_MS = 1000L

        /** Maximum backoff delay in milliseconds (5 minutes) */
        private const val MAX_DELAY_MS = 300_000L

        /** Minimum interval between sync attempts regardless of success (5 seconds) */
        private const val MIN_SYNC_INTERVAL_MS = 5000L

        /** Backoff multiplier (exponential base) */
        private const val MULTIPLIER = 2.0
    }

    /** Number of consecutive sync failures */
    private val consecutiveFailures = AtomicInteger(0)

    /** Timestamp of last sync attempt */
    private val lastAttemptTime = AtomicLong(0)

    /** Timestamp of last successful sync */
    private val lastSuccessTime = AtomicLong(0)

    /**
     * Check if a sync attempt should be allowed based on backoff and debouncing.
     *
     * @return true if sync should proceed, false if it should be skipped
     */
    fun shouldAttemptSync(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastAttempt = now - lastAttemptTime.get()
        val requiredDelay = getNextDelay()

        // Check debounce interval
        if (timeSinceLastAttempt < MIN_SYNC_INTERVAL_MS) {
            Log.d(TAG, "Sync debounced - only ${timeSinceLastAttempt}ms since last attempt")
            return false
        }

        // Check backoff delay
        if (timeSinceLastAttempt < requiredDelay) {
            Log.d(TAG, "Sync backoff active - need ${requiredDelay}ms, only ${timeSinceLastAttempt}ms elapsed")
            return false
        }

        return true
    }

    /**
     * Mark the start of a sync attempt.
     * Call this before starting a sync operation.
     */
    fun markAttemptStarted() {
        lastAttemptTime.set(System.currentTimeMillis())
    }

    /**
     * Record a successful sync.
     * Resets the failure counter and updates success timestamp.
     */
    fun recordSuccess() {
        val previousFailures = consecutiveFailures.getAndSet(0)
        lastSuccessTime.set(System.currentTimeMillis())
        if (previousFailures > 0) {
            Log.d(TAG, "Sync succeeded after $previousFailures consecutive failures, backoff reset")
        }
    }

    /**
     * Record a failed sync.
     * Increments the failure counter for exponential backoff.
     */
    fun recordFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        val nextDelay = getNextDelay()
        Log.w(TAG, "Sync failed ($failures consecutive), next attempt delayed by ${nextDelay}ms")
    }

    /**
     * Get the next backoff delay based on consecutive failures.
     *
     * @return Delay in milliseconds (0 if no failures)
     */
    fun getNextDelay(): Long {
        val failures = consecutiveFailures.get()
        if (failures == 0) return 0

        val delay = (INITIAL_DELAY_MS * MULTIPLIER.pow((failures - 1).toDouble())).toLong()
        return min(delay, MAX_DELAY_MS)
    }

    /**
     * Get the current number of consecutive failures.
     */
    fun getConsecutiveFailures(): Int = consecutiveFailures.get()

    /**
     * Get the time since last successful sync.
     *
     * @return Milliseconds since last success, or -1 if no successful sync yet
     */
    fun getTimeSinceLastSuccess(): Long {
        val lastSuccess = lastSuccessTime.get()
        return if (lastSuccess > 0) {
            System.currentTimeMillis() - lastSuccess
        } else {
            -1
        }
    }

    /**
     * Reset all backoff state.
     * Call this when user explicitly triggers a sync or when app restarts.
     */
    fun reset() {
        consecutiveFailures.set(0)
        lastAttemptTime.set(0)
        Log.d(TAG, "Backoff strategy reset")
    }
}
