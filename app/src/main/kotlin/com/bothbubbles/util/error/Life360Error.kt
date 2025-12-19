package com.bothbubbles.util.error

/**
 * Life360-specific errors.
 *
 * Following the sibling sealed class pattern used by [NetworkError], [SmsError], etc.
 */
sealed class Life360Error(
    override val message: String,
    override val cause: Throwable? = null,
    override val isRetryable: Boolean = true
) : AppError(message, cause, isRetryable) {

    /**
     * Rate limited by Life360 API (HTTP 429).
     * Should wait [retryAfterSeconds] before retrying.
     */
    data class RateLimited(
        val retryAfterSeconds: Long,
        override val cause: Throwable? = null
    ) : Life360Error("Rate limited by Life360", cause, true) {
        override val userMessage = "Life360 is busy. Please wait ${retryAfterSeconds}s."
    }

    /**
     * Authentication failed (e.g., invalid credentials).
     */
    data class AuthenticationFailed(
        val reason: String,
        override val cause: Throwable? = null
    ) : Life360Error("Authentication failed: $reason", cause, false) {
        override val userMessage = "Life360 login failed. Please reconnect your account."
    }

    /**
     * Token has expired and needs refresh.
     */
    data class TokenExpired(
        override val cause: Throwable? = null
    ) : Life360Error("Token expired", cause, false) {
        override val userMessage = "Life360 session expired. Please reconnect."
    }

    /**
     * API is blocked or unavailable (HTTP 403, 5xx).
     */
    data class ApiBlocked(
        val statusCode: Int,
        override val cause: Throwable? = null
    ) : Life360Error("API blocked: HTTP $statusCode", cause, false) {
        override val userMessage = "Life360 is temporarily unavailable."
    }

    /**
     * No circles found for the authenticated account.
     */
    data class NoCirclesFound(
        override val cause: Throwable? = null
    ) : Life360Error("No circles found", cause, false) {
        override val userMessage = "No Life360 circles found for this account."
    }

    /**
     * Network error during Life360 API call.
     */
    data class NetworkFailure(
        override val message: String = "Network error",
        override val cause: Throwable? = null
    ) : Life360Error(message, cause, true) {
        override val userMessage = "Couldn't connect to Life360. Check your connection."
    }

    /**
     * Parsing error for Life360 API response.
     */
    data class ParseError(
        override val message: String = "Failed to parse Life360 response",
        override val cause: Throwable? = null
    ) : Life360Error(message, cause, false) {
        override val userMessage = "Life360 returned unexpected data."
    }
}
