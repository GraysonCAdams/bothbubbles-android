package com.bothbubbles.services.sync

import android.util.Log
import com.bothbubbles.data.repository.ChatRepository
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject

/**
 * Helper for chat synchronization operations.
 * Handles fetching chats from server and queueing them for message sync.
 */
class ChatSyncHelper @Inject constructor(
    private val chatRepository: ChatRepository
) {
    companion object {
        private const val TAG = "ChatSyncHelper"
        private const val CHAT_PAGE_SIZE = 50
    }

    /** Lightweight data for queuing chats for message sync */
    data class ChatSyncTask(val guid: String, val displayName: String?)

    /**
     * Fetch all chats from server and queue them for message syncing.
     * Chats are fetched page by page and immediately queued for concurrent message sync.
     *
     * @param alreadySynced Set of chat GUIDs that have already been synced
     * @param progressTracker Tracker for updating progress counters
     * @param chatQueue Channel to send chat tasks for message syncing
     * @param onProgress Callback for progress updates
     */
    suspend fun fetchAndQueueChats(
        alreadySynced: Set<String>,
        progressTracker: SyncProgressTracker,
        chatQueue: Channel<ChatSyncTask>,
        onProgress: ((Int) -> Unit)?
    ) {
        var offset = 0

        onProgress?.invoke(5)

        do {
            val result = chatRepository.syncChats(
                limit = CHAT_PAGE_SIZE,
                offset = offset
            )

            val chats = result.getOrThrow()
            progressTracker.totalChatsFound.addAndGet(chats.size)
            offset += CHAT_PAGE_SIZE

            // Queue each chat for message syncing
            chats.forEach { chat ->
                if (chat.guid !in alreadySynced) {
                    chatQueue.send(ChatSyncTask(chat.guid, chat.displayName))
                } else {
                    // Already synced from previous run
                    progressTracker.processedChats.incrementAndGet()
                }
            }

            // Update progress - chat fetching is ~20% of total work
            val fetchProgressPercent = progressTracker.calculateChatFetchProgress(offset)
            onProgress?.invoke(fetchProgressPercent)
        } while (result.getOrNull()?.size == CHAT_PAGE_SIZE)

        chatQueue.close()
        Log.i(TAG, "Chat fetching complete: ${progressTracker.totalChatsFound.get()} chats found")
    }
}
