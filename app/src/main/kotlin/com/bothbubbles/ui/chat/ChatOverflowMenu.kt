package com.bothbubbles.ui.chat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Enum representing all available chat menu actions
 */
enum class ChatMenuAction(
    val label: String,
    val isDestructive: Boolean = false
) {
    ADD_PEOPLE("Add people"),
    DETAILS("Details"),
    SWITCH_SEND_MODE("Switch send mode"), // Dynamic label set at call site
    STARRED("Starred"),
    SEARCH("Search"),
    SELECT_MESSAGES("Select messages"),
    ARCHIVE("Archive"),
    UNARCHIVE("Unarchive"),
    DELETE("Delete", isDestructive = true),
    BLOCK_AND_REPORT("Block & report spam", isDestructive = true),
    HELP_AND_FEEDBACK("Help & feedback")
}

/**
 * Data class to configure menu item visibility and state
 */
data class ChatMenuState(
    val isGroupChat: Boolean = false,
    val isArchived: Boolean = false,
    val isStarred: Boolean = false,
    val showSubjectField: Boolean = false,
    val isSmsChat: Boolean = false,
    val showSendModeSwitch: Boolean = false,
    val currentSendMode: ChatSendMode = ChatSendMode.IMESSAGE,
    val isBubbleMode: Boolean = false
)

/**
 * Chat overflow menu composable that displays the dropdown menu
 * Styled to match Google Messages
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
        offset = DpOffset(x = (-8).dp, y = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
    ) {
        // In bubble mode, only show send mode switch
        if (menuState.isBubbleMode) {
            // Send mode switch - only when available
            if (menuState.showSendModeSwitch) {
                val switchLabel = when (menuState.currentSendMode) {
                    ChatSendMode.IMESSAGE -> "Switch to SMS"
                    ChatSendMode.SMS -> "Switch to iMessage"
                }
                ChatMenuItem(
                    action = ChatMenuAction.SWITCH_SEND_MODE,
                    label = switchLabel,
                    onClick = {
                        onAction(ChatMenuAction.SWITCH_SEND_MODE)
                        onDismissRequest()
                    }
                )
            }
            return@DropdownMenu
        }

        // Full menu for non-bubble mode
        // Add people - only for group chats
        if (menuState.isGroupChat) {
            ChatMenuItem(
                action = ChatMenuAction.ADD_PEOPLE,
                onClick = {
                    onAction(ChatMenuAction.ADD_PEOPLE)
                    onDismissRequest()
                }
            )
        }

        ChatMenuItem(
            action = ChatMenuAction.DETAILS,
            onClick = {
                onAction(ChatMenuAction.DETAILS)
                onDismissRequest()
            }
        )

        // Send mode switch - only when auto-switch is disabled and not a group chat
        if (menuState.showSendModeSwitch) {
            val switchLabel = when (menuState.currentSendMode) {
                ChatSendMode.IMESSAGE -> "Switch to SMS"
                ChatSendMode.SMS -> "Switch to iMessage"
            }
            ChatMenuItem(
                action = ChatMenuAction.SWITCH_SEND_MODE,
                label = switchLabel,
                onClick = {
                    onAction(ChatMenuAction.SWITCH_SEND_MODE)
                    onDismissRequest()
                }
            )
        }

        ChatMenuItem(
            action = ChatMenuAction.STARRED,
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

        ChatMenuItem(
            action = ChatMenuAction.SELECT_MESSAGES,
            onClick = {
                onAction(ChatMenuAction.SELECT_MESSAGES)
                onDismissRequest()
            }
        )

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

        ChatMenuItem(
            action = ChatMenuAction.BLOCK_AND_REPORT,
            onClick = {
                onAction(ChatMenuAction.BLOCK_AND_REPORT)
                onDismissRequest()
            }
        )

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
 * Individual menu item composable styled to match Google Messages (text only, no icons)
 */
@Composable
private fun ChatMenuItem(
    action: ChatMenuAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier
    )
}
