package com.bothbubbles.ui.setup

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.services.sms.SmsCapabilityStatus
import com.bothbubbles.ui.setup.delegates.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val permissionsDelegate: PermissionsDelegate,
    serverConnectionDelegateFactory: ServerConnectionDelegate.Factory,
    smsSetupDelegateFactory: SmsSetupDelegate.Factory,
    syncDelegateFactory: SyncDelegate.Factory,
    mlModelDelegateFactory: MlModelDelegate.Factory,
    autoResponderDelegateFactory: AutoResponderDelegate.Factory
) : ViewModel() {

    // Create delegates with viewModelScope
    private val serverConnectionDelegate = serverConnectionDelegateFactory.create(viewModelScope)
    private val smsSetupDelegate = smsSetupDelegateFactory.create(viewModelScope)
    private val syncDelegate = syncDelegateFactory.create(viewModelScope)
    private val mlModelDelegate = mlModelDelegateFactory.create(viewModelScope)
    private val autoResponderDelegate = autoResponderDelegateFactory.create(viewModelScope)

    // Navigation state managed locally as StateFlow
    private val _currentPage = MutableStateFlow(0)

    // Combined UI state from all delegates (using array syntax for 7 flows)
    val uiState: StateFlow<SetupUiState> = combine(
        permissionsDelegate.state,
        serverConnectionDelegate.state,
        smsSetupDelegate.state,
        syncDelegate.state,
        mlModelDelegate.state,
        autoResponderDelegate.state,
        _currentPage
    ) { values: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val permissions = values[0] as? PermissionsState ?: PermissionsState()
        val serverConnection = values[1] as? ServerConnectionState ?: ServerConnectionState()
        val smsSetup = values[2] as? SmsSetupState ?: SmsSetupState()
        val sync = values[3] as? SyncState ?: SyncState()
        val mlModel = values[4] as? MlModelState ?: MlModelState()
        val autoResponder = values[5] as? AutoResponderState ?: AutoResponderState()
        val currentPage = values[6] as? Int ?: 0

        SetupUiState(
            currentPage = currentPage,
            // Permissions
            hasNotificationPermission = permissions.hasNotificationPermission,
            hasContactsPermission = permissions.hasContactsPermission,
            isBatteryOptimizationDisabled = permissions.isBatteryOptimizationDisabled,
            // Server connection
            serverUrl = serverConnection.serverUrl,
            password = serverConnection.password,
            isTestingConnection = serverConnection.isTestingConnection,
            isConnectionSuccessful = serverConnection.isConnectionSuccessful,
            connectionError = serverConnection.connectionError,
            showQrScanner = serverConnection.showQrScanner,
            // SMS setup
            smsEnabled = smsSetup.smsEnabled,
            smsCapabilityStatus = smsSetup.smsCapabilityStatus,
            userPhoneNumber = smsSetup.userPhoneNumber,
            // Sync
            messagesPerChat = sync.messagesPerChat,
            isSyncing = sync.isSyncing,
            syncProgress = sync.syncProgress,
            isSyncComplete = sync.isSyncComplete,
            syncError = sync.syncError,
            iMessageProgress = sync.iMessageProgress,
            iMessageComplete = sync.iMessageComplete,
            smsProgress = sync.smsProgress,
            smsComplete = sync.smsComplete,
            smsCurrent = sync.smsCurrent,
            smsTotal = sync.smsTotal,
            // ML model
            mlModelDownloaded = mlModel.mlModelDownloaded,
            mlDownloading = mlModel.mlDownloading,
            mlDownloadError = mlModel.mlDownloadError,
            mlDownloadSkipped = mlModel.mlDownloadSkipped,
            mlEnableCellularUpdates = mlModel.mlEnableCellularUpdates,
            isOnWifi = mlModel.isOnWifi,
            // Auto-responder
            autoResponderFilter = autoResponder.autoResponderFilter,
            autoResponderEnabled = autoResponder.autoResponderEnabled
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SetupUiState()
    )

    init {
        checkPermissions()
    }

    // ===== Permissions =====

    fun checkPermissions() = permissionsDelegate.checkPermissions()

    // ===== Server Connection =====

    fun updateServerUrl(url: String) = serverConnectionDelegate.updateServerUrl(url)

    fun updatePassword(password: String) = serverConnectionDelegate.updatePassword(password)

    fun testConnection() = serverConnectionDelegate.testConnection()

    fun onQrCodeScanned(data: String) = serverConnectionDelegate.onQrCodeScanned(data)

    fun showQrScanner() = serverConnectionDelegate.showQrScanner()

    fun hideQrScanner() = serverConnectionDelegate.hideQrScanner()

    fun skipServerSetup() = serverConnectionDelegate.skipServerSetup()

    // ===== SMS Setup =====

    fun updateSmsEnabled(enabled: Boolean) = smsSetupDelegate.updateSmsEnabled(enabled)

    fun getMissingSmsPermissions(): Array<String> = smsSetupDelegate.getMissingSmsPermissions()

    fun getDefaultSmsAppIntent(): Intent = smsSetupDelegate.getDefaultSmsAppIntent()

    fun onSmsPermissionsResult() = smsSetupDelegate.onSmsPermissionsResult()

    fun onDefaultSmsAppResult() = smsSetupDelegate.onDefaultSmsAppResult()

    fun finalizeSmsSettings() = smsSetupDelegate.finalizeSmsSettings()

    fun loadUserPhoneNumber() = smsSetupDelegate.loadUserPhoneNumber()

    // ===== ML Model =====

    fun updateMlCellularAutoUpdate(enabled: Boolean) = mlModelDelegate.updateMlCellularAutoUpdate(enabled)

    fun downloadMlModel() = mlModelDelegate.downloadMlModel()

    fun skipMlDownload() = mlModelDelegate.skipMlDownload()

    // ===== Sync =====

    fun completeSetupWithoutSync() = syncDelegate.completeSetupWithoutSync()

    fun startSync() = syncDelegate.startSync()

    // ===== Auto-responder =====

    fun updateAutoResponderFilter(filter: String) = autoResponderDelegate.updateAutoResponderFilter(filter)

    fun enableAutoResponder() = autoResponderDelegate.enableAutoResponder()

    fun skipAutoResponder() = autoResponderDelegate.skipAutoResponder()

    // ===== Navigation =====
    // Page indices: 0=Welcome, 1=Contacts, 2=Permissions, 3=Server, 4=SMS, 5=Categorization, 6=AutoResponder, 7=Sync

    fun nextPage() {
        _currentPage.update { current ->
            if (current < 7) current + 1 else current
        }
    }

    fun previousPage() {
        _currentPage.update { current ->
            if (current > 0) current - 1 else current
        }
    }

    fun setPage(page: Int) {
        _currentPage.value = page.coerceIn(0, 7)
    }
}

data class SetupUiState(
    val currentPage: Int = 0,

    // Permissions
    val hasNotificationPermission: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val isBatteryOptimizationDisabled: Boolean = false,

    // Server connection
    val serverUrl: String = "",
    val password: String = "",
    val isTestingConnection: Boolean = false,
    val isConnectionSuccessful: Boolean = false,
    val connectionError: String? = null,
    val showQrScanner: Boolean = false,

    // SMS settings
    val smsEnabled: Boolean = true,
    val smsCapabilityStatus: SmsCapabilityStatus? = null,
    val userPhoneNumber: String? = null,

    // Sync settings (messagesPerChat is fixed for Signal-style pagination)
    val messagesPerChat: Int = 500,  // Optimal for on-demand pagination - fetch more when scrolling
    val isSyncing: Boolean = false,
    val syncProgress: Float = 0f,
    val isSyncComplete: Boolean = false,
    val syncError: String? = null,
    // Separate progress tracking for iMessage and SMS
    val iMessageProgress: Float = 0f,
    val iMessageComplete: Boolean = false,
    val smsProgress: Float = 0f,
    val smsComplete: Boolean = false,
    val smsCurrent: Int = 0,
    val smsTotal: Int = 0,

    // ML model settings
    val mlModelDownloaded: Boolean = false,
    val mlDownloading: Boolean = false,
    val mlDownloadError: String? = null,
    val mlDownloadSkipped: Boolean = false,
    val mlEnableCellularUpdates: Boolean = false,
    val isOnWifi: Boolean = true,

    // Auto-responder settings
    val autoResponderFilter: String = "known_senders",
    val autoResponderEnabled: Boolean = false
) {
    val canProceedFromPermissions: Boolean
        get() = hasNotificationPermission && hasContactsPermission

    val canProceedFromConnection: Boolean
        get() = isConnectionSuccessful

    val allPermissionsGranted: Boolean
        get() = hasNotificationPermission && hasContactsPermission && isBatteryOptimizationDisabled

    /** ML setup is complete when downloaded or explicitly skipped */
    val mlSetupComplete: Boolean
        get() = mlModelDownloaded || mlDownloadSkipped

    /** Show ML step when connected to server (categorization is for iMessage) and model not yet downloaded */
    val shouldShowMlStep: Boolean
        get() = isConnectionSuccessful && !mlModelDownloaded
}
