package com.bothbubbles.services.sync

import android.database.sqlite.SQLiteException
import android.util.Log
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.dao.MessageDao
import com.bothbubbles.data.local.db.dao.UnifiedChatGroupDao
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.data.repository.MessageRepository
import com.bothbubbles.data.repository.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        val isInitialSync: Boolean = false
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
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "SyncService"
        private const val CHAT_PAGE_SIZE = 50
        private const val MESSAGE_PAGE_SIZE = 25
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    /**
     * Perform initial full sync
     * Downloads all chats and recent messages from the server.
     * Progress is persisted per-chat so sync can resume after app restart.
     */
    suspend fun performInitialSync(
        messagesPerChat: Int = MESSAGE_PAGE_SIZE
    ): Result<Unit> = runCatching {
        Log.i(TAG, "Starting initial sync")

        // Mark sync as started and store settings for potential resume
        settingsDataStore.setInitialSyncStarted(true)
        settingsDataStore.setInitialSyncMessagesPerChat(messagesPerChat)

        _syncState.value = SyncState.Syncing(
            progress = 0f,
            stage = "Starting sync...",
            isInitialSync = true
        )

        var offset = 0
        var totalChats = 0
        var processedChats = 0
        var syncedMessages = 0

        // Phase 1: Sync all chats
        _syncState.value = SyncState.Syncing(
            progress = 0.1f,
            stage = "Fetching conversations...",
            isInitialSync = true
        )

        do {
            val result = chatRepository.syncChats(
                limit = CHAT_PAGE_SIZE,
                offset = offset
            )

            val chats = result.getOrThrow()
            totalChats += chats.size
            offset += CHAT_PAGE_SIZE

            _syncState.value = SyncState.Syncing(
                progress = 0.1f + (0.2f * (offset.toFloat() / maxOf(totalChats, 1))),
                stage = "Fetching conversations...",
                totalChats = totalChats,
                isInitialSync = true
            )
        } while (result.getOrNull()?.size == CHAT_PAGE_SIZE)

        Log.i(TAG, "Synced $totalChats chats")

        // Phase 2: Sync messages for each chat
        val allChats = chatDao.getAllChats().first()
        val chatCount = allChats.size

        // Get already synced chats (for resume scenario)
        val alreadySynced = settingsDataStore.syncedChatGuids.first()

        allChats.forEachIndexed { index, chat ->
            // Skip already synced chats (resume scenario)
            if (chat.guid in alreadySynced) {
                processedChats++
                return@forEachIndexed
            }

            try {
                val chatName = chat.displayName ?: "chat"
                _syncState.value = SyncState.Syncing(
                    progress = 0.3f + (0.6f * (index.toFloat() / chatCount)),
                    stage = "Syncing messages...",
                    totalChats = chatCount,
                    processedChats = processedChats,
                    syncedMessages = syncedMessages,
                    currentChatName = chatName,
                    isInitialSync = true
                )

                val result = messageRepository.syncMessagesForChat(
                    chatGuid = chat.guid,
                    limit = messagesPerChat
                )
                syncedMessages += result.getOrNull()?.size ?: 0
                processedChats++

                // Mark this chat as synced (persisted for resume)
                settingsDataStore.markChatSynced(chat.guid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync messages for chat ${chat.guid}", e)
                processedChats++
                // Continue with next chat - mark as synced to avoid retry loop
                settingsDataStore.markChatSynced(chat.guid)
            }
        }

        Log.i(TAG, "Synced messages for $processedChats/$chatCount chats ($syncedMessages messages)")

        // Update last sync time
        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        _lastSyncTime.value = syncTime

        // Mark initial sync as complete
        settingsDataStore.setInitialSyncComplete(true)

        _syncState.value = SyncState.Completed
        Log.i(TAG, "Initial sync completed")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "Initial sync failed", e)
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
     * Resume an interrupted initial sync.
     * Called on app startup if initialSyncStarted=true but initialSyncComplete=false.
     * Skips already-synced chats and continues from where it left off.
     */
    suspend fun resumeInitialSync(): Result<Unit> = runCatching {
        Log.i(TAG, "Resuming interrupted initial sync")

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
        var processedChats = alreadySynced.size
        var syncedMessages = 0

        _syncState.value = SyncState.Syncing(
            progress = 0.3f + (0.6f * (processedChats.toFloat() / totalChats)),
            stage = "Resuming sync...",
            totalChats = totalChats,
            processedChats = processedChats,
            syncedMessages = syncedMessages,
            isInitialSync = true
        )

        remainingChats.forEachIndexed { index, chat ->
            try {
                val chatName = chat.displayName ?: "chat"
                _syncState.value = SyncState.Syncing(
                    progress = 0.3f + (0.6f * ((processedChats + index).toFloat() / totalChats)),
                    stage = "Syncing messages...",
                    totalChats = totalChats,
                    processedChats = processedChats + index,
                    syncedMessages = syncedMessages,
                    currentChatName = chatName,
                    isInitialSync = true
                )

                val result = messageRepository.syncMessagesForChat(
                    chatGuid = chat.guid,
                    limit = messagesPerChat
                )
                syncedMessages += result.getOrNull()?.size ?: 0

                settingsDataStore.markChatSynced(chat.guid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync messages for chat ${chat.guid}", e)
                settingsDataStore.markChatSynced(chat.guid)
            }
        }

        processedChats = totalChats
        Log.i(TAG, "Resume sync completed: $syncedMessages messages synced")

        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        _lastSyncTime.value = syncTime

        settingsDataStore.setInitialSyncComplete(true)
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
        scope.launch {
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
     * Downloads only new messages since last sync
     */
    suspend fun performIncrementalSync(): Result<Unit> = runCatching {
        val lastSync = settingsDataStore.lastSyncTime.first()
        if (lastSync == 0L) {
            // No previous sync, perform initial
            performInitialSync().getOrThrow()
            return@runCatching
        }

        Log.i(TAG, "Starting incremental sync from $lastSync")
        _syncState.value = SyncState.Syncing(0f, "Checking for new messages...")

        // Sync chats first to get any new conversations
        chatRepository.syncChats(
            limit = CHAT_PAGE_SIZE,
            offset = 0
        )

        _syncState.value = SyncState.Syncing(0.3f, "Syncing new messages...")

        // Get all chats and sync new messages
        val allChats = chatDao.getAllChats().first()
        val chatCount = allChats.size
        var newMessageCount = 0

        allChats.forEachIndexed { index, chat ->
            try {
                val result = messageRepository.syncMessagesForChat(
                    chatGuid = chat.guid,
                    after = lastSync,
                    limit = 100
                )
                newMessageCount += result.getOrNull()?.size ?: 0

                _syncState.value = SyncState.Syncing(
                    0.3f + (0.6f * (index.toFloat() / chatCount)),
                    "Synced $newMessageCount new messages..."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync messages for chat ${chat.guid}", e)
            }
        }

        // Update last sync time
        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        _lastSyncTime.value = syncTime

        _syncState.value = SyncState.Completed
        Log.i(TAG, "Incremental sync completed: $newMessageCount new messages")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "Incremental sync failed", e)
        _syncState.value = SyncState.Error(e.message ?: "Sync failed")
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
     */
    suspend fun performUnifiedResync(): Result<Unit> = runCatching {
        Log.i(TAG, "Starting unified resync - purging all data")
        _syncState.value = SyncState.Syncing(0f, "Clearing existing data...")

        // Clear all existing data including unified groups
        messageDao.deleteAllMessages()
        chatDao.deleteAllChatHandleCrossRefs()
        chatDao.deleteAllChats()
        handleDao.deleteAllHandles()
        unifiedChatGroupDao.deleteAllData()

        // Reset sync markers and clear sync progress
        settingsDataStore.setLastSyncTime(0L)
        settingsDataStore.clearSyncProgress()
        _lastSyncTime.value = 0L

        _syncState.value = SyncState.Syncing(
            progress = 0.1f,
            stage = "Reimporting from server...",
            isInitialSync = true
        )

        // Reimport iMessage chats (will create unified groups for single contacts)
        performInitialSync().getOrThrow()

        _syncState.value = SyncState.Syncing(0.8f, "Reimporting SMS threads...")

        // Reimport SMS threads (will link to unified groups)
        smsRepository.importAllThreads(
            onProgress = { current, total ->
                val progress = 0.8f + (0.15f * (current.toFloat() / total))
                _syncState.value = SyncState.Syncing(progress, "Importing SMS: $current/$total...")
            }
        )

        _syncState.value = SyncState.Completed
        Log.i(TAG, "Unified resync completed")
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
     * Start background sync (called periodically)
     */
    fun startBackgroundSync() {
        scope.launch {
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
