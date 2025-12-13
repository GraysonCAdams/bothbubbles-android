package com.bothbubbles.services.sync

import android.util.Log
import com.bothbubbles.data.local.db.entity.SyncSource
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.MessageRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/**
 * Helper for message synchronization operations.
 * Handles concurrent message syncing with backpressure control.
 */
class MessageSyncHelper @Inject constructor(
    private val messageRepository: MessageRepository,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "MessageSyncHelper"
        private const val MESSAGE_SYNC_CONCURRENCY = 3
    }

    /**
     * Sync messages for queued chats with limited concurrency.
     * Processes chats from the queue and syncs their messages in parallel,
     * but limits concurrent operations to avoid overwhelming the system.
     *
     * @param chatQueue Channel providing chat sync tasks
     * @param messagesPerChat Number of messages to sync per chat
     * @param syncSource Source of the sync operation
     * @param progressTracker Tracker for updating progress counters
     * @param onProgress Callback for progress updates (percent, processed, total, synced)
     */
    suspend fun syncMessagesForQueuedChats(
        chatQueue: Channel<ChatSyncHelper.ChatSyncTask>,
        messagesPerChat: Int,
        syncSource: SyncSource,
        progressTracker: SyncProgressTracker,
        onProgress: ((Int, Int, Int, Int) -> Unit)?
    ) {
        // Semaphore to limit concurrent message sync operations
        val syncSemaphore = Semaphore(MESSAGE_SYNC_CONCURRENCY)

        coroutineScope {
            val syncJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            for (task in chatQueue) {
                val job = async {
                    syncSemaphore.withPermit {
                        try {
                            val result = messageRepository.syncMessagesForChat(
                                chatGuid = task.guid,
                                limit = messagesPerChat,
                                syncSource = syncSource
                            )
                            progressTracker.syncedMessages.addAndGet(result.getOrNull()?.size ?: 0)
                            settingsDataStore.markChatSynced(task.guid)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to sync messages for chat ${task.guid}", e)
                            // Mark as synced to avoid retry loop
                            settingsDataStore.markChatSynced(task.guid)
                        }

                        val processed = progressTracker.processedChats.incrementAndGet()
                        val total = progressTracker.totalChatsFound.get()

                        // Update progress - message syncing is ~80% of total work (20% to 90%)
                        val messageProgressPercent = progressTracker.calculateInitialSyncProgress()

                        onProgress?.invoke(
                            messageProgressPercent,
                            processed,
                            total,
                            progressTracker.syncedMessages.get()
                        )
                    }
                }
                syncJobs.add(job)
            }

            // Wait for all message sync jobs to complete
            syncJobs.forEach { it.await() }
        }
    }

    /**
     * Sync messages for a list of chats concurrently.
     * Used for resuming interrupted syncs.
     *
     * @param chats List of chat entities with guid and displayName
     * @param messagesPerChat Number of messages to sync per chat
     * @param syncSource Source of the sync operation
     * @param progressTracker Tracker for updating progress counters
     * @param totalChats Total number of chats (for progress calculation)
     * @param onProgressUpdate Callback for progress updates
     */
    suspend fun syncMessagesForChats(
        chats: List<ChatInfo>,
        messagesPerChat: Int,
        syncSource: SyncSource,
        progressTracker: SyncProgressTracker,
        totalChats: Int,
        onProgressUpdate: (SyncState.Syncing) -> Unit
    ) {
        val syncSemaphore = Semaphore(MESSAGE_SYNC_CONCURRENCY)

        coroutineScope {
            val syncJobs = chats.map { chat ->
                async {
                    syncSemaphore.withPermit {
                        try {
                            val result = messageRepository.syncMessagesForChat(
                                chatGuid = chat.guid,
                                limit = messagesPerChat,
                                syncSource = syncSource
                            )
                            progressTracker.syncedMessages.addAndGet(result.getOrNull()?.size ?: 0)
                            settingsDataStore.markChatSynced(chat.guid)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to sync messages for chat ${chat.guid}", e)
                            settingsDataStore.markChatSynced(chat.guid)
                        }

                        val processed = progressTracker.processedChats.incrementAndGet()
                        onProgressUpdate(
                            SyncState.Syncing(
                                progress = 0.2f + (0.7f * (processed.toFloat() / totalChats)),
                                stage = "Syncing messages...",
                                totalChats = totalChats,
                                processedChats = processed,
                                syncedMessages = progressTracker.syncedMessages.get(),
                                currentChatName = chat.displayName,
                                isInitialSync = true
                            )
                        )
                    }
                }
            }

            // Wait for all sync jobs to complete
            syncJobs.forEach { it.await() }
        }
    }

    /**
     * Simple data class for chat information needed for syncing.
     */
    data class ChatInfo(
        val guid: String,
        val displayName: String?
    )
}
