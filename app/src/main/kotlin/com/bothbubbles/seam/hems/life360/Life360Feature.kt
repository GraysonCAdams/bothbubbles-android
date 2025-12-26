package com.bothbubbles.seam.hems.life360

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.seam.hems.Feature
import com.bothbubbles.seam.settings.DedicatedSettingsMenuItem
import com.bothbubbles.seam.settings.SettingsContribution
import com.bothbubbles.seam.settings.SettingsSection
import com.bothbubbles.services.life360.Life360Service
import com.bothbubbles.ui.settings.components.SettingsIconColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Life360Feature provides location sharing integration across all messaging platforms.
 *
 * This Feature (Hem) enhances existing iMessage/SMS chats with real-time location data
 * from Life360 circles. It does NOT provide messaging capabilities itself, rather it:
 * - Shows contact locations in chat headers
 * - Displays location maps in conversation details
 * - Auto-links Life360 members to BothBubbles contacts by phone number
 *
 * Life360 is a FEATURE (not a Stitch) because:
 * - It works across ALL messaging platforms (iMessage, SMS)
 * - It enriches existing conversations with location context
 * - It doesn't send/receive messages through Life360
 *
 * Connection state lifecycle:
 * - Authenticated when user has logged in and stored an access token
 * - Can be paused via "ghost mode" without logging out
 * - Rate-limited to comply with Life360 API restrictions
 *
 * User-facing name: "Life360 Integration"
 * Code name: "Life360Feature" (implements Feature interface)
 */
@Singleton
class Life360Feature @Inject constructor(
    private val life360Service: Life360Service,
    private val featurePreferences: FeaturePreferences,
    @ApplicationScope private val applicationScope: CoroutineScope
) : Feature {

    companion object {
        const val ID = "life360"
        const val DISPLAY_NAME = "Life360"
        const val DESCRIPTION = "Show contact locations from Life360 circles in your chats"
        const val FEATURE_FLAG_KEY = "life360_enabled"
        const val SETTINGS_ROUTE = "settings/life360"
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val description: String = DESCRIPTION
    override val featureFlagKey: String = FEATURE_FLAG_KEY

    /**
     * Feature is enabled when:
     * 1. User has toggled Life360 integration on
     * 2. User is authenticated with Life360
     * 3. Syncing is not paused (ghost mode)
     */
    override val isEnabled: StateFlow<Boolean> =
        featurePreferences.life360Enabled.stateIn(
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
                subtitle = "Show friends and family locations",
                icon = Icons.Outlined.LocationOn,
                iconTint = SettingsIconColors.Location,
                section = SettingsSection.SHARING,
                route = SETTINGS_ROUTE,
                enabled = true
            )
        )

    override suspend fun onEnable() {
        // Called when feature is enabled
        Timber.d("Life360Feature enabled")

        // The actual enabling logic (setting preferences, starting sync worker)
        // is handled by Life360SettingsViewModel and Life360Service
        // This hook is just for observability
    }

    override suspend fun onDisable() {
        // Called when feature is disabled
        Timber.d("Life360Feature disabled")

        // When disabled, we keep the auth token and local data
        // User can re-enable without logging in again
        // To fully clear data, they must use "Logout" in settings
    }
}
