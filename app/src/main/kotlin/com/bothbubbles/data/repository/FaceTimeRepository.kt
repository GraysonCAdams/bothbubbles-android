package com.bothbubbles.data.repository

import com.bothbubbles.core.network.api.BothBubblesApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for FaceTime call operations
 */
@Singleton
class FaceTimeRepository @Inject constructor(
    private val api: BothBubblesApi
) {
    /**
     * Answer a FaceTime call and get the link to join
     * @param callUuid The unique identifier of the call
     * @return The FaceTime link to open in browser
     */
    suspend fun answerCall(callUuid: String): Result<String> = runCatching {
        val response = api.answerFaceTime(callUuid)
        val body = response.body()

        if (!response.isSuccessful || body == null || body.status != 200) {
            throw Exception(body?.message ?: "Failed to answer FaceTime call")
        }

        body.data?.link ?: throw Exception("No FaceTime link returned")
    }

    /**
     * Decline/leave a FaceTime call
     * @param callUuid The unique identifier of the call
     */
    suspend fun declineCall(callUuid: String): Result<Unit> = runCatching {
        val response = api.leaveFaceTime(callUuid)

        if (!response.isSuccessful) {
            throw Exception("Failed to decline FaceTime call")
        }
    }
}
