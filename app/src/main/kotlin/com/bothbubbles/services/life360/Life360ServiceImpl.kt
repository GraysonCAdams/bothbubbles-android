package com.bothbubbles.services.life360

import com.bothbubbles.core.model.Life360Circle
import com.bothbubbles.core.model.NoLocationReason
import com.bothbubbles.core.model.entity.Life360MemberEntity
import com.bothbubbles.core.network.api.Life360Api
import com.bothbubbles.core.network.api.dto.Life360MemberDto
import com.bothbubbles.data.repository.Life360Repository
import com.bothbubbles.util.error.Life360Error
import kotlinx.coroutines.delay
import retrofit2.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Implementation of [Life360Service].
 *
 * Handles Life360 API calls with rate limiting and error handling.
 * Converts API responses to domain models and persists to local database.
 */
@Singleton
class Life360ServiceImpl @Inject constructor(
    private val api: Life360Api,
    private val tokenStorage: Life360TokenStorage,
    private val rateLimiter: Life360RateLimiter,
    private val repository: Life360Repository
) : Life360Service {

    override val isAuthenticated: Boolean
        get() = tokenStorage.isAuthenticated

    override suspend fun storeToken(accessToken: String) {
        tokenStorage.storeTokens(accessToken)
        Timber.d("Life360 token stored")
    }

    override suspend fun logout() {
        tokenStorage.clear()
        repository.deleteAllMembers()
        Timber.d("Life360 logged out and data cleared")
    }

    override suspend fun fetchCircles(): Result<List<Life360Circle>> {
        val authHeader = tokenStorage.buildAuthHeader()
            ?: return Result.failure(Life360Error.AuthenticationFailed("Not authenticated"))

        // Check rate limit
        val delay = rateLimiter.getDelayUntilAllowed(Life360RateLimiter.Endpoint.CIRCLES)
        if (delay > 0) {
            Timber.d("Waiting ${delay}ms before circles API call")
            delay(delay)
        }

        return try {
            val response = api.getCircles(authHeader)
            handleResponse(response, Life360RateLimiter.Endpoint.CIRCLES) { body ->
                body.circles.map { dto ->
                    Life360Circle(
                        id = dto.id,
                        name = dto.name,
                        memberCount = dto.memberCount ?: 0
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch circles")
            Result.failure(Life360Error.NetworkFailure(cause = e))
        }
    }

    override suspend fun syncCircleMembers(circleId: String): Result<Int> {
        val authHeader = tokenStorage.buildAuthHeader()
            ?: return Result.failure(Life360Error.AuthenticationFailed("Not authenticated"))

        // Check rate limit
        val delay = rateLimiter.getDelayUntilAllowed(Life360RateLimiter.Endpoint.MEMBERS)
        if (delay > 0) {
            Timber.d("Waiting ${delay}ms before members API call")
            delay(delay)
        }

        return try {
            val response = api.getCircleMembers(authHeader, circleId)
            handleResponse(response, Life360RateLimiter.Endpoint.MEMBERS) { body ->
                val entities = body.members.map { dto ->
                    dtoToEntity(dto, circleId)
                }
                repository.saveMembers(circleId, entities)

                // Auto-map contacts after sync
                repository.autoMapContacts()

                entities.size
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync circle members")
            Result.failure(Life360Error.NetworkFailure(cause = e))
        }
    }

    override suspend fun syncAllCircles(): Result<Int> {
        val circlesResult = fetchCircles()
        if (circlesResult.isFailure) {
            return Result.failure(circlesResult.exceptionOrNull() ?: Exception("Unknown error"))
        }

        val circles = circlesResult.getOrThrow()
        if (circles.isEmpty()) {
            return Result.failure(Life360Error.NoCirclesFound())
        }

        var totalSynced = 0
        for (circle in circles) {
            val result = syncCircleMembers(circle.id)
            if (result.isSuccess) {
                totalSynced += result.getOrDefault(0)
            }
        }

        return Result.success(totalSynced)
    }

    override suspend fun requestLocationUpdate(circleId: String, memberId: String): Result<Unit> {
        val authHeader = tokenStorage.buildAuthHeader()
            ?: return Result.failure(Life360Error.AuthenticationFailed("Not authenticated"))

        // Check rate limit - fail immediately if rate limited (don't wait)
        val delay = rateLimiter.getDelayUntilAllowed(Life360RateLimiter.Endpoint.REQUEST_LOCATION)
        if (delay > 0) {
            Timber.d("Location request rate limited, ${delay}ms until allowed")
            return Result.failure(Life360Error.RateLimited(delay / 1000))
        }

        return try {
            val response = api.requestLocationUpdate(authHeader, circleId, memberId)
            handleResponse(response, Life360RateLimiter.Endpoint.REQUEST_LOCATION) { }
        } catch (e: Exception) {
            Timber.e(e, "Failed to request location update")
            Result.failure(Life360Error.NetworkFailure(cause = e))
        }
    }

    override suspend fun syncMember(circleId: String, memberId: String): Result<Unit> {
        val authHeader = tokenStorage.buildAuthHeader()
            ?: return Result.failure(Life360Error.AuthenticationFailed("Not authenticated"))

        // Check rate limit for individual member fetch
        val delay = rateLimiter.getDelayUntilAllowed(Life360RateLimiter.Endpoint.MEMBER)
        if (delay > 0) {
            Timber.d("Waiting ${delay}ms before member fetch")
            delay(delay)
        }

        return try {
            val response = api.getCircleMember(authHeader, circleId, memberId)
            handleResponse(response, Life360RateLimiter.Endpoint.MEMBER) { dto ->
                val entity = dtoToEntity(dto, circleId)
                repository.saveMembers(circleId, listOf(entity))
                Timber.d("Synced single member: ${entity.firstName} ${entity.lastName}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync member")
            Result.failure(Life360Error.NetworkFailure(cause = e))
        }
    }

    override suspend fun getCirclesRateLimitDelay(): Long =
        rateLimiter.getDelayUntilAllowed(Life360RateLimiter.Endpoint.CIRCLES)

    override suspend fun getMembersRateLimitDelay(): Long =
        rateLimiter.getDelayUntilAllowed(Life360RateLimiter.Endpoint.MEMBERS)

    // ===== Private Helpers =====

    /**
     * Handle API response with rate limit tracking.
     */
    private suspend fun <T, R> handleResponse(
        response: Response<T>,
        endpoint: Life360RateLimiter.Endpoint,
        transform: suspend (T) -> R
    ): Result<R> {
        return when {
            response.isSuccessful -> {
                val body = response.body()
                if (body != null) {
                    rateLimiter.recordRequest(endpoint)
                    rateLimiter.clearRateLimit()
                    Result.success(transform(body))
                } else {
                    Result.failure(Life360Error.ParseError("Empty response body"))
                }
            }

            response.code() == 401 -> {
                tokenStorage.clear()
                Result.failure(Life360Error.TokenExpired())
            }

            response.code() == 403 || response.code() == 429 -> {
                val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
                rateLimiter.recordRateLimit(retryAfter)
                Result.failure(Life360Error.RateLimited(retryAfter ?: 60))
            }

            response.code() in 500..599 -> {
                Result.failure(Life360Error.ApiBlocked(response.code()))
            }

            else -> {
                Result.failure(Life360Error.NetworkFailure("HTTP ${response.code()}"))
            }
        }
    }

    /**
     * Convert API DTO to database entity.
     *
     * Handles Life360's string-based numeric values and feet-to-meters conversion.
     */
    private fun dtoToEntity(dto: Life360MemberDto, circleId: String): Life360MemberEntity {
        val location = dto.location
        val now = System.currentTimeMillis()

        // Determine no-location reason if location is null
        val noLocationReason = when {
            location != null -> null
            dto.issues?.title?.contains("explicit", ignoreCase = true) == true ->
                NoLocationReason.EXPLICIT.name
            dto.issues?.title?.contains("sharing", ignoreCase = true) == true ->
                NoLocationReason.NOT_SHARING.name
            dto.issues != null -> NoLocationReason.NO_REASON.name
            else -> null
        }

        return Life360MemberEntity(
            memberId = dto.id,
            circleId = circleId,
            firstName = dto.firstName,
            lastName = dto.lastName,
            avatarUrl = dto.avatar,
            phoneNumber = dto.loginPhone,
            latitude = location?.latitude?.toDoubleOrNull(),
            longitude = location?.longitude?.toDoubleOrNull(),
            accuracyMeters = location?.accuracy?.toDoubleOrNull()?.let { feet ->
                (feet * FEET_TO_METERS).roundToInt()
            },
            address = buildAddress(location?.address1, location?.address2),
            placeName = location?.name,
            battery = location?.battery?.toFloatOrNull()?.roundToInt(),
            isDriving = location?.isDriving == "1",
            isCharging = location?.charge == "1",
            locationTimestamp = location?.timestamp?.toLongOrNull()?.let { it * 1000 },
            lastUpdated = now,
            noLocationReason = noLocationReason,
            mappedHandleId = null  // Preserved by DAO upsert
        )
    }

    private fun buildAddress(address1: String?, address2: String?): String? {
        val parts = listOfNotNull(address1?.takeIf { it.isNotBlank() }, address2?.takeIf { it.isNotBlank() })
        return if (parts.isNotEmpty()) parts.joinToString(", ") else null
    }

    companion object {
        private const val FEET_TO_METERS = 0.3048
    }
}
