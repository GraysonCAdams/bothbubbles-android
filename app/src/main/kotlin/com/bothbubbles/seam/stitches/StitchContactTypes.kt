package com.bothbubbles.seam.stitches

/**
 * Types of contact identifiers that Stitches can handle.
 *
 * Each Stitch declares which identifier types it supports via
 * [Stitch.supportedIdentifierTypes]. This enables intelligent
 * routing of messages to the appropriate Stitch.
 */
enum class ContactIdentifierType {
    /**
     * Phone number in E.164 format (e.g., "+1234567890").
     * Supported by: SMS, iMessage, Signal, WhatsApp, Telegram
     */
    PHONE_NUMBER,

    /**
     * Email address (e.g., "user@example.com").
     * Supported by: iMessage
     */
    EMAIL,

    /**
     * Signal-specific identifier (future).
     */
    SIGNAL_ID,

    /**
     * Discord username with discriminator (e.g., "user#1234").
     */
    DISCORD_USERNAME,

    /**
     * Telegram username (e.g., "@username").
     */
    TELEGRAM_USERNAME,

    /**
     * Matrix user ID (e.g., "@user:matrix.org").
     */
    MATRIX_ID
}

/**
 * A contact identifier with its type.
 *
 * @property type The type of identifier
 * @property value The raw identifier value
 * @property normalized A normalized form for caching/comparison
 */
data class ContactIdentifier(
    val type: ContactIdentifierType,
    val value: String,
    val normalized: String = value
)

/**
 * Result of checking if a Stitch can reach a contact.
 *
 * Used by [Stitch.checkContactAvailability] to report whether
 * a contact is reachable via that Stitch.
 */
sealed class ContactAvailability {
    /**
     * Contact is reachable via this Stitch.
     *
     * @property confidence How confident we are in this result
     */
    data class Available(
        val confidence: AvailabilityConfidence = AvailabilityConfidence.HIGH
    ) : ContactAvailability()

    /**
     * Contact is NOT reachable via this Stitch.
     * For example, the phone number is not registered with iMessage.
     */
    data object NotAvailable : ContactAvailability()

    /**
     * Stitch cannot determine availability (server offline, timeout, etc.).
     *
     * @property reason Human-readable explanation
     * @property fallbackHint Last known state (true = was available, false = was not, null = unknown)
     */
    data class Unknown(
        val reason: String,
        val fallbackHint: Boolean? = null
    ) : ContactAvailability()

    /**
     * Stitch doesn't support this identifier type at all.
     * For example, SMS doesn't support email addresses.
     */
    data object UnsupportedIdentifierType : ContactAvailability()
}

/**
 * Confidence level for availability results.
 */
enum class AvailabilityConfidence {
    /**
     * Direct server confirmation or definitive local check.
     */
    HIGH,

    /**
     * Cache hit that hasn't expired.
     */
    MEDIUM,

    /**
     * Fallback/heuristic or stale cache.
     */
    LOW
}

/**
 * Options for availability checks.
 *
 * @property forceRecheck Bypass cache and perform fresh check
 * @property timeoutMs Maximum time to wait for async checks
 * @property allowStaleCache Use expired cache as fallback if fresh check fails
 * @property requireStability For mode switching: require server to be stable
 */
data class AvailabilityCheckOptions(
    val forceRecheck: Boolean = false,
    val timeoutMs: Long = 3000L,
    val allowStaleCache: Boolean = true,
    val requireStability: Boolean = false
)
