package com.bothbubbles.util.error

import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps exceptions to structured AppError types.
 */
object ErrorMapper {

    fun mapException(e: Throwable): AppError {
        return when (e) {
            is AppError -> e // Already mapped

            // Network errors
            is UnknownHostException -> NetworkError.NoConnection(e)
            is SocketTimeoutException -> NetworkError.Timeout(e)
            is SSLException -> NetworkError.Unknown("SSL error", e)

            is HttpException -> mapHttpException(e)

            is IOException -> {
                val message = e.message?.lowercase() ?: ""
                when {
                    message.contains("connection") -> NetworkError.NoConnection(e)
                    message.contains("timeout") -> NetworkError.Timeout(e)
                    else -> NetworkError.Unknown(e.message ?: "IO error", e)
                }
            }

            // Database errors
            is android.database.SQLException -> DatabaseError.QueryFailed(e)

            // Generic fallback
            else -> NetworkError.Unknown(e.message ?: "Unknown error", e)
        }
    }

    private fun mapHttpException(e: HttpException): NetworkError {
        return when (e.code()) {
            401, 403 -> NetworkError.Unauthorized(e)
            429 -> NetworkError.ServerError(429, "Rate limited", e)
            in 500..599 -> NetworkError.ServerError(e.code(), e.message(), e)
            else -> NetworkError.ServerError(e.code(), e.message(), e)
        }
    }
}
