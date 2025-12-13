package com.bothbubbles.services.sync

import android.database.sqlite.SQLiteException
import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.db.entity.SyncSource
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.categorization.CategorizationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync state with detailed progress information
 */
sealed class SyncState {
    data object Idle : SyncState()

    data class Syncing(
        val progress: Float,
        val stage: String,
        val totalChats: Int = 0,
        val processedChats: Int = 0,
        val syncedMessages: Int = 0,
        val currentChatName: String? = null,
        val isInitialSync: Boolean = false,
        // Separate progress tracking for iMessage and SMS
        val iMessageProgress: Float = 0f,
        val iMessageComplete: Boolean = false,
        val smsProgress: Float = 0f,
        val smsComplete: Boolean = false,
        val smsCurrent: Int = 0,
        val smsTotal: Int = 0
    ) : SyncState()

    data object Completed : SyncState()

    data class Error(
        val message: String,
        val isCorrupted: Boolean = false,
        val canRetry: Boolean = true
    ) : SyncState()
}

@Singleton
class SyncService @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val smsRepository: SmsRepository,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageDao: MessageDao,
    private val unifiedChatGroupDao: UnifiedChatGroupDao,
    private val settingsDataStore: SettingsDataStore,
    private val categorizationRepository: CategorizationRepository,
    private val syncRangeTracker: SyncRangeTracker,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "SyncService"
        private const val CHAT_PAGE_SIZE = 50
        private const val MESSAGE_PAGE_SIZE = 25
        private const val MESSAGE_SYNC_CONCURRENCY = 3
    }

    /** Lightweight data for queuing chats for message sync */
    private data class ChatSyncTask(val guid: String, val displayName: String?)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    /**
     * Perform initial full sync
     * Downloads all chats and recent messages from the server concurrently.
     * Chat fetching and message syncing happen in tandem - as each page of chats
     * is fetched, those chats are immediately queued for message syncing.
     * Progress is persisted per-chat so sync can resume after app restart.
     *
     * When SMS is enabled and not yet imported, SMS import runs concurrently
     * with iMessage sync for faster initial setup.
     *
     * @param messagesPerChat Number of messages to sync per chat
     * @param onProgress Optional callback for progress updates (0-100). When provided,
     *                   internal _syncState updates are suppressed (caller manages state).
     */
    suspend fun performInitialSync(
        messagesPerChat: Int = MESSAGE_PAGE_SIZE,
        onProgress: ((progress: Int, processedChats: Int, totalChats: Int, syncedMessages: Int) -> Unit)? = null
    ): Result<Unit> = runCatching {
        Log.i(TAG, "Starting initial sync (concurrent mode)")

        // Mark sync as started and store settings for potential resume
        settingsDataStore.setInitialSyncStarted(true)
        settingsDataStore.setInitialSyncMessagesPerChat(messagesPerChat)

        // Check if we should also run SMS import concurrently
        val smsEnabled = settingsDataStore.smsEnabled.first()
        val smsAlreadyImported = settingsDataStore.hasCompletedInitialSmsImport.first()
        val shouldImportSms = smsEnabled && !smsAlreadyImported && onProgress == null

        if (shouldImportSms) {
            Log.i(TAG, "SMS enabled and not imported - will run concurrent SMS import")
        }

        // Only update internal state if no external progress handler
        if (onProgress == null) {
            _syncState.value = SyncState.Syncing(
                progress = 0f,
                stage = "Starting sync...",
                isInitialSync = true
            )
        }

        // Get already synced chats (for resume scenario)
        val alreadySynced = settingsDataStore.syncedChatGuids.first()

        // Atomic counters for thread-safe progress tracking
        val totalChatsFound = AtomicInteger(0)
        val processedChats = AtomicInteger(0)
        val syncedMessages = AtomicInteger(0)

        // SMS progress tracking (only used when SMS import runs concurrently)
        val smsCurrentThread = AtomicInteger(0)
        val smsTotalThreads = AtomicInteger(0)
        val smsComplete = AtomicInteger(0) // 0 = in progress, 1 = complete

        // Helper to update progress - tracks iMessage and SMS separately
        fun updateProgress(iMessageProgressPercent: Int, stage: String, chatName: String? = null) {
            val imProgress = iMessageProgressPercent / 100f
            val imComplete = iMessageProgressPercent >= 100
            val smsProgressPct = if (smsTotalThreads.get() > 0) {
                smsCurrentThread.get().toFloat() / smsTotalThreads.get()
            } else 0f
            val smsIsDone = smsComplete.get() == 1

            // Overall progress: if SMS is running, average both; otherwise just iMessage
            val overallProgress = if (shouldImportSms && smsTotalThreads.get() > 0) {
                (imProgress + smsProgressPct) / 2
            } else {
                imProgress
            }

            _syncState.value = SyncState.Syncing(
                progress = overallProgress,
                stage = stage,
                totalChats = totalChatsFound.get(),
                processedChats = processedChats.get(),
                syncedMessages = syncedMessages.get(),
                currentChatName = chatName,
                isInitialSync = true,
                iMessageProgress = imProgress,
                iMessageComplete = imComplete,
                smsProgress = smsProgressPct,
                smsComplete = smsIsDone,
                smsCurrent = smsCurrentThread.get(),
                smsTotal = smsTotalThreads.get()
            )
        }

        // Channel to queue chats for message syncing as they're fetched
        val chatQueue = Channel<ChatSyncTask>(Channel.UNLIMITED)

        // Semaphore to limit concurrent message sync operations
        val syncSemaphore = Semaphore(MESSAGE_SYNC_CONCURRENCY)

        coroutineScope {
            // Optional: Run SMS import concurrently if enabled
            val smsJob = if (shouldImportSms) {
                async {
                    Log.i(TAG, "Starting concurrent SMS import")
                    smsRepository.importAllThreads(
                        onProgress = { current, total ->
                            smsCurrentThread.set(current)
                            smsTotalThreads.set(total)
                        }
                    ).fold(
                        onSuccess = { count ->
                            smsComplete.set(1)
                            settingsDataStore.setHasCompletedInitialSmsImport(true)
                            Log.i(TAG, "Concurrent SMS import completed: $count threads")
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Concurrent SMS import failed", e)
                            // Don't mark as complete so it can retry later
                        }
                    )
                }
            } else null

            // Producer: Fetch chats page by page and queue them for message syncing
            val chatFetcher = async {
                var offset = 0

                if (onProgress == null) {
                    updateProgress(5, "Fetching conversations...")
                } else {
                    onProgress(5, 0, 0, 0)
                }

                do {
                    val result = chatRepository.syncChats(
                        limit = CHAT_PAGE_SIZE,
                        offset = offset
                    )

                    val chats = result.getOrThrow()
                    totalChatsFound.addAndGet(chats.size)
                    offset += CHAT_PAGE_SIZE

                    // Queue each chat for message syncing
                    chats.forEach { chat ->
                        if (chat.guid !in alreadySynced) {
                            chatQueue.send(ChatSyncTask(chat.guid, chat.displayName))
                        } else {
                            // Already synced from previous run
                            processedChats.incrementAndGet()
                        }
                    }

                    // Update progress - chat fetching is ~20% of total work
                    val fetchProgressPercent = minOf(20, 5 + (15 * offset / maxOf(totalChatsFound.get(), 100)))
                    if (onProgress == null) {
                        updateProgress(fetchProgressPercent, "Fetching conversations...")
                    } else {
                        onProgress(fetchProgressPercent, processedChats.get(), totalChatsFound.get(), syncedMessages.get())
                    }
                } while (result.getOrNull()?.size == CHAT_PAGE_SIZE)

                chatQueue.close()
                Log.i(TAG, "Chat fetching complete: ${totalChatsFound.get()} chats found")
            }

            // Consumer: Sync messages for chats as they arrive, with limited concurrency
            val messageSyncer = async {
                val syncJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                for (task in chatQueue) {
                    val job = async {
                        syncSemaphore.withPermit {
                            try {
                                val result = messageRepository.syncMessagesForChat(
                                    chatGuid = task.guid,
                                    limit = messagesPerChat,
                                    syncSource = SyncSource.INITIAL
                                )
                                syncedMessages.addAndGet(result.getOrNull()?.size ?: 0)
                                settingsDataStore.markChatSynced(task.guid)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to sync messages for chat ${task.guid}", e)
                                // Mark as synced to avoid retry loop
                                settingsDataStore.markChatSynced(task.guid)
                            }

                            val processed = processedChats.incrementAndGet()
                            val total = totalChatsFound.get()

                            // Update progress - message syncing is ~80% of total work (20% to 90%)
                            val messageProgressPercent = if (total > 0) {
                                20 + (70 * processed / total)
                            } else 20

                            if (onProgress == null) {
                                updateProgress(messageProgressPercent, "Syncing messages...", task.displayName)
                            } else {
                                onProgress(messageProgressPercent, processed, total, syncedMessages.get())
                            }
                        }
                    }
                    syncJobs.add(job)
                }

                // Wait for all message sync jobs to complete
                syncJobs.forEach { it.await() }
            }

            // Wait for iMessage sync (both producer and consumer) to complete
            chatFetcher.await()
            messageSyncer.await()

            // Wait for SMS import if it was started
            smsJob?.await()
        }

        Log.i(TAG, "Synced messages for ${processedChats.get()}/${totalChatsFound.get()} chats (${syncedMessages.get()} messages)")

        // Update last sync time
        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        _lastSyncTime.value = syncTime

        // Mark initial sync as complete
        settingsDataStore.setInitialSyncComplete(true)

        // Run retroactive categorization after sync completes (only if enabled)
        val categorizationEnabled = settingsDataStore.categorizationEnabled.first()
        if (categorizationEnabled) {
            Log.i(TAG, "Starting retroactive categorization after sync...")
            val categorized = categorizationRepository.categorizeAllChats()
            Log.i(TAG, "Retroactive categorization complete: $categorized chats")
        }

        // Only update internal state if no external progress handler
        if (onProgress == null) {
            _syncState.value = SyncState.Completed
        } else {
            onProgress(100, processedChats.get(), totalChatsFound.get(), syncedMessages.get())
        }
        Log.i(TAG, "Initial sync completed")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "Initial sync failed", e)
        // Only update internal state on error if no external handler
        // (caller should handle errors from Result)
        if (onProgress == null) {
            val isCorrupted = e is SQLiteException ||
                e.message?.contains("database", ignoreCase = true) == true &&
                (e.message?.contains("corrupt", ignoreCase = true) == true ||
                 e.message?.contains("malformed", ignoreCase = true) == true)
            _syncState.value = SyncState.Error(
                message = if (isCorrupted) "Database corruption detected" else (e.message ?: "Sync failed"),
                isCorrupted = isCorrupted,
                canRetry = !isCorrupted
            )
        }
    }

    /**
     * Resume an interrupted initial sync.
     * Called on app startup if initialSyncStarted=true but initialSyncComplete=false.
     * Skips already-synced chats and continues from where it left off.
     * Uses concurrent message syncing for better performance.
     */
    suspend fun resumeInitialSync(): Result<Unit> = runCatching {
        Log.i(TAG, "Resuming interrupted initial sync (concurrent mode)")

        val alreadySynced = settingsDataStore.syncedChatGuids.first()
        val messagesPerChat = settingsDataStore.initialSyncMessagesPerChat.first()
        val allChats = chatDao.getAllChats().first()
        val remainingChats = allChats.filter { it.guid !in alreadySynced }

        if (remainingChats.isEmpty()) {
            Log.i(TAG, "No remaining chats to sync - marking complete")
            settingsDataStore.setInitialSyncComplete(true)
            _syncState.value = SyncState.Completed
            return@runCatching
        }

        Log.i(TAG, "Resuming sync: ${remainingChats.size} chats remaining (${alreadySynced.size} already synced)")

        val totalChats = allChats.size
        val processedChats = AtomicInteger(alreadySynced.size)
        val syncedMessages = AtomicInteger(0)

        _syncState.value = SyncState.Syncing(
            progress = 0.2f + (0.7f * (processedChats.get().toFloat() / totalChats)),
            stage = "Resuming sync...",
            totalChats = totalChats,
            processedChats = processedChats.get(),
            syncedMessages = 0,
            isInitialSync = true
        )

        // Semaphore to limit concurrent message sync operations
        val syncSemaphore = Semaphore(MESSAGE_SYNC_CONCURRENCY)

        coroutineScope {
            val syncJobs = remainingChats.map { chat ->
                async {
                    syncSemaphore.withPermit {
                        try {
                            val result = messageRepository.syncMessagesForChat(
                                chatGuid = chat.guid,
                                limit = messagesPerChat,
                                syncSource = SyncSource.INITIAL
                            )
                            syncedMessages.addAndGet(result.getOrNull()?.size ?: 0)
                            settingsDataStore.markChatSynced(chat.guid)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to sync messages for chat ${chat.guid}", e)
                            settingsDataStore.markChatSynced(chat.guid)
                        }

                        val processed = processedChats.incrementAndGet()
                        _syncState.value = SyncState.Syncing(
                            progress = 0.2f + (0.7f * (processed.toFloat() / totalChats)),
                            stage = "Syncing messages...",
                            totalChats = totalChats,
                            processedChats = processed,
                            syncedMessages = syncedMessages.get(),
                            currentChatName = chat.displayName,
                            isInitialSync = true
                        )
                    }
                }
            }

            // Wait for all sync jobs to complete
            syncJobs.forEach { it.await() }
        }

        Log.i(TAG, "Resume sync completed: ${syncedMessages.get()} messages synced")

        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        _lastSyncTime.value = syncTime

        settingsDataStore.setInitialSyncComplete(true)

        // Run retroactive categorization after resume sync completes (only if enabled)
        val categorizationEnabled = settingsDataStore.categorizationEnabled.first()
        if (categorizationEnabled) {
            Log.i(TAG, "Starting retroactive categorization after resume sync...")
            val categorized = categorizationRepository.categorizeAllChats()
            Log.i(TAG, "Retroactive categorization complete: $categorized chats")
        }

        _syncState.value = SyncState.Completed
    }.onFailure { e ->
        Log.e(TAG, "Resume sync failed", e)
        val isCorrupted = e is SQLiteException ||
            e.message?.contains("database", ignoreCase = true) == true &&
            (e.message?.contains("corrupt", ignoreCase = true) == true ||
             e.message?.contains("malformed", ignoreCase = true) == true)
        _syncState.value = SyncState.Error(
            message = if (isCorrupted) "Database corruption detected" else (e.message ?: "Sync failed"),
            isCorrupted = isCorrupted,
            canRetry = !isCorrupted
        )
    }

    /**
     * Priority sync for a specific chat.
     * Used when user opens a chat that hasn't been synced yet.
     * This bumps the chat to immediate sync without blocking UI.
     */
    fun prioritizeChatSync(chatGuid: String, limit: Int = MESSAGE_PAGE_SIZE) {
        applicationScope.launch(ioDispatcher) {
            try {
                Log.i(TAG, "Priority syncing chat: $chatGuid")
                messageRepository.syncMessagesForChat(
                    chatGuid = chatGuid,
                    limit = limit
                )
                // Mark as synced if we're in initial sync
                val syncStarted = settingsDataStore.initialSyncStarted.first()
                val syncComplete = settingsDataStore.initialSyncComplete.first()
                if (syncStarted && !syncComplete) {
                    settingsDataStore.markChatSynced(chatGuid)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Priority sync failed for chat $chatGuid", e)
            }
        }
    }

    /**
     * Check if a specific chat has been synced during initial sync.
     */
    suspend fun isChatSynced(chatGuid: String): Boolean {
        val syncComplete = settingsDataStore.initialSyncComplete.first()
        if (syncComplete) return true
        val syncedGuids = settingsDataStore.syncedChatGuids.first()
        return chatGuid in syncedGuids
    }

    /**
     * Perform incremental sync
     * Downloads only new messages since last sync using a single API call.
     * Runs silently in the background without updating UI progress state.
     */
    suspend fun performIncrementalSync(): Result<Unit> = runCatching {
        val lastSync = settingsDataStore.lastSyncTime.first()
        if (lastSync == 0L) {
            // No previous sync, perform initial
            performInitialSync().getOrThrow()
            return@runCatching
        }

        Log.i(TAG, "Starting incremental sync from $lastSync")

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
            Log.e(TAG, "Global message sync failed", e)
            0
        }

        // Update last sync time
        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        _lastSyncTime.value = syncTime

        Log.i(TAG, "Incremental sync completed: $newMessageCount new messages")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "Incremental sync failed", e)
        // Don't update syncState for background incremental sync failures
    }

    /**
     * Sync a specific chat's messages
     */
    suspend fun syncChat(chatGuid: String, limit: Int = 50): Result<Unit> = runCatching {
        _syncState.value = SyncState.Syncing(0.5f, "Syncing conversation...")

        messageRepository.syncMessagesForChat(
            chatGuid = chatGuid,
            limit = limit
        ).getOrThrow()

        _syncState.value = SyncState.Completed
    }.onFailure { e ->
        _syncState.value = SyncState.Error(e.message ?: "Sync failed")
    }

    /**
     * Load more messages for a chat (pagination)
     */
    suspend fun loadMoreMessages(chatGuid: String, beforeTimestamp: Long): Result<Unit> = runCatching {
        messageRepository.syncMessagesForChat(
            chatGuid = chatGuid,
            before = beforeTimestamp,
            limit = MESSAGE_PAGE_SIZE
        ).getOrThrow()
    }

    /**
     * Clear all local data and perform fresh sync
     */
    suspend fun performCleanSync(): Result<Unit> = runCatching {
        Log.i(TAG, "Performing clean sync - clearing local data")
        _syncState.value = SyncState.Syncing(
            progress = 0f,
            stage = "Clearing local data...",
            isInitialSync = true
        )

        // Clear all messages and chats
        messageDao.deleteAllMessages()
        chatDao.deleteAllChats()

        // Clear sync range tracking
        syncRangeTracker.clearAllRanges()

        // Reset sync time and clear sync progress
        settingsDataStore.setLastSyncTime(0L)
        settingsDataStore.clearSyncProgress()
        _lastSyncTime.value = 0L

        // Perform initial sync
        performInitialSync().getOrThrow()
    }

    /**
     * Purge all data and reimport with unified chat groups.
     * This creates proper iMessage/SMS conversation merging for single contacts.
     * Groups (both SMS/MMS and iMessage) remain separate.
     *
     * iMessage and SMS import run concurrently for faster sync.
     * Race conditions on unified group creation are handled by:
     * - Atomic getOrCreateGroup in UnifiedChatGroupDao
     * - iMessage claiming primary status if SMS created the group first
     */
    suspend fun performUnifiedResync(): Result<Unit> = runCatching {
        Log.i(TAG, "Starting unified resync - purging all data (concurrent mode)")
        _syncState.value = SyncState.Syncing(0f, "Clearing existing data...")

        // Clear all existing data including unified groups
        messageDao.deleteAllMessages()
        chatDao.deleteAllChatHandleCrossRefs()
        chatDao.deleteAllChats()
        handleDao.deleteAllHandles()
        unifiedChatGroupDao.deleteAllData()

        // Clear sync range tracking
        syncRangeTracker.clearAllRanges()

        // Reset sync markers and clear sync progress
        settingsDataStore.setLastSyncTime(0L)
        settingsDataStore.clearSyncProgress()
        _lastSyncTime.value = 0L

        _syncState.value = SyncState.Syncing(
            progress = 0.1f,
            stage = "Importing messages...",
            isInitialSync = true
        )

        // Track progress from both sources (0-100 each)
        val iMessageProgress = AtomicInteger(0)
        val smsProgress = AtomicInteger(0)
        val smsCurrentThread = AtomicInteger(0)
        val smsTotalThreads = AtomicInteger(0)

        // Helper to update progress display - tracks iMessage and SMS separately
        fun updateProgress() {
            val imPct = iMessageProgress.get()
            val smsPct = smsProgress.get()
            val imComplete = imPct >= 100
            val smsIsDone = smsPct >= 100
            val smsTotal = smsTotalThreads.get()
            val smsCurrent = smsCurrentThread.get()
            val smsProgressFloat = if (smsTotal > 0) smsCurrent.toFloat() / smsTotal else 0f

            // Combined progress: 10% base + 85% for actual work (split between iMessage and SMS)
            val combinedProgress = 0.1f + (0.85f * (imPct + smsPct) / 200f)
            _syncState.value = SyncState.Syncing(
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
        }

        // Run iMessage and SMS import concurrently
        coroutineScope {
            // iMessage sync (server data)
            val iMessageJob = async {
                Log.i(TAG, "Starting concurrent iMessage sync")
                performInitialSync(
                    onProgress = { progress, _, _, _ ->
                        iMessageProgress.set(progress)
                        updateProgress()
                    }
                ).getOrThrow()
                iMessageProgress.set(100)
                updateProgress()
                Log.i(TAG, "Concurrent iMessage sync completed")
            }

            // SMS import (local device data)
            val smsJob = async {
                Log.i(TAG, "Starting concurrent SMS import")
                smsRepository.importAllThreads(
                    onProgress = { current, total ->
                        smsCurrentThread.set(current)
                        smsTotalThreads.set(total)
                        smsProgress.set((current * 100) / maxOf(total, 1))
                        updateProgress()
                    }
                )
                smsProgress.set(100)
                updateProgress()
                Log.i(TAG, "Concurrent SMS import completed")
            }

            // Wait for both to complete
            iMessageJob.await()
            smsJob.await()
        }

        _syncState.value = SyncState.Completed
        Log.i(TAG, "Unified resync completed (concurrent)")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "Unified resync failed", e)
        _syncState.value = SyncState.Error(e.message ?: "Unified resync failed")
    }

    /**
     * Clean up server data (iMessage) while keeping local SMS/MMS
     * Used when disconnecting from BlueBubbles server
     */
    suspend fun cleanupServerData(): Result<Unit> = runCatching {
        Log.i(TAG, "Cleaning up server data - keeping local SMS/MMS")
        _syncState.value = SyncState.Syncing(0f, "Removing server data...")

        // Delete server messages (iMessage)
        _syncState.value = SyncState.Syncing(0.3f, "Removing server messages...")
        messageDao.deleteServerMessages()

        // Delete cross-references for server chats
        _syncState.value = SyncState.Syncing(0.6f, "Removing server conversations...")
        chatDao.deleteServerChatCrossRefs()

        // Delete server chats
        chatDao.deleteServerChats()

        // Reset sync time since server data is gone
        settingsDataStore.setLastSyncTime(0L)
        _lastSyncTime.value = 0L

        _syncState.value = SyncState.Completed
        Log.i(TAG, "Server data cleanup completed")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "Server data cleanup failed", e)
        _syncState.value = SyncState.Error(e.message ?: "Cleanup failed")
    }

    /**
     * Start initial sync in the service's own scope.
     * This survives ViewModel destruction, allowing sync to continue in background.
     * Called from SetupViewModel after server connection is established.
     */
    fun startInitialSync(messagesPerChat: Int = MESSAGE_PAGE_SIZE) {
        // Skip if sync already in progress (coalescing)
        if (_syncState.value !is SyncState.Idle) {
            Log.d(TAG, "Skipping initial sync - sync already in progress")
            return
        }
        applicationScope.launch(ioDispatcher) {
            performInitialSync(messagesPerChat)
        }
    }

    /**
     * Start background sync (called periodically)
     */
    fun startBackgroundSync() {
        // Skip if sync already in progress (coalescing)
        if (_syncState.value !is SyncState.Idle) {
            Log.d(TAG, "Skipping background sync - sync already in progress")
            return
        }
        applicationScope.launch(ioDispatcher) {
            performIncrementalSync()
        }
    }

    /**
     * Reset sync state to idle
     */
    fun resetState() {
        _syncState.value = SyncState.Idle
    }
}
