package com.bothbubbles.ui.chat.paging

import timber.log.Timber
import com.bothbubbles.ui.components.message.MessageUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    // Track message GUIDs that were optimistically inserted
    // Used to prevent redundant shifts/reloads when Room Flow eventually reports the new size
    private val optimisticallyInsertedGuids = mutableSetOf<String>()

    // Internal state
    private val state = PagingState()

    // Mutex for coroutine-safe synchronization of state mutations
    // Using Mutex instead of synchronized because coroutines suspend
    private val stateMutex = Mutex()

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
        Timber.d("Initializing paging controller")

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
            Timber.d("Jump to message: $guid found in cache at position $cachedPosition")
            // Ensure data around this position is loaded
            loadAroundPosition(cachedPosition)
            return cachedPosition
        }

        // Query the database for the position
        val position = dataSource.getMessagePosition(guid)

        if (position < 0) {
            Timber.d("Jump to message: $guid not found in database")
            return null
        }

        Timber.d("Jump to message: $guid found at position $position")

        // Cache the position
        guidToPosition[guid] = position

        // Load data around the target position
        loadAroundPosition(position)

        return position
    }

    /**
     * Get a message by position.
     * Returns null if the position is not loaded or out of bounds.
     */
    fun getMessageAt(position: Int): MessageUiModel? {
        if (position < 0 || position >= state.totalSize) return null
        return sparseData[position]
    }

    /**
     * Check if a position is loaded.
     */
    fun isPositionLoaded(position: Int): Boolean {
        if (position < 0 || position >= state.totalSize) return false
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
                            Timber.d("Updated message at position $currentPosition: $guid")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to update message: $guid")
            }
        }
    }

    /**
     * Update a message locally without database round-trip.
     * Used for optimistic UI updates (e.g., reactions).
     *
     * @param guid The message GUID to update
     * @param transform Function to transform the message model
     * @return True if the message was found and updated
     */
    suspend fun updateMessageLocally(guid: String, transform: (MessageUiModel) -> MessageUiModel): Boolean {
        return stateMutex.withLock {
            val position = guidToPosition[guid] ?: return@withLock false
            val currentModel = sparseData[position] ?: return@withLock false

            val updatedModel = transform(currentModel)
            sparseData[position] = updatedModel
            emitMessagesLocked()
            Timber.d("Locally updated message at position $position: $guid")
            true
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
        Timber.d("New message inserted: $guid")

        // The size observer will detect the change and trigger reload
        // For now, we'll let the size change handler deal with it
        // In the future, we could optimistically insert the message at position 0
    }

    /**
     * Optimistically insert a new message at position 0.
     * Used for instant display of sent messages without waiting for Room Flow.
     *
     * This bypasses the expensive shiftPositions+loadRange cycle by:
     * 1. Directly inserting the model at position 0
     * 2. Shifting existing positions in memory (no DB)
     * 3. Emitting immediately
     *
     * @param model The pre-built MessageUiModel to insert
     */
    fun insertMessageOptimistically(model: MessageUiModel) {
        val insertStart = System.currentTimeMillis()
        Timber.i("[SEND_TRACE] ── MessagePagingController.insertMessageOptimistically START ──")
        Timber.i("[SEND_TRACE] guid=${model.guid}, text=\"${model.text?.take(30) ?: ""}...\"")

        // Check if already inserted (prevent duplicates)
        if (guidToPosition.containsKey(model.guid)) {
            Timber.i("[SEND_TRACE] SKIPPED - already exists: ${model.guid}")
            return
        }

        // Increment generation to invalidate any in-flight loads
        state.generation++
        Timber.i("[SEND_TRACE] Incrementing generation to ${state.generation} +${System.currentTimeMillis() - insertStart}ms")

        // Shift all existing positions by 1 (in memory, no DB)
        val shiftStart = System.currentTimeMillis()
        val newSparseData = mutableMapOf<Int, MessageUiModel>()
        val newGuidToPosition = mutableMapOf<String, Int>()

        sparseData.forEach { (oldPosition, existingModel) ->
            val newPosition = oldPosition + 1
            if (newPosition < state.totalSize + 1) {
                newSparseData[newPosition] = existingModel
                newGuidToPosition[existingModel.guid] = newPosition
            }
        }
        Timber.i("[SEND_TRACE] Shifted ${sparseData.size} positions in ${System.currentTimeMillis() - shiftStart}ms")

        // Insert new message at position 0
        newSparseData[0] = model
        newGuidToPosition[model.guid] = 0

        // Update state
        sparseData.clear()
        sparseData.putAll(newSparseData)
        guidToPosition.clear()
        guidToPosition.putAll(newGuidToPosition)

        // Shift BitSet
        val newLoadStatus = MessagePagingHelpers.shiftBitSet(loadStatus, 1, state.totalSize + 1)
        loadStatus.clear()
        loadStatus.or(newLoadStatus)
        loadStatus.set(0) // Mark position 0 as loaded

        // Update total size
        state.totalSize++
        _totalCount.value = state.totalSize

        // Mark as seen (no entrance animation)
        seenMessageGuids.add(model.guid)

        // Mark as optimistically inserted
        optimisticallyInsertedGuids.add(model.guid)

        Timber.i("[SEND_TRACE] State updated, totalSize=${state.totalSize} +${System.currentTimeMillis() - insertStart}ms")

        // Emit immediately
        val emitStart = System.currentTimeMillis()
        emitMessagesLocked()
        Timber.i("[SEND_TRACE] emitMessagesLocked() took ${System.currentTimeMillis() - emitStart}ms")

        Timber.i("[SEND_TRACE] ── MessagePagingController.insertMessageOptimistically END: ${System.currentTimeMillis() - insertStart}ms total ──")
    }

    /**
     * Remove an optimistic message that failed to persist to the database.
     * This reverses the effects of insertMessageOptimistically().
     *
     * @param guid The GUID of the message to remove
     */
    fun removeOptimisticMessage(guid: String) {
        Timber.i("[SEND_TRACE] ── MessagePagingController.removeOptimisticMessage START ──")
        Timber.i("[SEND_TRACE] Removing optimistic message: $guid")

        // Check if this message exists and was optimistically inserted
        val position = guidToPosition[guid]
        if (position == null) {
            Timber.w("[SEND_TRACE] Message not found in guidToPosition: $guid")
            return
        }

        if (!optimisticallyInsertedGuids.contains(guid)) {
            Timber.w("[SEND_TRACE] Message was not optimistically inserted, skipping removal: $guid")
            return
        }

        // Increment generation to invalidate any in-flight loads
        state.generation++

        // Remove from tracking sets
        optimisticallyInsertedGuids.remove(guid)
        seenMessageGuids.remove(guid)
        guidToPosition.remove(guid)
        sparseData.remove(position)

        // Shift all positions after this one down by 1
        val newSparseData = mutableMapOf<Int, MessageUiModel>()
        val newGuidToPosition = mutableMapOf<String, Int>()

        sparseData.forEach { (pos, model) ->
            if (pos < position) {
                // Keep positions before the removed message unchanged
                newSparseData[pos] = model
                newGuidToPosition[model.guid] = pos
            } else if (pos > position) {
                // Shift positions after the removed message down by 1
                val newPos = pos - 1
                newSparseData[newPos] = model
                newGuidToPosition[model.guid] = newPos
            }
        }

        // Update state
        sparseData.clear()
        sparseData.putAll(newSparseData)
        guidToPosition.clear()
        guidToPosition.putAll(newGuidToPosition)

        // Shift BitSet down by 1
        val newLoadStatus = BitSet()
        for (i in 0 until state.totalSize - 1) {
            if (i < position) {
                newLoadStatus.set(i, loadStatus.get(i))
            } else {
                newLoadStatus.set(i, loadStatus.get(i + 1))
            }
        }
        loadStatus.clear()
        loadStatus.or(newLoadStatus)

        // Update total size
        state.totalSize--
        _totalCount.value = state.totalSize

        // Emit updated messages
        emitMessagesLocked()

        Timber.i("[SEND_TRACE] ── MessagePagingController.removeOptimisticMessage END ──")
    }

    /**
     * Force refresh all loaded data.
     * Use sparingly as it reloads everything.
     */
    fun refresh() {
        Timber.d("Force refresh requested")

        scope.launch {
            stateMutex.withLock {
                // Increment generation to invalidate any in-flight loads
                state.generation++
                activeLoadJobs.clear()
                loadStatus.clear()
                sparseData.clear()
                guidToPosition.clear()
            }

            // Get fresh size
            state.totalSize = dataSource.size()
            _totalCount.value = state.totalSize

            // Reload visible range
            if (state.totalSize > 0) {
                val loadEnd = minOf(state.visibleEnd + config.prefetchDistance, state.totalSize)
                loadRange(state.visibleStart, loadEnd)
            }
        }
    }

    // ===== Private Implementation =====

    private suspend fun performInitialLoad() {
        _isLoading.value = true
        _error.value = null

        try {
            // Get total size
            state.totalSize = dataSource.size()
            _totalCount.value = state.totalSize
            Timber.d("Initial size: ${state.totalSize}")

            if (state.totalSize == 0) {
                emitMessages()
                return
            }

            // Load initial batch (newest messages)
            val loadCount = minOf(config.initialLoadSize, state.totalSize)
            loadRange(0, loadCount)

        } catch (e: Exception) {
            Timber.e(e, "Initial load failed")
            _error.value = "Failed to load messages: ${e.message}"
        } finally {
            _isLoading.value = false
            _initialLoadComplete.value = true
        }
    }

    private fun onSizeChanged(newSize: Int) {
        val oldSize = state.totalSize
        if (newSize == oldSize) {
            // If sizes match, it means our optimistic update (if any) is now consistent with DB
            if (optimisticallyInsertedGuids.isNotEmpty()) {
                Timber.d("onSizeChanged: Size matched ($newSize), clearing ${optimisticallyInsertedGuids.size} optimistic GUIDs")
                optimisticallyInsertedGuids.clear()
            }
            return
        }

        val sizeChangeTime = System.currentTimeMillis()
        Timber.d("⏱️ onSizeChanged: $oldSize -> $newSize")
        state.totalSize = newSize
        _totalCount.value = newSize

        scope.launch {
            when {
                newSize > oldSize -> {
                    // New messages added (likely at position 0)
                    val addedCount = newSize - oldSize

                    // Shift existing positions (atomic with mutex)
                    val shiftStart = System.currentTimeMillis()
                    shiftPositions(addedCount)
                    Timber.d("⏱️ shiftPositions took: ${System.currentTimeMillis() - shiftStart}ms")

                    // Load the new messages at the beginning
                    val loadStart = System.currentTimeMillis()
                    loadRange(0, minOf(addedCount + config.prefetchDistance, newSize))
                    Timber.d("⏱️ loadRange took: ${System.currentTimeMillis() - loadStart}ms")
                    Timber.d("⏱️ TOTAL from size change: ${System.currentTimeMillis() - sizeChangeTime}ms")
                }
                newSize < oldSize -> {
                    // Messages deleted
                    // For simplicity, reload the visible range
                    val loadStart = maxOf(0, state.visibleStart - config.prefetchDistance)
                    val loadEnd = minOf(state.visibleEnd + config.prefetchDistance, newSize)
                    loadRange(loadStart, loadEnd)
                }
            }
        }
    }

    /**
     * PHASE 2 OPTIMIZATION: Position shifting runs on background thread.
     *
     * When new messages arrive, all existing positions shift by N. This is O(N)
     * where N = number of loaded messages. At 5000+ messages, this can take >16ms.
     *
     * Strategy:
     * 1. Snapshot current data under mutex (fast)
     * 2. Build new data structures on Dispatchers.Default (O(N), off main thread)
     * 3. Atomic swap under mutex (fast)
     */
    private suspend fun shiftPositions(shiftBy: Int) {
        // Step 1: Quick snapshot under mutex
        val snapshotData: Map<Int, MessageUiModel>
        val totalSize: Int
        val expectedGeneration: Long
        stateMutex.withLock {
            // Increment generation FIRST to invalidate ALL in-flight loads immediately
            state.generation++
            expectedGeneration = state.generation
            snapshotData = sparseData.toMap()
            totalSize = state.totalSize
            // Clear active load jobs - their positions are now invalid
            activeLoadJobs.clear()
        }

        // Step 2: Build new data structures on background thread (O(N) work)
        val (newSparseData, newGuidToPosition, newLoadStatus) = withContext(Dispatchers.Default) {
            val newSparse = mutableMapOf<Int, MessageUiModel>()
            val newGuids = mutableMapOf<String, Int>()

            snapshotData.forEach { (oldPosition, model) ->
                val newPosition = oldPosition + shiftBy
                if (newPosition >= 0 && newPosition < totalSize) {
                    newSparse[newPosition] = model
                    newGuids[model.guid] = newPosition
                }
            }

            // BitSet shifting
            val newStatus = MessagePagingHelpers.shiftBitSet(loadStatus, shiftBy, totalSize)

            Triple(newSparse, newGuids, newStatus)
        }

        // Step 3: Atomic swap under mutex (fast)
        stateMutex.withLock {
            if (state.generation != expectedGeneration) {
                Timber.w("State changed during shiftPositions (gen $expectedGeneration -> ${state.generation}), aborting swap")
                return@withLock
            }

            sparseData.clear()
            sparseData.putAll(newSparseData)
            guidToPosition.clear()
            guidToPosition.putAll(newGuidToPosition)
            loadStatus.clear()
            loadStatus.or(newLoadStatus)

            Timber.d("Shifted ${newSparseData.size} positions by $shiftBy (generation=${state.generation})")

            // NOTE: Don't emit here - wait for loadRange() to complete so we have consistent data
            // Emitting here with position 0 empty causes IndexOutOfBoundsException during scroll
        }
    }

    private fun loadAroundRange(firstVisible: Int, lastVisible: Int) {
        state.visibleStart = firstVisible
        state.visibleEnd = lastVisible

        // Calculate range with prefetch
        val loadStart = maxOf(0, firstVisible - config.prefetchDistance)
        val loadEnd = minOf(lastVisible + config.prefetchDistance, state.totalSize)

        // Find gaps in the range that need loading
        val gaps = MessagePagingHelpers.findGaps(loadStart, loadEnd, loadStatus)

        if (gaps.isEmpty()) {
            Timber.d("No gaps to load in range [$loadStart, $loadEnd]")
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

    /**
     * Load data around a specific position (used by jumpToMessage).
     * This is a non-debounced version of loadAroundRange for immediate loading.
     */
    private fun loadAroundPosition(position: Int) {
        val loadStart = maxOf(0, position - config.prefetchDistance)
        val loadEnd = minOf(position + config.prefetchDistance, state.totalSize)

        // Find gaps in the range that need loading
        val gaps = MessagePagingHelpers.findGaps(loadStart, loadEnd, loadStatus)

        if (gaps.isEmpty()) {
            Timber.d("No gaps to load around position $position")
            return
        }

        // Load each gap
        gaps.forEach { gap ->
            scope.launch {
                loadRange(gap.first, gap.last + 1)
            }
        }
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
                Timber.d("Skipping load for range [$start, $end) - already in progress")
                return
            }
            activeLoadJobs.add(range)
            loadGeneration = state.generation
            true
        }

        if (!shouldProceed) return

        try {
            Timber.d("Loading range [$start, $end) ($count messages) at generation $loadGeneration")
            _isLoading.value = true

            // Async DB query - this is where we yield and races can happen
            val models = dataSource.load(start, count)

            // Validate and write under mutex
            stateMutex.withLock {
                // If generation changed, our positions are stale - discard
                if (state.generation != loadGeneration) {
                    Timber.d("Discarding stale load for [$start, $end): gen $loadGeneration != ${state.generation}")
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
                        Timber.w("GUID CONFLICT: ${model.guid} exists at position $existingPosition, storing at $position - removing old entry")
                        sparseData.remove(existingPosition)
                        loadStatus.clear(existingPosition)
                    }

                    sparseData[position] = model
                    guidToPosition[model.guid] = position
                    loadStatus.set(position)
                    seenMessageGuids.add(model.guid)
                }

                activeLoadJobs.remove(range)
                Timber.d("Loaded ${models.size} messages for range [$start, $end)")

                emitMessagesLocked()
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to load range [$start, $end)")
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

            val keepStart = maxOf(0, state.visibleStart - config.pageSize * config.bufferPages)
            val keepEnd = minOf(state.totalSize, state.visibleEnd + config.pageSize * config.bufferPages)

            val toEvict = sparseData.keys.filter { it < keepStart || it >= keepEnd }

            if (toEvict.isEmpty()) return@withLock

            toEvict.forEach { position ->
                sparseData[position]?.let { model ->
                    guidToPosition.remove(model.guid)
                }
                sparseData.remove(position)
                loadStatus.clear(position)
            }

            Timber.d("Evicted ${toEvict.size} messages outside range [$keepStart, $keepEnd)")

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
            totalSize = state.totalSize,
            loadedData = sparseData.toMap(),
            loadedRanges = MessagePagingHelpers.computeLoadedRanges(loadStatus, state.totalSize)
        )
        val firstMessage = sparseData[0]
        Timber.tag("ChatScroll").d("📜 [EMIT] PagingController._messages: ${list.totalSize} total, " +
            "${sparseData.size} loaded, first=${firstMessage?.guid?.takeLast(8)}, " +
            "isFromMe=${firstMessage?.isFromMe}")
        _messages.value = list
    }
}
