package com.bothbubbles.ui.conversations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Conversation list filter options (shown in top bar dropdown).
 */
enum class ConversationFilter(val label: String, val icon: ImageVector) {
    ALL("All", Icons.Outlined.Inbox),
    UNREAD("Unread", Icons.Outlined.MarkChatUnread),
    SPAM("Spam", Icons.Outlined.Report),
    UNKNOWN_SENDERS("Unknown Senders", Icons.Outlined.PersonSearch),
    KNOWN_SENDERS("Known Senders", Icons.Outlined.Person)
}

/**
 * Search filter options for conversation search.
 */
enum class SearchFilter(val label: String, val icon: ImageVector) {
    UNREAD("Unread", Icons.Outlined.MarkChatUnread),
    KNOWN("Known", Icons.Outlined.Person),
    UNKNOWN("Unknown", Icons.Outlined.Block),
    STARRED("Starred", Icons.Outlined.Star),
    IMAGES("Images", Icons.Outlined.Image),
    VIDEOS("Videos", Icons.Outlined.VideoLibrary),
    PLACES("Places", Icons.Outlined.Place),
    LINKS("Links", Icons.Outlined.Link)
}

/**
 * Screen state for animated transitions.
 */
internal enum class ConversationScreenState {
    LOADING, EMPTY, CONTENT
}
