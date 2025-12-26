package com.bothbubbles.seam.hems

import com.bothbubbles.seam.settings.SettingsContribution
import kotlinx.coroutines.flow.StateFlow

/**
 * A Feature (Hem) represents a cross-platform enhancement.
 *
 * Features work across ALL enabled Stitches to provide additional functionality.
 * Examples: Reels feed, Life360 integration, ETA sharing.
 *
 * ## Settings Integration
 * Features can contribute to the Settings screen in two ways:
 * 1. **Dedicated settings page**: A menu item that navigates to a full settings screen
 * 2. **Additional items**: Inject settings into existing sections
 *
 * Override [settingsContribution] to customize. The default provides a dedicated
 * menu item in the appropriate section if [settingsRoute] is non-null.
 *
 * @see SettingsContribution
 */
interface Feature {
    val id: String
    val displayName: String
    val description: String
    val featureFlagKey: String

    val isEnabled: StateFlow<Boolean>

    /**
     * Navigation route for the dedicated settings page.
     *
     * @deprecated Use [settingsContribution] instead for full control over settings integration.
     * This property is kept for backward compatibility.
     */
    val settingsRoute: String?

    suspend fun onEnable()
    suspend fun onDisable()

    /**
     * Settings contribution for this Feature.
     *
     * Override this to customize how this Feature appears in Settings and
     * to inject additional settings items into existing sections.
     *
     * Default implementation returns [SettingsContribution.NONE].
     * Implementations should override this to provide their settings.
     */
    val settingsContribution: SettingsContribution
        get() = SettingsContribution.NONE
}
