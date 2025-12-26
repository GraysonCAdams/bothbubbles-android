package com.bothbubbles.seam.hems.autoresponder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AutoResponderFeature provides rule-based automatic message responses.
 *
 * This Feature (Hem) allows users to create custom auto-response rules with
 * various trigger conditions:
 * - **Source filtering**: Which Stitches (SMS, iMessage) trigger the response
 * - **First-time sender**: Only respond to new senders
 * - **Time-based**: Day of week and time range conditions
 * - **System state**: DND mode, Android Auto, phone call state
 * - **Location**: Geofence-based conditions (at/away from a location)
 *
 * Rules are evaluated in priority order - the first matching rule's message is sent.
 *
 * User-facing name: "Auto-Responder"
 * Code name: "AutoResponderFeature" (implements Feature interface)
 *
 * The feature uses:
 * - AutoResponderRuleEngine to evaluate rules
 * - AutoResponderConditionEvaluator for individual condition checks
 * - System context providers for DND, call, driving, and location state
 *
 * Settings are accessible via the Auto-Responder settings screen.
 */
@Singleton
class AutoResponderFeature @Inject constructor(
    private val featurePreferences: FeaturePreferences,
    @ApplicationScope private val applicationScope: CoroutineScope
) : Feature {

    companion object {
        const val ID = "auto_responder"
        const val DISPLAY_NAME = "Auto-Responder"
        const val DESCRIPTION = "Send automatic replies based on customizable rules"
        const val FEATURE_FLAG_KEY = "auto_responder_enabled"
        const val SETTINGS_ROUTE = "settings/auto_responder"
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val description: String = DESCRIPTION
    override val featureFlagKey: String = FEATURE_FLAG_KEY

    override val isEnabled: StateFlow<Boolean> =
        featurePreferences.autoResponderEnabled.stateIn(
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
                subtitle = "Automatic replies based on rules",
                icon = Icons.AutoMirrored.Outlined.Reply,
                iconTint = SettingsIconColors.Messaging,
                section = SettingsSection.MESSAGING,
                route = SETTINGS_ROUTE,
                enabled = true
            )
        )

    override suspend fun onEnable() {
        // Called when feature is enabled via settings
        // The AutoResponderService checks isEnabled preference on each message
        // No additional initialization needed here
    }

    override suspend fun onDisable() {
        // Called when feature is disabled via settings
        // AutoResponderService will stop processing when preference is false
        // No cleanup needed - rules persist in database for when re-enabled
    }
}
