package com.bothbubbles.services.life360

import com.bothbubbles.core.model.Life360Circle
import com.bothbubbles.core.model.Life360Member

/**
 * Interface for Life360 operations.
 *
 * Implementations handle network calls, rate limiting, and data persistence.
 * Use this interface in consumers for testability.
 */
interface Life360Service {

    /**
     * Whether currently authenticated with Life360.
     */
    val isAuthenticated: Boolean

    /**
     * Store an access token (from WebView login or manual entry).
     */
    suspend fun storeToken(accessToken: String)

    /**
     * Logout and clear all Life360 data.
     */
    suspend fun logout()

    /**
     * Fetch circles from the API.
     *
     * @return List of circles or throws [Life360Error]
     */
    suspend fun fetchCircles(): Result<List<Life360Circle>>

    /**
     * Sync members for a specific circle.
     * Updates local database with fresh data from API.
     *
     * @return Number of members synced or throws [Life360Error]
     */
    suspend fun syncCircleMembers(circleId: String): Result<Int>

    /**
     * Sync all circles the user has access to.
     *
     * @return Total number of members synced
     */
    suspend fun syncAllCircles(): Result<Int>

    /**
     * Request an immediate location update for a member.
     * Use sparingly - triggers more frequent updates on target device.
     */
    suspend fun requestLocationUpdate(circleId: String, memberId: String): Result<Unit>

    /**
     * Get time until rate limit allows next circles API call (ms).
     * Returns 0 if allowed now.
     */
    suspend fun getCirclesRateLimitDelay(): Long

    /**
     * Get time until rate limit allows next members API call (ms).
     * Returns 0 if allowed now.
     */
    suspend fun getMembersRateLimitDelay(): Long
}
