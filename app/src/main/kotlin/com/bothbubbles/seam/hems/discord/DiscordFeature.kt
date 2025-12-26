package com.bothbubbles.seam.hems.discord

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.seam.hems.Feature
import com.bothbubbles.seam.settings.DedicatedSettingsMenuItem
import com.bothbubbles.seam.settings.SettingsContribution
import com.bothbubbles.seam.settings.SettingsSection
import com.bothbubbles.ui.settings.components.SettingsIconColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DiscordFeature provides Discord calling/messaging integration for contacts.
 *
 * This Feature (Hem) enhances existing contacts with Discord channel IDs,
 * enabling quick access to Discord DMs directly from the chat interface.
 *
 * Features:
 * - Store Discord channel IDs in device contacts (syncs via Google Contacts)
 * - Show Discord call option in conversation details
 * - Quick-dial Discord DMs from chat headers
 *
 * Discord is a FEATURE (not a Stitch) because:
 * - It works across ALL messaging platforms (iMessage, SMS)
 * - It enriches existing conversations with Discord calling capability
 * - It doesn't send/receive messages through Discord within BothBubbles
 *
 * User-facing name: "Discord Integration"
 * Code name: "DiscordFeature" (implements Feature interface)
 */
@Singleton
class DiscordFeature @Inject constructor(
    private val featurePreferences: FeaturePreferences,
    @ApplicationScope private val applicationScope: CoroutineScope
) : Feature {

    companion object {
        const val ID = "discord"
        const val DISPLAY_NAME = "Discord"
        const val DESCRIPTION = "Link Discord channels to contacts for quick calling"
        const val FEATURE_FLAG_KEY = "discord_enabled"
        const val SETTINGS_ROUTE = "settings/discord"
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val description: String = DESCRIPTION
    override val featureFlagKey: String = FEATURE_FLAG_KEY

    override val isEnabled: StateFlow<Boolean> =
        featurePreferences.discordEnabled.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    @Deprecated("Use settingsContribution instead", ReplaceWith("settingsContribution"))
    override val settingsRoute: String = SETTINGS_ROUTE

    override val settingsContribution: SettingsContribution
        get() = SettingsContribution(
            dedicatedMenuItem = DedicatedSettingsMenuItem(
                id = ID,
                title = DISPLAY_NAME,
                subtitle = "Quick call contacts via Discord",
                icon = Icons.Outlined.Headphones,
                iconTint = SettingsIconColors.Appearance,
                section = SettingsSection.SHARING,
                route = SETTINGS_ROUTE,
                enabled = true
            )
        )

    override suspend fun onEnable() {
        Timber.d("DiscordFeature enabled")
        // Discord integration is passive - it just makes the channel ID
        // storage and lookup available to the UI layer
    }

    override suspend fun onDisable() {
        Timber.d("DiscordFeature disabled")
        // When disabled, Discord options are hidden from UI
        // Stored channel IDs remain in contacts for re-enable
    }
}
