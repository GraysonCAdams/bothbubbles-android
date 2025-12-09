package com.bothbubbles

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.AppLifecycleTracker
import com.bothbubbles.services.contacts.ContactsContentObserver
import com.bothbubbles.services.developer.ConnectionModeManager
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.socket.SocketConnectionManager
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BothBubblesApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var socketConnectionManager: SocketConnectionManager

    @Inject
    lateinit var connectionModeManager: ConnectionModeManager

    @Inject
    lateinit var developerEventLog: DeveloperEventLog

    @Inject
    lateinit var appLifecycleTracker: AppLifecycleTracker

    @Inject
    lateinit var smsRepository: SmsRepository

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var contactsContentObserver: ContactsContentObserver

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // Use ImageDecoder for GIFs on Android 9+ (API 28+), fallback to GifDecoder
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        PhoneNumberFormatter.init(this)
        createNotificationChannels()

        // Initialize app lifecycle tracker (must be before other managers that may depend on it)
        appLifecycleTracker.initialize()

        // Initialize developer mode from settings
        initializeDeveloperMode()

        // Initialize connection mode manager (handles Socket <-> FCM auto-switching)
        connectionModeManager.initialize()

        // Keep socket connection manager for legacy/fallback (will be phased out)
        socketConnectionManager.initialize()

        // Initialize SMS content observer for external SMS detection (Android Auto, etc.)
        initializeSmsObserver()

        // Initialize contacts content observer for cache invalidation
        initializeContactsObserver()
    }

    /**
     * Initialize developer mode settings.
     * Enables event logging if developer mode was previously enabled.
     */
    private fun initializeDeveloperMode() {
        applicationScope.launch {
            try {
                val developerModeEnabled = settingsDataStore.developerModeEnabled.first()
                developerEventLog.setEnabled(developerModeEnabled)
                if (developerModeEnabled) {
                    Log.d(TAG, "Developer mode is enabled - event logging active")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error initializing developer mode", e)
            }
        }
    }

    /**
     * Start SMS content observer if setup is complete and app is default SMS app.
     * Also triggers one-time SMS re-sync on app update to recover historical external SMS.
     * This detects external SMS sent via Android Auto, Google Assistant, etc.
     */
    private fun initializeSmsObserver() {
        applicationScope.launch {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                if (!smsRepository.isDefaultSmsApp()) return@launch

                // Start the SMS content observer for future external SMS detection
                smsRepository.startObserving()

                // Check if we need to do a one-time SMS re-sync (app update recovery)
                checkAndPerformSmsResync()
            } catch (e: Exception) {
                Log.w(TAG, "Error initializing SMS observer", e)
            }
        }
    }

    /**
     * Perform one-time SMS re-sync on app update to recover historical external SMS
     * (e.g., Android Auto messages that weren't detected before this fix).
     */
    private suspend fun checkAndPerformSmsResync() {
        try {
            val currentVersionCode = packageManager.getPackageInfo(packageName, 0).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    it.versionCode
                }
            }

            val lastResyncVersion = settingsDataStore.lastSmsResyncVersion.first()

            // Only resync if this is a new version AND we've never done a resync before
            // (lastResyncVersion == 0 means first time, or pre-update install)
            if (lastResyncVersion == 0 || currentVersionCode > lastResyncVersion) {
                Log.i(TAG, "Performing one-time SMS re-sync (version $lastResyncVersion -> $currentVersionCode)")

                // Import all SMS threads to recover any missed external messages
                val result = smsRepository.importAllThreads(limit = 500)
                result.onSuccess { imported ->
                    Log.i(TAG, "SMS re-sync complete: $imported threads imported")
                }.onFailure { error ->
                    Log.w(TAG, "SMS re-sync failed", error)
                }

                // Update version to prevent re-syncing on next launch
                settingsDataStore.setLastSmsResyncVersion(currentVersionCode)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking SMS re-sync", e)
        }
    }

    /**
     * Start contacts content observer if setup is complete.
     * This monitors Android contacts for changes and updates cached contact info.
     */
    private fun initializeContactsObserver() {
        applicationScope.launch {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                // Start observing contacts for changes
                contactsContentObserver.startObserving()
                Log.d(TAG, "Contacts content observer started")
            } catch (e: Exception) {
                Log.w(TAG, "Error initializing contacts observer", e)
            }
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Messages channel
        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_messages_desc)
            enableVibration(true)
            enableLights(true)
        }

        // Background service channel
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_service_desc)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannels(listOf(messagesChannel, serviceChannel))
    }

    companion object {
        private const val TAG = "BothBubblesApp"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
    }
}
