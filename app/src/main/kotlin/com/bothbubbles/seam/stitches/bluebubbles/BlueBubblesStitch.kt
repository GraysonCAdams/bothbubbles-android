package com.bothbubbles.seam.stitches.bluebubbles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.core.data.prefs.ServerPreferences
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.seam.hems.autoresponder.AutoResponderQuickAddExample
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
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.settings.components.SettingsIconColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BlueBubblesStitch wraps the existing BlueBubbles server functionality.
 *
 * This provides iMessage support by connecting to a BlueBubbles server
 * running on a Mac.
 *
 * The Stitch WRAPS existing services - it does not replace them.
 */
@Singleton
class BlueBubblesStitch @Inject constructor(
    private val socketConnection: SocketConnection,
    private val serverPreferences: ServerPreferences,
    @ApplicationScope private val applicationScope: CoroutineScope
) : Stitch {

    companion object {
        const val ID = "bluebubbles"
        const val DISPLAY_NAME = "iMessage"
        const val CHAT_GUID_PREFIX = "iMessage;-;"
        const val SETTINGS_ROUTE = "server_settings"
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val iconResId: Int = android.R.drawable.sym_def_app_icon  // Temporary
    override val chatGuidPrefix: String = CHAT_GUID_PREFIX

    override val capabilities: StitchCapabilities = StitchCapabilities.BLUEBUBBLES

    /**
     * Maps the existing SocketService connection state to StitchConnectionState.
     */
    override val connectionState: StateFlow<StitchConnectionState> =
        socketConnection.connectionState.map { state ->
            when (state) {
                ConnectionState.NOT_CONFIGURED -> StitchConnectionState.NotConfigured
                ConnectionState.DISCONNECTED -> StitchConnectionState.Disconnected
                ConnectionState.CONNECTING -> StitchConnectionState.Connecting
                ConnectionState.CONNECTED -> StitchConnectionState.Connected
                ConnectionState.ERROR -> StitchConnectionState.Error("Connection error")
            }
        }.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = StitchConnectionState.Disconnected
        )

    /**
     * Enabled when server is configured (address is not blank).
     */
    override val isEnabled: StateFlow<Boolean> =
        serverPreferences.serverAddress.map { address ->
            address.isNotBlank()
        }.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

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
                    subtitle = "BlueBubbles server settings",
                    icon = Icons.Default.Cloud,
                    iconTint = SettingsIconColors.Connectivity,
                    section = SettingsSection.CONNECTIVITY,
                    route = SETTINGS_ROUTE,
                    enabled = true,
                    badge = badge
                )
            )
        }

    override val autoResponderQuickAddExample: AutoResponderQuickAddExample
        get() = AutoResponderQuickAddExample(
            name = "iMessage Introduction",
            message = "Hello, I am on BlueBubbles which lets me use iMessage on my Android. " +
                "Please add my iMessage address to my contact card so future messages " +
                "go through iMessage.",
            description = "Introduce yourself to new iMessage contacts"
        )

    override suspend fun initialize() {
        // The socket service handles its own initialization
        // This is called when the stitch is first enabled
    }

    override suspend fun teardown() {
        // Don't disconnect the socket here - it's managed by SocketService
        // This is called when the stitch is disabled
    }

    // ===== Contact Availability =====

    override val supportedIdentifierTypes: Set<ContactIdentifierType> =
        setOf(ContactIdentifierType.PHONE_NUMBER, ContactIdentifierType.EMAIL)

    override val defaultBubbleColor: Long = 0xFF007AFF  // iOS iMessage blue

    override val contactPriority: Int = 100  // Prefer iMessage for rich features

    override suspend fun checkContactAvailability(
        identifier: ContactIdentifier,
        options: AvailabilityCheckOptions
    ): ContactAvailability {
        // Check if this identifier type is supported
        if (identifier.type !in supportedIdentifierTypes) {
            return ContactAvailability.UnsupportedIdentifierType
        }

        // Email addresses are always iMessage
        if (identifier.type == ContactIdentifierType.EMAIL) {
            // Need server connection to send to email addresses
            return if (connectionState.value == StitchConnectionState.Connected) {
                ContactAvailability.Available(confidence = AvailabilityConfidence.HIGH)
            } else {
                ContactAvailability.Unknown(
                    reason = "Server not connected",
                    fallbackHint = true  // Email addresses are always iMessage
                )
            }
        }

        // For phone numbers, check server connection
        // Full implementation would query IMessageAvailabilityService
        return when (connectionState.value) {
            StitchConnectionState.Connected -> {
                // TODO: Query IMessageAvailabilityService for actual availability
                // For now, return Unknown with positive hint (likely available)
                ContactAvailability.Unknown(
                    reason = "Availability check not yet implemented",
                    fallbackHint = true
                )
            }
            StitchConnectionState.Connecting -> {
                ContactAvailability.Unknown(
                    reason = "Server connecting",
                    fallbackHint = false
                )
            }
            else -> {
                ContactAvailability.Unknown(
                    reason = "Server not connected",
                    fallbackHint = false
                )
            }
        }
    }
}
