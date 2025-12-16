package com.bothbubbles

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import timber.log.Timber
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.PendingMessageSource
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.util.logging.CrashlyticsTree
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.AppLifecycleTracker
import com.bothbubbles.services.contacts.ContactsContentObserver
import com.bothbubbles.services.shortcut.ShortcutService
import com.bothbubbles.services.developer.ConnectionModeManager
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.sync.BackgroundSyncWorker
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.PerformanceProfiler
import dagger.hilt.android.HiltAndroidApp
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BothBubblesApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var connectionModeManager: ConnectionModeManager

    @Inject
    lateinit var developerEventLog: DeveloperEventLog

    @Inject
    lateinit var activeConversationManager: ActiveConversationManager

    @Inject
    lateinit var smsRepository: SmsRepository

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var contactsContentObserver: ContactsContentObserver

    @Inject
    lateinit var shortcutService: ShortcutService

    @Inject
    lateinit var pendingMessageSource: PendingMessageSource

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // Use ImageDecoder for GIFs on Android 9+ (API 28+), fallback to GifDecoder
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // Video frame extraction for video thumbnails
                add(VideoFrameDecoder.Factory())
            }
            // Memory cache: ~15% of available memory for image caching
            // Reduced from 25% to leave more headroom for downloads and processing
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            // Disk cache: 250MB dedicated directory for attachment images
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024) // 250MB
                    .build()
            }
            .crossfade(150) // Faster crossfade for snappier feel
            .respectCacheHeaders(false) // Don't respect server headers for local files
            .build()
    }

    override fun onCreate() {
        val startupId = PerformanceProfiler.start("App.onCreate")
        super.onCreate()

        // Note: Timber is initialized via TimberInitializer (AndroidX Startup)
        // Note: AppLifecycleTracker is initialized via AppLifecycleTrackerInitializer
        // Note: WorkManager is initialized via WorkManagerInitializer

        // Initialize Crashlytics logging for release builds
        if (!BuildConfig.DEBUG) {
            Timber.plant(CrashlyticsTree())
        }

        PhoneNumberFormatter.init(this)
        createNotificationChannels()

        // Initialize active conversation manager (clears active chat when app backgrounds)
        activeConversationManager.initialize()

        // Initialize developer mode from settings
        initializeDeveloperMode()

        // Initialize connection mode manager (handles Socket <-> FCM auto-switching)
        val connectionModeId = PerformanceProfiler.start("App.connectionModeManager")
        connectionModeManager.initialize()
        PerformanceProfiler.end(connectionModeId)

        // Initialize SMS content observer for external SMS detection (Android Auto, etc.)
        initializeSmsObserver()

        // Initialize contacts content observer for cache invalidation
        initializeContactsObserver()

        // Initialize sharing shortcuts for share sheet integration
        initializeShortcutService()

        // Re-enqueue any pending messages from previous session
        initializePendingMessageQueue()

        // Schedule background sync worker (catches messages missed by push/socket)
        initializeBackgroundSync()

        PerformanceProfiler.end(startupId)
        Timber.d("App.onCreate complete - print stats with: adb logcat | grep PerfProfiler")
    }

    /**
     * Initialize developer mode settings.
     * Enables event logging if developer mode was previously enabled.
     */
    private fun initializeDeveloperMode() {
        applicationScope.launch(ioDispatcher) {
            try {
                val developerModeEnabled = settingsDataStore.developerModeEnabled.first()
                developerEventLog.setEnabled(developerModeEnabled)
                if (developerModeEnabled) {
                    Timber.d("Developer mode is enabled - event logging active")
                }
            } catch (e: Exception) {
                Timber.w(e, "Error initializing developer mode")
            }
        }
    }

    /**
     * Start SMS content observer if setup is complete and app is default SMS app.
     * Also triggers one-time SMS re-sync on app update to recover historical external SMS.
     * This detects external SMS sent via Android Auto, Google Assistant, etc.
     */
    private fun initializeSmsObserver() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                if (!smsRepository.isDefaultSmsApp()) return@launch

                // Start the SMS content observer for future external SMS detection
                smsRepository.startObserving()

                // Check if we need to do a one-time SMS re-sync (app update recovery)
                checkAndPerformSmsResync()
            } catch (e: Exception) {
                Timber.w(e, "Error initializing SMS observer")
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
                Timber.i("Performing one-time SMS re-sync (version $lastResyncVersion -> $currentVersionCode)")

                // Import all SMS threads to recover any missed external messages
                val result = smsRepository.importAllThreads(limit = 500)
                result.onSuccess { imported ->
                    Timber.i("SMS re-sync complete: $imported threads imported")
                }.onFailure { error ->
                    Timber.w(error, "SMS re-sync failed")
                }

                // Update version to prevent re-syncing on next launch
                settingsDataStore.setLastSmsResyncVersion(currentVersionCode)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error checking SMS re-sync")
        }
    }

    /**
     * Start contacts content observer if setup is complete.
     * This monitors Android contacts for changes and updates cached contact info.
     */
    private fun initializeContactsObserver() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                // Start observing contacts for changes
                contactsContentObserver.startObserving()
                Timber.d("Contacts content observer started")
            } catch (e: Exception) {
                Timber.w(e, "Error initializing contacts observer")
            }
        }
    }

    /**
     * Start shortcut service if setup is complete.
     * This publishes recent conversations as share targets in the Android share sheet.
     */
    private fun initializeShortcutService() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                // Start observing conversations for share targets
                shortcutService.startObserving()
                Timber.d("Shortcut service started")
            } catch (e: Exception) {
                Timber.w(e, "Error initializing shortcut service")
            }
        }
    }

    /**
     * Re-enqueue pending messages from previous session.
     * This ensures messages survive app kills and device reboots.
     */
    private fun initializePendingMessageQueue() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                // Re-enqueue any pending messages and clean up sent ones
                pendingMessageSource.reEnqueuePendingMessages()
                pendingMessageSource.cleanupSentMessages()

                val unsentCount = pendingMessageSource.getUnsentCount()
                if (unsentCount > 0) {
                    Timber.i("Found $unsentCount unsent messages in queue")
                }
            } catch (e: Exception) {
                Timber.w(e, "Error initializing pending message queue")
            }
        }
    }

    /**
     * Schedule background sync worker to periodically check for missed messages.
     * This is a fallback mechanism for when Socket.IO push and FCM fail to deliver.
     * Runs every 15 minutes (Android's minimum interval for periodic work).
     */
    private fun initializeBackgroundSync() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                BackgroundSyncWorker.schedule(this@BothBubblesApp)
            } catch (e: Exception) {
                Timber.w(e, "Error scheduling background sync")
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
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
    }
}
