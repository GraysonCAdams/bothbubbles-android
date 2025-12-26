package com.bothbubbles.seam.stitches

import com.bothbubbles.seam.hems.autoresponder.AutoResponderQuickAddExample
import com.bothbubbles.seam.settings.SettingsContribution
import kotlinx.coroutines.flow.StateFlow

/**
 * A Stitch represents a messaging platform integration.
 *
 * Stitches are the "pipes" that connect BothBubbles to external messaging services.
 * Examples: BlueBubbles/iMessage, SMS/MMS.
 *
 * ## Settings Integration
 * Stitches can contribute to the Settings screen in two ways:
 * 1. **Dedicated settings page**: A menu item that navigates to a full settings screen
 * 2. **Additional items**: Inject settings into existing sections
 *
 * Override [settingsContribution] to customize. The default provides a dedicated
 * menu item in the Connectivity section if [settingsRoute] is non-null.
 *
 * @see SettingsContribution
 */
interface Stitch {
    val id: String
    val displayName: String
    val iconResId: Int

    /**
     * Primary chat GUID prefix for this Stitch.
     *
     * Used for simple single-prefix matching. For Stitches that handle multiple
     * prefixes (e.g., SMS handles both "sms;-;" and "mms;-;"), override
     * [matchesChatGuid] instead.
     */
    val chatGuidPrefix: String?

    val connectionState: StateFlow<StitchConnectionState>
    val isEnabled: StateFlow<Boolean>
    val capabilities: StitchCapabilities

    /**
     * Checks if this Stitch handles chats with the given GUID.
     *
     * Default implementation checks if the GUID starts with [chatGuidPrefix].
     * Override this for Stitches that handle multiple prefixes.
     *
     * @param chatGuid The chat GUID to check
     * @return true if this Stitch handles the chat
     */
    fun matchesChatGuid(chatGuid: String): Boolean {
        return chatGuidPrefix?.let { chatGuid.startsWith(it) } ?: false
    }

    suspend fun initialize()
    suspend fun teardown()

    /**
     * Navigation route for the dedicated settings page.
     *
     * @deprecated Use [settingsContribution] instead for full control over settings integration.
     * This property is kept for backward compatibility and will be used to auto-generate
     * a basic [SettingsContribution] if [settingsContribution] is not overridden.
     */
    val settingsRoute: String?

    /**
     * Settings contribution for this Stitch.
     *
     * Override this to customize how this Stitch appears in Settings and
     * to inject additional settings items into existing sections.
     *
     * Default implementation returns [SettingsContribution.NONE].
     * Implementations should override this to provide their settings.
     */
    val settingsContribution: SettingsContribution
        get() = SettingsContribution.NONE

    /**
     * Quick-add example for auto-responder rules.
     *
     * Stitches can provide a single example that users can quickly add
     * when setting up auto-responder rules for this platform.
     *
     * Return null if this Stitch doesn't provide an example.
     */
    val autoResponderQuickAddExample: AutoResponderQuickAddExample?
        get() = null

    // ===== Contact Availability =====

    /**
     * Identifier types this Stitch can handle.
     *
     * Used to determine which Stitches can potentially reach a contact.
     *
     * Examples:
     * - SMS: [PHONE_NUMBER]
     * - BlueBubbles/iMessage: [PHONE_NUMBER, EMAIL]
     * - Signal: [PHONE_NUMBER, SIGNAL_ID]
     * - Discord: [DISCORD_USERNAME]
     */
    val supportedIdentifierTypes: Set<ContactIdentifierType>
        get() = emptySet()

    /**
     * Default bubble color for this Stitch (ARGB format).
     *
     * This is the brand color for the platform (e.g., blue for iMessage, green for SMS).
     * Users can override this with a custom color in settings.
     */
    val defaultBubbleColor: Long
        get() = 0xFF6750A4  // MD3 primary purple as fallback

    /**
     * Check if this Stitch can reach a contact.
     *
     * Implementation guidelines:
     * - MUST be fast for synchronous checks (< 10ms)
     * - MUST respect [options.timeoutMs] for async checks
     * - SHOULD cache results for repeated queries
     * - SHOULD return [ContactAvailability.Unknown] with [fallbackHint] when server unavailable
     *
     * @param identifier The contact identifier to check
     * @param options Configuration for the check
     * @return Availability result
     */
    suspend fun checkContactAvailability(
        identifier: ContactIdentifier,
        options: AvailabilityCheckOptions = AvailabilityCheckOptions()
    ): ContactAvailability = ContactAvailability.UnsupportedIdentifierType

    /**
     * Priority for auto-selection when multiple Stitches can reach a contact.
     * Higher value = higher priority.
     *
     * Default priorities:
     * - BlueBubbles (iMessage): 100 (prefer iMessage features)
     * - SMS: 50 (reliable fallback)
     * - Future platforms: 60-90 (based on feature richness)
     *
     * This is the default priority; user's custom priority order takes precedence.
     */
    val contactPriority: Int
        get() = 50
}
