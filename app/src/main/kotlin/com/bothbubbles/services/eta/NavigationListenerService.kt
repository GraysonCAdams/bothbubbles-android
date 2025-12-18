package com.bothbubbles.services.eta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import timber.log.Timber
import androidx.core.app.NotificationCompat
import com.bothbubbles.MainActivity
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.ChatQueryDao
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.util.PermissionStateMonitor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * NotificationListenerService that monitors navigation app notifications (Google Maps, Waze)
 * to extract ETA data for sharing with contacts.
 *
 * This service runs when notification access is granted and listens for navigation
 * notifications even when Android Auto is active.
 */
@AndroidEntryPoint
class NavigationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NavigationListenerSvc"
        const val CHANNEL_ETA_SHARING = "eta_sharing"
        const val CHANNEL_ETA_PROMPT = "eta_prompt"
        const val NOTIFICATION_ID_ETA_SHARING = 3000001
        const val NOTIFICATION_ID_ETA_PROMPT = 3000002

        const val ACTION_STOP_SHARING = "com.bothbubbles.action.STOP_ETA_SHARING"

        // Delay before showing prompt notification (to ensure navigation is stable)
        private const val NAVIGATION_STABLE_DELAY_MS = 20_000L // 20 seconds
    }

    @Inject
    lateinit var etaParser: NavigationEtaParser

    @Inject
    lateinit var etaSharingManager: EtaSharingManager

    @Inject
    lateinit var chatQueryDao: ChatQueryDao

    @Inject
    lateinit var activeConversationManager: ActiveConversationManager

    @Inject
    lateinit var permissionStateMonitor: PermissionStateMonitor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    // Track which navigation notifications we're monitoring
    private val activeNavigationNotifications = mutableSetOf<String>()

    // Track if we've already shown the prompt for this navigation session
    private var promptShownForSession = false
    private var pendingPromptRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("NavigationListenerService created")
        createNotificationChannels()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("NavigationListenerService destroyed")
        scope.cancel()
        pendingPromptRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.d("Notification listener connected")

        // Guard: Check if required permissions are available before accessing protected resources.
        // When the process restarts after permission revocation, skip initialization to avoid crash loop.
        if (!permissionStateMonitor.hasContactsPermission()) {
            Timber.w("Notification listener connected but contacts permission missing - skipping initialization")
            return
        }

        // Check for any existing navigation notifications
        try {
            activeNotifications?.forEach { sbn ->
                checkNavigationNotification(sbn)
            }
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException checking notifications - permission may have been revoked")
        } catch (e: Exception) {
            Timber.e(e, "Error checking existing notifications")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Guard: Ignore notifications if required permissions were revoked
        if (!permissionStateMonitor.hasContactsPermission()) {
            return
        }
        checkNavigationNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key

        if (activeNavigationNotifications.contains(key)) {
            activeNavigationNotifications.remove(key)
            Timber.d("Navigation notification removed: $key")

            // If no more navigation notifications, navigation has stopped
            if (activeNavigationNotifications.isEmpty()) {
                etaSharingManager.onNavigationStopped()
                cancelSharingNotification()
                cancelPromptNotification()
                // Reset session tracking for next navigation
                promptShownForSession = false
                pendingPromptRunnable?.let { handler.removeCallbacks(it) }
                pendingPromptRunnable = null
            }
        }
    }

    /**
     * Check if this is a navigation notification and process it
     */
    private fun checkNavigationNotification(sbn: StatusBarNotification) {
        val app = NavigationApp.fromPackage(sbn.packageName) ?: return

        // Skip non-navigation categories (Maps has many notification types)
        val category = sbn.notification?.category
        if (app == NavigationApp.GOOGLE_MAPS && category != Notification.CATEGORY_NAVIGATION) {
            return
        }

        val isNewSession = activeNavigationNotifications.isEmpty()
        Timber.d("Navigation notification from ${app.name}: ${sbn.key} (new session: $isNewSession)")
        activeNavigationNotifications.add(sbn.key)

        // Parse ETA data
        val etaData = etaParser.parse(sbn)
        if (etaData != null) {
            Timber.d("Parsed ETA: ${etaData.etaMinutes} min")
            etaSharingManager.onEtaUpdate(etaData)
            updateSharingNotificationIfActive(etaData)

            // Schedule prompt notification for new navigation sessions
            if (isNewSession && !promptShownForSession && !etaSharingManager.state.value.isSharing) {
                schedulePromptNotification(etaData)
            }
        } else {
            Timber.d("Could not parse ETA from notification")
        }
    }

    /**
     * Schedule the "Navigation Detected" prompt notification after a delay
     * to ensure navigation is stable and not just a quick check
     */
    private fun schedulePromptNotification(etaData: ParsedEtaData) {
        // Cancel any existing pending prompt
        pendingPromptRunnable?.let { handler.removeCallbacks(it) }

        pendingPromptRunnable = Runnable {
            // Double-check navigation is still active and not already sharing
            if (activeNavigationNotifications.isNotEmpty() &&
                !etaSharingManager.state.value.isSharing &&
                !promptShownForSession
            ) {
                showPromptNotification(etaData)
                promptShownForSession = true
            }
        }

        handler.postDelayed(pendingPromptRunnable!!, NAVIGATION_STABLE_DELAY_MS)
        Timber.d("Scheduled prompt notification in ${NAVIGATION_STABLE_DELAY_MS}ms")
    }

    /**
     * Create notification channels for ETA sharing
     */
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Channel for active sharing status (low priority, ongoing)
        val sharingChannel = NotificationChannel(
            CHANNEL_ETA_SHARING,
            "ETA Sharing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when you're sharing your ETA with someone"
            setShowBadge(false)
        }

        // Channel for prompt notifications (default priority for visibility)
        val promptChannel = NotificationChannel(
            CHANNEL_ETA_PROMPT,
            "Navigation Detected",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Suggests sharing your ETA when navigation starts"
            setShowBadge(true)
        }

        notificationManager.createNotificationChannel(sharingChannel)
        notificationManager.createNotificationChannel(promptChannel)
    }

    /**
     * Show the "Navigation Detected" prompt notification with Android Auto support
     */
    private fun showPromptNotification(etaData: ParsedEtaData) {
        scope.launch {
            // Try to get a suggested recipient
            val suggestedChat = getSuggestedRecipient()
            val recipientName = suggestedChat?.let {
                it.displayName ?: it.chatIdentifier ?: "contact"
            }

            val contentTitle = "Navigation Detected"
            val contentText = "ETA: ${EtaNotificationHelper.formatEtaMinutes(etaData.etaMinutes)}"

            // Create start sharing intent
            val startIntent = Intent(EtaSharingReceiver.ACTION_START_SHARING).apply {
                setPackage(packageName)
                suggestedChat?.let {
                    putExtra(EtaSharingReceiver.EXTRA_CHAT_GUID, it.guid)
                    putExtra(EtaSharingReceiver.EXTRA_DISPLAY_NAME, recipientName)
                }
            }
            val startPendingIntent = PendingIntent.getBroadcast(
                this@NavigationListenerService,
                1,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Create open app intent
            val openIntent = Intent(this@NavigationListenerService, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                this@NavigationListenerService,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Build the action button text
            val actionText = if (recipientName != null) {
                "Share with $recipientName"
            } else {
                "Share ETA"
            }

            val notification = NotificationCompat.Builder(this@NavigationListenerService, CHANNEL_ETA_PROMPT)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .addAction(
                    android.R.drawable.ic_menu_share,
                    actionText,
                    startPendingIntent
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                // Android Auto support via CarExtender
                .extend(
                    NotificationCompat.CarExtender()
                        .setUnreadConversation(
                            NotificationCompat.CarExtender.UnreadConversation.Builder(contentTitle)
                                .addMessage(contentText)
                                .setLatestTimestamp(System.currentTimeMillis())
                                .setReplyAction(startPendingIntent, null)
                                .build()
                        )
                )
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID_ETA_PROMPT, notification)
            Timber.d("Showed prompt notification (suggested: $recipientName)")
        }
    }

    /**
     * Get a suggested recipient for ETA sharing based on context:
     * 1. Currently active chat (if any)
     * 2. Most recently messaged non-group chat
     */
    private suspend fun getSuggestedRecipient(): com.bothbubbles.data.local.db.entity.ChatEntity? {
        // First, check if there's an active conversation
        val activeGuid = activeConversationManager.getActiveConversation()
        if (activeGuid != null) {
            val activeChat = chatQueryDao.getChatByGuid(activeGuid)
            if (activeChat != null && !activeChat.isGroup) {
                return activeChat
            }
        }

        // Fall back to most recent non-group chat
        return chatQueryDao.getRecentChats(5)
            .firstOrNull { !it.isGroup }
    }

    /**
     * Show or update the "sharing active" notification with Android Auto support
     */
    fun showSharingNotification(recipientName: String, etaMinutes: Int) {
        EtaNotificationHelper.showSharingNotification(this, recipientName, etaMinutes)
    }

    /**
     * Update sharing notification if currently sharing
     */
    private fun updateSharingNotificationIfActive(etaData: ParsedEtaData) {
        val session = etaSharingManager.state.value.session ?: return
        showSharingNotification(session.recipientDisplayName, etaData.etaMinutes)
    }

    /**
     * Cancel the sharing notification
     */
    fun cancelSharingNotification() {
        EtaNotificationHelper.cancelSharingNotification(this)
    }

    /**
     * Cancel the prompt notification
     */
    private fun cancelPromptNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID_ETA_PROMPT)
    }
}
