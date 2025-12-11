package com.bothbubbles.ui.chat.paging

import android.util.Log
import com.bothbubbles.ui.components.MessageUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.BitSet

/**
 * Signal-style BitSet pagination controller for messages.
 *
 * Based on Signal's FixedSizePagingController pattern:
 * - Uses BitSet to track which positions are loaded
 * - Stores loaded messages in a sparse map (position -> MessageUiModel)
 * - Only loads data for visible + prefetch range
 * - Supports jump-to-position for search results and deep links
 *
 * Key differences from AndroidX Paging3:
 * - Sparse loading: can load position 5000 without loading 0-4999
 * - BitSet tracking: O(1) lookup for "is position loaded?"
 * - No PagingData wrapper: direct StateFlow of messages
 *
 * Thread safety: All public methods must be called from main thread.
 * Data loading happens on IO dispatcher internally.
 */
class MessagePagingController(
    private val dataSource: MessageDataSource,
    private val config: PagingConfig,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MessagePagingController"
    }

    // BitSet tracking which positions are loaded
    // true = loaded, false = not loaded
    private val loadStatus = BitSet()

    // Sparse storage: only loaded positions have entries
    private val sparseData = mutableMapOf<Int, MessageUiModel>()

    // GUID to position mapping for efficient updates
    private val guidToPosition = mutableMapOf<String, Int>()

    // Current total size (from database)
    private var totalSize = 0

    // Current visible range for determining what to keep in memory
    private var visibleStart = 0
    private var visibleEnd = 0

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Message list exposed to UI
    // This is a sparse list - positions outside loaded range are null
    private val _messages = MutableStateFlow<SparseMessageList>(SparseMessageList.empty())
    val messages: StateFlow<SparseMessageList> = _messages.asStateFlow()

    // Total message count
    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    // Debounce job for scroll updates
    private var scrollDebounceJob: Job? = null

    // Active load jobs to prevent duplicate requests
    private val activeLoadJobs = mutableSetOf<IntRange>()

    // Size observer job
    private var sizeObserverJob: Job? = null

    /**
     * Initialize the controller and start observing size changes.
     * Must be called once before using the controller.
     */
    fun initialize() {
        Log.d(TAG, "Initializing paging controller")

        // Observe size changes
        sizeObserverJob?.cancel()
        sizeObserverJob = scope.launch {
            dataSource.observeSize().collect { newSize ->
                onSizeChanged(newSize)
            }
        }

        // Perform initial load
        scope.launch {
            performInitialLoad()
        }
    }

    /**
     * Clean up resources when controller is no longer needed.
     */
    fun dispose() {
        sizeObserverJob?.cancel()
        scrollDebounceJob?.cancel()
        activeLoadJobs.clear()
        loadStatus.clear()
        sparseData.clear()
        guidToPosition.clear()
    }

    /**
     * Called by UI when scroll position changes.
     * Triggers loading of data around the visible range.
     *
     * @param firstVisibleIndex First visible message index (0 = newest)
     * @param lastVisibleIndex Last visible message index
     */
    fun onDataNeededAroundIndex(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        // Debounce rapid scroll updates
        scrollDebounceJob?.cancel()
        scrollDebounceJob = scope.launch {
            delay(config.scrollDebounceMs)
            loadAroundRange(firstVisibleIndex, lastVisibleIndex)
        }
    }

    /**
     * Jump to a specific message by GUID.
     * Used for search results, deep links, and "scroll to reply".
     *
     * @param guid The message GUID to jump to
     * @return The position of the message, or null if not found
     */
    suspend fun jumpToMessage(guid: String): Int? {
        // Check if we already have the position cached
        guidToPosition[guid]?.let { cachedPosition ->
            Log.d(TAG, "Jump to message: $guid found in cache at position $cachedPosition")
            // Ensure data around this position is loaded
            loadAroundPosition(cachedPosition)
            return cachedPosition
        }

        // Query the database for the position
        val position = dataSource.getMessagePosition(guid)

        if (position < 0) {
            Log.d(TAG, "Jump to message: $guid not found in database")
            return null
        }

        Log.d(TAG, "Jump to message: $guid found at position $position")

        // Cache the position
        guidToPosition[guid] = position

        // Load data around the target position
        loadAroundPosition(position)

        return position
    }

    /**
     * Load data around a specific position (used by jumpToMessage).
     * This is a non-debounced version of loadAroundRange for immediate loading.
     */
    private fun loadAroundPosition(position: Int) {
        val loadStart = maxOf(0, position - config.prefetchDistance)
        val loadEnd = minOf(position + config.prefetchDistance, totalSize)

        // Find gaps in the range that need loading
        val gaps = findGaps(loadStart, loadEnd)

        if (gaps.isEmpty()) {
            Log.d(TAG, "No gaps to load around position $position")
            return
        }

        // Load each gap
        gaps.forEach { gap ->
            scope.launch {
                loadRange(gap.first, gap.last + 1)
            }
        }
    }

    /**
     * Get a message by position.
     * Returns null if the position is not loaded or out of bounds.
     */
    fun getMessageAt(position: Int): MessageUiModel? {
        if (position < 0 || position >= totalSize) return null
        return sparseData[position]
    }

    /**
     * Check if a position is loaded.
     */
    fun isPositionLoaded(position: Int): Boolean {
        if (position < 0 || position >= totalSize) return false
        return loadStatus.get(position)
    }

    /**
     * Update a single message by GUID.
     * Used for real-time updates (reactions, delivery status, etc.)
     */
    fun updateMessage(guid: String) {
        val position = guidToPosition[guid] ?: return

        scope.launch {
            try {
                val updatedModel = dataSource.loadByKey(guid)
                if (updatedModel != null) {
                    sparseData[position] = updatedModel
                    emitMessages()
                    Log.d(TAG, "Updated message at position $position: $guid")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update message: $guid", e)
            }
        }
    }

    /**
     * Handle a new message being inserted.
     * Called when a message arrives via socket or is sent locally.
     *
     * In Signal-style ordering (newest = position 0), new messages shift
     * all existing positions by 1.
     */
    fun onNewMessageInserted(guid: String) {
        Log.d(TAG, "New message inserted: $guid")

        // The size observer will detect the change and trigger reload
        // For now, we'll let the size change handler deal with it
        // In the future, we could optimistically insert the message at position 0
    }

    /**
     * Force refresh all loaded data.
     * Use sparingly as it reloads everything.
     */
    fun refresh() {
        Log.d(TAG, "Force refresh requested")

        scope.launch {
            loadStatus.clear()
            sparseData.clear()
            guidToPosition.clear()

            // Get fresh size
            totalSize = dataSource.size()
            _totalCount.value = totalSize

            // Reload visible range
            if (totalSize > 0) {
                val loadEnd = minOf(visibleEnd + config.prefetchDistance, totalSize)
                loadRange(visibleStart, loadEnd)
            }
        }
    }

    // ===== Private Implementation =====

    private suspend fun performInitialLoad() {
        _isLoading.value = true
        _error.value = null

        try {
            // Get total size
            totalSize = dataSource.size()
            _totalCount.value = totalSize
            Log.d(TAG, "Initial size: $totalSize")

            if (totalSize == 0) {
                emitMessages()
                return
            }

            // Load initial batch (newest messages)
            val loadCount = minOf(config.initialLoadSize, totalSize)
            loadRange(0, loadCount)

        } catch (e: Exception) {
            Log.e(TAG, "Initial load failed", e)
            _error.value = "Failed to load messages: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    private fun onSizeChanged(newSize: Int) {
        val oldSize = totalSize
        if (newSize == oldSize) return

        Log.d(TAG, "Size changed: $oldSize -> $newSize")
        totalSize = newSize
        _totalCount.value = newSize

        when {
            newSize > oldSize -> {
                // New messages added (likely at position 0)
                val addedCount = newSize - oldSize

                // Shift existing positions
                shiftPositions(addedCount)

                // Load the new messages at the beginning
                scope.launch {
                    loadRange(0, minOf(addedCount + config.prefetchDistance, newSize))
                }
            }
            newSize < oldSize -> {
                // Messages deleted
                // For simplicity, reload the visible range
                scope.launch {
                    val loadStart = maxOf(0, visibleStart - config.prefetchDistance)
                    val loadEnd = minOf(visibleEnd + config.prefetchDistance, newSize)
                    loadRange(loadStart, loadEnd)
                }
            }
        }
    }

    private fun shiftPositions(shiftBy: Int) {
        // Create new shifted maps
        val newSparseData = mutableMapOf<Int, MessageUiModel>()
        val newGuidToPosition = mutableMapOf<String, Int>()

        sparseData.forEach { (oldPosition, model) ->
            val newPosition = oldPosition + shiftBy
            if (newPosition < totalSize) {
                newSparseData[newPosition] = model
                newGuidToPosition[model.guid] = newPosition
            }
        }

        // Update BitSet
        val newLoadStatus = BitSet()
        for (i in 0 until loadStatus.length()) {
            if (loadStatus.get(i)) {
                val newPosition = i + shiftBy
                if (newPosition < totalSize) {
                    newLoadStatus.set(newPosition)
                }
            }
        }

        sparseData.clear()
        sparseData.putAll(newSparseData)
        guidToPosition.clear()
        guidToPosition.putAll(newGuidToPosition)
        loadStatus.clear()
        loadStatus.or(newLoadStatus)

        Log.d(TAG, "Shifted ${newSparseData.size} positions by $shiftBy")
    }

    private fun loadAroundRange(firstVisible: Int, lastVisible: Int) {
        visibleStart = firstVisible
        visibleEnd = lastVisible

        // Calculate range with prefetch
        val loadStart = maxOf(0, firstVisible - config.prefetchDistance)
        val loadEnd = minOf(lastVisible + config.prefetchDistance, totalSize)

        // Find gaps in the range that need loading
        val gaps = findGaps(loadStart, loadEnd)

        if (gaps.isEmpty()) {
            Log.d(TAG, "No gaps to load in range [$loadStart, $loadEnd]")
            return
        }

        // Load each gap
        gaps.forEach { gap ->
            scope.launch {
                loadRange(gap.first, gap.last + 1)
            }
        }

        // Evict data far from visible range
        evictDistantData()
    }

    private fun findGaps(start: Int, end: Int): List<IntRange> {
        val gaps = mutableListOf<IntRange>()
        var gapStart: Int? = null

        for (i in start until end) {
            val isLoaded = loadStatus.get(i)

            if (!isLoaded && gapStart == null) {
                gapStart = i
            } else if (isLoaded && gapStart != null) {
                gaps.add(gapStart until i)
                gapStart = null
            }
        }

        // Handle gap that extends to end
        if (gapStart != null) {
            gaps.add(gapStart until end)
        }

        return gaps
    }

    private suspend fun loadRange(start: Int, end: Int) {
        val range = start until end
        val count = end - start

        if (count <= 0) return

        // Check if already loading this range
        synchronized(activeLoadJobs) {
            if (activeLoadJobs.any { it.first <= start && it.last >= end - 1 }) {
                Log.d(TAG, "Already loading range [$start, $end)")
                return
            }
            activeLoadJobs.add(range)
        }

        try {
            Log.d(TAG, "Loading range [$start, $end) ($count messages)")
            _isLoading.value = true

            val models = dataSource.load(start, count)

            // Store loaded messages
            models.forEachIndexed { index, model ->
                val position = start + index
                sparseData[position] = model
                guidToPosition[model.guid] = position
                loadStatus.set(position)
            }

            Log.d(TAG, "Loaded ${models.size} messages for range [$start, $end)")
            emitMessages()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load range [$start, $end)", e)
            _error.value = "Failed to load messages: ${e.message}"
        } finally {
            synchronized(activeLoadJobs) {
                activeLoadJobs.remove(range)
            }
            _isLoading.value = activeLoadJobs.isEmpty()
        }
    }

    private fun evictDistantData() {
        if (sparseData.size <= config.pageSize * config.bufferPages * 2) {
            return // Not enough data to warrant eviction
        }

        val keepStart = maxOf(0, visibleStart - config.pageSize * config.bufferPages)
        val keepEnd = minOf(totalSize, visibleEnd + config.pageSize * config.bufferPages)

        val toEvict = sparseData.keys.filter { it < keepStart || it >= keepEnd }

        if (toEvict.isEmpty()) return

        toEvict.forEach { position ->
            sparseData[position]?.let { model ->
                guidToPosition.remove(model.guid)
            }
            sparseData.remove(position)
            loadStatus.clear(position)
        }

        Log.d(TAG, "Evicted ${toEvict.size} messages outside range [$keepStart, $keepEnd)")
    }

    private fun emitMessages() {
        val list = SparseMessageList(
            totalSize = totalSize,
            loadedData = sparseData.toMap(),
            loadedRanges = computeLoadedRanges()
        )
        _messages.value = list
    }

    private fun computeLoadedRanges(): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var rangeStart: Int? = null

        for (i in 0 until totalSize) {
            val isLoaded = loadStatus.get(i)

            if (isLoaded && rangeStart == null) {
                rangeStart = i
            } else if (!isLoaded && rangeStart != null) {
                ranges.add(rangeStart until i)
                rangeStart = null
            }
        }

        if (rangeStart != null) {
            ranges.add(rangeStart until totalSize)
        }

        return ranges
    }
}

/**
 * Sparse message list for Compose LazyColumn.
 *
 * Unlike a regular List<MessageUiModel>, this allows for holes
 * where messages haven't been loaded yet. The UI can show
 * placeholders for unloaded positions.
 */
data class SparseMessageList(
    val totalSize: Int,
    private val loadedData: Map<Int, MessageUiModel>,
    val loadedRanges: List<IntRange>
) {
    companion object {
        fun empty() = SparseMessageList(0, emptyMap(), emptyList())
    }

    val size: Int get() = totalSize

    val isEmpty: Boolean get() = totalSize == 0

    /**
     * Get message at position, or null if not loaded.
     */
    operator fun get(index: Int): MessageUiModel? {
        if (index < 0 || index >= totalSize) return null
        return loadedData[index]
    }

    /**
     * Check if position is loaded.
     */
    fun isLoaded(index: Int): Boolean {
        return loadedData.containsKey(index)
    }

    /**
     * Get all loaded messages as a list (for backwards compatibility).
     * Note: This loses position information. Use sparingly.
     */
    fun toList(): List<MessageUiModel> {
        return loadedData.entries
            .sortedBy { it.key }
            .map { it.value }
    }

    /**
     * Get loaded messages in a range.
     */
    fun getRange(start: Int, end: Int): List<MessageUiModel?> {
        return (start until end).map { loadedData[it] }
    }

    /**
     * Find position by GUID.
     */
    fun findPositionByGuid(guid: String): Int? {
        return loadedData.entries.find { it.value.guid == guid }?.key
    }
}
