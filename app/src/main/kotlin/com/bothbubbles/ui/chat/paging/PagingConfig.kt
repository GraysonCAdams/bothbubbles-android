package com.bothbubbles.ui.chat.paging

/**
 * Configuration for Signal-style BitSet pagination.
 *
 * Based on Signal's paging library patterns, optimized for messaging apps where:
 * - Messages are sorted by date (newest first, position 0)
 * - Users typically view newest messages first
 * - Jump-to-position (search, deep links) requires sparse loading
 */
data class PagingConfig(
    /**
     * Number of messages to load per page when filling gaps.
     * Larger values reduce database calls but increase memory per load.
     * Signal uses 50.
     */
    val pageSize: Int = 50,

    /**
     * Number of messages to prefetch beyond the visible range.
     * When user scrolls, we preload this many positions ahead in each direction.
     * Larger values provide smoother scrolling but use more memory.
     */
    val prefetchDistance: Int = 50,

    /**
     * Number of messages to load on initial chat open.
     * Should be larger than a typical screen to avoid immediate loading.
     * Signal loads around the current scroll position; we start at newest.
     */
    val initialLoadSize: Int = 100,

    /**
     * Buffer pages to keep loaded on each side of visible range.
     * When user scrolls away, we keep this many pages in memory before evicting.
     * Prevents flickering when user scrolls back quickly.
     */
    val bufferPages: Int = 2,

    /**
     * Debounce time in milliseconds for scroll position updates.
     * Prevents excessive load requests during fast scrolling.
     */
    val scrollDebounceMs: Long = 50L
) {
    companion object {
        /**
         * Default configuration suitable for most conversations.
         */
        val Default = PagingConfig()

        /**
         * Configuration for very large conversations (10k+ messages).
         * Smaller pages and larger prefetch for smoother scrolling.
         */
        val LargeConversation = PagingConfig(
            pageSize = 30,
            prefetchDistance = 75,
            initialLoadSize = 75,
            bufferPages = 3
        )
    }
}
