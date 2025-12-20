package com.bothbubbles.services.messaging

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.SeenMessageDao
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks recently processed message GUIDs to prevent duplicate notifications.
 *
 * When a message arrives via both FCM and Socket.IO (or due to race conditions),
 * this prevents showing multiple notifications for the same message.
 *
 * Uses Room for persistence so deduplication survives app restarts.
 * Also maintains an in-memory cache for fast lookups during the current session.
 *
 * Implementation mirrors the old BlueBubbles app's ActionHandler.shouldNotifyForNewMessageGuid().
 */
@Singleton
class MessageDeduplicator @Inject constructor(
    private val seenMessageDao: SeenMessageDao,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    companion object {
        private const val MAX_MEMORY_CACHE = 200
        // Keep seen messages for 24 hours (sufficient for dedup across restarts)
        private val RETENTION_DURATION_MS = TimeUnit.HOURS.toMillis(24)
    }

    // In-memory cache for fast lookups (supplements Room for current session)
    private val memoryCache = LinkedHashSet<String>()
    private val lock = Any()

    init {
        // Clean up old entries on startup
        applicationScope.launch(ioDispatcher) {
            cleanupOldEntries()
        }
    }

    /**
     * Check if we should notify for this message GUID.
     * This is a suspend function to avoid blocking coroutine threads.
     *
     * @param guid The message GUID to check
     * @return true if this is a new message we should notify for, false if already handled
     */
    suspend fun shouldNotifyForMessage(guid: String): Boolean {
        // First check in-memory cache (fast path)
        synchronized(lock) {
            if (guid in memoryCache) {
                Timber.d("Message $guid in memory cache, skipping notification")
                return false
            }
        }

        // Check Room database (handles cross-restart dedup) - now properly suspending
        val existsInDb = seenMessageDao.exists(guid)

        if (existsInDb) {
            // Also add to memory cache for faster future lookups
            synchronized(lock) {
                addToMemoryCache(guid)
            }
            Timber.d("Message $guid in database, skipping notification")
            return false
        }

        // New message - mark as seen
        markAsHandled(guid)
        Timber.d("New message $guid, will notify")
        return true
    }

    /**
     * Mark a message as handled without checking (useful when we know we processed it).
     */
    fun markAsHandled(guid: String) {
        // Add to memory cache
        synchronized(lock) {
            addToMemoryCache(guid)
        }

        // Persist to Room
        applicationScope.launch(ioDispatcher) {
            seenMessageDao.markAsSeen(guid)
        }
    }

    /**
     * Clear all tracked messages (useful for testing or reset scenarios).
     */
    fun clear() {
        synchronized(lock) {
            memoryCache.clear()
        }
        applicationScope.launch(ioDispatcher) {
            seenMessageDao.clear()
        }
    }

    /**
     * Get count of currently tracked messages (for debugging).
     */
    suspend fun trackedCount(): Int {
        return seenMessageDao.count()
    }

    /**
     * Add to memory cache with size limit.
     */
    private fun addToMemoryCache(guid: String) {
        memoryCache.add(guid)

        // Trim if too large (remove oldest entries)
        while (memoryCache.size > MAX_MEMORY_CACHE) {
            val iterator = memoryCache.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    /**
     * Clean up entries older than retention period.
     */
    private suspend fun cleanupOldEntries() {
        val cutoffTime = System.currentTimeMillis() - RETENTION_DURATION_MS
        seenMessageDao.deleteOlderThan(cutoffTime)
        Timber.d("Cleaned up seen messages older than 24 hours")
    }
}
