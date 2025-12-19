package com.bothbubbles.services.life360

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Rate limiter for Life360 API calls.
 *
 * Life360 has aggressive rate limiting:
 * - Circles endpoint is heavily rate-limited (may need 10+ min delays)
 * - Returns 429 or 403 when rate limited
 * - Official app updates location every ~5 seconds per member
 *
 * This rate limiter:
 * - Tracks per-endpoint request times
 * - Respects Retry-After headers (+10s buffer)
 * - Provides backoff delays for failed requests
 */
@Singleton
class Life360RateLimiter @Inject constructor() {

    private val mutex = Mutex()
    private val endpointTimestamps = mutableMapOf<Endpoint, Long>()
    private var rateLimitedUntil: Long = 0

    /**
     * Check if an endpoint can be called now.
     *
     * @return Time in milliseconds until the endpoint can be called (0 if allowed now)
     */
    suspend fun getDelayUntilAllowed(endpoint: Endpoint): Long = mutex.withLock {
        val now = System.currentTimeMillis()

        // Check if globally rate limited
        if (now < rateLimitedUntil) {
            val delay = rateLimitedUntil - now
            Timber.d("Life360 rate limiter: $endpoint globally rate limited, waiting ${delay}ms")
            return delay
        }

        // Check endpoint-specific delay
        val lastCall = endpointTimestamps[endpoint] ?: 0
        val minInterval = endpoint.minIntervalMs
        val nextAllowed = lastCall + minInterval
        val delay = max(0, nextAllowed - now)

        if (delay > 0) {
            Timber.d("Life360 rate limiter: $endpoint needs ${delay}ms delay (min interval: ${minInterval}ms)")
        }

        return delay
    }

    /**
     * Record that an endpoint was successfully called.
     */
    suspend fun recordRequest(endpoint: Endpoint) = mutex.withLock {
        endpointTimestamps[endpoint] = System.currentTimeMillis()
    }

    /**
     * Record a rate limit response (HTTP 429 or 403).
     *
     * @param retryAfterSeconds Retry-After header value in seconds
     */
    suspend fun recordRateLimit(retryAfterSeconds: Long?) = mutex.withLock {
        // Add 10 second buffer to Retry-After as per Life360 API behavior
        val delayMs = ((retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS) + RETRY_BUFFER_SECONDS) * 1000
        rateLimitedUntil = System.currentTimeMillis() + delayMs
        Timber.w("Life360 rate limited, retry after ${delayMs / 1000}s")
    }

    /**
     * Clear rate limit state (e.g., after successful request).
     */
    suspend fun clearRateLimit() = mutex.withLock {
        rateLimitedUntil = 0
    }

    /**
     * Whether currently rate limited.
     */
    suspend fun isRateLimited(): Boolean = mutex.withLock {
        System.currentTimeMillis() < rateLimitedUntil
    }

    /**
     * API endpoints with their minimum intervals.
     */
    enum class Endpoint(val minIntervalMs: Long) {
        /** Get circles list - heavily rate limited */
        CIRCLES(600_000),       // 10 minutes

        /** Get members in a circle */
        MEMBERS(30_000),        // 30 seconds

        /** Get individual member update */
        MEMBER(10_000),         // 10 seconds

        /** Request location update - conservative to avoid bans */
        REQUEST_LOCATION(300_000) // 5 minutes
    }

    companion object {
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60L
        private const val RETRY_BUFFER_SECONDS = 10L
    }
}
