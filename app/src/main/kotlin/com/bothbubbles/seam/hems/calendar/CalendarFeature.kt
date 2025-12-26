package com.bothbubbles.seam.hems.calendar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
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
 * CalendarFeature provides calendar event integration for contacts.
 *
 * This Feature (Hem) enriches chat conversations by showing the current or
 * upcoming calendar events of contacts in the chat header. It helps users
 * know if someone is busy before messaging.
 *
 * Features:
 * - Associate device calendars with contacts
 * - Show current/upcoming events in chat headers
 * - Display events up to 4 hours in advance
 * - Tap to open event in device calendar app
 *
 * Calendar is a FEATURE (not a Stitch) because:
 * - It works across ALL messaging platforms (iMessage, SMS)
 * - It enriches existing conversations with calendar context
 * - It doesn't send/receive messages itself
 *
 * User-facing name: "Calendar Integration"
 * Code name: "CalendarFeature" (implements Feature interface)
 */
@Singleton
class CalendarFeature @Inject constructor(
    private val featurePreferences: FeaturePreferences,
    @ApplicationScope private val applicationScope: CoroutineScope
) : Feature {

    companion object {
        const val ID = "calendar"
        const val DISPLAY_NAME = "Calendar"
        const val DESCRIPTION = "Show contact calendar events in chat headers"
        const val FEATURE_FLAG_KEY = "calendar_enabled"
        const val SETTINGS_ROUTE = "settings/calendar"
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val description: String = DESCRIPTION
    override val featureFlagKey: String = FEATURE_FLAG_KEY

    override val isEnabled: StateFlow<Boolean> =
        featurePreferences.calendarEnabled.stateIn(
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
                subtitle = "See when contacts are busy",
                icon = Icons.Outlined.CalendarMonth,
                iconTint = SettingsIconColors.Location,
                section = SettingsSection.SHARING,
                route = SETTINGS_ROUTE,
                enabled = true
            )
        )

    override suspend fun onEnable() {
        Timber.d("CalendarFeature enabled")
        // Calendar integration is activated - header integration and
        // sync workers will check this state before operating
    }

    override suspend fun onDisable() {
        Timber.d("CalendarFeature disabled")
        // When disabled, calendar content is hidden from headers
        // Calendar associations remain for re-enable
    }
}
