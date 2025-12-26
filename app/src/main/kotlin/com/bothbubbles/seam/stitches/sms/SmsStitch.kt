package com.bothbubbles.seam.stitches.sms

import com.bothbubbles.seam.stitches.Stitch
import com.bothbubbles.seam.stitches.StitchCapabilities
import com.bothbubbles.seam.stitches.StitchConnectionState
import com.bothbubbles.services.sms.SmsPermissionHelper
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

    override val settingsRoute: String? = null

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
    fun matchesChatGuid(chatGuid: String): Boolean {
        return chatGuid.startsWith(CHAT_GUID_PREFIX_SMS) ||
               chatGuid.startsWith(CHAT_GUID_PREFIX_MMS)
    }
}
