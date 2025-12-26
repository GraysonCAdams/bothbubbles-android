package com.bothbubbles.seam.hems.eta

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
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
 * EtaFeature provides automatic ETA sharing functionality that works across
 * all messaging platforms (both iMessage and SMS).
 *
 * This Feature (Hem) integrates with Google Maps and Waze navigation apps
 * to automatically detect when the user is navigating, extract ETA information
 * from notifications, and allow sharing arrival times with contacts.
 *
 * User-facing name: "ETA Sharing"
 * Code name: "EtaFeature" (implements Feature interface)
 *
 * Key capabilities:
 * - Monitors Google Maps and Waze notifications for ETA data
 * - Extracts destination information via AccessibilityService (optional)
 * - Automatically shares ETA with configured contacts when navigation starts
 * - Sends updates when arrival time changes significantly
 * - Sends "arriving soon" message when within threshold
 *
 * The feature uses:
 * - NavigationListenerService (NotificationListenerService) to detect navigation
 * - NavigationAccessibilityService (optional) for destination scraping
 * - EtaSharingManager to coordinate sharing logic
 * - DrivingStateTracker to detect when user is actively driving
 *
 * Settings are accessible via the ETA Sharing settings screen.
 */
@Singleton
class EtaFeature @Inject constructor(
    private val featurePreferences: FeaturePreferences,
    @ApplicationScope private val applicationScope: CoroutineScope
) : Feature {

    companion object {
        const val ID = "eta_sharing"
        const val DISPLAY_NAME = "ETA sharing"
        const val DESCRIPTION = "Auto-share arrival times from Google Maps and Waze"
        const val FEATURE_FLAG_KEY = "eta_sharing_enabled"
        const val SETTINGS_ROUTE = "settings/eta"
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val description: String = DESCRIPTION
    override val featureFlagKey: String = FEATURE_FLAG_KEY

    override val isEnabled: StateFlow<Boolean> =
        featurePreferences.etaSharingEnabled.stateIn(
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
                subtitle = "Share arrival time while navigating",
                icon = Icons.Outlined.Navigation,
                iconTint = SettingsIconColors.Location,
                section = SettingsSection.SHARING,
                route = SETTINGS_ROUTE,
                enabled = true
            )
        )

    override suspend fun onEnable() {
        // Called when feature is enabled via settings
        // The actual enabling logic is handled by FeaturePreferences.setEtaSharingEnabled()
        // which updates the etaSharingEnabled preference
        //
        // EtaSharingManager observes this preference and starts monitoring
        // navigation notifications when enabled
    }

    override suspend fun onDisable() {
        // Called when feature is disabled via settings
        // The actual disabling logic is handled by FeaturePreferences.setEtaSharingEnabled(false)
        //
        // This will stop EtaSharingManager from monitoring navigation notifications
        // and prevent any automatic sharing sessions from starting
        //
        // Note: Any active sharing sessions are ended by NavigationListenerService
        // when it detects the navigation notification is removed
    }
}
