package com.bothbubbles.ui.setup

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.categorization.EntityExtractionService
import com.bothbubbles.services.fcm.FirebaseConfigManager
import com.bothbubbles.services.fcm.FcmTokenManager
import com.bothbubbles.services.sms.SmsCapabilityStatus
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.ui.setup.delegates.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsDataStore: SettingsDataStore,
    api: BothBubblesApi,
    socketService: SocketService,
    syncService: SyncService,
    smsPermissionHelper: SmsPermissionHelper,
    entityExtractionService: EntityExtractionService,
    firebaseConfigManager: FirebaseConfigManager,
    fcmTokenManager: FcmTokenManager,
    private val smsRepository: SmsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    // Delegates for different setup steps
    private val permissionsDelegate = PermissionsDelegate(context)
    private val serverConnectionDelegate = ServerConnectionDelegate(settingsDataStore, api)
    private val smsSetupDelegate = SmsSetupDelegate(smsPermissionHelper, settingsDataStore, smsRepository)
    private val mlModelDelegate = MlModelDelegate(context, settingsDataStore, entityExtractionService)
    private val syncDelegate = SyncDelegate(settingsDataStore, socketService, syncService, firebaseConfigManager, fcmTokenManager)
    private val autoResponderDelegate = AutoResponderDelegate(settingsDataStore)

    init {
        checkPermissions()
        loadSavedSettings()
        loadSmsStatus()
        checkMlModelStatus()
    }

    // ===== Permissions =====

    fun checkPermissions() = permissionsDelegate.checkPermissions(_uiState)

    // ===== Server Connection =====

    private fun loadSavedSettings() {
        viewModelScope.launch {
            serverConnectionDelegate.loadSavedSettings(_uiState)
        }
    }

    fun updateServerUrl(url: String) = serverConnectionDelegate.updateServerUrl(_uiState, url)

    fun updatePassword(password: String) = serverConnectionDelegate.updatePassword(_uiState, password)

    fun testConnection() = serverConnectionDelegate.testConnection(viewModelScope, _uiState)

    fun onQrCodeScanned(data: String) = serverConnectionDelegate.onQrCodeScanned(viewModelScope, _uiState, data)

    fun showQrScanner() = serverConnectionDelegate.showQrScanner(_uiState)

    fun hideQrScanner() = serverConnectionDelegate.hideQrScanner(_uiState)

    fun skipServerSetup() {
        viewModelScope.launch {
            serverConnectionDelegate.skipServerSetup(_uiState)
        }
    }

    // ===== SMS Setup =====

    private fun loadSmsStatus() = smsSetupDelegate.loadSmsStatus(_uiState)

    fun updateSmsEnabled(enabled: Boolean) = smsSetupDelegate.updateSmsEnabled(_uiState, enabled)

    fun getMissingSmsPermissions(): Array<String> = smsSetupDelegate.getMissingSmsPermissions()

    fun getDefaultSmsAppIntent(): Intent = smsSetupDelegate.getDefaultSmsAppIntent()

    fun onSmsPermissionsResult() = smsSetupDelegate.onSmsPermissionsResult(_uiState)

    fun onDefaultSmsAppResult() = smsSetupDelegate.onDefaultSmsAppResult(viewModelScope, _uiState)

    fun finalizeSmsSettings() {
        viewModelScope.launch {
            smsSetupDelegate.finalizeSmsSettings(_uiState)
        }
    }

    fun loadUserPhoneNumber() = smsSetupDelegate.loadUserPhoneNumber(_uiState)

    // ===== ML Model =====

    private fun checkMlModelStatus() {
        viewModelScope.launch {
            mlModelDelegate.checkMlModelStatus(_uiState)
        }
    }

    fun updateMlCellularAutoUpdate(enabled: Boolean) = mlModelDelegate.updateMlCellularAutoUpdate(_uiState, enabled)

    fun downloadMlModel() = mlModelDelegate.downloadMlModel(viewModelScope, _uiState)

    fun skipMlDownload() = mlModelDelegate.skipMlDownload(_uiState)

    // ===== Sync =====

    fun updateSkipEmptyChats(skip: Boolean) = syncDelegate.updateSkipEmptyChats(_uiState, skip)

    fun completeSetupWithoutSync() {
        viewModelScope.launch {
            syncDelegate.completeSetupWithoutSync(_uiState)
        }
    }

    fun startSync() = syncDelegate.startSync(viewModelScope, _uiState)

    // ===== Auto-responder =====

    fun updateAutoResponderFilter(filter: String) = autoResponderDelegate.updateAutoResponderFilter(_uiState, filter)

    fun enableAutoResponder() = autoResponderDelegate.enableAutoResponder(viewModelScope, _uiState)

    fun skipAutoResponder() = autoResponderDelegate.skipAutoResponder(viewModelScope, _uiState)

    // ===== Navigation =====

    fun nextPage() {
        val currentPage = _uiState.value.currentPage
        if (currentPage < 6) {
            _uiState.value = _uiState.value.copy(currentPage = currentPage + 1)
        }
    }

    fun previousPage() {
        val currentPage = _uiState.value.currentPage
        if (currentPage > 0) {
            _uiState.value = _uiState.value.copy(currentPage = currentPage - 1)
        }
    }

    fun setPage(page: Int) {
        _uiState.value = _uiState.value.copy(currentPage = page.coerceIn(0, 6))
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

    // Sync settings (messagesPerChat is fixed for Signal-style pagination)
    val messagesPerChat: Int = 500,  // Optimal for on-demand pagination - fetch more when scrolling
    val skipEmptyChats: Boolean = true,
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
    val autoResponderEnabled: Boolean = false,
    val userPhoneNumber: String? = null
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
