package com.bothbubbles.ui.components.conversation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Available swipe actions for conversation tiles
 */
enum class SwipeActionType(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val color: Color
) {
    NONE("none", "None", Icons.Default.Block, Color.Gray),
    PIN("pin", "Pin", Icons.Default.PushPin, Color(0xFF1976D2)),
    UNPIN("unpin", "Unpin", Icons.Outlined.PushPin, Color(0xFF1976D2)),
    ARCHIVE("archive", "Archive", Icons.Default.Archive, Color(0xFF388E3C)),
    DELETE("delete", "Delete", Icons.Default.Delete, Color(0xFFD32F2F)),
    MUTE("mute", "Mute", Icons.Default.NotificationsOff, Color(0xFF7B1FA2)),
    UNMUTE("unmute", "Unmute", Icons.Default.Notifications, Color(0xFF7B1FA2)),
    MARK_READ("mark_read", "Mark as Read", Icons.Default.MarkEmailRead, Color(0xFF0097A7)),
    MARK_UNREAD("mark_unread", "Mark as Unread", Icons.Default.MarkEmailUnread, Color(0xFF0097A7)),
    SNOOZE("snooze", "Snooze", Icons.Default.Snooze, Color(0xFF9C27B0)),
    UNSNOOZE("unsnooze", "Unsnooze", Icons.Outlined.Snooze, Color(0xFF9C27B0));

    companion object {
        fun fromKey(key: String): SwipeActionType =
            entries.find { it.key == key } ?: NONE

        /**
         * Get the appropriate action based on current state
         */
        fun getContextualAction(
            baseAction: SwipeActionType,
            isPinned: Boolean,
            isMuted: Boolean,
            isRead: Boolean,
            isSnoozed: Boolean = false
        ): SwipeActionType {
            return when (baseAction) {
                PIN, UNPIN -> if (isPinned) UNPIN else PIN
                MUTE, UNMUTE -> if (isMuted) UNMUTE else MUTE
                MARK_READ, MARK_UNREAD -> if (isRead) MARK_UNREAD else MARK_READ
                SNOOZE, UNSNOOZE -> if (isSnoozed) UNSNOOZE else SNOOZE
                else -> baseAction
            }
        }
    }
}

/**
 * Data class to hold swipe configuration
 */
data class SwipeConfig(
    val enabled: Boolean = true,
    val leftAction: SwipeActionType = SwipeActionType.ARCHIVE,
    val rightAction: SwipeActionType = SwipeActionType.PIN,
    val sensitivity: Float = 0.4f
)
