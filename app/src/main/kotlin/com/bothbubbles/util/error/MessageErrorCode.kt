package com.bothbubbles.util.error

/**
 * Message error codes from BlueBubbles server and iMessage.
 *
 * These codes match the server-side error codes for consistent error handling.
 * The most important code is [NOT_REGISTERED_WITH_IMESSAGE] (22), which indicates
 * the recipient is not an iMessage user and suggests SMS fallback.
 */
object MessageErrorCode {
    /** No error - message sent successfully */
    const val NO_ERROR = 0

    /** Generic send failure (legacy/fallback) */
    const val GENERIC_ERROR = 1

    /** Server-side timeout during send */
    const val TIMEOUT = 4

    /**
     * Recipient is not registered with iMessage.
     * This is the most actionable error - user should try SMS instead.
     */
    const val NOT_REGISTERED_WITH_IMESSAGE = 22

    /** No network connection */
    const val NO_CONNECTION = 1000

    /** Bad request (invalid parameters) */
    const val BAD_REQUEST = 1001

    /** Server error (500, 502, 503, etc.) */
    const val SERVER_ERROR = 1002

    /**
     * Returns a user-friendly error message for the given error code.
     *
     * @param errorCode The numeric error code from the message
     * @param rawErrorMessage Optional raw error message from server for additional context
     * @return A user-friendly error message suitable for display in UI
     */
    fun getUserMessage(errorCode: Int, rawErrorMessage: String? = null): String {
        return when (errorCode) {
            NO_ERROR -> "Message sent"
            TIMEOUT -> "Send timed out. Please try again."
            NOT_REGISTERED_WITH_IMESSAGE -> "Recipient is not registered with iMessage. Try sending as SMS."
            NO_CONNECTION -> "No internet connection. Check your network and try again."
            BAD_REQUEST -> "Could not send message. Please try again."
            SERVER_ERROR -> "Server error. Please try again later."
            GENERIC_ERROR -> rawErrorMessage ?: "Failed to send message. Please try again."
            else -> rawErrorMessage ?: "Failed to send message (error $errorCode). Please try again."
        }
    }

    /**
     * Returns a short error title for the given error code.
     */
    fun getErrorTitle(errorCode: Int): String {
        return when (errorCode) {
            TIMEOUT -> "Send Timeout"
            NOT_REGISTERED_WITH_IMESSAGE -> "Not on iMessage"
            NO_CONNECTION -> "No Connection"
            BAD_REQUEST -> "Send Failed"
            SERVER_ERROR -> "Server Error"
            else -> "Send Failed"
        }
    }

    /**
     * Returns whether this error suggests SMS fallback as a solution.
     * Currently only error code 22 (not registered with iMessage) suggests SMS.
     */
    fun suggestsSmsRetry(errorCode: Int): Boolean {
        return errorCode == NOT_REGISTERED_WITH_IMESSAGE
    }

    /**
     * Returns whether the error is retryable (same delivery method).
     *
     * NOTE: This is a legacy function. For WorkManager retry logic, use [isNetworkError] instead.
     * We only retry if the message never reached the server (network error).
     */
    fun isRetryable(errorCode: Int): Boolean {
        return when (errorCode) {
            NO_ERROR -> false
            NOT_REGISTERED_WITH_IMESSAGE -> false // Retry won't help, need SMS
            TIMEOUT, NO_CONNECTION, SERVER_ERROR, GENERIC_ERROR -> true
            else -> true
        }
    }

    /**
     * Returns whether the error indicates the message never reached the BlueBubbles server.
     *
     * These are the ONLY errors that should trigger automatic retry, because:
     * - If we never reached the server, retrying might succeed
     * - If the server responded with an error, retrying won't help (server already rejected it)
     *
     * @param errorCode The error code from the failed send attempt
     * @return true if the message never reached the server (network/connection failure)
     */
    fun isNetworkError(errorCode: Int): Boolean {
        return when (errorCode) {
            NO_CONNECTION -> true  // DNS failure, connection refused, no internet
            TIMEOUT -> true        // Connection or read timeout (server didn't respond in time)
            else -> false          // Server responded with an error - don't retry
        }
    }

    /**
     * Parse error code from a raw error message string.
     * Server sometimes returns error messages like "Error 22: Not registered..."
     */
    fun parseFromMessage(message: String?): Int? {
        if (message.isNullOrBlank()) return null

        // Check for "error 22" pattern
        val errorPattern = Regex("""error\s*(\d+)""", RegexOption.IGNORE_CASE)
        errorPattern.find(message)?.let {
            return it.groupValues[1].toIntOrNull()
        }

        // Check for "not registered" text which implies error 22
        if (message.contains("not registered", ignoreCase = true)) {
            return NOT_REGISTERED_WITH_IMESSAGE
        }

        return null
    }

    /**
     * Determine error code from exception type.
     */
    fun fromException(e: Throwable): Int {
        return when (e) {
            is java.net.UnknownHostException -> NO_CONNECTION
            is java.net.ConnectException -> NO_CONNECTION
            is java.net.SocketTimeoutException -> TIMEOUT
            is java.io.InterruptedIOException -> TIMEOUT
            is NetworkError.NoConnection -> NO_CONNECTION
            is NetworkError.Timeout -> TIMEOUT
            is NetworkError.ServerError -> if (e.statusCode >= 500) SERVER_ERROR else BAD_REQUEST
            is NetworkError.Unauthorized -> BAD_REQUEST
            is MessageError -> GENERIC_ERROR
            else -> GENERIC_ERROR
        }
    }
}
