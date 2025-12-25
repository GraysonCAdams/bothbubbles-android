package com.bothbubbles.services.sync

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks progress for concurrent sync operations.
 * Thread-safe counters for tracking chat and message sync progress.
 *
 * Progress is based on chats processed, with synced message count shown
 * as supplementary info. Chat-based progress is more reliable than
 * server message counts which can be inaccurate.
 */
class SyncProgressTracker {
    val totalChatsFound = AtomicInteger(0)
    val processedChats = AtomicInteger(0)
    val syncedMessages = AtomicInteger(0)

    // Expected total messages for accurate progress (set from totalChats × messagesPerChat)
    val expectedTotalMessages = AtomicInteger(0)

    // SMS-specific progress
    val smsCurrentThread = AtomicInteger(0)
    val smsTotalThreads = AtomicInteger(0)
    val smsComplete = AtomicInteger(0) // 0 = in progress, 1 = complete

    // iMessage-specific progress (for unified resync)
    val iMessageProgress = AtomicInteger(0)
    val smsProgress = AtomicInteger(0)

    /**
     * Calculate overall progress percentage for initial sync.
     * Based on chats processed vs total chats found.
     *
     * Progress breakdown:
     * - 0-10%: Fetching chat list
     * - 10-100%: Syncing messages for each chat
     */
    fun calculateInitialSyncProgress(): Int {
        val total = totalChatsFound.get()
        val processed = processedChats.get()
        return if (total > 0) {
            10 + (90 * processed / total)
        } else {
            5 // Starting progress while fetching chats
        }
    }

    /**
     * Calculate progress as a float (0.0 - 1.0) for initial sync.
     * Based on chats processed vs total chats found.
     */
    fun calculateInitialSyncProgressFloat(): Float {
        val total = totalChatsFound.get()
        val processed = processedChats.get()
        return if (total > 0) {
            0.1f + (0.9f * processed / total)
        } else {
            0.05f // Starting progress while fetching chats
        }
    }

    /**
     * Calculate message-based progress for initial sync.
     * Uses expectedTotalMessages if available for a stable denominator,
     * falls back to chat-based progress if not.
     *
     * Progress breakdown:
     * - 5%: Setup (fetching chat count and list)
     * - 95%: Syncing messages (syncedMessages / expectedTotalMessages)
     *
     * @return Progress as float (0.0 - 1.0)
     */
    fun calculateMessageBasedProgress(): Float {
        val expected = expectedTotalMessages.get()
        val synced = syncedMessages.get()

        return if (expected > 0) {
            // Message-based: 5% for setup, 95% for actual sync
            0.05f + (0.95f * (synced.toFloat() / expected).coerceIn(0f, 1f))
        } else {
            // Fallback to chat-based if count fetch failed
            calculateInitialSyncProgressFloat()
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
        expectedTotalMessages.set(0)
        smsCurrentThread.set(0)
        smsTotalThreads.set(0)
        smsComplete.set(0)
        iMessageProgress.set(0)
        smsProgress.set(0)
    }
}
