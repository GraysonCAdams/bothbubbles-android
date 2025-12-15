package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate

/**
 * State owned by ChatSearchDelegate.
 * Contains all search-related UI state, isolated from other domains to prevent cascade recompositions.
 */
@Stable
data class SearchState(
    val isActive: Boolean = false,
    val query: String = "",
    val matchIndices: List<Int> = emptyList(),
    val currentMatchIndex: Int = -1,
    val isSearchingDatabase: Boolean = false,
    val databaseResults: List<ChatSearchDelegate.SearchResult> = emptyList(),
    val showResultsSheet: Boolean = false
)
