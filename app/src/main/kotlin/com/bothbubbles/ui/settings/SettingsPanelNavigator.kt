package com.bothbubbles.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bothbubbles.ui.settings.search.SearchableSettingsItem
import com.bothbubbles.ui.settings.search.SettingsSearchIndex

/**
 * Manages navigation state within the settings panel.
 * Maintains a back stack for proper back navigation and tracks
 * navigation direction for animations.
 */
@Stable
class SettingsPanelNavigator {
    private val backStack = mutableListOf<SettingsPanelPage>()

    var currentPage by mutableStateOf<SettingsPanelPage>(SettingsPanelPage.Main)
        private set

    /** Whether the last navigation was forward (true) or backward (false) */
    var isNavigatingForward by mutableStateOf(true)
        private set

    // ═══════════════════════════════════════════════════════════════
    // Search state
    // ═══════════════════════════════════════════════════════════════

    /** Search query entered by user */
    var searchQuery by mutableStateOf("")
        private set

    /** Whether search mode is active */
    var isSearchActive by mutableStateOf(false)
        private set

    /** Filtered search results based on current query */
    val searchResults: List<SearchableSettingsItem> by derivedStateOf {
        if (searchQuery.isBlank()) emptyList()
        else SettingsSearchIndex.search(searchQuery)
    }

    /** ID of the setting item to highlight after navigation from search */
    var highlightedSettingId by mutableStateOf<String?>(null)
        private set

    /** Update the search query */
    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    /** Clear the highlighted setting (called after animation completes) */
    fun clearHighlight() {
        highlightedSettingId = null
    }

    /** Toggle search mode on/off */
    fun toggleSearch() {
        isSearchActive = !isSearchActive
        if (!isSearchActive) {
            searchQuery = ""
        }
    }

    /** Close search and clear query */
    fun closeSearch() {
        isSearchActive = false
        searchQuery = ""
    }

    // ═══════════════════════════════════════════════════════════════
    // Page navigation
    // ═══════════════════════════════════════════════════════════════

    /** Navigate to a new page, adding current to back stack */
    fun navigateTo(page: SettingsPanelPage) {
        // Close search when navigating
        if (isSearchActive) {
            closeSearch()
        }
        isNavigatingForward = true
        backStack.add(currentPage)
        currentPage = page
    }

    /** Navigate from a search result with highlighting */
    fun navigateFromSearch(item: SearchableSettingsItem) {
        highlightedSettingId = item.id
        navigateTo(item.page)
    }

    /** Navigate back. Returns true if navigated back, false if at root */
    fun navigateBack(): Boolean {
        // First close search if active
        if (isSearchActive) {
            closeSearch()
            return true
        }
        return if (backStack.isNotEmpty()) {
            isNavigatingForward = false
            currentPage = backStack.removeLast()
            true
        } else {
            false
        }
    }

    /** Check if we can navigate back within the panel */
    fun canGoBack(): Boolean = isSearchActive || backStack.isNotEmpty()

    /** Reset to main page and clear back stack */
    fun resetToMain() {
        closeSearch()
        backStack.clear()
        currentPage = SettingsPanelPage.Main
    }
}

@Composable
fun rememberSettingsPanelNavigator(): SettingsPanelNavigator {
    return remember { SettingsPanelNavigator() }
}
