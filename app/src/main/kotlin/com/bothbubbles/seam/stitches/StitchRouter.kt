package com.bothbubbles.seam.stitches

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes messages to the appropriate Stitch based on chat GUID, capabilities, and connection state.
 *
 * The router provides intelligent stitch selection:
 * - [getStitchForChat] - Simple lookup by chat GUID
 * - [getConnectedStitchForChat] - Only returns if the stitch is connected
 * - [getStitchWithCapability] - Find any stitch supporting a specific capability
 * - [getConnectedStitches] - All currently connected stitches
 * - [canPerformAction] - Check if an action is possible for a chat
 */
@Singleton
class StitchRouter @Inject constructor(
    private val registry: StitchRegistry
) {
    /**
     * Gets the Stitch that handles the given chat GUID.
     * Does not check connection state - use [getConnectedStitchForChat] for that.
     */
    fun getStitchForChat(chatGuid: String): Stitch? = registry.getStitchForChat(chatGuid)

    /**
     * Gets the Stitch for a chat only if it's currently connected.
     * Returns null if the stitch exists but is disconnected.
     */
    fun getConnectedStitchForChat(chatGuid: String): Stitch? {
        val stitch = registry.getStitchForChat(chatGuid) ?: return null
        return if (stitch.connectionState.value == StitchConnectionState.Connected) stitch else null
    }

    /**
     * Gets all currently connected Stitches.
     */
    fun getConnectedStitches(): List<Stitch> {
        return registry.getEnabledStitches().filter {
            it.connectionState.value == StitchConnectionState.Connected
        }
    }

    /**
     * Finds any connected Stitch that supports the given capability.
     *
     * @param requirement A function that checks if capabilities meet the requirement
     * @return The first connected stitch matching the requirement, or null
     */
    fun getStitchWithCapability(requirement: (StitchCapabilities) -> Boolean): Stitch? {
        return getConnectedStitches().find { requirement(it.capabilities) }
    }

    /**
     * Gets all Stitches (connected or not) that support the given capability.
     */
    fun getStitchesWithCapability(requirement: (StitchCapabilities) -> Boolean): List<Stitch> {
        return registry.getEnabledStitches().filter { requirement(it.capabilities) }
    }

    /**
     * Checks if a specific action can be performed on a chat.
     *
     * @param chatGuid The chat to check
     * @param capabilityCheck A function that checks if the capability exists
     * @return true if the chat's stitch is connected and supports the capability
     */
    fun canPerformAction(chatGuid: String, capabilityCheck: (StitchCapabilities) -> Boolean): Boolean {
        val stitch = getConnectedStitchForChat(chatGuid) ?: return false
        return capabilityCheck(stitch.capabilities)
    }

    /**
     * Checks if reactions are supported for a chat.
     */
    fun canReact(chatGuid: String): Boolean = canPerformAction(chatGuid) { it.supportsReactions }

    /**
     * Checks if message editing is supported for a chat.
     */
    fun canEdit(chatGuid: String): Boolean = canPerformAction(chatGuid) { it.supportsMessageEditing }

    /**
     * Checks if message unsend is supported for a chat.
     */
    fun canUnsend(chatGuid: String): Boolean = canPerformAction(chatGuid) { it.supportsMessageUnsend }

    /**
     * Checks if replies are supported for a chat.
     */
    fun canReply(chatGuid: String): Boolean = canPerformAction(chatGuid) { it.supportsReplies }

    /**
     * Checks if typing indicators are supported for a chat.
     */
    fun canSendTypingIndicator(chatGuid: String): Boolean =
        canPerformAction(chatGuid) { it.supportsTypingIndicators }

    /**
     * Checks if message effects (e.g., slam, loud) are supported for a chat.
     */
    fun canSendWithEffect(chatGuid: String): Boolean =
        canPerformAction(chatGuid) { it.supportsMessageEffects }

    /**
     * Gets the maximum attachment size for a chat, or null if unlimited.
     */
    fun getMaxAttachmentSize(chatGuid: String): Long? {
        return getStitchForChat(chatGuid)?.capabilities?.maxAttachmentSize
    }

    /**
     * Checks if a MIME type is supported for attachments in a chat.
     *
     * @param chatGuid The chat to check
     * @param mimeType The MIME type to check (e.g., "image/heic")
     * @return true if supported, or if the stitch has no restrictions (null supportedMimeTypes)
     */
    fun isMimeTypeSupported(chatGuid: String, mimeType: String): Boolean {
        val capabilities = getStitchForChat(chatGuid)?.capabilities ?: return false
        val supportedTypes = capabilities.supportedMimeTypes ?: return true // null = all supported
        return supportedTypes.any { supported ->
            mimeType.equals(supported, ignoreCase = true) ||
                mimeType.startsWith(supported.removeSuffix("*"), ignoreCase = true)
        }
    }

    // ===== Contact-Based Routing =====

    /**
     * Gets the best Stitch for reaching a contact.
     *
     * Considers:
     * 1. User-defined priority order
     * 2. Contact availability (which Stitches can reach this contact)
     * 3. Connection state (prefer connected Stitches)
     *
     * @param identifier The contact identifier
     * @param priorityOrder User-defined priority order
     * @param options Availability check options
     * @return The best Stitch selection, or null if no Stitches can reach the contact
     */
    suspend fun getBestStitchForContact(
        identifier: ContactIdentifier,
        priorityOrder: List<String>,
        options: AvailabilityCheckOptions = AvailabilityCheckOptions()
    ): StitchSelection? {
        val eligibleStitches = registry.getStitchesForIdentifierType(
            identifier.type,
            priorityOrder
        ).filter { it.isEnabled.value }

        if (eligibleStitches.isEmpty()) return null

        var bestUnknown: Pair<Stitch, ContactAvailability.Unknown>? = null

        for (stitch in eligibleStitches) {
            when (val availability = stitch.checkContactAvailability(identifier, options)) {
                is ContactAvailability.Available -> {
                    return StitchSelection(
                        stitch = stitch,
                        availability = availability,
                        reason = SelectionReason.AVAILABLE
                    )
                }
                is ContactAvailability.Unknown -> {
                    // Keep track of the best Unknown result (with positive fallback hint)
                    if (bestUnknown == null && availability.fallbackHint == true) {
                        bestUnknown = stitch to availability
                    }
                }
                is ContactAvailability.Unavailable,
                ContactAvailability.UnsupportedIdentifierType -> {
                    // Skip these
                }
            }
        }

        // Use Unknown with positive fallback hint as last resort
        bestUnknown?.let { (stitch, availability) ->
            return StitchSelection(
                stitch = stitch,
                availability = availability,
                reason = SelectionReason.FALLBACK_HINT
            )
        }

        return null
    }

    /**
     * Gets all Stitches that can potentially reach a contact, with their availability status.
     *
     * @param identifier The contact identifier
     * @param priorityOrder User-defined priority order
     * @param options Availability check options
     * @return List of Stitches with their availability, sorted by priority
     */
    suspend fun getStitchesForContact(
        identifier: ContactIdentifier,
        priorityOrder: List<String>,
        options: AvailabilityCheckOptions = AvailabilityCheckOptions()
    ): List<StitchWithAvailability> {
        return registry.getStitchesForIdentifierType(identifier.type, priorityOrder)
            .filter { it.isEnabled.value }
            .map { stitch ->
                StitchWithAvailability(
                    stitch = stitch,
                    availability = stitch.checkContactAvailability(identifier, options)
                )
            }
    }
}

/**
 * Result of selecting a Stitch for a contact.
 */
data class StitchSelection(
    val stitch: Stitch,
    val availability: ContactAvailability,
    val reason: SelectionReason
)

/**
 * Why a particular Stitch was selected.
 */
enum class SelectionReason {
    /**
     * Stitch confirmed it can reach the contact.
     */
    AVAILABLE,

    /**
     * Stitch is uncertain but has a positive fallback hint.
     */
    FALLBACK_HINT
}

/**
 * A Stitch paired with its availability check result.
 */
data class StitchWithAvailability(
    val stitch: Stitch,
    val availability: ContactAvailability
)
