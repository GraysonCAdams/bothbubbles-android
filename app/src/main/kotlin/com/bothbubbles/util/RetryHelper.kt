package com.bothbubbles.util

import timber.log.Timber
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Executes a suspend block with exponential backoff retry logic.
 *
 * @param times Maximum number of attempts (default 3)
 * @param initialDelayMs Initial delay before first retry (default 500ms)
 * @param maxDelayMs Maximum delay between retries (default 5000ms)
 * @param factor Multiplier for delay after each attempt (default 2.0)
 * @param shouldRetry Custom predicate to determine if exception is retryable
 * @param block The suspend function to execute
 * @return Result of the block execution
 */
suspend fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelayMs: Long = 500,
    maxDelayMs: Long = 5000,
    factor: Double = 2.0,
    shouldRetry: (Exception) -> Boolean = { it.isRetryable() },
    block: suspend () -> T
): T {
    var currentDelay = initialDelayMs
    var lastException: Exception? = null

    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastException = e

            if (!shouldRetry(e)) {
                Timber.d("Non-retryable exception on attempt ${attempt + 1}: ${e.javaClass.simpleName}")
                throw e
            }

            if (attempt == times - 1) {
                Timber.w("Final attempt ${attempt + 1} failed: ${e.javaClass.simpleName}")
                throw e
            }

            Timber.d("Attempt ${attempt + 1} failed, retrying in ${currentDelay}ms: ${e.javaClass.simpleName}")
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
        }
    }

    throw lastException ?: IllegalStateException("Retry failed without exception")
}

/**
 * Convenience wrapper that returns Result instead of throwing.
 */
suspend fun <T> retryWithBackoffResult(
    times: Int = 3,
    initialDelayMs: Long = 500,
    maxDelayMs: Long = 5000,
    factor: Double = 2.0,
    shouldRetry: (Exception) -> Boolean = { it.isRetryable() },
    block: suspend () -> T
): Result<T> {
    return runCatching {
        retryWithBackoff(
            times = times,
            initialDelayMs = initialDelayMs,
            maxDelayMs = maxDelayMs,
            factor = factor,
            shouldRetry = shouldRetry,
            block = block
        )
    }
}

/**
 * Determines if an exception should trigger a retry.
 */
fun Exception.isRetryable(): Boolean {
    return when (this) {
        // Network connectivity issues - always retry
        is SocketTimeoutException -> true
        is UnknownHostException -> true
        is IOException -> {
            // Retry most IOExceptions, but not all
            val message = this.message?.lowercase() ?: ""
            !message.contains("canceled") && !message.contains("closed")
        }
        is SSLException -> {
            // Retry SSL handshake failures (often transient)
            val message = this.message?.lowercase() ?: ""
            message.contains("handshake") || message.contains("reset")
        }

        // HTTP errors that are retryable
        is HttpException -> {
            when (this.code()) {
                408 -> true  // Request Timeout
                429 -> true  // Too Many Requests (rate limited)
                500 -> true  // Internal Server Error
                502 -> true  // Bad Gateway
                503 -> true  // Service Unavailable
                504 -> true  // Gateway Timeout
                else -> false
            }
        }

        // Wrapped exceptions
        else -> {
            this.cause?.let { (it as? Exception)?.isRetryable() } ?: false
        }
    }
}

/**
 * Special handling for rate-limited responses (429).
 * Extracts retry-after header if present.
 */
suspend fun <T> retryWithRateLimitAwareness(
    times: Int = 3,
    initialDelayMs: Long = 1000,
    block: suspend () -> retrofit2.Response<T>
): retrofit2.Response<T> {
    var currentDelay = initialDelayMs

    repeat(times) { attempt ->
        val response = block()

        if (response.isSuccessful) {
            return response
        }

        if (response.code() == 429) {
            // Check for Retry-After header
            val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
            val delayMs = if (retryAfter != null) {
                retryAfter * 1000 // Convert seconds to milliseconds
            } else {
                currentDelay
            }

            if (attempt < times - 1) {
                Timber.d("Rate limited, waiting ${delayMs}ms before retry")
                delay(delayMs)
                currentDelay = (currentDelay * 2).coerceAtMost(30000)
                return@repeat
            }
        }

        // For other errors on last attempt, return the response
        if (attempt == times - 1) {
            return response
        }

        // Retry on server errors
        if (response.code() in 500..599) {
            delay(currentDelay)
            currentDelay = (currentDelay * 2).coerceAtMost(10000)
        } else {
            // Non-retryable error, return immediately
            return response
        }
    }

    return block() // Shouldn't reach here, but compile safety
}
