package com.bothbubbles.seam.stitches.sms

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import com.bothbubbles.seam.settings.BadgeStatus
import com.bothbubbles.seam.settings.DedicatedSettingsMenuItem
import com.bothbubbles.seam.settings.SettingsBadge
import com.bothbubbles.seam.settings.SettingsContribution
import com.bothbubbles.seam.settings.SettingsSection
import com.bothbubbles.seam.stitches.AvailabilityCheckOptions
import com.bothbubbles.seam.stitches.AvailabilityConfidence
import com.bothbubbles.seam.stitches.ContactAvailability
import com.bothbubbles.seam.stitches.ContactIdentifier
import com.bothbubbles.seam.stitches.ContactIdentifierType
import com.bothbubbles.seam.stitches.Stitch
import com.bothbubbles.seam.stitches.StitchCapabilities
import com.bothbubbles.seam.stitches.StitchConnectionState
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.ui.settings.components.SettingsIconColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SmsStitch wraps the existing Android SMS/MMS functionality.
 *
 * This Stitch is "connected" when the app is the default SMS app,
 * since SMS/MMS functionality requires that permission on Android.
 *
 * The Stitch WRAPS existing services - it does not replace them.
 */
@Singleton
class SmsStitch @Inject constructor(
    private val smsPermissionHelper: SmsPermissionHelper
) : Stitch {

    companion object {
        const val ID = "sms"
        const val DISPLAY_NAME = "SMS/MMS"
        const val CHAT_GUID_PREFIX_SMS = "sms;-;"
        const val CHAT_GUID_PREFIX_MMS = "mms;-;"
        const val SETTINGS_ROUTE = "sms_settings"
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val iconResId: Int = android.R.drawable.sym_action_call
    override val chatGuidPrefix: String? = CHAT_GUID_PREFIX_SMS

    override val capabilities: StitchCapabilities = StitchCapabilities.SMS

    private val _connectionState = MutableStateFlow<StitchConnectionState>(StitchConnectionState.NotConfigured)
    override val connectionState: StateFlow<StitchConnectionState> = _connectionState.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    @Deprecated("Use settingsContribution instead", ReplaceWith("settingsContribution"))
    override val settingsRoute: String = SETTINGS_ROUTE

    override val settingsContribution: SettingsContribution
        get() {
            val currentState = connectionState.value
            val badge = when (currentState) {
                is StitchConnectionState.Connected -> SettingsBadge.Status(BadgeStatus.CONNECTED)
                is StitchConnectionState.NotConfigured -> SettingsBadge.Status(BadgeStatus.DISABLED)
                else -> SettingsBadge.Status(BadgeStatus.ERROR)
            }

            return SettingsContribution(
                dedicatedMenuItem = DedicatedSettingsMenuItem(
                    id = ID,
                    title = DISPLAY_NAME,
                    subtitle = "Local SMS messaging options",
                    icon = Icons.Default.CellTower,
                    iconTint = SettingsIconColors.Connectivity,
                    section = SettingsSection.CONNECTIVITY,
                    route = SETTINGS_ROUTE,
                    enabled = true,
                    badge = badge
                )
            )
        }

    override suspend fun initialize() {
        updateConnectionState()
    }

    override suspend fun teardown() {
        // SMS doesn't need cleanup - it's always available when enabled
    }

    private fun updateConnectionState() {
        val isDefaultApp = smsPermissionHelper.isDefaultSmsApp()

        if (isDefaultApp) {
            _connectionState.value = StitchConnectionState.Connected
            _isEnabled.value = true
        } else {
            _connectionState.value = StitchConnectionState.NotConfigured
            _isEnabled.value = false
        }
    }

    /**
     * Matches both sms;-; and mms;-; prefixes.
     */
    override fun matchesChatGuid(chatGuid: String): Boolean {
        return chatGuid.startsWith(CHAT_GUID_PREFIX_SMS) ||
               chatGuid.startsWith(CHAT_GUID_PREFIX_MMS)
    }

    // ===== Contact Availability =====

    override val supportedIdentifierTypes: Set<ContactIdentifierType> =
        setOf(ContactIdentifierType.PHONE_NUMBER)

    override val defaultBubbleColor: Long = 0xFF34C759  // iOS SMS green

    override val contactPriority: Int = 50  // Lower than iMessage

    override suspend fun checkContactAvailability(
        identifier: ContactIdentifier,
        options: AvailabilityCheckOptions
    ): ContactAvailability {
        // SMS only supports phone numbers
        if (identifier.type != ContactIdentifierType.PHONE_NUMBER) {
            return ContactAvailability.UnsupportedIdentifierType
        }

        // Check if SMS is functional (app is default SMS app)
        if (connectionState.value != StitchConnectionState.Connected) {
            return ContactAvailability.Unknown(
                reason = "SMS not available - app is not default SMS app",
                fallbackHint = true  // Phone numbers are always reachable in principle
            )
        }

        // All phone numbers are reachable via SMS when we're the default app
        return ContactAvailability.Available(
            confidence = AvailabilityConfidence.HIGH
        )
    }
}
