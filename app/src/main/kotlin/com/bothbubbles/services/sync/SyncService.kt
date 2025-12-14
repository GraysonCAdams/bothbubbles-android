package com.bothbubbles.services.sync

import android.database.sqlite.SQLiteException
import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.entity.SyncSource
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.SmsRepository
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.util.NetworkConfig
import com.bothbubbles.util.retryWithBackoffResult
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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncService @Inject constructor(
    private val chatDao: ChatDao,
    private val messageRepository: MessageRepository,
    private val settingsDataStore: SettingsDataStore,
    private val chatSyncHelper: ChatSyncHelper,
    private val messageSyncHelper: MessageSyncHelper,
    private val syncOperations: SyncOperations,
    private val smsRepository: SmsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "SyncService"
        private const val CHAT_PAGE_SIZE = 50
        private const val MESSAGE_PAGE_SIZE = 25
    }

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
    ): Result<Unit> = retryWithBackoffResult(
        times = NetworkConfig.DEFAULT_RETRY_ATTEMPTS,
        initialDelayMs = NetworkConfig.DEFAULT_INITIAL_DELAY_MS,
        maxDelayMs = NetworkConfig.DEFAULT_MAX_DELAY_MS
    ) {
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

        // Initialize progress tracker
        val progressTracker = SyncProgressTracker()

        // Helper to update progress - tracks iMessage and SMS separately
        fun updateProgress(iMessageProgressPercent: Int, stage: String, chatName: String? = null) {
            val imProgress = iMessageProgressPercent / 100f
            val imComplete = iMessageProgressPercent >= 100
            val smsProgressPct = progressTracker.calculateSmsProgress()
            val smsIsDone = progressTracker.smsComplete.get() == 1

            // Overall progress: if SMS is running, average both; otherwise just iMessage
            val overallProgress = if (shouldImportSms && progressTracker.smsTotalThreads.get() > 0) {
                (imProgress + smsProgressPct) / 2
            } else {
                imProgress
            }

            _syncState.value = SyncState.Syncing(
                progress = overallProgress,
                stage = stage,
                totalChats = progressTracker.totalChatsFound.get(),
                processedChats = progressTracker.processedChats.get(),
                syncedMessages = progressTracker.syncedMessages.get(),
                currentChatName = chatName,
                isInitialSync = true,
                iMessageProgress = imProgress,
                iMessageComplete = imComplete,
                smsProgress = smsProgressPct,
                smsComplete = smsIsDone,
                smsCurrent = progressTracker.smsCurrentThread.get(),
                smsTotal = progressTracker.smsTotalThreads.get()
            )
        }

        // Channel to queue chats for message syncing as they're fetched
        val chatQueue = Channel<ChatSyncHelper.ChatSyncTask>(Channel.UNLIMITED)

        coroutineScope {
            // Optional: Run SMS import concurrently if enabled
            val smsJob = if (shouldImportSms) {
                async {
                    Log.i(TAG, "Starting concurrent SMS import")
                    smsRepository.importAllThreads(
                        onProgress = { current, total ->
                            progressTracker.smsCurrentThread.set(current)
                            progressTracker.smsTotalThreads.set(total)
                        }
                    ).fold(
                        onSuccess = { count ->
                            progressTracker.smsComplete.set(1)
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
                chatSyncHelper.fetchAndQueueChats(
                    alreadySynced = alreadySynced,
                    progressTracker = progressTracker,
                    chatQueue = chatQueue,
                    onProgress = { progress ->
                        if (onProgress == null) {
                            updateProgress(progress, "Fetching conversations...")
                        } else {
                            onProgress(progress, 0, 0, 0)
                        }
                    }
                )
            }

            // Consumer: Sync messages for chats as they arrive, with limited concurrency
            val messageSyncer = async {
                messageSyncHelper.syncMessagesForQueuedChats(
                    chatQueue = chatQueue,
                    messagesPerChat = messagesPerChat,
                    syncSource = SyncSource.INITIAL,
                    progressTracker = progressTracker,
                    onProgress = { progress, processed, total, synced ->
                        if (onProgress == null) {
                            updateProgress(
                                progress,
                                "Syncing messages...",
                                null // Chat name is tracked internally
                            )
                        } else {
                            onProgress(progress, processed, total, synced)
                        }
                    }
                )
            }

            // Wait for iMessage sync (both producer and consumer) to complete
            chatFetcher.await()
            messageSyncer.await()

            // Wait for SMS import if it was started
            smsJob?.await()
        }

        Log.i(TAG, "Synced messages for ${progressTracker.processedChats.get()}/${progressTracker.totalChatsFound.get()} chats (${progressTracker.syncedMessages.get()} messages)")

        // Update last sync time
        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        _lastSyncTime.value = syncTime

        // Mark initial sync as complete
        settingsDataStore.setInitialSyncComplete(true)

        // Run retroactive categorization after sync completes (only if enabled)
        syncOperations.runCategorizationIfEnabled()

        // Only update internal state if no external progress handler
        if (onProgress == null) {
            _syncState.value = SyncState.Completed
        } else {
            onProgress(100, progressTracker.processedChats.get(), progressTracker.totalChatsFound.get(), progressTracker.syncedMessages.get())
        }
        Log.i(TAG, "Initial sync completed")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "Initial sync failed", e)
        // Only update internal state on error if no external handler
        // (caller should handle errors from Result)
        if (onProgress == null) {
            _syncState.value = createErrorState(e)
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
        val progressTracker = SyncProgressTracker().apply {
            processedChats.set(alreadySynced.size)
        }

        _syncState.value = SyncState.Syncing(
            progress = 0.2f + (0.7f * (progressTracker.processedChats.get().toFloat() / totalChats)),
            stage = "Resuming sync...",
            totalChats = totalChats,
            processedChats = progressTracker.processedChats.get(),
            syncedMessages = 0,
            isInitialSync = true
        )

        // Convert to ChatInfo list
        val chatInfoList = remainingChats.map { chat ->
            MessageSyncHelper.ChatInfo(chat.guid, chat.displayName)
        }

        messageSyncHelper.syncMessagesForChats(
            chats = chatInfoList,
            messagesPerChat = messagesPerChat,
            syncSource = SyncSource.INITIAL,
            progressTracker = progressTracker,
            totalChats = totalChats,
            onProgressUpdate = { state -> _syncState.value = state }
        )

        Log.i(TAG, "Resume sync completed: ${progressTracker.syncedMessages.get()} messages synced")

        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        _lastSyncTime.value = syncTime

        settingsDataStore.setInitialSyncComplete(true)

        // Run retroactive categorization after resume sync completes (only if enabled)
        syncOperations.runCategorizationIfEnabled()

        _syncState.value = SyncState.Completed
    }.onFailure { e ->
        Log.e(TAG, "Resume sync failed", e)
        _syncState.value = createErrorState(e)
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
    suspend fun performIncrementalSync(): Result<Unit> {
        val lastSync = settingsDataStore.lastSyncTime.first()
        if (lastSync == 0L) {
            // No previous sync, perform initial
            return performInitialSync()
        }

        return syncOperations.performIncrementalSync { time ->
            _lastSyncTime.value = time
        }
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
     * Clear all local data and perform fresh sync.
     * Delegates to SyncOperations for database cleanup.
     */
    suspend fun performCleanSync(): Result<Unit> =
        syncOperations.performCleanSync(
            performInitialSync = { performInitialSync() },
            onStateUpdate = { _syncState.value = it },
            onLastSyncTimeUpdate = { _lastSyncTime.value = it }
        )

    /**
     * Purge all data and reimport with unified chat groups.
     * Delegates to SyncOperations for database cleanup and concurrent import.
     */
    suspend fun performUnifiedResync(): Result<Unit> =
        syncOperations.performUnifiedResync(
            performInitialSync = { onProgress ->
                performInitialSync(
                    onProgress = { progress, chatsSynced, chatsTotal, messagesSynced ->
                        onProgress(progress, chatsSynced, chatsTotal, messagesSynced)
                    }
                )
            },
            onStateUpdate = { _syncState.value = it }
        ).also {
            if (it.isSuccess) {
                _lastSyncTime.value = System.currentTimeMillis()
            }
        }

    /**
     * Clean up server data (iMessage) while keeping local SMS/MMS.
     * Delegates to SyncOperations for database cleanup.
     */
    suspend fun cleanupServerData(): Result<Unit> =
        syncOperations.cleanupServerData(
            onStateUpdate = { _syncState.value = it },
            onLastSyncTimeUpdate = { _lastSyncTime.value = it }
        )

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

    /**
     * Helper to create error state from exception.
     */
    private fun createErrorState(e: Throwable): SyncState.Error {
        val isCorrupted = e is SQLiteException ||
            e.message?.contains("database", ignoreCase = true) == true &&
            (e.message?.contains("corrupt", ignoreCase = true) == true ||
             e.message?.contains("malformed", ignoreCase = true) == true)
        return SyncState.Error(
            message = if (isCorrupted) "Database corruption detected" else (e.message ?: "Sync failed"),
            isCorrupted = isCorrupted,
            canRetry = !isCorrupted
        )
    }
}
