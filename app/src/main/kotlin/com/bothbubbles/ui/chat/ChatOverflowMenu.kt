package com.bothbubbles.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Enum representing all available chat menu actions
 */
enum class ChatMenuAction(
    val label: String,
    val isDestructive: Boolean = false
) {
    ADD_PEOPLE("Add people"),
    DETAILS("Details"),
    SEND_VIA("Send via"), // Opens Stitch selection submenu
    SWITCH_SEND_MODE("Switch send mode"), // Legacy: Dynamic label set at call site
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
 * Represents an available Stitch option for sending messages.
 */
data class StitchMenuItem(
    val id: String,
    val displayName: String,
    val bubbleColor: Color,
    val isConnected: Boolean,
    val isSelected: Boolean
)

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
    val isBubbleMode: Boolean = false,
    // Stitch-based send mode selection
    val availableStitches: ImmutableList<StitchMenuItem> = persistentListOf(),
    val currentStitchId: String? = null,
    val showStitchSelection: Boolean = false  // True when multiple Stitches available
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
    onStitchSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Track whether the Stitch submenu is expanded
    var showStitchSubmenu by remember { mutableStateOf(false) }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            showStitchSubmenu = false
            onDismissRequest()
        },
        offset = DpOffset(x = (-8).dp, y = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
    ) {
        // In bubble mode, only show send mode switch
        if (menuState.isBubbleMode) {
            // Stitch selection - when multiple Stitches available
            if (menuState.showStitchSelection && menuState.availableStitches.isNotEmpty()) {
                SendViaSubmenu(
                    expanded = showStitchSubmenu,
                    stitches = menuState.availableStitches,
                    currentStitchId = menuState.currentStitchId,
                    onExpandToggle = { showStitchSubmenu = !showStitchSubmenu },
                    onStitchSelected = { stitchId ->
                        onStitchSelected(stitchId)
                        showStitchSubmenu = false
                        onDismissRequest()
                    }
                )
            } else if (menuState.showSendModeSwitch) {
                // Legacy fallback for simple SMS/iMessage toggle
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

        // Stitch selection - when multiple Stitches available
        if (menuState.showStitchSelection && menuState.availableStitches.isNotEmpty()) {
            SendViaSubmenu(
                expanded = showStitchSubmenu,
                stitches = menuState.availableStitches,
                currentStitchId = menuState.currentStitchId,
                onExpandToggle = { showStitchSubmenu = !showStitchSubmenu },
                onStitchSelected = { stitchId ->
                    onStitchSelected(stitchId)
                    showStitchSubmenu = false
                    onDismissRequest()
                }
            )
        } else if (menuState.showSendModeSwitch) {
            // Legacy fallback for simple SMS/iMessage toggle
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
 * "Send via" submenu that shows available Stitches for message sending.
 * Includes an "Auto" option at the top.
 */
@Composable
private fun SendViaSubmenu(
    expanded: Boolean,
    stitches: ImmutableList<StitchMenuItem>,
    currentStitchId: String?,
    onExpandToggle: () -> Unit,
    onStitchSelected: (String) -> Unit
) {
    // "Send via" expandable row
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send via")
                Spacer(modifier = Modifier.weight(1f))
                // Show current Stitch color indicator
                currentStitchId?.let { id ->
                    stitches.find { it.id == id }?.let { stitch ->
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(stitch.bubbleColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                } ?: run {
                    // "Auto" indicator
                    Text(
                        text = "Auto",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = onExpandToggle
    )

    // Expanded Stitch options
    if (expanded) {
        // Auto option - uses priority order automatically
        StitchOptionItem(
            label = "Auto",
            subtitle = "Use priority order",
            bubbleColor = null,
            isSelected = currentStitchId == null,
            isConnected = true,
            onClick = { onStitchSelected("auto") }
        )

        // Individual Stitch options
        stitches.forEach { stitch ->
            StitchOptionItem(
                label = stitch.displayName,
                subtitle = if (!stitch.isConnected) "Disconnected" else null,
                bubbleColor = stitch.bubbleColor,
                isSelected = stitch.isSelected,
                isConnected = stitch.isConnected,
                onClick = { onStitchSelected(stitch.id) }
            )
        }
    }
}

/**
 * Individual Stitch option in the Send via submenu.
 */
@Composable
private fun StitchOptionItem(
    label: String,
    subtitle: String?,
    bubbleColor: Color?,
    isSelected: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp)
            ) {
                // Color indicator
                if (bubbleColor != null) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (isConnected) bubbleColor
                                else bubbleColor.copy(alpha = 0.4f)
                            )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                // Label
                Text(
                    text = label,
                    color = if (isConnected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    }
                )

                // Subtitle (e.g., "Disconnected")
                if (subtitle != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "($subtitle)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Selected indicator
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        onClick = onClick,
        enabled = isConnected
    )
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
