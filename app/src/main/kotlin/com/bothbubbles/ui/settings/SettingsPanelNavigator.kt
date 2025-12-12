package com.bothbubbles.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Manages navigation state within the settings panel.
 * Maintains a back stack for proper back navigation.
 */
@Stable
class SettingsPanelNavigator {
    private val backStack = mutableListOf<SettingsPanelPage>()

    var currentPage by mutableStateOf<SettingsPanelPage>(SettingsPanelPage.Main)
        private set

    /** Navigate to a new page, adding current to back stack */
    fun navigateTo(page: SettingsPanelPage) {
        backStack.add(currentPage)
        currentPage = page
    }

    /** Navigate back. Returns true if navigated back, false if at root */
    fun navigateBack(): Boolean {
        return if (backStack.isNotEmpty()) {
            currentPage = backStack.removeLast()
            true
        } else {
            false
        }
    }

    /** Check if we can navigate back within the panel */
    fun canGoBack(): Boolean = backStack.isNotEmpty()

    /** Reset to main page and clear back stack */
    fun resetToMain() {
        backStack.clear()
        currentPage = SettingsPanelPage.Main
    }
}

@Composable
fun rememberSettingsPanelNavigator(): SettingsPanelNavigator {
    return remember { SettingsPanelNavigator() }
}
