package com.bothbubbles.seam.stitches

/**
 * Types of contact identifiers that Stitches can handle.
 *
 * Different platforms support different ways to identify contacts:
 * - SMS/iMessage: Phone numbers and emails
 * - Signal: Phone numbers and Signal-specific IDs
 * - Discord: Discord usernames
 */
enum class ContactIdentifierType {
    /** Standard phone number (E.164 format preferred) */
    PHONE_NUMBER,

    /** Email address */
    EMAIL,

    /** Signal-specific identifier */
    SIGNAL_ID,

    /** Discord username (user#1234 or new username format) */
    DISCORD_USERNAME
}

/**
 * A contact identifier with its type.
 *
 * @property type The type of identifier
 * @property value The identifier value (phone number, email, etc.)
 */
data class ContactIdentifier(
    val type: ContactIdentifierType,
    val value: String
) {
    companion object {
        /**
         * Creates a phone number identifier.
         */
        fun phone(number: String): ContactIdentifier =
            ContactIdentifier(ContactIdentifierType.PHONE_NUMBER, number)

        /**
         * Creates an email identifier.
         */
        fun email(address: String): ContactIdentifier =
            ContactIdentifier(ContactIdentifierType.EMAIL, address)
    }
}

/**
 * Options for checking contact availability.
 *
 * @property timeoutMs Maximum time to wait for async availability checks
 * @property useCache Whether to use cached results (faster but potentially stale)
 * @property forceRefresh Force a fresh check even if cached result exists
 */
data class AvailabilityCheckOptions(
    val timeoutMs: Long = 5000L,
    val useCache: Boolean = true,
    val forceRefresh: Boolean = false
)

/**
 * Confidence level for availability results.
 *
 * Higher confidence means more reliable result.
 */
enum class AvailabilityConfidence {
    /** Real-time server confirmation - highest confidence */
    HIGH,

    /** Based on cached data that may be slightly stale */
    MEDIUM,

    /** Assumed based on identifier type match or old cache */
    LOW,

    /** Unknown - no data available */
    UNKNOWN
}

/**
 * Result of checking if a Stitch can reach a contact.
 */
sealed class ContactAvailability {
    /**
     * The contact is definitely reachable via this Stitch.
     *
     * @property confidence How confident we are in this result
     * @property lastVerified When this was last verified (epoch millis), or null if unknown
     */
    data class Available(
        val confidence: AvailabilityConfidence = AvailabilityConfidence.CONFIRMED,
        val lastVerified: Long? = null
    ) : ContactAvailability()

    /**
     * The contact is definitely NOT reachable via this Stitch.
     *
     * @property reason Optional reason why unavailable
     */
    data class Unavailable(
        val reason: String? = null
    ) : ContactAvailability()

    /**
     * Availability is unknown (e.g., server is unreachable).
     *
     * @property reason Optional reason why availability couldn't be determined
     * @property fallbackHint Whether a fallback stitch might work for this contact
     */
    data class Unknown(
        val reason: String? = null,
        val fallbackHint: Boolean = false
    ) : ContactAvailability()

    /**
     * The identifier type is not supported by this Stitch.
     * (e.g., checking email availability on SMS Stitch)
     */
    data object UnsupportedIdentifierType : ContactAvailability()

    /** Returns true if the contact is definitely reachable. */
    val isAvailable: Boolean get() = this is Available

    /** Returns true if the contact is definitely NOT reachable. */
    val isUnavailable: Boolean get() = this is Unavailable
}
