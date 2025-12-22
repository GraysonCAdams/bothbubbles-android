package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.ui.chat.integration.ChatHeaderContent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * State for the cycling subtext display in ChatTopBar.
 *
 * This state is managed by [ChatHeaderIntegrationsDelegate] and consumed
 * by [CyclingSubtextDisplay] composable.
 */
@Stable
data class ChatHeaderSubtextState(
    /** All available content items from integrations, sorted by priority (highest first) */
    val contentItems: ImmutableList<ChatHeaderContent> = persistentListOf(),

    /** Current index in the content rotation */
    val currentIndex: Int = 0,

    /** Whether we're in the initial display phase (first 5 seconds) */
    val isInitialPhase: Boolean = true
) {
    /** Current content to display, or null if none available */
    val currentContent: ChatHeaderContent?
        get() = contentItems.getOrNull(currentIndex)

    /** Whether there are multiple items to cycle through */
    val hasMultipleItems: Boolean
        get() = contentItems.size > 1

    /** Total number of content items */
    val itemCount: Int
        get() = contentItems.size
}
