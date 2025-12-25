package com.bothbubbles.services.sync

import timber.log.Timber
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
     * @param totalChatCount Pre-fetched total chat count for stable progress denominator.
     *                       If 0, falls back to incrementing as pages are fetched.
     * @param onProgress Callback for progress updates
     */
    suspend fun fetchAndQueueChats(
        alreadySynced: Set<String>,
        progressTracker: SyncProgressTracker,
        chatQueue: Channel<ChatSyncTask>,
        totalChatCount: Int = 0,
        onProgress: ((Int) -> Unit)?
    ) {
        var offset = 0

        // Log already synced count for debugging
        if (alreadySynced.isNotEmpty()) {
            Timber.tag(TAG).i("Already synced ${alreadySynced.size} chats from previous run")
        }

        // Use pre-fetched count if available (stable denominator for progress)
        if (totalChatCount > 0) {
            progressTracker.totalChatsFound.set(totalChatCount)
            Timber.tag(TAG).i("Using pre-fetched chat count: $totalChatCount")
        }

        onProgress?.invoke(5)

        do {
            val result = chatRepository.syncChats(
                limit = CHAT_PAGE_SIZE,
                offset = offset
            )

            val chats = result.getOrThrow()

            // Only increment if we didn't have a pre-fetched count
            if (totalChatCount == 0) {
                progressTracker.totalChatsFound.addAndGet(chats.size)
            }

            Timber.tag(TAG).d("Fetched page at offset $offset: ${chats.size} chats (total: ${progressTracker.totalChatsFound.get()})")
            offset += CHAT_PAGE_SIZE

            // Queue each chat for message syncing
            var queuedCount = 0
            var skippedCount = 0
            chats.forEach { chat ->
                if (chat.guid !in alreadySynced) {
                    chatQueue.send(ChatSyncTask(chat.guid, chat.displayName))
                    queuedCount++
                } else {
                    // Already synced from previous run
                    progressTracker.processedChats.incrementAndGet()
                    skippedCount++
                }
            }
            if (skippedCount > 0) {
                Timber.tag(TAG).d("Page at offset $offset: queued $queuedCount, skipped $skippedCount (already synced)")
            }

            // Update progress - chat fetching is ~20% of total work
            val fetchProgressPercent = progressTracker.calculateChatFetchProgress(offset)
            onProgress?.invoke(fetchProgressPercent)
        } while (result.getOrNull()?.size == CHAT_PAGE_SIZE)

        chatQueue.close()
        Timber.i("Chat fetching complete: ${progressTracker.totalChatsFound.get()} chats found")
    }
}
