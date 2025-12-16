package com.bothbubbles.ui.setup.delegates

import com.bothbubbles.services.sms.SmsCapabilityStatus

/**
 * State classes for setup delegates.
 * Each delegate manages its own state, exposed as StateFlow.
 */

data class PermissionsState(
    val hasNotificationPermission: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val isBatteryOptimizationDisabled: Boolean = false
) {
    val canProceed: Boolean
        get() = hasNotificationPermission && hasContactsPermission

    val allGranted: Boolean
        get() = hasNotificationPermission && hasContactsPermission && isBatteryOptimizationDisabled
}

data class ServerConnectionState(
    val serverUrl: String = "",
    val password: String = "",
    val isTestingConnection: Boolean = false,
    val isConnectionSuccessful: Boolean = false,
    val connectionError: String? = null,
    val showQrScanner: Boolean = false
) {
    val canProceed: Boolean
        get() = isConnectionSuccessful
}

data class SmsSetupState(
    val smsEnabled: Boolean = true,
    val smsCapabilityStatus: SmsCapabilityStatus? = null,
    val userPhoneNumber: String? = null
)

data class SyncState(
    val messagesPerChat: Int = 500,
    val skipEmptyChats: Boolean = true,
    val isSyncing: Boolean = false,
    val syncProgress: Float = 0f,
    val isSyncComplete: Boolean = false,
    val syncError: String? = null,
    val iMessageProgress: Float = 0f,
    val iMessageComplete: Boolean = false,
    val smsProgress: Float = 0f,
    val smsComplete: Boolean = false,
    val smsCurrent: Int = 0,
    val smsTotal: Int = 0
)

data class MlModelState(
    val mlModelDownloaded: Boolean = false,
    val mlDownloading: Boolean = false,
    val mlDownloadError: String? = null,
    val mlDownloadSkipped: Boolean = false,
    val mlEnableCellularUpdates: Boolean = false,
    val isOnWifi: Boolean = true
) {
    val isComplete: Boolean
        get() = mlModelDownloaded || mlDownloadSkipped
}

data class AutoResponderState(
    val autoResponderFilter: String = "known_senders",
    val autoResponderEnabled: Boolean = false
)
