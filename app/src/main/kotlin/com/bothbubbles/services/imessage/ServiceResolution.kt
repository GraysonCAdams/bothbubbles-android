package com.bothbubbles.services.imessage

/**
 * Result of resolving an address to a messaging service (iMessage or SMS).
 *
 * Used by [AddressServiceResolver] to provide consistent service determination
 * across all chat creation and compose flows.
 */
sealed class ServiceResolution {
    /**
     * Successfully resolved the service for the address.
     *
     * @property service The determined messaging service
     * @property source Where this resolution came from (for debugging/logging)
     * @property confidence How confident we are in this resolution
     */
    data class Resolved(
        val service: MessagingService,
        val source: ResolutionSource,
        val confidence: ResolutionConfidence = ResolutionConfidence.HIGH
    ) : ServiceResolution()

    /**
     * The address is invalid and cannot be resolved.
     *
     * @property reason Human-readable reason for the invalidity
     */
    data class Invalid(val reason: String) : ServiceResolution()

    /**
     * Resolution is pending - an async check is in progress.
     * The caller should use the [initialService] until resolution completes.
     *
     * @property initialService The service to use while awaiting resolution
     */
    data class Pending(val initialService: MessagingService) : ServiceResolution()
}

/**
 * The messaging service to use for sending.
 */
enum class MessagingService {
    /** Apple iMessage via BlueBubbles server */
    IMESSAGE,

    /** Local SMS/MMS via device carrier */
    SMS
}

/**
 * Where the service resolution came from.
 * Useful for debugging and understanding fallback behavior.
 */
enum class ResolutionSource {
    /** Email addresses are always iMessage (rule-based, no lookup) */
    EMAIL_RULE,

    /** User has SMS-only mode enabled in settings */
    SMS_ONLY_MODE,

    /** Fresh cache hit (within TTL) */
    CACHE,

    /** Local handle database has this address marked as iMessage */
    LOCAL_HANDLE,

    /** Server API confirmed availability */
    SERVER_API,

    /** Using expired cache as fallback (server unavailable) */
    STALE_CACHE,

    /** Based on most recent chat activity with this address */
    ACTIVITY_HISTORY,

    /** No data available, using default (SMS for phones) */
    DEFAULT
}

/**
 * Confidence level of the resolution.
 */
enum class ResolutionConfidence {
    /** Direct server confirmation or email rule */
    HIGH,

    /** Cache hit or local handle lookup */
    MEDIUM,

    /** Stale cache, activity history, or default fallback */
    LOW
}

/**
 * Options for service resolution behavior.
 */
data class ResolutionOptions(
    /**
     * Force a fresh server check, bypassing cache.
     * Use sparingly - adds latency and server load.
     */
    val forceRecheck: Boolean = false,

    /**
     * Timeout for server API check in milliseconds.
     * Default: 3000ms (3 seconds)
     */
    val timeoutMs: Long = 3000L,

    /**
     * Allow using expired cache entries as fallback when server is unavailable.
     * Default: true
     */
    val allowStaleCache: Boolean = true,

    /**
     * Require server connection to be stable before trusting iMessage availability.
     * Used by ChatSendModeManager for mode switching.
     * Default: false
     */
    val requireServerStability: Boolean = false
)
