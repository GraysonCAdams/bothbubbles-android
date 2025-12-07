package com.bothbubbles.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.services.categorization.EntityExtractionService
import com.bothbubbles.services.categorization.MlModelUpdateWorker
import com.bothbubbles.services.fcm.FirebaseConfigManager
import com.bothbubbles.services.fcm.FcmTokenManager
import com.bothbubbles.services.sms.SmsCapabilityStatus
import com.bothbubbles.services.sms.SmsPermissionHelper
import com.bothbubbles.services.socket.SocketService
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.services.sync.SyncState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val api: BothBubblesApi,
    private val socketService: SocketService,
    private val syncService: SyncService,
    private val smsPermissionHelper: SmsPermissionHelper,
    private val entityExtractionService: EntityExtractionService,
    private val firebaseConfigManager: FirebaseConfigManager,
    private val fcmTokenManager: FcmTokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        checkPermissions()
        loadSavedSettings()
        loadSmsStatus()
        checkMlModelStatus()
    }

    private fun loadSmsStatus() {
        val status = smsPermissionHelper.getSmsCapabilityStatus()
        _uiState.update { it.copy(smsCapabilityStatus = status) }
    }

    private fun loadSavedSettings() {
        viewModelScope.launch {
            val serverAddress = settingsDataStore.serverAddress.first()
            val password = settingsDataStore.guidAuthKey.first()
            _uiState.update {
                it.copy(
                    serverUrl = serverAddress,
                    password = password
                )
            }
        }
    }

    fun checkPermissions() {
        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryOptimizationDisabled = powerManager.isIgnoringBatteryOptimizations(context.packageName)

        _uiState.update {
            it.copy(
                hasNotificationPermission = hasNotificationPermission,
                hasContactsPermission = hasContactsPermission,
                isBatteryOptimizationDisabled = isBatteryOptimizationDisabled
            )
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url, connectionError = null) }
    }

    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password, connectionError = null) }
    }

    fun testConnection() {
        val serverUrl = _uiState.value.serverUrl.trim()
        val password = _uiState.value.password.trim()

        if (serverUrl.isBlank()) {
            _uiState.update { it.copy(connectionError = "Please enter a server URL") }
            return
        }

        if (password.isBlank()) {
            _uiState.update { it.copy(connectionError = "Please enter a password") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, connectionError = null) }

            try {
                // Save settings temporarily to make the API call
                settingsDataStore.setServerAddress(serverUrl)
                settingsDataStore.setGuidAuthKey(password)

                // Test connection
                val response = api.getServerInfo()

                if (response.isSuccessful) {
                    // Persist server capabilities for feature flag detection
                    val serverInfo = response.body()?.data
                    settingsDataStore.setServerCapabilities(
                        osVersion = serverInfo?.osVersion,
                        serverVersion = serverInfo?.serverVersion,
                        privateApiEnabled = serverInfo?.privateApi ?: false,
                        helperConnected = serverInfo?.helperConnected ?: false
                    )

                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            isConnectionSuccessful = true,
                            connectionError = null
                        )
                    }
                } else {
                    val errorMessage = when (response.code()) {
                        401 -> "Invalid password"
                        404 -> "Server not found"
                        else -> "Connection failed: ${response.code()}"
                    }
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            isConnectionSuccessful = false,
                            connectionError = errorMessage
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        isConnectionSuccessful = false,
                        connectionError = "Connection failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun onQrCodeScanned(data: String) {
        try {
            // QR code format: ["password", "serverUrl"]
            val parsed = data.trim()
            if (parsed.startsWith("[") && parsed.endsWith("]")) {
                val content = parsed.substring(1, parsed.length - 1)
                val parts = content.split(",").map { it.trim().removeSurrounding("\"") }
                if (parts.size >= 2) {
                    val password = parts[0]
                    val serverUrl = parts[1]
                    _uiState.update {
                        it.copy(
                            password = password,
                            serverUrl = serverUrl,
                            showQrScanner = false
                        )
                    }
                    // Auto-test after scanning
                    testConnection()
                    return
                }
            }
            _uiState.update { it.copy(connectionError = "Invalid QR code format") }
        } catch (e: Exception) {
            _uiState.update { it.copy(connectionError = "Failed to parse QR code") }
        }
    }

    fun showQrScanner() {
        _uiState.update { it.copy(showQrScanner = true) }
    }

    fun hideQrScanner() {
        _uiState.update { it.copy(showQrScanner = false) }
    }

    // Sync settings
    fun updateMessagesPerChat(count: Int) {
        _uiState.update { it.copy(messagesPerChat = count) }
    }

    fun updateSkipEmptyChats(skip: Boolean) {
        _uiState.update { it.copy(skipEmptyChats = skip) }
    }

    // SMS settings
    fun updateSmsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(smsEnabled = enabled) }
    }

    /**
     * Get missing SMS permissions as an array for the permission launcher
     */
    fun getMissingSmsPermissions(): Array<String> {
        return smsPermissionHelper.getMissingSmsPermissions().toTypedArray()
    }

    /**
     * Get intent to request default SMS app status
     */
    fun getDefaultSmsAppIntent(): Intent {
        return smsPermissionHelper.createDefaultSmsAppIntent()
    }

    /**
     * Called after SMS permissions are granted/denied
     */
    fun onSmsPermissionsResult() {
        loadSmsStatus()
    }

    /**
     * Called after default SMS app request completes
     */
    fun onDefaultSmsAppResult() {
        loadSmsStatus()
        // If we're now the default SMS app, auto-enable SMS
        if (smsPermissionHelper.isDefaultSmsApp()) {
            viewModelScope.launch {
                settingsDataStore.setSmsEnabled(true)
            }
            _uiState.update { it.copy(smsEnabled = true) }
        }
    }

    /**
     * Finalize SMS settings when completing setup
     */
    fun finalizeSmsSettings() {
        viewModelScope.launch {
            settingsDataStore.setSmsEnabled(_uiState.value.smsEnabled)
        }
    }

    // ===== ML Model Methods =====

    /**
     * Check if ML model is already downloaded and network status
     */
    private fun checkMlModelStatus() {
        viewModelScope.launch {
            val isDownloaded = entityExtractionService.checkModelDownloaded()
            _uiState.update {
                it.copy(
                    mlModelDownloaded = isDownloaded,
                    isOnWifi = isOnWifi()
                )
            }
        }
    }

    /**
     * Check if device is on WiFi
     */
    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Update the checkbox for enabling cellular auto-updates
     */
    fun updateMlCellularAutoUpdate(enabled: Boolean) {
        _uiState.update { it.copy(mlEnableCellularUpdates = enabled) }
    }

    /**
     * Start ML model download
     */
    fun downloadMlModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(mlDownloading = true, mlDownloadError = null) }

            val allowCellular = !isOnWifi() // If not on WiFi, user has consented to cellular
            val success = entityExtractionService.downloadModel(allowCellular)

            if (success) {
                // Save to settings
                settingsDataStore.setMlModelDownloaded(true)
                // If downloaded on cellular and user checked the box, enable cellular updates
                if (!isOnWifi() && _uiState.value.mlEnableCellularUpdates) {
                    settingsDataStore.setMlAutoUpdateOnCellular(true)
                }
                // Schedule periodic ML model updates
                MlModelUpdateWorker.schedule(context)
                // Complete setup now that ML is downloaded
                settingsDataStore.setSetupComplete(true)
                _uiState.update {
                    it.copy(
                        mlDownloading = false,
                        mlModelDownloaded = true,
                        isSyncComplete = true
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        mlDownloading = false,
                        mlDownloadError = "Failed to download ML model"
                    )
                }
            }
        }
    }

    /**
     * Skip ML model download - complete setup without ML
     */
    fun skipMlDownload() {
        viewModelScope.launch {
            settingsDataStore.setSetupComplete(true)
            _uiState.update {
                it.copy(
                    mlDownloadSkipped = true,
                    isSyncComplete = true
                )
            }
        }
    }

    fun skipServerSetup() {
        // Clear server settings when skipping
        viewModelScope.launch {
            settingsDataStore.setServerAddress("")
            settingsDataStore.setGuidAuthKey("")
        }
        _uiState.update {
            it.copy(
                serverUrl = "",
                password = "",
                isConnectionSuccessful = false,
                connectionError = null
            )
        }
    }

    fun completeSetupWithoutSync() {
        viewModelScope.launch {
            settingsDataStore.setSetupComplete(true)
            _uiState.update { it.copy(isSyncComplete = true) }
        }
    }

    fun startSync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncProgress = 0f, syncError = null) }

            try {
                // Connect socket first
                socketService.connect()

                // Initialize FCM for push notifications (non-blocking)
                launch {
                    try {
                        firebaseConfigManager.initializeFromServer()
                        fcmTokenManager.refreshToken()
                    } catch (e: Exception) {
                        android.util.Log.w("SetupViewModel", "FCM init failed", e)
                    }
                }

                // Mark setup complete IMMEDIATELY so user can use the app
                // Sync will continue in background
                settingsDataStore.setSetupComplete(true)
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        isSyncComplete = true
                    )
                }

                // Start initial sync in background (continues after navigation)
                launch {
                    syncService.performInitialSync(
                        messagesPerChat = _uiState.value.messagesPerChat
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncError = "Failed to start sync: ${e.message}"
                    )
                }
            }
        }
    }

    fun nextPage() {
        val currentPage = _uiState.value.currentPage
        if (currentPage < 4) {
            _uiState.update { it.copy(currentPage = currentPage + 1) }
        }
    }

    fun previousPage() {
        val currentPage = _uiState.value.currentPage
        if (currentPage > 0) {
            _uiState.update { it.copy(currentPage = currentPage - 1) }
        }
    }

    fun setPage(page: Int) {
        _uiState.update { it.copy(currentPage = page.coerceIn(0, 4)) }
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

    // Sync settings
    val messagesPerChat: Int = 25,
    val skipEmptyChats: Boolean = true,
    val isSyncing: Boolean = false,
    val syncProgress: Float = 0f,
    val isSyncComplete: Boolean = false,
    val syncError: String? = null,

    // ML model settings
    val mlModelDownloaded: Boolean = false,
    val mlDownloading: Boolean = false,
    val mlDownloadError: String? = null,
    val mlDownloadSkipped: Boolean = false,
    val mlEnableCellularUpdates: Boolean = false,
    val isOnWifi: Boolean = true
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
