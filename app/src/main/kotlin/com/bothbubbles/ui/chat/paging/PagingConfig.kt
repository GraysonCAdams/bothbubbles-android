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
     */
    val pageSize: Int = 100,

    /**
     * Number of messages to prefetch beyond the visible range.
     * When user scrolls, we preload this many positions ahead in each direction.
     * Larger values provide smoother scrolling but use more memory.
     */
    val prefetchDistance: Int = 100,

    /**
     * Number of messages to load on initial chat open.
     * Should be larger than a typical screen to avoid immediate loading.
     */
    val initialLoadSize: Int = 200,

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
    val scrollDebounceMs: Long = 50L,

    /**
     * When true, messages are kept in memory for the entire chat session.
     * When false, distant messages are evicted based on bufferPages setting.
     * Default is true to prevent re-loading when scrolling back.
     */
    val disableEviction: Boolean = true
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
