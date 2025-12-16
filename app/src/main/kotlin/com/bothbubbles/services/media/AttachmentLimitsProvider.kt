package com.bothbubbles.services.media

import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import timber.log.Timber
import com.bothbubbles.services.messaging.MessageDeliveryMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides protocol-aware attachment size limits.
 *
 * MMS has strict carrier-specific limits (typically 300KB total, 250KB per image).
 * iMessage via BlueBubbles server is more permissive (up to 100MB).
 */
@Singleton
class AttachmentLimitsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // MMS defaults (conservative, carriers may allow more)
        private const val DEFAULT_MMS_MAX_SIZE = 300 * 1024L // 300KB
        private const val DEFAULT_MMS_MAX_IMAGE_SIZE = 250 * 1024L // 250KB per image
        private const val MMS_MAX_REASONABLE_SIZE = 2 * 1024 * 1024L // 2MB absolute max for MMS

        // iMessage limits (via BlueBubbles server)
        private const val IMESSAGE_MAX_SIZE = 100 * 1024 * 1024L // 100MB
        private const val IMESSAGE_WARN_SIZE = 20 * 1024 * 1024L // Warn above 20MB

        // Attachment count limits
        private const val MMS_MAX_ATTACHMENTS = 10
        private const val IMESSAGE_MAX_ATTACHMENTS = 20
    }

    /**
     * Get attachment limits for the specified delivery mode.
     *
     * @param deliveryMode The message delivery mode (iMessage, SMS, MMS, or AUTO)
     * @param subscriptionId Optional SIM subscription ID for carrier-specific MMS limits
     * @return Attachment limits for the protocol
     */
    fun getLimits(
        deliveryMode: MessageDeliveryMode,
        subscriptionId: Int = -1
    ): AttachmentLimits {
        return when (deliveryMode) {
            MessageDeliveryMode.LOCAL_SMS,
            MessageDeliveryMode.LOCAL_MMS -> getMmsLimits(subscriptionId)
            MessageDeliveryMode.IMESSAGE -> getIMessageLimits()
            MessageDeliveryMode.AUTO -> {
                // For AUTO mode, return the more restrictive MMS limits
                // to ensure the message can be sent via any protocol
                getMmsLimits(subscriptionId)
            }
        }
    }

    /**
     * Get iMessage limits (via BlueBubbles server).
     */
    fun getIMessageLimits(): AttachmentLimits {
        return AttachmentLimits(
            protocol = Protocol.IMESSAGE,
            maxTotalSize = IMESSAGE_MAX_SIZE,
            maxSingleFileSize = IMESSAGE_MAX_SIZE,
            maxAttachments = IMESSAGE_MAX_ATTACHMENTS,
            compressionRequired = false,
            warnAboveSize = IMESSAGE_WARN_SIZE
        )
    }

    /**
     * Get MMS limits based on carrier configuration.
     *
     * @param subscriptionId SIM subscription ID (-1 for default)
     */
    fun getMmsLimits(subscriptionId: Int = -1): AttachmentLimits {
        val carrierMaxSize = getCarrierMmsMaxSize(subscriptionId)
        return AttachmentLimits(
            protocol = Protocol.MMS,
            maxTotalSize = carrierMaxSize,
            maxSingleFileSize = DEFAULT_MMS_MAX_IMAGE_SIZE.coerceAtMost(carrierMaxSize),
            maxAttachments = MMS_MAX_ATTACHMENTS,
            compressionRequired = true,
            warnAboveSize = null // No warning, just enforce
        )
    }

    /**
     * Check if an attachment size is valid for the given protocol.
     *
     * @return ValidationResult with details about any issues
     */
    fun validateAttachment(
        sizeBytes: Long,
        deliveryMode: MessageDeliveryMode,
        existingTotalSize: Long = 0,
        existingCount: Int = 0,
        subscriptionId: Int = -1
    ): ValidationResult {
        val limits = getLimits(deliveryMode, subscriptionId)
        val newTotalSize = existingTotalSize + sizeBytes

        return when {
            // Check attachment count
            existingCount >= limits.maxAttachments -> {
                ValidationResult(
                    isValid = false,
                    error = ValidationError.TOO_MANY_ATTACHMENTS,
                    message = "Maximum ${limits.maxAttachments} attachments allowed for ${limits.protocol.displayName}",
                    limits = limits
                )
            }

            // Check single file size
            sizeBytes > limits.maxSingleFileSize -> {
                val canCompress = limits.compressionRequired
                ValidationResult(
                    isValid = false,
                    error = if (canCompress) ValidationError.SIZE_EXCEEDS_LIMIT_COMPRESSIBLE else ValidationError.SIZE_EXCEEDS_LIMIT,
                    message = "File too large for ${limits.protocol.displayName} (${formatSize(sizeBytes)} / ${formatSize(limits.maxSingleFileSize)} max)",
                    limits = limits,
                    suggestCompression = canCompress
                )
            }

            // Check total size
            newTotalSize > limits.maxTotalSize -> {
                ValidationResult(
                    isValid = false,
                    error = ValidationError.TOTAL_SIZE_EXCEEDS_LIMIT,
                    message = "Total attachments too large for ${limits.protocol.displayName} (${formatSize(newTotalSize)} / ${formatSize(limits.maxTotalSize)} max)",
                    limits = limits,
                    suggestCompression = limits.compressionRequired
                )
            }

            // Check warning threshold (valid but warn)
            limits.warnAboveSize != null && sizeBytes > limits.warnAboveSize -> {
                ValidationResult(
                    isValid = true,
                    warning = "Large file (${formatSize(sizeBytes)}) - may take longer to send",
                    limits = limits
                )
            }

            // Valid
            else -> {
                ValidationResult(
                    isValid = true,
                    limits = limits
                )
            }
        }
    }

    /**
     * Query carrier configuration for MMS max size.
     */
    private fun getCarrierMmsMaxSize(subscriptionId: Int): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return DEFAULT_MMS_MAX_SIZE
        }

        return try {
            val carrierConfigManager = context.getSystemService(Context.CARRIER_CONFIG_SERVICE)
                as? CarrierConfigManager ?: return DEFAULT_MMS_MAX_SIZE

            val subId = if (subscriptionId >= 0) {
                subscriptionId
            } else {
                SubscriptionManager.getDefaultSmsSubscriptionId()
            }

            val config: PersistableBundle? = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                carrierConfigManager.getConfigForSubId(subId)
            } else {
                carrierConfigManager.config
            }

            val maxSize = config?.getInt(
                CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT,
                DEFAULT_MMS_MAX_SIZE.toInt()
            )?.toLong() ?: DEFAULT_MMS_MAX_SIZE

            // Sanity check - don't trust absurd values
            val sanitizedSize = maxSize.coerceIn(DEFAULT_MMS_MAX_SIZE, MMS_MAX_REASONABLE_SIZE)
            Timber.d("Carrier MMS max size: $sanitizedSize bytes (raw: $maxSize)")
            sanitizedSize
        } catch (e: Exception) {
            Timber.w(e, "Failed to get carrier config, using default")
            DEFAULT_MMS_MAX_SIZE
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

/**
 * Protocol type for attachment handling.
 */
enum class Protocol(val displayName: String) {
    IMESSAGE("iMessage"),
    MMS("MMS"),
    SMS("SMS") // SMS doesn't support attachments but included for completeness
}

/**
 * Attachment limits for a specific protocol.
 */
data class AttachmentLimits(
    /** Protocol these limits apply to */
    val protocol: Protocol,

    /** Maximum total size of all attachments combined */
    val maxTotalSize: Long,

    /** Maximum size for a single attachment */
    val maxSingleFileSize: Long,

    /** Maximum number of attachments */
    val maxAttachments: Int,

    /** Whether compression should be applied to oversized files */
    val compressionRequired: Boolean,

    /** Size threshold above which to show a warning (null = no warning) */
    val warnAboveSize: Long?
)

/**
 * Result of validating an attachment against protocol limits.
 */
data class ValidationResult(
    /** Whether the attachment is valid for the protocol */
    val isValid: Boolean,

    /** Error type if invalid */
    val error: ValidationError? = null,

    /** Human-readable error/warning message */
    val message: String? = null,

    /** Non-blocking warning message */
    val warning: String? = null,

    /** The limits that were checked against */
    val limits: AttachmentLimits,

    /** Whether compression might help */
    val suggestCompression: Boolean = false
)

/**
 * Types of validation errors.
 */
enum class ValidationError {
    /** Single file exceeds protocol limit, not compressible */
    SIZE_EXCEEDS_LIMIT,

    /** Single file exceeds protocol limit, but compression may help */
    SIZE_EXCEEDS_LIMIT_COMPRESSIBLE,

    /** Total attachments size exceeds protocol limit */
    TOTAL_SIZE_EXCEEDS_LIMIT,

    /** Too many attachments */
    TOO_MANY_ATTACHMENTS
}
