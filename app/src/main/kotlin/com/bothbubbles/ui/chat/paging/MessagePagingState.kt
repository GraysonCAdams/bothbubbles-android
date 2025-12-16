package com.bothbubbles.ui.chat.paging

import timber.log.Timber
import com.bothbubbles.ui.components.message.MessageUiModel

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
                    Timber.w("DEDUP: Duplicate GUID ${model.guid} at position $position - skipping")
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

    companion object {
        fun empty() = SparseMessageList(0, emptyMap(), emptyList())
    }
}

/**
 * Internal state container for the paging controller.
 * Encapsulates all mutable state for thread-safe access.
 */
internal data class PagingState(
    var totalSize: Int = 0,
    var visibleStart: Int = 0,
    var visibleEnd: Int = 0,
    var generation: Long = 0L
)
