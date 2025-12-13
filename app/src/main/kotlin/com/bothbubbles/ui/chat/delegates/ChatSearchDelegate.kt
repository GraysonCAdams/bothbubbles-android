package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.ui.components.message.MessageUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for in-chat message search functionality.
 * Handles search activation, query filtering, and navigation through results.
 */
class ChatSearchDelegate @Inject constructor() {

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
    }

    private lateinit var scope: CoroutineScope
    private var searchJob: Job? = null

    // Search state
    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchMatchIndices = MutableStateFlow<List<Int>>(emptyList())
    val searchMatchIndices: StateFlow<List<Int>> = _searchMatchIndices.asStateFlow()

    private val _currentSearchMatchIndex = MutableStateFlow(-1)
    val currentSearchMatchIndex: StateFlow<Int> = _currentSearchMatchIndex.asStateFlow()

    /**
     * Initialize the delegate.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Activate search mode.
     */
    fun activateSearch() {
        _isSearchActive.value = true
        _searchQuery.value = ""
        _searchMatchIndices.value = emptyList()
        _currentSearchMatchIndex.value = -1
    }

    /**
     * Close search mode.
     */
    fun closeSearch() {
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchMatchIndices.value = emptyList()
        _currentSearchMatchIndex.value = -1
    }

    /**
     * Update search query and perform search.
     */
    fun updateSearchQuery(query: String, messages: List<MessageUiModel>) {
        // Cancel previous search job
        searchJob?.cancel()

        // Update query immediately for responsive UI
        _searchQuery.value = query

        // Debounce the actual search
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)

            val matchIndices = if (query.isBlank()) {
                emptyList()
            } else {
                messages.mapIndexedNotNull { index, message ->
                    if (message.text?.contains(query, ignoreCase = true) == true) index else null
                }
            }
            val currentIndex = if (matchIndices.isNotEmpty()) 0 else -1
            _searchMatchIndices.value = matchIndices
            _currentSearchMatchIndex.value = currentIndex
        }
    }

    /**
     * Navigate to previous search match.
     */
    fun navigateSearchUp() {
        val matchIndices = _searchMatchIndices.value
        if (matchIndices.isEmpty()) return

        val currentIndex = _currentSearchMatchIndex.value
        val newIndex = if (currentIndex <= 0) {
            matchIndices.size - 1
        } else {
            currentIndex - 1
        }
        _currentSearchMatchIndex.value = newIndex
    }

    /**
     * Navigate to next search match.
     */
    fun navigateSearchDown() {
        val matchIndices = _searchMatchIndices.value
        if (matchIndices.isEmpty()) return

        val currentIndex = _currentSearchMatchIndex.value
        val newIndex = if (currentIndex >= matchIndices.size - 1) {
            0
        } else {
            currentIndex + 1
        }
        _currentSearchMatchIndex.value = newIndex
    }
}
