package com.bluebubbles.services.sync

import android.util.Log
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.data.repository.ChatRepository
import com.bluebubbles.data.repository.MessageRepository
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
 * Sync state
 */
sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(val progress: Float, val stage: String) : SyncState()
    data object Completed : SyncState()
    data class Error(val message: String) : SyncState()
}

@Singleton
class SyncService @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
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
     * Downloads all chats and recent messages from the server
     */
    suspend fun performInitialSync(
        messagesPerChat: Int = MESSAGE_PAGE_SIZE
    ): Result<Unit> = runCatching {
        Log.i(TAG, "Starting initial sync")
        _syncState.value = SyncState.Syncing(0f, "Starting sync...")

        var offset = 0
        var totalChats = 0
        var processedChats = 0

        // Phase 1: Sync all chats
        _syncState.value = SyncState.Syncing(0.1f, "Fetching conversations...")

        do {
            val result = chatRepository.syncChats(
                limit = CHAT_PAGE_SIZE,
                offset = offset
            )

            val chats = result.getOrThrow()
            totalChats += chats.size
            offset += CHAT_PAGE_SIZE

            _syncState.value = SyncState.Syncing(
                0.1f + (0.2f * (offset.toFloat() / maxOf(totalChats, 1))),
                "Fetched $totalChats conversations..."
            )
        } while (result.getOrNull()?.size == CHAT_PAGE_SIZE)

        Log.i(TAG, "Synced $totalChats chats")

        // Phase 2: Sync messages for each chat
        val allChats = chatDao.getAllChats().first()
        val chatCount = allChats.size

        allChats.forEachIndexed { index, chat ->
            try {
                _syncState.value = SyncState.Syncing(
                    0.3f + (0.6f * (index.toFloat() / chatCount)),
                    "Syncing messages for ${chat.displayName ?: "chat"}..."
                )

                messageRepository.syncMessagesForChat(
                    chatGuid = chat.guid,
                    limit = messagesPerChat
                )
                processedChats++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync messages for chat ${chat.guid}", e)
                // Continue with next chat
            }
        }

        Log.i(TAG, "Synced messages for $processedChats/$chatCount chats")

        // Update last sync time
        val syncTime = System.currentTimeMillis()
        settingsDataStore.setLastSyncTime(syncTime)
        _lastSyncTime.value = syncTime

        _syncState.value = SyncState.Completed
        Log.i(TAG, "Initial sync completed")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "Initial sync failed", e)
        _syncState.value = SyncState.Error(e.message ?: "Sync failed")
    }

    /**
     * Perform incremental sync
     * Downloads only new messages since last sync
     */
    suspend fun performIncrementalSync(): Result<Unit> = runCatching {
        val lastSync = settingsDataStore.lastSyncTime.first()
        if (lastSync == 0L) {
            // No previous sync, perform initial
            return performInitialSync()
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
        _syncState.value = SyncState.Syncing(0f, "Clearing local data...")

        // Clear all messages and chats
        messageDao.deleteAllMessages()
        chatDao.deleteAllChats()

        // Reset sync time
        settingsDataStore.setLastSyncTime(0L)
        _lastSyncTime.value = 0L

        // Perform initial sync
        performInitialSync().getOrThrow()
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
