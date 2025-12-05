package com.bluebubbles.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Enum representing all available chat menu actions
 */
enum class ChatMenuAction(
    val label: String,
    val icon: ImageVector,
    val isDestructive: Boolean = false
) {
    ADD_PEOPLE("Add people", Icons.Outlined.PersonAdd),
    DETAILS("Details", Icons.Outlined.Info),
    STARRED("Starred", Icons.Outlined.Star),
    SEARCH("Search", Icons.Outlined.Search),
    ARCHIVE("Archive", Icons.Outlined.Archive),
    UNARCHIVE("Unarchive", Icons.Outlined.Unarchive),
    DELETE("Delete", Icons.Outlined.Delete, isDestructive = true),
    VIDEO("Video", Icons.Outlined.Videocam),
    BLOCK_AND_REPORT("Block & report spam", Icons.Outlined.Block, isDestructive = true),
    SHOW_SUBJECT_FIELD("Show subject field", Icons.Outlined.Subject),
    HELP_AND_FEEDBACK("Help & feedback", Icons.Outlined.HelpOutline)
}

/**
 * Data class to configure menu item visibility and state
 */
data class ChatMenuState(
    val isGroupChat: Boolean = false,
    val isArchived: Boolean = false,
    val isStarred: Boolean = false,
    val showSubjectField: Boolean = false,
    val isSmsChat: Boolean = false
)

/**
 * Chat overflow menu composable that displays the dropdown menu
 */
@Composable
fun ChatOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    menuState: ChatMenuState,
    onAction: (ChatMenuAction) -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        // Add people - only for group chats
        if (menuState.isGroupChat) {
            ChatMenuItem(
                action = ChatMenuAction.ADD_PEOPLE,
                onClick = {
                    onAction(ChatMenuAction.ADD_PEOPLE)
                    onDismissRequest()
                }
            )
            HorizontalDivider()
        }

        // Details, Starred, Search
        ChatMenuItem(
            action = ChatMenuAction.DETAILS,
            onClick = {
                onAction(ChatMenuAction.DETAILS)
                onDismissRequest()
            }
        )

        ChatMenuItem(
            action = ChatMenuAction.STARRED,
            icon = if (menuState.isStarred) Icons.Filled.Star else Icons.Outlined.Star,
            onClick = {
                onAction(ChatMenuAction.STARRED)
                onDismissRequest()
            }
        )

        ChatMenuItem(
            action = ChatMenuAction.SEARCH,
            onClick = {
                onAction(ChatMenuAction.SEARCH)
                onDismissRequest()
            }
        )

        HorizontalDivider()

        // Archive / Unarchive, Delete
        if (menuState.isArchived) {
            ChatMenuItem(
                action = ChatMenuAction.UNARCHIVE,
                onClick = {
                    onAction(ChatMenuAction.UNARCHIVE)
                    onDismissRequest()
                }
            )
        } else {
            ChatMenuItem(
                action = ChatMenuAction.ARCHIVE,
                onClick = {
                    onAction(ChatMenuAction.ARCHIVE)
                    onDismissRequest()
                }
            )
        }

        ChatMenuItem(
            action = ChatMenuAction.DELETE,
            onClick = {
                onAction(ChatMenuAction.DELETE)
                onDismissRequest()
            }
        )

        HorizontalDivider()

        // Video
        ChatMenuItem(
            action = ChatMenuAction.VIDEO,
            onClick = {
                onAction(ChatMenuAction.VIDEO)
                onDismissRequest()
            }
        )

        HorizontalDivider()

        // Block & report spam, Show subject field
        ChatMenuItem(
            action = ChatMenuAction.BLOCK_AND_REPORT,
            onClick = {
                onAction(ChatMenuAction.BLOCK_AND_REPORT)
                onDismissRequest()
            }
        )

        ChatMenuItem(
            action = ChatMenuAction.SHOW_SUBJECT_FIELD,
            label = if (menuState.showSubjectField) "Hide subject field" else "Show subject field",
            onClick = {
                onAction(ChatMenuAction.SHOW_SUBJECT_FIELD)
                onDismissRequest()
            }
        )

        HorizontalDivider()

        // Help & feedback
        ChatMenuItem(
            action = ChatMenuAction.HELP_AND_FEEDBACK,
            onClick = {
                onAction(ChatMenuAction.HELP_AND_FEEDBACK)
                onDismissRequest()
            }
        )
    }
}

/**
 * Individual menu item composable
 */
@Composable
private fun ChatMenuItem(
    action: ChatMenuAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = action.icon,
    label: String = action.label
) {
    val contentColor = if (action.isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = contentColor
            )
        },
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor
            )
        },
        modifier = modifier
    )
}
