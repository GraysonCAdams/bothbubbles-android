package com.bothbubbles.ui.chat.paging

import android.util.Log
import com.bothbubbles.ui.components.message.MessageUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Track message GUIDs that have been loaded this session (for animation control)
    // Messages in this set should not animate when scrolled back into view
    private val seenMessageGuids = mutableSetOf<String>()

    // Current total size (from database)
    private var totalSize = 0

    // Current visible range for determining what to keep in memory
    private var visibleStart = 0
    private var visibleEnd = 0

    // Mutex for coroutine-safe synchronization of state mutations
    // Using Mutex instead of synchronized because coroutines suspend
    private val stateMutex = Mutex()

    // Generation counter to detect stale loads after position shifts
    // Incremented on every shiftPositions() call - all in-flight loads become invalid
    private var generation = 0L

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

    // Track whether initial load has completed (for animation control)
    // Messages loaded during initial load should appear instantly (no animation)
    // Messages arriving after initial load (new incoming) should animate
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete.asStateFlow()

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
     * Check if a message has been loaded this session.
     * Used to skip entrance animations for messages that were already displayed.
     */
    fun hasBeenSeen(guid: String): Boolean = guid in seenMessageGuids

    /**
     * Update a single message by GUID.
     * Used for real-time updates (reactions, delivery status, etc.)
     */
    fun updateMessage(guid: String) {
        scope.launch {
            try {
                // Get position under lock
                val position = stateMutex.withLock { guidToPosition[guid] } ?: return@launch

                val updatedModel = dataSource.loadByKey(guid)
                if (updatedModel != null) {
                    stateMutex.withLock {
                        // Re-verify position hasn't changed
                        val currentPosition = guidToPosition[guid]
                        if (currentPosition != null) {
                            sparseData[currentPosition] = updatedModel
                            emitMessagesLocked()
                            Log.d(TAG, "Updated message at position $currentPosition: $guid")
                        }
                    }
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
            stateMutex.withLock {
                // Increment generation to invalidate any in-flight loads
                generation++
                activeLoadJobs.clear()
                loadStatus.clear()
                sparseData.clear()
                guidToPosition.clear()
            }

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
            _initialLoadComplete.value = true
        }
    }

    private fun onSizeChanged(newSize: Int) {
        val oldSize = totalSize
        if (newSize == oldSize) return

        Log.d(TAG, "Size changed: $oldSize -> $newSize")
        totalSize = newSize
        _totalCount.value = newSize

        scope.launch {
            when {
                newSize > oldSize -> {
                    // New messages added (likely at position 0)
                    val addedCount = newSize - oldSize

                    // Shift existing positions (atomic with mutex)
                    shiftPositions(addedCount)

                    // Load the new messages at the beginning
                    loadRange(0, minOf(addedCount + config.prefetchDistance, newSize))
                }
                newSize < oldSize -> {
                    // Messages deleted
                    // For simplicity, reload the visible range
                    val loadStart = maxOf(0, visibleStart - config.prefetchDistance)
                    val loadEnd = minOf(visibleEnd + config.prefetchDistance, newSize)
                    loadRange(loadStart, loadEnd)
                }
            }
        }
    }

    private suspend fun shiftPositions(shiftBy: Int) {
        stateMutex.withLock {
            // Increment generation FIRST to invalidate ALL in-flight loads immediately
            generation++

            // Create new shifted maps
            val newSparseData = mutableMapOf<Int, MessageUiModel>()
            val newGuidToPosition = mutableMapOf<String, Int>()

            sparseData.forEach { (oldPosition, model) ->
                val newPosition = oldPosition + shiftBy
                if (newPosition >= 0 && newPosition < totalSize) {
                    newSparseData[newPosition] = model
                    newGuidToPosition[model.guid] = newPosition
                }
            }

            // Update BitSet
            val newLoadStatus = BitSet()
            for (i in 0 until loadStatus.length()) {
                if (loadStatus.get(i)) {
                    val newPosition = i + shiftBy
                    if (newPosition >= 0 && newPosition < totalSize) {
                        newLoadStatus.set(newPosition)
                    }
                }
            }

            // Atomic swap - all three data structures updated together under lock
            sparseData.clear()
            sparseData.putAll(newSparseData)
            guidToPosition.clear()
            guidToPosition.putAll(newGuidToPosition)
            loadStatus.clear()
            loadStatus.or(newLoadStatus)

            // Clear active load jobs - their positions are now invalid
            activeLoadJobs.clear()

            Log.d(TAG, "Shifted ${newSparseData.size} positions by $shiftBy (generation=$generation)")

            // NOTE: Don't emit here - wait for loadRange() to complete so we have consistent data
            // Emitting here with position 0 empty causes IndexOutOfBoundsException during scroll
        }
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

        // Evict data far from visible range (now suspend, run in scope)
        scope.launch {
            evictDistantData()
        }
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

        // Capture generation and register load under mutex
        val loadGeneration: Long
        val shouldProceed = stateMutex.withLock {
            // Check if this exact range is already being loaded
            if (activeLoadJobs.any { it.first <= start && it.last >= end - 1 }) {
                Log.d(TAG, "Skipping load for range [$start, $end) - already in progress")
                return
            }
            activeLoadJobs.add(range)
            loadGeneration = generation
            true
        }

        if (!shouldProceed) return

        try {
            Log.d(TAG, "Loading range [$start, $end) ($count messages) at generation $loadGeneration")
            _isLoading.value = true

            // Async DB query - this is where we yield and races can happen
            val models = dataSource.load(start, count)

            // Validate and write under mutex
            stateMutex.withLock {
                // If generation changed, our positions are stale - discard
                if (generation != loadGeneration) {
                    Log.d(TAG, "Discarding stale load for [$start, $end): gen $loadGeneration != $generation")
                    activeLoadJobs.remove(range)
                    return
                }

                // Store loaded messages with GUID conflict detection
                models.forEachIndexed { index, model ->
                    val position = start + index

                    // CRITICAL: Check if this GUID already exists at a DIFFERENT position
                    val existingPosition = guidToPosition[model.guid]
                    if (existingPosition != null && existingPosition != position) {
                        // Remove the OLD entry to prevent duplicates
                        Log.w(TAG, "GUID CONFLICT: ${model.guid} exists at position $existingPosition, storing at $position - removing old entry")
                        sparseData.remove(existingPosition)
                        loadStatus.clear(existingPosition)
                    }

                    sparseData[position] = model
                    guidToPosition[model.guid] = position
                    loadStatus.set(position)
                    seenMessageGuids.add(model.guid)
                }

                activeLoadJobs.remove(range)
                Log.d(TAG, "Loaded ${models.size} messages for range [$start, $end)")

                emitMessagesLocked()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load range [$start, $end)", e)
            _error.value = "Failed to load messages: ${e.message}"
            stateMutex.withLock {
                activeLoadJobs.remove(range)
            }
        } finally {
            _isLoading.value = stateMutex.withLock { activeLoadJobs.isEmpty() }
        }
    }

    private suspend fun evictDistantData() {
        // Skip eviction if disabled - keep all messages in memory for the session
        if (config.disableEviction) return

        stateMutex.withLock {
            if (sparseData.size <= config.pageSize * config.bufferPages * 2) {
                return@withLock // Not enough data to warrant eviction
            }

            val keepStart = maxOf(0, visibleStart - config.pageSize * config.bufferPages)
            val keepEnd = minOf(totalSize, visibleEnd + config.pageSize * config.bufferPages)

            val toEvict = sparseData.keys.filter { it < keepStart || it >= keepEnd }

            if (toEvict.isEmpty()) return@withLock

            toEvict.forEach { position ->
                sparseData[position]?.let { model ->
                    guidToPosition.remove(model.guid)
                }
                sparseData.remove(position)
                loadStatus.clear(position)
            }

            Log.d(TAG, "Evicted ${toEvict.size} messages outside range [$keepStart, $keepEnd)")

            emitMessagesLocked()
        }
    }

    /**
     * Emit messages to the UI. Acquires lock if not already held.
     * Use emitMessagesLocked() when already holding stateMutex.
     */
    private suspend fun emitMessages() {
        stateMutex.withLock {
            emitMessagesLocked()
        }
    }

    /**
     * Emit messages to the UI. Caller MUST hold stateMutex.
     * This is safe to call from within withLock blocks.
     */
    private fun emitMessagesLocked() {
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
     *
     * DEFENSIVE: Includes deduplication as final safety net before UI.
     * If duplicates are detected, they are logged and filtered out.
     */
    fun toList(): List<MessageUiModel> {
        val seenGuids = mutableSetOf<String>()
        return loadedData.entries
            .sortedBy { it.key }
            .mapNotNull { (position, model) ->
                if (model.guid in seenGuids) {
                    // This should never happen if upstream mutex logic works correctly
                    Log.w("SparseMessageList", "DEDUP: Duplicate GUID ${model.guid} at position $position - skipping")
                    null
                } else {
                    seenGuids.add(model.guid)
                    model
                }
            }
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
