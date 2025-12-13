package com.bothbubbles.services.sync

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks progress for concurrent sync operations.
 * Thread-safe counters for tracking chat and message sync progress.
 */
class SyncProgressTracker {
    val totalChatsFound = AtomicInteger(0)
    val processedChats = AtomicInteger(0)
    val syncedMessages = AtomicInteger(0)

    // SMS-specific progress
    val smsCurrentThread = AtomicInteger(0)
    val smsTotalThreads = AtomicInteger(0)
    val smsComplete = AtomicInteger(0) // 0 = in progress, 1 = complete

    // iMessage-specific progress (for unified resync)
    val iMessageProgress = AtomicInteger(0)
    val smsProgress = AtomicInteger(0)

    /**
     * Calculate overall progress percentage for initial sync.
     * Chat fetching is ~20% of work, message syncing is ~80%.
     */
    fun calculateInitialSyncProgress(): Int {
        val total = totalChatsFound.get()
        val processed = processedChats.get()

        return if (total > 0) {
            20 + (70 * processed / total)
        } else {
            5 // Starting progress
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
        smsCurrentThread.set(0)
        smsTotalThreads.set(0)
        smsComplete.set(0)
        iMessageProgress.set(0)
        smsProgress.set(0)
    }
}
