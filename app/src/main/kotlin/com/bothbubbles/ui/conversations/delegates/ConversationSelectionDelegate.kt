package com.bothbubbles.ui.conversations.delegates

import com.bothbubbles.data.repository.UnifiedChatRepository
import com.bothbubbles.ui.conversations.ConversationFilter
import com.bothbubbles.ui.conversations.ConversationUiModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Selection state for Gmail-style "Select All" functionality.
 *
 * This tracks selection in two modes:
 * 1. Normal mode: Tracks individually selected items by GUID
 * 2. Select All mode: All matching items are selected, tracks deselections
 *
 * This allows "Select All" to select ALL conversations matching filters,
 * not just the visible/loaded ones.
 */
data class SelectionState(
    /** Whether we're in "select all" mode (all matching filters are selected) */
    val isSelectAllMode: Boolean = false,
    /** Total count of items matching current filters (from database query) */
    val totalMatchingCount: Int = 0,
    /** Items explicitly selected (when NOT in select-all mode) */
    val selectedIds: Set<String> = emptySet(),
    /** Items explicitly deselected (when in select-all mode) */
    val deselectedFromAll: Set<String> = emptySet(),
    /** Loading state for total count query */
    val isLoadingCount: Boolean = false,
    /** Current filter being applied */
    val currentFilter: ConversationFilter = ConversationFilter.ALL,
    /** Current category filter */
    val currentCategoryFilter: String? = null
) {
    /** The actual count of selected items */
    val selectedCount: Int
        get() = if (isSelectAllMode) {
            totalMatchingCount - deselectedFromAll.size
        } else {
            selectedIds.size
        }

    /** Whether selection mode is active */
    val isSelectionMode: Boolean
        get() = selectedIds.isNotEmpty() || isSelectAllMode

    /** Check if a specific item is selected */
    fun isSelected(guid: String): Boolean {
        return if (isSelectAllMode) {
            guid !in deselectedFromAll
        } else {
            guid in selectedIds
        }
    }
}

/**
 * Events emitted by the selection delegate.
 */
sealed class SelectionEvent {
    /** Selection state changed, UI should update */
    data class StateChanged(val state: SelectionState) : SelectionEvent()

    /** Batch action should be applied to all selected items */
    data class ApplyBatchAction(
        val action: BatchAction,
        val filter: ConversationFilter,
        val categoryFilter: String?,
        val excludeIds: Set<String>
    ) : SelectionEvent()

    /** Batch action progress update */
    data class BatchProgress(
        val action: BatchAction,
        val current: Int,
        val total: Int
    ) : SelectionEvent()

    /** Batch action completed */
    data class BatchComplete(val action: BatchAction, val count: Int) : SelectionEvent()
}

/**
 * Batch actions that can be applied to selected conversations.
 */
enum class BatchAction {
    MARK_READ,
    MARK_UNREAD,
    ARCHIVE,
    DELETE,
    BLOCK,
    SNOOZE
}

/**
 * Delegate responsible for managing conversation selection state.
 * Handles Gmail-style "Select All" that selects ALL matching conversations,
 * not just visible ones.
 *
 * Key features:
 * - Queries total count from database when "Select All" is clicked
 * - Shows loading overlay during count query
 * - Tracks deselections from "select all" mode
 * - Auto-selects newly loaded items when scrolling in select-all mode
 * - Applies batch actions in chunks for efficiency
 */
class ConversationSelectionDelegate @AssistedInject constructor(
    private val unifiedChatRepository: UnifiedChatRepository,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ConversationSelectionDelegate
    }

    companion object {
        private const val BATCH_SIZE = 50
    }

    private val _selectionState = MutableStateFlow(SelectionState())
    val selectionState: StateFlow<SelectionState> = _selectionState.asStateFlow()

    private val _events = MutableSharedFlow<SelectionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SelectionEvent> = _events.asSharedFlow()

    /**
     * Toggle selection for a single conversation.
     * In select-all mode, this adds to deselectedFromAll.
     * In normal mode, this toggles selectedIds.
     * Pinned items can now be selected in selection mode.
     */
    fun toggleSelection(guid: String) {
        _selectionState.update { state ->
            if (state.isSelectAllMode) {
                // In select-all mode, toggle deselection
                val newDeselected = if (guid in state.deselectedFromAll) {
                    state.deselectedFromAll - guid
                } else {
                    state.deselectedFromAll + guid
                }
                state.copy(deselectedFromAll = newDeselected)
            } else {
                // Normal mode, toggle selection
                val newSelected = if (guid in state.selectedIds) {
                    state.selectedIds - guid
                } else {
                    state.selectedIds + guid
                }
                state.copy(selectedIds = newSelected)
            }
        }
    }

    /**
     * Clear all selections and exit selection mode.
     */
    fun clearSelection() {
        _selectionState.value = SelectionState()
    }

    /**
     * Trigger "Select All" for current filter.
     * This queries the database for total count and enters select-all mode.
     *
     * @param filter Current conversation filter
     * @param categoryFilter Current category filter (if any)
     * @param visibleConversations Currently loaded/visible conversations (used as fallback)
     */
    fun selectAll(
        filter: ConversationFilter,
        categoryFilter: String?,
        visibleConversations: List<ConversationUiModel>
    ) {
        val currentState = _selectionState.value

        // If already in select-all mode with same filter, deselect all
        if (currentState.isSelectAllMode &&
            currentState.currentFilter == filter &&
            currentState.currentCategoryFilter == categoryFilter
        ) {
            clearSelection()
            return
        }

        // Start loading count
        _selectionState.update {
            it.copy(
                isLoadingCount = true,
                currentFilter = filter,
                currentCategoryFilter = categoryFilter
            )
        }

        scope.launch {
            try {
                val totalCount = queryFilteredCount(filter, categoryFilter)

                _selectionState.update {
                    SelectionState(
                        isSelectAllMode = true,
                        totalMatchingCount = totalCount,
                        selectedIds = emptySet(),
                        deselectedFromAll = emptySet(),
                        isLoadingCount = false,
                        currentFilter = filter,
                        currentCategoryFilter = categoryFilter
                    )
                }

                Timber.d("Select All: $totalCount conversations match filter $filter, category $categoryFilter")
            } catch (e: Exception) {
                Timber.e(e, "Failed to query filtered count")
                // Fallback to selecting visible conversations
                val selectableGuids = visibleConversations
                    .filter { !it.isPinned }
                    .map { it.guid }
                    .toSet()

                _selectionState.update {
                    SelectionState(
                        isSelectAllMode = false,
                        selectedIds = selectableGuids,
                        isLoadingCount = false,
                        currentFilter = filter,
                        currentCategoryFilter = categoryFilter
                    )
                }
            }
        }
    }

    /**
     * Query the total count of conversations matching the current filter.
     * This counts all conversations in the database, not just loaded ones.
     * All conversations (1:1 and groups) are now tracked via UnifiedChatEntity.
     */
    private suspend fun queryFilteredCount(
        filter: ConversationFilter,
        categoryFilter: String?
    ): Int {
        // All conversations are now tracked via UnifiedChatRepository
        return unifiedChatRepository.getFilteredCount(
            filter = filter,
            categoryFilter = categoryFilter
        )
    }

    /**
     * Apply a batch action to all selected conversations.
     * In select-all mode, this processes in batches to avoid memory issues.
     *
     * @param action The action to apply
     * @param onProgress Callback for progress updates
     * @param onComplete Callback when complete
     */
    fun applyBatchAction(
        action: BatchAction,
        conversations: List<ConversationUiModel>,
        onApplyToGuids: suspend (Set<String>) -> Unit
    ) {
        val state = _selectionState.value

        scope.launch {
            if (state.isSelectAllMode) {
                // Process all matching conversations in batches
                var processed = 0
                val total = state.selectedCount

                _events.emit(SelectionEvent.BatchProgress(action, 0, total))

                // Fetch and process in batches
                var offset = 0
                while (processed < total) {
                    val batch = fetchFilteredConversationGuids(
                        filter = state.currentFilter,
                        categoryFilter = state.currentCategoryFilter,
                        limit = BATCH_SIZE,
                        offset = offset
                    )

                    if (batch.isEmpty()) break

                    // Filter out deselected items
                    val toProcess = batch.filter { it !in state.deselectedFromAll }.toSet()

                    if (toProcess.isNotEmpty()) {
                        onApplyToGuids(toProcess)
                        processed += toProcess.size
                        _events.emit(SelectionEvent.BatchProgress(action, processed, total))
                    }

                    offset += BATCH_SIZE
                }

                _events.emit(SelectionEvent.BatchComplete(action, processed))
            } else {
                // Normal mode: apply to selected IDs directly
                _events.emit(SelectionEvent.BatchProgress(action, 0, state.selectedIds.size))
                onApplyToGuids(state.selectedIds)
                _events.emit(SelectionEvent.BatchComplete(action, state.selectedIds.size))
            }

            // Clear selection after action
            clearSelection()
        }
    }

    /**
     * Fetch conversation IDs matching the filter, in batches.
     * All conversations (1:1 and groups) are now tracked via UnifiedChatEntity.
     */
    private suspend fun fetchFilteredConversationGuids(
        filter: ConversationFilter,
        categoryFilter: String?,
        limit: Int,
        offset: Int
    ): List<String> {
        // All conversations are now tracked via UnifiedChatRepository
        return unifiedChatRepository.getFilteredIds(
            filter = filter,
            categoryFilter = categoryFilter,
            limit = limit,
            offset = offset
        )
    }

    /**
     * Check if a newly loaded conversation should be auto-selected.
     * Call this when new items are loaded while in select-all mode.
     */
    fun shouldAutoSelect(guid: String): Boolean {
        val state = _selectionState.value
        return state.isSelectAllMode && guid !in state.deselectedFromAll
    }

    /**
     * Update the filter context. Called when filter changes.
     * Clears selection if filter changes while in select-all mode.
     */
    fun updateFilterContext(filter: ConversationFilter, categoryFilter: String?) {
        val state = _selectionState.value
        if (state.isSelectAllMode &&
            (state.currentFilter != filter || state.currentCategoryFilter != categoryFilter)
        ) {
            // Filter changed, clear select-all mode
            clearSelection()
        }
    }
}
