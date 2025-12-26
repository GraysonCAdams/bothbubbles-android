package com.bothbubbles.seam.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Defines what settings a Stitch or Feature can contribute to the Settings screen.
 *
 * ## Design Principles
 * - Each Stitch/Feature can have at most ONE dedicated settings menu item
 * - Additional items can be injected into existing sections
 * - All contributions are optional - null/empty means no contribution
 *
 * ## Usage
 * Stitches and Features implement [settingsContribution] to declare their settings.
 * The SettingsScreen collects all contributions and renders them in the appropriate sections.
 *
 * @property dedicatedMenuItem Optional dedicated settings page menu item (appears in its own section)
 * @property additionalItems Items to inject into existing settings sections (keyed by section ID)
 */
data class SettingsContribution(
    val dedicatedMenuItem: DedicatedSettingsMenuItem? = null,
    val additionalItems: Map<SettingsSection, List<SettingsItem>> = emptyMap()
) {
    companion object {
        /** No settings contribution - use for Stitches/Features without settings */
        val NONE = SettingsContribution()
    }
}

/**
 * A dedicated menu item that appears as its own entry in the settings screen.
 *
 * Stitches/Features with complex configuration should use this to provide
 * a dedicated settings page accessible from the main settings screen.
 *
 * @property id Unique identifier for this menu item
 * @property title Display title for the menu item
 * @property subtitle Optional subtitle/description
 * @property icon Icon to display
 * @property iconTint Background color for the icon circle
 * @property section Which settings section this item should appear in
 * @property route Navigation route for the dedicated settings page
 * @property enabled Whether this item is currently enabled/clickable
 * @property badge Optional badge content (e.g., connection status, count)
 */
data class DedicatedSettingsMenuItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val iconTint: Color,
    val section: SettingsSection,
    val route: String,
    val enabled: Boolean = true,
    val badge: SettingsBadge? = null
)

/**
 * A settings item to inject into an existing settings section.
 *
 * Use this for small toggles or settings that logically belong
 * in an existing section rather than needing a dedicated page.
 *
 * @property id Unique identifier for this item
 * @property title Display title
 * @property subtitle Optional subtitle
 * @property icon Icon to display
 * @property iconTint Background color for the icon circle
 * @property priority Lower values appear first within the section (default 100)
 * @property content Composable content for this settings item
 */
data class SettingsItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val iconTint: Color,
    val priority: Int = 100,
    val content: @Composable (onClick: () -> Unit) -> Unit
)

/**
 * Predefined settings sections where items can be contributed.
 *
 * These match the sections in SettingsScreen.kt.
 */
enum class SettingsSection(val displayName: String) {
    /** Connectivity: iMessage, SMS, sync settings */
    CONNECTIVITY("Connectivity"),

    /** Notifications: alerts, sounds, vibration */
    NOTIFICATIONS("Notifications & alerts"),

    /** Appearance: effects, swipe, haptics, visual customization */
    APPEARANCE("Appearance & interaction"),

    /** Messaging: templates, auto-responder, media */
    MESSAGING("Messaging"),

    /** Sharing: ETA, Life360, calendar integrations */
    SHARING("Sharing & Social"),

    /** Privacy: spam, blocked, categorization, archive */
    PRIVACY("Privacy & permissions"),

    /** Data: storage, export */
    DATA("Data & backup"),

    /** About: version, licenses */
    ABOUT("About")
}

/**
 * Optional badge to display on a settings menu item.
 *
 * Badges can indicate status (connected/error) or counts.
 */
sealed class SettingsBadge {
    /** Status badge with color indicator */
    data class Status(val status: BadgeStatus) : SettingsBadge()

    /** Count badge showing a number */
    data class Count(val count: Int) : SettingsBadge()
}

/**
 * Status types for badge display.
 * Matches the existing BadgeStatus enum in SettingsComponents.kt.
 */
enum class BadgeStatus {
    CONNECTED,
    ERROR,
    DISABLED
}
