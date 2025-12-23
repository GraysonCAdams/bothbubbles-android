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
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.AppLifecycleTracker
import com.bothbubbles.services.contacts.ContactsContentObserver
import com.bothbubbles.services.shortcut.AppShortcutManager
import com.bothbubbles.services.shortcut.ShortcutService
import com.bothbubbles.services.developer.ConnectionModeManager
import com.bothbubbles.services.developer.DeveloperEventLog
import com.bothbubbles.services.fcm.FirebaseDatabaseService
import com.bothbubbles.services.notifications.BadgeManager
import com.bothbubbles.services.notifications.NotificationLinkPreviewUpdater
import com.bothbubbles.services.notifications.NotificationMediaUpdater
import com.bothbubbles.services.life360.Life360SyncWorker
import com.bothbubbles.services.life360.Life360TokenStorage
import com.bothbubbles.services.socket.SocketEventHandler
import com.bothbubbles.services.sync.BackgroundSyncWorker
import com.bothbubbles.services.sync.SyncService
import com.bothbubbles.services.contacts.sync.GroupContactSyncManager
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.util.PerformanceProfiler
import com.bothbubbles.core.network.api.AuthInterceptor
import dagger.hilt.android.HiltAndroidApp
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.acra.config.dialog
import org.acra.config.mailSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import com.bothbubbles.ui.crash.CrashReportActivity
import javax.inject.Inject

@HiltAndroidApp
class BothBubblesApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var connectionModeManager: ConnectionModeManager

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)

        // Initialize ACRA for local crash reporting (privacy-first: no automatic data collection)
        // Crashes are stored locally and only shared if user explicitly chooses to email them
        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.JSON

            // Show custom Compose crash report screen
            dialog {
                reportDialogClass = CrashReportActivity::class.java
            }

            // Use email sender - user manually sends via their email app (no automatic upload)
            mailSender {
                mailTo = "crashes@bothbubbles.com"
                reportAsFile = true
                reportFileName = "crash_report.json"
                subject = getString(R.string.crash_email_subject)
                body = getString(R.string.crash_email_body)
            }
        }
    }

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
    lateinit var appShortcutManager: AppShortcutManager

    @Inject
    lateinit var pendingMessageSource: PendingMessageSource

    @Inject
    lateinit var syncService: SyncService

    @Inject
    lateinit var socketEventHandler: SocketEventHandler

    @Inject
    lateinit var firebaseDatabaseService: FirebaseDatabaseService

    @Inject
    lateinit var badgeManager: BadgeManager

    @Inject
    lateinit var life360TokenStorage: Life360TokenStorage

    @Inject
    lateinit var notificationMediaUpdater: NotificationMediaUpdater

    @Inject
    lateinit var notificationLinkPreviewUpdater: NotificationLinkPreviewUpdater

    @Inject
    lateinit var authInterceptor: AuthInterceptor

    @Inject
    lateinit var socialMediaDownloadService: com.bothbubbles.services.socialmedia.SocialMediaDownloadService

    @Inject
    lateinit var socialMediaLinkMigrationHelper: com.bothbubbles.services.socialmedia.SocialMediaLinkMigrationHelper

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
        // Note: ACRA crash reporting is initialized in attachBaseContext()

        PhoneNumberFormatter.init(this)
        createNotificationChannels()

        // Initialize AuthInterceptor credentials cache (must happen before any network requests)
        initializeAuthInterceptor()

        // Check if database migration requires sync flag reset (must run before sync logic)
        checkMigrationState()

        // Initialize active conversation manager (clears active chat when app backgrounds)
        activeConversationManager.initialize()

        // Initialize developer mode from settings
        initializeDeveloperMode()

        // Initialize haptics enabled state from settings
        initializeHapticsSettings()

        // Initialize connection mode manager (handles Socket <-> FCM auto-switching)
        val connectionModeId = PerformanceProfiler.start("App.connectionModeManager")
        connectionModeManager.initialize()
        PerformanceProfiler.end(connectionModeId)

        // Start socket event handler to process incoming messages in real-time
        initializeSocketEventHandler()

        // Initialize SMS content observer for external SMS detection (Android Auto, etc.)
        initializeSmsObserver()

        // Initialize contacts content observer for cache invalidation
        initializeContactsObserver()

        // Initialize sharing shortcuts for share sheet integration
        initializeShortcutService()

        // Initialize launcher app shortcuts (popular chats in long-press menu)
        initializeAppShortcutManager()

        // Re-enqueue any pending messages from previous session
        initializePendingMessageQueue()

        // Schedule background sync worker (catches messages missed by push/socket)
        initializeBackgroundSync()

        // Schedule Life360 sync worker (if authenticated)
        initializeLife360Sync()

        // Self-healing: repair chats with missing messages
        initializeRepairSync()

        // Start Firebase Database listener for dynamic server URL sync
        initializeFirebaseDatabaseListener()

        // Initialize notification media updater for inline image previews
        initializeNotificationMediaUpdater()

        // Initialize notification link preview updater for rich link previews
        initializeNotificationLinkPreviewUpdater()

        // Sync group chats to system contacts for Google Assistant voice commands
        initializeGroupContactSync()

        // Auto-cache recent social media videos (when Reels enabled and on WiFi)
        initializeSocialMediaCache()

        PerformanceProfiler.end(startupId)
        Timber.d("App.onCreate complete - print stats with: adb logcat | grep PerfProfiler")
    }

    /**
     * Check if a database migration occurred that requires sync flag reset.
     *
     * Migration 49→50 clears all message/chat data to enable the new unified chat
     * architecture. This method detects the post-migration state (empty sync_ranges
     * but initialSyncComplete=true) and resets the DataStore flags to trigger
     * a full re-sync of both iMessage and SMS data.
     *
     * Must run early in initialization, before any sync logic.
     */
    private fun checkMigrationState() {
        applicationScope.launch(ioDispatcher) {
            try {
                val migrated = syncService.checkAndResetIfMigrated()
                if (migrated) {
                    Timber.i("Database migration detected - sync flags reset, will trigger full re-sync")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error checking migration state")
            }
        }
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
     * Initialize haptics settings.
     * Sets HapticUtils.enabled from user preferences.
     */
    private fun initializeHapticsSettings() {
        applicationScope.launch(ioDispatcher) {
            try {
                val hapticsEnabled = settingsDataStore.hapticsEnabled.first()
                HapticUtils.enabled = hapticsEnabled
                Timber.d("Haptics initialized: enabled=$hapticsEnabled")
            } catch (e: Exception) {
                Timber.w(e, "Error initializing haptics settings")
            }
        }
    }

    /**
     * Start socket event handler to process incoming messages in real-time.
     *
     * The socket event handler listens for Socket.IO events (new messages, updates, etc.)
     * and processes them to save to the database, show notifications, and trigger UI updates.
     * Without this, socket events would be emitted but not processed.
     */
    private fun initializeSocketEventHandler() {
        socketEventHandler.startListening()
        Timber.i("Socket event handler started - listening for real-time message events")
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

                // Repair any orphaned SMS chats by linking them to unified groups
                // This fixes messages sent via Android Auto that weren't linked before
                val repaired = smsRepository.repairOrphanedSmsChats()
                if (repaired > 0) {
                    Timber.i("Repaired $repaired orphaned SMS chats")
                }

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
     * Start app shortcut manager if setup is complete.
     * This publishes the top 3 most popular chats as launcher shortcuts
     * (visible when long-pressing the app icon).
     */
    private fun initializeAppShortcutManager() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                // Start observing popular chats for launcher shortcuts
                appShortcutManager.startObserving()
                Timber.d("App shortcut manager started")
            } catch (e: Exception) {
                Timber.w(e, "Error initializing app shortcut manager")
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

                // FIRST: Verify stuck SENDING messages with server and mark as FAILED if not delivered
                // This prevents messages from being stuck in "sending" state forever after app kill
                val verifiedCount = pendingMessageSource.verifyAndFailStuckMessages()
                if (verifiedCount > 0) {
                    Timber.i("Verified $verifiedCount stuck messages on startup")
                }

                // Re-enqueue any pending messages (NOT failed - let user manually retry those)
                pendingMessageSource.reEnqueuePendingMessages()
                pendingMessageSource.cleanupSentMessages()

                // Clean up orphaned temp messages from race conditions
                pendingMessageSource.cleanupOrphanedTempMessages()

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

    /**
     * Schedule Life360 sync worker to periodically update family member locations.
     * Only runs if Life360 is authenticated and enabled.
     *
     * DISABLED: Life360 data is now only fetched while viewing a conversation with a
     * Life360-linked contact. This avoids unnecessary background API calls and reduces
     * risk of rate limiting/bans. See ConversationDetailsViewModel.startLife360Polling().
     */
    private fun initializeLife360Sync() {
        // Background sync disabled - Life360 data is fetched on-demand when viewing contacts
        Timber.d("Life360 background sync disabled, using foreground-only polling")
    }

    /**
     * Self-healing repair sync for chats with missing messages.
     *
     * This detects and repairs chats where:
     * - Chat exists with latest_message_date set (server indicated messages exist)
     * - No messages were ever synced (sync_ranges empty, messages empty)
     *
     * This is idempotent: runs a fast local query first (~10ms), only triggers
     * server sync if broken chats are found. Subsequent app launches will find
     * nothing to repair once messages are synced.
     */
    private fun initializeRepairSync() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                val initialSyncComplete = settingsDataStore.initialSyncComplete.first()
                if (!initialSyncComplete) return@launch

                // Start repair sync (fast local check, server sync only if needed)
                syncService.startRepairSync()
            } catch (e: Exception) {
                Timber.w(e, "Error initializing repair sync")
            }
        }
    }

    /**
     * Initialize notification media updater to add inline image previews to notifications.
     *
     * When an image/video attachment downloads after the initial notification is shown,
     * this updates the notification with the actual media preview.
     */
    private fun initializeNotificationMediaUpdater() {
        notificationMediaUpdater.initialize()
    }

    /**
     * Initialize notification link preview updater to add rich link previews to notifications.
     *
     * When a link preview is fetched after the initial notification is shown,
     * this updates the notification with the title, domain, and preview image.
     */
    private fun initializeNotificationLinkPreviewUpdater() {
        notificationLinkPreviewUpdater.initialize()
    }

    /**
     * Sync group chats to system contacts for Google Assistant integration.
     *
     * This allows users to say "Send a message to [Group Name]" and have
     * Google Assistant recognize the group. Group chats appear as contacts
     * named "Group Name (BothBubbles)" in the system contacts.
     *
     * Features:
     * - Syncs on app launch to ensure contacts are always up-to-date
     * - Recreates any contacts the user may have deleted
     * - Uses our generated group avatars for contact photos
     */
    private fun initializeGroupContactSync() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                // Sync group chats to system contacts
                GroupContactSyncManager.performSyncSuspend(this@BothBubblesApp)
                Timber.d("Group contact sync completed")
            } catch (e: Exception) {
                Timber.w(e, "Error syncing group contacts")
            }
        }
    }

    /**
     * Auto-cache recent social media videos when Reels is enabled.
     * Runs in background, only on WiFi (unless cellular downloads are enabled).
     */
    private fun initializeSocialMediaCache() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                // Small delay to let other startup tasks complete first
                kotlinx.coroutines.delay(2000)

                // Run migration to populate social_media_links table and repair metadata
                // This handles users upgrading from older versions
                socialMediaLinkMigrationHelper.runMigrationIfNeeded()

                val cached = socialMediaDownloadService.cacheRecentVideos(maxVideos = 5)
                if (cached > 0) {
                    Timber.d("Auto-cached $cached social media videos")
                }
            } catch (e: Exception) {
                Timber.w(e, "Error auto-caching social media videos")
            }
        }
    }

    /**
     * Start Firebase Database listener for dynamic server URL sync.
     *
     * This listens to Firebase Realtime Database (with Firestore fallback) for server URL changes.
     * When the BlueBubbles server URL changes (e.g., dynamic DNS update), the listener
     * automatically updates the local settings and reconnects the socket.
     *
     * Only starts if setup is complete and Firebase is configured.
     */
    private fun initializeFirebaseDatabaseListener() {
        applicationScope.launch(ioDispatcher) {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                // Check if Firebase Database URL is configured
                val databaseUrl = settingsDataStore.firebaseDatabaseUrl.first()
                if (databaseUrl.isBlank()) {
                    Timber.d("Firebase Database URL not configured, skipping listener initialization")
                    return@launch
                }

                // Start listening for server URL changes
                firebaseDatabaseService.startListening()
                Timber.i("Firebase Database listener started for dynamic server URL sync")
            } catch (e: Exception) {
                Timber.w(e, "Error initializing Firebase Database listener")
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

    /**
     * Initialize AuthInterceptor credentials cache.
     * This must happen before any network requests to avoid runBlocking on OkHttp threads.
     */
    private fun initializeAuthInterceptor() {
        applicationScope.launch(ioDispatcher) {
            try {
                authInterceptor.preInitialize()
                Timber.d("AuthInterceptor credentials cache initialized")
            } catch (e: Exception) {
                Timber.w(e, "Error initializing AuthInterceptor - network requests will fail until credentials are available")
            }
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
    }
}
