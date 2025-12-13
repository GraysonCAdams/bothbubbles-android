package com.bothbubbles.util.error

/**
 * Attachment-specific error states for download and upload failures.
 * Each state includes a user-friendly message and whether the error is retryable.
 *
 * These error types are stored in AttachmentEntity for persistence and
 * displayed in the UI via AttachmentErrorOverlay.
 */
sealed class AttachmentErrorState(
    val type: String,
    val userMessage: String,
    val isRetryable: Boolean
) {
    /**
     * Network timeout during download/upload.
     * Common on slow connections or large files.
     */
    data object NetworkTimeout : AttachmentErrorState(
        type = "NETWORK_TIMEOUT",
        userMessage = "Connection timed out. Tap to retry.",
        isRetryable = true
    )

    /**
     * Server returned an error (5xx, 4xx, etc.)
     */
    data class ServerError(
        val statusCode: Int = 0,
        val serverMessage: String? = null
    ) : AttachmentErrorState(
        type = "SERVER_ERROR",
        userMessage = when (statusCode) {
            404 -> "File not found on server."
            403 -> "Access denied."
            500, 502, 503 -> "Server error. Tap to retry."
            else -> serverMessage ?: "Server error. Tap to retry."
        },
        isRetryable = statusCode >= 500
    )

    /**
     * File exceeds the maximum allowed size.
     */
    data class FileTooLarge(
        val fileSizeMb: Double = 0.0,
        val maxSizeMb: Double = 0.0
    ) : AttachmentErrorState(
        type = "FILE_TOO_LARGE",
        userMessage = "File too large (${String.format("%.1f", fileSizeMb)} MB max: ${maxSizeMb.toInt()} MB).",
        isRetryable = false
    )

    /**
     * Attachment format is not supported.
     */
    data class FormatUnsupported(
        val mimeType: String? = null
    ) : AttachmentErrorState(
        type = "FORMAT_UNSUPPORTED",
        userMessage = "Unsupported file format.",
        isRetryable = false
    )

    /**
     * Device storage is full - cannot save downloaded file.
     */
    data object StorageFull : AttachmentErrorState(
        type = "STORAGE_FULL",
        userMessage = "Storage full. Free up space and retry.",
        isRetryable = true
    )

    /**
     * No network connection available.
     */
    data object NoConnection : AttachmentErrorState(
        type = "NO_CONNECTION",
        userMessage = "No internet connection. Tap to retry.",
        isRetryable = true
    )

    /**
     * Maximum retry attempts exceeded.
     */
    data object MaxRetriesExceeded : AttachmentErrorState(
        type = "MAX_RETRIES_EXCEEDED",
        userMessage = "Download failed after multiple attempts. Tap to retry.",
        isRetryable = true // User can still manually retry
    )

    /**
     * Generic/unknown error.
     */
    data class Unknown(
        val message: String? = null
    ) : AttachmentErrorState(
        type = "UNKNOWN",
        userMessage = message ?: "Download failed. Tap to retry.",
        isRetryable = true
    )

    companion object {
        /**
         * Parse an error state from stored string type and optional message.
         * Used when loading from database.
         */
        fun fromString(type: String?, message: String? = null): AttachmentErrorState? {
            if (type == null) return null

            return when (type) {
                "NETWORK_TIMEOUT" -> NetworkTimeout
                "SERVER_ERROR" -> ServerError(serverMessage = message)
                "FILE_TOO_LARGE" -> FileTooLarge()
                "FORMAT_UNSUPPORTED" -> FormatUnsupported()
                "STORAGE_FULL" -> StorageFull
                "NO_CONNECTION" -> NoConnection
                "MAX_RETRIES_EXCEEDED" -> MaxRetriesExceeded
                "UNKNOWN" -> Unknown(message)
                else -> Unknown(message)
            }
        }

        /**
         * Create an appropriate error state from an exception.
         * Maps common exception types to specific error states.
         */
        fun fromException(e: Throwable): AttachmentErrorState {
            val message = e.message ?: ""
            return when {
                e is java.net.SocketTimeoutException -> NetworkTimeout
                e is java.net.UnknownHostException -> NoConnection
                e is java.net.ConnectException -> NoConnection
                e is java.io.IOException && message.contains("No space left", ignoreCase = true) -> StorageFull
                e is java.io.IOException && message.contains("timeout", ignoreCase = true) -> NetworkTimeout
                e is java.io.IOException && message.contains("Download failed: 4") -> {
                    // Extract status code from message like "Download failed: 404"
                    val code = message.substringAfter("Download failed: ").take(3).toIntOrNull() ?: 0
                    ServerError(statusCode = code)
                }
                e is java.io.IOException && message.contains("Download failed: 5") -> {
                    val code = message.substringAfter("Download failed: ").take(3).toIntOrNull() ?: 500
                    ServerError(statusCode = code)
                }
                else -> Unknown(message.takeIf { it.isNotBlank() })
            }
        }
    }
}
