package com.bothbubbles.core.network.api

import com.bothbubbles.core.network.api.dto.Life360CirclesResponse
import com.bothbubbles.core.network.api.dto.Life360MemberDto
import com.bothbubbles.core.network.api.dto.Life360MembersResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit interface for Life360 API.
 *
 * All endpoints require Authorization header in format: "Bearer {access_token}"
 *
 * Rate limits:
 * - Circles endpoint is heavily rate-limited (may get 429/403, need 10+ min delays)
 * - Member updates every ~5 seconds in official app
 * - Add 10 second buffer to Retry-After header values
 *
 * @see com.bothbubbles.services.life360.Life360RateLimiter
 */
interface Life360Api {

    companion object {
        const val BASE_URL = "https://api-cloudfront.life360.com/"
    }

    /**
     * Get all circles visible to authenticated user.
     *
     * Note: This endpoint is heavily rate-limited. Cache results and avoid frequent calls.
     */
    @GET("v3/circles")
    suspend fun getCircles(
        @Header("Authorization") authorization: String
    ): Response<Life360CirclesResponse>

    /**
     * Get all members in a specific circle.
     */
    @GET("v3/circles/{circleId}/members")
    suspend fun getCircleMembers(
        @Header("Authorization") authorization: String,
        @Path("circleId") circleId: String
    ): Response<Life360MembersResponse>

    /**
     * Get specific member's data (for individual updates).
     */
    @GET("v3/circles/{circleId}/members/{memberId}")
    suspend fun getCircleMember(
        @Header("Authorization") authorization: String,
        @Path("circleId") circleId: String,
        @Path("memberId") memberId: String
    ): Response<Life360MemberDto>

    /**
     * Request immediate location update for a member.
     *
     * After calling, location updates every ~5 seconds for ~1 minute.
     * Use sparingly - triggers more frequent updates on target device.
     */
    @POST("v3/circles/{circleId}/members/{memberId}/request")
    suspend fun requestLocationUpdate(
        @Header("Authorization") authorization: String,
        @Path("circleId") circleId: String,
        @Path("memberId") memberId: String
    ): Response<Unit>
}
