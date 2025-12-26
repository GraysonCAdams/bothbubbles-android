package com.bothbubbles.seam.stitches.bluebubbles

import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.core.data.prefs.ServerPreferences
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.seam.stitches.Stitch
import com.bothbubbles.seam.stitches.StitchCapabilities
import com.bothbubbles.seam.stitches.StitchConnectionState
import com.bothbubbles.services.socket.SocketConnection
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

    override val settingsRoute: String = "server_settings"

    override suspend fun initialize() {
        // The socket service handles its own initialization
        // This is called when the stitch is first enabled
    }

    override suspend fun teardown() {
        // Don't disconnect the socket here - it's managed by SocketService
        // This is called when the stitch is disabled
    }
}
