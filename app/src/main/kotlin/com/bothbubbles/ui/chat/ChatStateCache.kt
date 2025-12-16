package com.bothbubbles.ui.chat

import timber.log.Timber
import com.bothbubbles.ui.chat.paging.MessagePagingController
import com.bothbubbles.ui.chat.paging.SparseMessageList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LRU cache for recently-viewed chat states.
 *
 * When users navigate between chats, this cache preserves:
 * - The paging controller with loaded messages
 * - Scroll position (first visible item index + offset)
 * - Last access time for LRU eviction
 *
 * This enables instant restoration when returning to a recently-viewed chat:
 * - Messages appear immediately at the exact scroll position
 * - Background sync checks for new messages without blocking UI
 *
 * Cache size is limited to 5 chats to balance memory usage with UX.
 */
@Singleton
class ChatStateCache @Inject constructor() {

    companion object {
        private const val MAX_SIZE = 5
    }
    /**
     * Cached state for a chat conversation.
     *
     * @param chatGuid The primary chat GUID (or primary GUID for merged chats)
     * @param mergedGuids All GUIDs if this is a merged chat, otherwise just [chatGuid]
     * @param messages Snapshot of loaded messages (sparse list)
     * @param totalCount Total message count at time of caching
     * @param scrollPosition First visible item index in LazyColumn
     * @param scrollOffset Pixel offset of first visible item
     * @param lastAccessTime System time when this state was last accessed
     */
    data class CachedChatState(
        val chatGuid: String,
        val mergedGuids: List<String>,
        val messages: SparseMessageList,
        val totalCount: Int,
        val scrollPosition: Int,
        val scrollOffset: Int,
        val lastAccessTime: Long = System.currentTimeMillis()
    )

    // LinkedHashMap with accessOrder=true provides LRU behavior
    private val cache = object : LinkedHashMap<String, CachedChatState>(MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedChatState>?): Boolean {
            val shouldRemove = size > MAX_SIZE
            if (shouldRemove && eldest != null) {
                Timber.d("Evicting oldest chat from cache: ${eldest.key}")
            }
            return shouldRemove
        }
    }

    /**
     * Get cached state for a chat, if available.
     * Updates last access time on hit.
     *
     * @param chatGuid The chat GUID to look up
     * @return Cached state, or null if not in cache
     */
    @Synchronized
    fun get(chatGuid: String): CachedChatState? {
        val state = cache[chatGuid]
        if (state != null) {
            Timber.d("Cache hit for chat: $chatGuid")
            // Update access time
            cache[chatGuid] = state.copy(lastAccessTime = System.currentTimeMillis())
        }
        return state
    }

    /**
     * Store chat state in cache.
     * Automatically evicts oldest entry if cache is full.
     *
     * @param state The chat state to cache
     */
    @Synchronized
    fun put(state: CachedChatState) {
        Timber.d("Caching chat state: ${state.chatGuid}, messages=${state.messages.size}, scrollPos=${state.scrollPosition}")
        cache[state.chatGuid] = state.copy(lastAccessTime = System.currentTimeMillis())
    }

    /**
     * Remove a specific chat from cache.
     * Call this when chat data becomes stale (e.g., after deletion).
     *
     * @param chatGuid The chat GUID to remove
     */
    @Synchronized
    fun remove(chatGuid: String) {
        cache.remove(chatGuid)?.let {
            Timber.d("Removed chat from cache: $chatGuid")
        }
    }

    /**
     * Clear all cached states.
     * Call this on logout or when memory pressure is high.
     */
    @Synchronized
    fun clear() {
        Timber.d("Clearing all cached chat states (${cache.size} entries)")
        cache.clear()
    }

    /**
     * Check if a chat is in the cache.
     */
    @Synchronized
    fun contains(chatGuid: String): Boolean = cache.containsKey(chatGuid)

    /**
     * Get current cache size for debugging.
     */
    @Synchronized
    fun size(): Int = cache.size
}
