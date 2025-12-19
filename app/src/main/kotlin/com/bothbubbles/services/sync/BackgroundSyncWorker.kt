package com.bothbubbles.services.sync

import android.content.Context
import timber.log.Timber
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.entity.rawDisplayName
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.services.AppLifecycleTracker
import com.bothbubbles.services.contacts.AndroidContactsService
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.util.PhoneNumberFormatter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically syncs messages from the BlueBubbles server.
 *
 * This is a fallback mechanism to catch any messages that were missed by
 * Socket.IO push or FCM notifications while the app was in the background.
 *
 * Features:
 * - Runs every 15 minutes (Android minimum for periodic work)
 * - Only runs when network is available
 * - Syncs recent chats only (last 24 hours of activity)
 * - Shows notifications for new messages from others
 */
@HiltWorker
class BackgroundSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val handleDao: HandleDao,
    private val settingsDataStore: SettingsDataStore,
    private val notificationService: NotificationService,
    private val appLifecycleTracker: AppLifecycleTracker,
    private val androidContactsService: AndroidContactsService
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BackgroundSyncWorker"
        private const val WORK_NAME = "background_message_sync"

        // Only sync chats with activity in the last 24 hours
        private const val RECENT_CHAT_THRESHOLD_MS = 24 * 60 * 60 * 1000L

        // Fetch up to 20 recent messages per chat
        private const val MESSAGES_PER_CHAT = 20

        // Limit to 10 most recent chats to avoid excessive API calls
        private const val MAX_CHATS_TO_SYNC = 10

        /**
         * Schedule the background sync worker.
         * Should be called once when setup is complete.
         *
         * Constraints:
         * - Requires network connectivity (can't sync without server)
         * - Runs when device is not in battery saver mode (BATTERY_NOT_LOW)
         *   This is appropriate because message sync is non-critical background work
         *   and can be deferred when battery is low. Critical messages should arrive
         *   via Socket.IO push (foreground) or FCM (background with high priority).
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true) // Defer when battery is low
                .build()

            val workRequest = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                15, TimeUnit.MINUTES // Android minimum for periodic work
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
                workRequest
            )

            Timber.i("Background sync scheduled (every 15 minutes, network + battery constraints)")
        }

        /**
         * Cancel the background sync worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("Background sync cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Timber.d("Starting background sync")

        // Check if setup is complete
        val setupComplete = settingsDataStore.isSetupComplete.first()
        if (!setupComplete) {
            Timber.d("Setup not complete, skipping sync")
            return Result.success()
        }

        // Check if server is configured
        val serverAddress = settingsDataStore.serverAddress.first()
        if (serverAddress.isBlank()) {
            Timber.d("Server not configured, skipping sync")
            return Result.success()
        }

        return try {
            syncRecentChats()
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Background sync failed")
            Result.retry() // WorkManager will handle backoff
        }
    }

    private suspend fun syncRecentChats() {
        val recentChats = chatDao.getRecentChats(limit = MAX_CHATS_TO_SYNC)

        if (recentChats.isEmpty()) {
            Timber.d("No recent chats to sync")
            return
        }

        Timber.d("Syncing ${recentChats.size} recent chats")

        var totalNewMessages = 0

        for (chat in recentChats) {
            // Skip local SMS chats (no server to sync from)
            if (messageRepository.isLocalSmsChat(chat.guid)) {
                continue
            }

            try {
                // Get the newest message we have locally for this chat
                val newestLocalMessage = messageDao.getLatestMessageForChat(chat.guid)
                val afterTimestamp = newestLocalMessage?.dateCreated

                // Sync messages from server
                val result = messageRepository.syncMessagesForChat(
                    chatGuid = chat.guid,
                    limit = MESSAGES_PER_CHAT,
                    after = afterTimestamp
                )

                result.onSuccess { newMessages ->
                    if (newMessages.isNotEmpty()) {
                        Timber.d("Chat ${chat.guid}: found ${newMessages.size} new messages")
                        totalNewMessages += newMessages.size

                        // Show notifications for new messages from others if app is not in foreground
                        // Only notify for messages newer than what we had before (not old catch-up messages)
                        if (!appLifecycleTracker.isAppInForeground && afterTimestamp != null) {
                            showNotificationsForMessages(chat, newMessages, afterTimestamp)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w("Failed to sync chat ${chat.guid}: ${e.message}")
                // Continue with other chats
            }
        }

        Timber.d("Background sync complete: $totalNewMessages new messages across ${recentChats.size} chats")
    }

    /**
     * Show notifications for messages found during background sync.
     * Only shows notifications for messages not from me AND newer than our previous newest message.
     * @param previousNewestTimestamp The timestamp of our newest message before this sync
     */
    private suspend fun showNotificationsForMessages(
        chat: com.bothbubbles.data.local.db.entity.ChatEntity,
        messages: List<com.bothbubbles.data.local.db.entity.MessageEntity>,
        previousNewestTimestamp: Long
    ) {
        // Check notification settings for this chat
        if (chat.notificationsEnabled == false) {
            Timber.d("Notifications disabled for chat ${chat.guid}")
            return
        }
        if (chat.isSnoozed) {
            Timber.d("Chat ${chat.guid} is snoozed")
            return
        }

        // Filter to only messages from others that are newer than what we had
        // This prevents notifications for old messages we're just now catching up on
        val messagesFromOthers = messages.filter {
            !it.isFromMe &&
            it.text?.isNotBlank() == true &&
            it.dateCreated > previousNewestTimestamp
        }
        if (messagesFromOthers.isEmpty()) return

        val isGroup = chat.isGroup

        // Fetch participants for chat title resolution and group avatar collage
        val participants = chatRepository.getParticipantsForChat(chat.guid)
        val participantNames = participants.map { it.rawDisplayName }
        val participantAvatarPaths = participants.map { it.cachedAvatarPath }

        // Use centralized chat title logic (same as conversation list)
        val chatTitle = chatRepository.resolveChatTitle(chat, participants)

        for (message in messagesFromOthers) {
            // Resolve sender info
            val (senderName, senderAvatarUri) = resolveSenderNameAndAvatar(message.handleId)

            // For group chats, use first name only
            val displaySenderName = if (isGroup && senderName != null) {
                extractFirstName(senderName)
            } else {
                senderName
            }

            // Get sender address for reply action
            val senderAddress = message.handleId?.let { handleId ->
                handleDao.getHandleById(handleId)?.address
            }

            Timber.d("Showing notification: chat=$chatTitle, message='${message.text?.take(30)}...'")

            notificationService.showMessageNotification(
                chatGuid = chat.guid,
                chatTitle = chatTitle,
                messageText = message.text ?: "",
                messageGuid = message.guid,
                senderName = displaySenderName,
                senderAddress = senderAddress,
                isGroup = isGroup,
                avatarUri = senderAvatarUri,
                linkPreviewTitle = null,
                linkPreviewDomain = null,
                participantNames = participantNames,
                participantAvatarPaths = participantAvatarPaths,
                subject = message.subject
            )
        }
    }

    /**
     * Resolve sender name and avatar from handle ID.
     */
    private suspend fun resolveSenderNameAndAvatar(handleId: Long?): Pair<String?, String?> {
        if (handleId == null) return null to null

        val handle = handleDao.getHandleById(handleId) ?: return null to null
        val address = handle.address

        // Try live contact lookup first
        val contactName = androidContactsService.getContactDisplayName(address)
        if (contactName != null) {
            val photoUri = androidContactsService.getContactPhotoUri(address)
            return contactName to photoUri
        }

        // Check for cached contact info
        if (handle.cachedDisplayName != null) {
            return handle.cachedDisplayName to handle.cachedAvatarPath
        }

        // Fall back to inferred name or formatted address
        return (handle.inferredName ?: PhoneNumberFormatter.format(address)) to null
    }

    /**
     * Extract first name from full name for cleaner group chat display.
     */
    private fun extractFirstName(fullName: String): String {
        val words = fullName.trim().split(Regex("\\s+"))
        for (word in words) {
            val cleaned = word.filter { it.isLetterOrDigit() }
            if (cleaned.isNotEmpty() && cleaned.any { it.isLetter() }) {
                return cleaned
            }
        }
        return words.firstOrNull()?.filter { it.isLetterOrDigit() } ?: fullName
    }
}
