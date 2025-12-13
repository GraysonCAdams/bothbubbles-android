package com.bothbubbles.util.error

/**
 * Base sealed class for all application errors.
 * Extends Throwable so errors can be thrown and caught in Result types.
 * Provides structured error information for logging, display, and recovery.
 */
sealed class AppError(
    override val message: String,
    override val cause: Throwable? = null,
    open val isRetryable: Boolean = false
) : Throwable(message, cause) {
    /**
     * User-friendly message suitable for display in UI.
     * Override in subclasses for specific messages.
     */
    open val userMessage: String get() = "Something went wrong. Please try again."

    /**
     * Technical message for logging.
     */
    val technicalMessage: String get() = "$message${cause?.let { ": ${it.message}" } ?: ""}"
}

/**
 * Network-related errors
 */
sealed class NetworkError(
    override val message: String,
    override val cause: Throwable? = null,
    override val isRetryable: Boolean = true
) : AppError(message, cause, isRetryable) {

    data class NoConnection(
        override val cause: Throwable? = null
    ) : NetworkError("No internet connection", cause, true) {
        override val userMessage = "No internet connection. Please check your network settings."
    }

    data class Timeout(
        override val cause: Throwable? = null
    ) : NetworkError("Request timed out", cause, true) {
        override val userMessage = "Request timed out. Please try again."
    }

    data class ServerError(
        val statusCode: Int,
        val serverMessage: String? = null,
        override val cause: Throwable? = null
    ) : NetworkError("Server error: $statusCode", cause, statusCode >= 500) {
        override val userMessage = when (statusCode) {
            500 -> "Server error. Please try again later."
            502, 503 -> "Server is temporarily unavailable. Please try again."
            429 -> "Too many requests. Please wait a moment."
            else -> "Server error ($statusCode). Please try again."
        }
    }

    data class Unauthorized(
        override val cause: Throwable? = null
    ) : NetworkError("Unauthorized", cause, false) {
        override val userMessage = "Authentication failed. Please check your server settings."
    }

    data class Unknown(
        override val message: String = "Unknown network error",
        override val cause: Throwable? = null
    ) : NetworkError(message, cause, true)
}

/**
 * Database-related errors
 */
sealed class DatabaseError(
    override val message: String,
    override val cause: Throwable? = null,
    override val isRetryable: Boolean = false
) : AppError(message, cause, isRetryable) {

    data class QueryFailed(
        override val cause: Throwable? = null
    ) : DatabaseError("Database query failed", cause) {
        override val userMessage = "Failed to load data. Please restart the app."
    }

    data class InsertFailed(
        override val cause: Throwable? = null
    ) : DatabaseError("Failed to save data", cause) {
        override val userMessage = "Failed to save. Please try again."
    }

    data class MigrationFailed(
        override val cause: Throwable? = null
    ) : DatabaseError("Database migration failed", cause) {
        override val userMessage = "App update failed. Please reinstall the app."
    }
}

/**
 * Message sending/receiving errors
 */
sealed class MessageError(
    override val message: String,
    override val cause: Throwable? = null,
    override val isRetryable: Boolean = true
) : AppError(message, cause, isRetryable) {

    data class SendFailed(
        val messageGuid: String,
        val reason: String? = null,
        override val cause: Throwable? = null
    ) : MessageError("Failed to send message: $reason", cause) {
        override val userMessage = reason ?: "Failed to send message. Please try again."
    }

    data class DeliveryFailed(
        val messageGuid: String,
        override val cause: Throwable? = null
    ) : MessageError("Message delivery failed", cause) {
        override val userMessage = "Message couldn't be delivered."
    }

    data class AttachmentTooLarge(
        val sizeMb: Double,
        val maxSizeMb: Double
    ) : MessageError("Attachment too large: ${sizeMb}MB > ${maxSizeMb}MB", isRetryable = false) {
        override val userMessage = "File is too large (${String.format("%.1f", sizeMb)}MB). Maximum size is ${maxSizeMb.toInt()}MB."
    }

    data class UnsupportedAttachment(
        val mimeType: String
    ) : MessageError("Unsupported attachment type: $mimeType", isRetryable = false) {
        override val userMessage = "This file type is not supported."
    }
}

/**
 * SMS-specific errors
 */
sealed class SmsError(
    override val message: String,
    override val cause: Throwable? = null,
    override val isRetryable: Boolean = true
) : AppError(message, cause, isRetryable) {

    data class NoDefaultApp(
        override val cause: Throwable? = null
    ) : SmsError("Not set as default SMS app", cause, false) {
        override val userMessage = "Set BothBubbles as your default messaging app to send SMS."
    }

    data class PermissionDenied(
        override val cause: Throwable? = null
    ) : SmsError("SMS permission denied", cause, false) {
        override val userMessage = "SMS permission is required to send messages."
    }

    data class CarrierBlocked(
        override val cause: Throwable? = null
    ) : SmsError("Carrier blocked message", cause, false) {
        override val userMessage = "Your carrier blocked this message."
    }
}

/**
 * Validation errors
 */
sealed class ValidationError(
    override val message: String,
    override val isRetryable: Boolean = false
) : AppError(message, isRetryable = isRetryable) {

    data class InvalidInput(
        val field: String,
        val reason: String
    ) : ValidationError("Invalid $field: $reason") {
        override val userMessage = reason
    }

    data class MissingRequired(
        val field: String
    ) : ValidationError("Missing required field: $field") {
        override val userMessage = "$field is required."
    }
}
