package com.bothbubbles.services.sync

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.services.categorization.CategorizationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Helper for advanced sync operations like clean sync, unified resync, and data cleanup.
 * Handles database clearing and concurrent iMessage/SMS imports.
 */
class SyncOperations @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val smsRepository: SmsRepository,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val settingsDataStore: SettingsDataStore,
    private val categorizationRepository: CategorizationRepository,
    private val syncRangeTracker: SyncRangeTracker
) {
    companion object {
        private const val TAG = "SyncOperations"
        private const val CHAT_PAGE_SIZE = 50
    }

    /**
     * Purge all data and reimport with unified chats.
     * This creates proper iMessage/SMS conversation merging for single contacts.
     * Groups (both SMS/MMS and iMessage) remain separate.
     *
     * iMessage and SMS import run concurrently for faster sync.
     * Race conditions on unified chat creation are handled by:
     * - Atomic getOrCreate in UnifiedChatDao
     * - iMessage claiming primary status if SMS created the unified chat first
     *
     * @param performInitialSync Function to perform initial iMessage sync
     * @param onStateUpdate Callback for updating sync state
     */
    suspend fun performUnifiedResync(
        performInitialSync: suspend ((Int, Int, Int, Int) -> Unit) -> Result<Unit>,
        onStateUpdate: (SyncState) -> Unit
    ): Result<Unit> = runCatching {
        Timber.i("Starting unified resync - purging all data (concurrent mode)")
        onStateUpdate(SyncState.Syncing(0f, "Clearing existing data..."))

        // Clear all existing data including unified chats
        messageDao.deleteAllMessages()
        chatDao.deleteAllChatHandleCrossRefs()
        chatDao.deleteAllChats()
        handleDao.deleteAllHandles()
        unifiedChatDao.deleteAll()

        // Clear sync range tracking
        syncRangeTracker.clearAllRanges()

        // Reset sync markers and clear sync progress
        settingsDataStore.setLastSyncTime(0L)
        settingsDataStore.clearSyncProgress()

        onStateUpdate(
            SyncState.Syncing(
                progress = 0.1f,
                stage = "Importing messages...",
                isInitialSync = true
            )
        )

        // Initialize progress tracker
        val progressTracker = SyncProgressTracker()

        // Helper to update progress display - tracks iMessage and SMS separately
        fun updateProgress() {
            val imPct = progressTracker.iMessageProgress.get()
            val smsPct = progressTracker.smsProgress.get()
            val imComplete = imPct >= 100
            val smsIsDone = smsPct >= 100
            val smsTotal = progressTracker.smsTotalThreads.get()
            val smsCurrent = progressTracker.smsCurrentThread.get()
            val smsProgressFloat = if (smsTotal > 0) smsCurrent.toFloat() / smsTotal else 0f

            // Combined progress: 10% base + 85% for actual work (split between iMessage and SMS)
            val combinedProgress = progressTracker.calculateUnifiedResyncProgress()
            onStateUpdate(
                SyncState.Syncing(
                    progress = combinedProgress,
                    stage = "Importing messages...",
                    isInitialSync = true,
                    iMessageProgress = imPct / 100f,
                    iMessageComplete = imComplete,
                    smsProgress = smsProgressFloat,
                    smsComplete = smsIsDone,
                    smsCurrent = smsCurrent,
                    smsTotal = smsTotal
                )
            )
        }

        // Run iMessage and SMS import concurrently
        coroutineScope {
            // iMessage sync (server data)
            val iMessageJob = async {
                Timber.i("Starting concurrent iMessage sync")
                performInitialSync { progress, _, _, _ ->
                    progressTracker.iMessageProgress.set(progress)
                    updateProgress()
                }.getOrThrow()
                progressTracker.iMessageProgress.set(100)
                updateProgress()
                Timber.i("Concurrent iMessage sync completed")
            }

            // SMS import (local device data)
            val smsJob = async {
                Timber.i("Starting concurrent SMS import")
                smsRepository.importAllThreads(
                    onProgress = { current, total ->
                        progressTracker.smsCurrentThread.set(current)
                        progressTracker.smsTotalThreads.set(total)
                        progressTracker.smsProgress.set((current * 100) / maxOf(total, 1))
                        updateProgress()
                    }
                )
                progressTracker.smsProgress.set(100)
                updateProgress()
                Timber.i("Concurrent SMS import completed")
            }

            // Wait for both to complete
            iMessageJob.await()
            smsJob.await()
        }

        onStateUpdate(SyncState.Completed)
        Timber.i("Unified resync completed (concurrent)")
        Unit
    }.onFailure { e ->
        Timber.e(e, "Unified resync failed")
        onStateUpdate(SyncState.Error(e.message ?: "Unified resync failed"))
    }

    /**
     * Clear all local data and perform fresh sync.
     *
     * @param performInitialSync Function to perform initial iMessage sync
     * @param onStateUpdate Callback for updating sync state
     * @param onLastSyncTimeUpdate Callback for updating last sync time
     */
    suspend fun performCleanSync(
        performInitialSync: suspend () -> Result<Unit>,
        onStateUpdate: (SyncState) -> Unit,
        onLastSyncTimeUpdate: (Long) -> Unit
    ): Result<Unit> = runCatching {
        Timber.tag(TAG).i("performCleanSync() STARTED - clearing local data")
        onStateUpdate(
            SyncState.Syncing(
                progress = 0f,
                stage = "Clearing local data...",
                isInitialSync = true
            )
        )

        // Clear all messages and chats
        messageDao.deleteAllMessages()
        chatDao.deleteAllChats()

        // Clear sync range tracking
        syncRangeTracker.clearAllRanges()

        // Reset sync time and clear sync progress
        settingsDataStore.setLastSyncTime(0L)
        settingsDataStore.clearSyncProgress()
        onLastSyncTimeUpdate(0L)

        // Perform initial sync
        performInitialSync().getOrThrow()
    }

    /**
     * Clean up server data (iMessage) while keeping local SMS/MMS.
     * Used when disconnecting from BlueBubbles server.
     *
     * @param onStateUpdate Callback for updating sync state
     * @param onLastSyncTimeUpdate Callback for updating last sync time
     */
    suspend fun cleanupServerData(
        onStateUpdate: (SyncState) -> Unit,
        onLastSyncTimeUpdate: (Long) -> Unit
    ): Result<Unit> = runCatching {
        Timber.i("Cleaning up server data - keeping local SMS/MMS")
        onStateUpdate(SyncState.Syncing(0f, "Removing server data..."))

        // Delete server messages (iMessage)
        onStateUpdate(SyncState.Syncing(0.3f, "Removing server messages..."))
        messageDao.deleteServerMessages()

        // Delete cross-references for server chats
        onStateUpdate(SyncState.Syncing(0.6f, "Removing server conversations..."))
        chatDao.deleteServerChatCrossRefs()

        // Delete server chats
        chatDao.deleteServerChats()

        // Reset sync time since server data is gone
        settingsDataStore.setLastSyncTime(0L)
        onLastSyncTimeUpdate(0L)

        onStateUpdate(SyncState.Completed)
        Timber.i("Server data cleanup completed")
        Unit
    }.onFailure { e ->
        Timber.e(e, "Server data cleanup failed")
        onStateUpdate(SyncState.Error(e.message ?: "Cleanup failed"))
    }

    /**
     * Perform incremental sync - downloads only new messages since last sync.
     * Runs silently in the background without updating UI progress state.
     *
     * @param onLastSyncTimeUpdate Callback for updating last sync time
     */
    suspend fun performIncrementalSync(
        onLastSyncTimeUpdate: (Long) -> Unit
    ): Result<Unit> = runCatching {
        val lastSync = settingsDataStore.lastSyncTime.first()
        if (lastSync == 0L) {
            throw IllegalStateException("Cannot perform incremental sync without initial sync")
        }

        Timber.i("Starting incremental sync from $lastSync")

        // Sync chats first to get any new conversations
        chatRepository.syncChats(
            limit = CHAT_PAGE_SIZE,
            offset = 0
        )

        // Fetch all new messages globally in a single API call
        val newMessageCount = messageRepository.syncMessagesGlobally(
            after = lastSync,
            limit = 1000
        ).getOrElse { e ->
            Timber.e(e, "Global message sync failed")
            0
        }

        // Update last sync time
        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        onLastSyncTimeUpdate(syncTime)

        Timber.i("Incremental sync completed: $newMessageCount new messages")
        Unit
    }.onFailure { e ->
        Timber.e(e, "Incremental sync failed")
        // Don't update syncState for background incremental sync failures
    }

    /**
     * Run retroactive categorization if enabled.
     * Called after sync completes.
     */
    suspend fun runCategorizationIfEnabled() {
        val categorizationEnabled = settingsDataStore.categorizationEnabled.first()
        if (categorizationEnabled) {
            Timber.i("Starting retroactive categorization...")
            val categorized = categorizationRepository.categorizeAllChats()
            Timber.i("Retroactive categorization complete: $categorized chats")
        }
    }
}
