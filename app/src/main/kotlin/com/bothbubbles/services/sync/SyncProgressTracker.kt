package com.bothbubbles.services.sync

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks progress for concurrent sync operations.
 * Thread-safe counters for tracking chat and message sync progress.
 *
 * Progress is primarily tracked by message count for accuracy.
 * When totalMessagesExpected is set (from server API), progress is calculated
 * based on synced messages vs expected total. Otherwise falls back to
 * chat-based estimation.
 */
class SyncProgressTracker {
    val totalChatsFound = AtomicInteger(0)
    val processedChats = AtomicInteger(0)
    val syncedMessages = AtomicInteger(0)

    // Total messages expected from server (set upfront for accurate progress)
    val totalMessagesExpected = AtomicInteger(0)

    // SMS-specific progress
    val smsCurrentThread = AtomicInteger(0)
    val smsTotalThreads = AtomicInteger(0)
    val smsComplete = AtomicInteger(0) // 0 = in progress, 1 = complete

    // iMessage-specific progress (for unified resync)
    val iMessageProgress = AtomicInteger(0)
    val smsProgress = AtomicInteger(0)

    /**
     * Calculate overall progress percentage for initial sync.
     *
     * When totalMessagesExpected is set (from /api/v1/message/count),
     * uses message-based progress for accuracy:
     * - 5% for startup/fetching count
     * - 15% for chat fetching
     * - 80% for message syncing (based on actual message counts)
     *
     * Falls back to chat-based estimation when message count unavailable.
     */
    fun calculateInitialSyncProgress(): Int {
        val expectedMessages = totalMessagesExpected.get()
        val synced = syncedMessages.get()

        return if (expectedMessages > 0) {
            // Message-based progress (more accurate)
            // 20% for setup/chat fetching, 80% for messages
            val messageProgress = if (synced >= expectedMessages) {
                80
            } else {
                (80 * synced / expectedMessages)
            }
            20 + messageProgress
        } else {
            // Fallback to chat-based estimation
            val total = totalChatsFound.get()
            val processed = processedChats.get()
            if (total > 0) {
                20 + (70 * processed / total)
            } else {
                5 // Starting progress
            }
        }
    }

    /**
     * Calculate progress as a float (0.0 - 1.0) for initial sync.
     * Uses message-based progress when available.
     */
    fun calculateInitialSyncProgressFloat(): Float {
        val expectedMessages = totalMessagesExpected.get()
        val synced = syncedMessages.get()

        return if (expectedMessages > 0) {
            // 20% for setup/chat fetching, 80% for messages
            val messageProgress = if (synced >= expectedMessages) {
                0.8f
            } else {
                0.8f * synced / expectedMessages
            }
            0.2f + messageProgress
        } else {
            // Fallback to chat-based estimation
            val total = totalChatsFound.get()
            val processed = processedChats.get()
            if (total > 0) {
                0.2f + (0.7f * processed / total)
            } else {
                0.05f
            }
        }
    }

    /**
     * Calculate chat fetch progress percentage (0-20%).
     */
    fun calculateChatFetchProgress(offset: Int): Int {
        val total = maxOf(totalChatsFound.get(), 100)
        return minOf(20, 5 + (15 * offset / total))
    }

    /**
     * Calculate SMS progress as float (0.0-1.0).
     */
    fun calculateSmsProgress(): Float {
        val total = smsTotalThreads.get()
        return if (total > 0) {
            smsCurrentThread.get().toFloat() / total
        } else {
            0f
        }
    }

    /**
     * Calculate combined progress for unified resync.
     * Returns progress as float (0.0-1.0).
     */
    fun calculateUnifiedResyncProgress(): Float {
        val imPct = iMessageProgress.get()
        val smsPct = smsProgress.get()
        // 10% base + 85% for actual work (split between iMessage and SMS)
        return 0.1f + (0.85f * (imPct + smsPct) / 200f)
    }

    /**
     * Reset all counters to initial state.
     */
    fun reset() {
        totalChatsFound.set(0)
        processedChats.set(0)
        syncedMessages.set(0)
        totalMessagesExpected.set(0)
        smsCurrentThread.set(0)
        smsTotalThreads.set(0)
        smsComplete.set(0)
        iMessageProgress.set(0)
        smsProgress.set(0)
    }
}
