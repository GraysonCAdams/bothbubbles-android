package com.bothbubbles.ui.conversations.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.bothbubbles.ui.components.conversation.SwipeActionType

/**
 * Dialog shown when data corruption is detected.
 * Non-dismissable and requires app reset.
 */
@Composable
fun CorruptionDetectedDialog(
    onResetAppData: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Not dismissable */ },
        icon = {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text("Data Issue Detected")
        },
        text = {
            Text(
                "The app encountered a data issue that cannot be automatically fixed. " +
                "To continue, the app data needs to be reset. Your messages on the server are safe."
            )
        },
        confirmButton = {
            Button(
                onClick = onResetAppData,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Reset App")
            }
        }
    )
}

/**
 * Confirmation dialog for swipe actions.
 * Supports: Archive, Delete, Pin, Mute, Mark as Unread
 */
@Composable
fun SwipeActionConfirmationDialog(
    action: SwipeActionType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val (icon, iconTint, title, description, confirmText, isDestructive) = when (action) {
        SwipeActionType.DELETE -> ActionDialogConfig(
            icon = Icons.Default.Delete,
            iconTint = MaterialTheme.colorScheme.error,
            title = "Delete Conversation?",
            description = "This conversation will be permanently deleted. This cannot be undone.",
            confirmText = "Delete",
            isDestructive = true
        )
        SwipeActionType.ARCHIVE -> ActionDialogConfig(
            icon = Icons.Default.Archive,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Archive Conversation?",
            description = "This conversation will be moved to the archive.",
            confirmText = "Archive",
            isDestructive = false
        )
        SwipeActionType.PIN -> ActionDialogConfig(
            icon = Icons.Default.PushPin,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Pin Conversation?",
            description = "This conversation will be pinned to the top of your list.",
            confirmText = "Pin",
            isDestructive = false
        )
        SwipeActionType.MUTE -> ActionDialogConfig(
            icon = Icons.Default.NotificationsOff,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = "Mute Conversation?",
            description = "You won't receive notifications for new messages in this conversation.",
            confirmText = "Mute",
            isDestructive = false
        )
        SwipeActionType.MARK_UNREAD -> ActionDialogConfig(
            icon = Icons.Default.MarkEmailUnread,
            iconTint = MaterialTheme.colorScheme.tertiary,
            title = "Mark as Unread?",
            description = "This conversation will appear as unread with a badge.",
            confirmText = "Mark Unread",
            isDestructive = false
        )
        else -> ActionDialogConfig(
            icon = Icons.Default.Archive,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Confirm Action?",
            description = "Are you sure you want to perform this action?",
            confirmText = "Confirm",
            isDestructive = false
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint
            )
        },
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Configuration for action dialog content
 */
private data class ActionDialogConfig(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconTint: androidx.compose.ui.graphics.Color,
    val title: String,
    val description: String,
    val confirmText: String,
    val isDestructive: Boolean
)

/**
 * Confirmation dialog for batch actions in selection mode.
 */
@Composable
fun BatchActionConfirmationDialog(
    action: SwipeActionType,
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDelete = action == SwipeActionType.DELETE

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (isDelete) Icons.Default.Delete else Icons.Default.Archive,
                contentDescription = null,
                tint = if (isDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                if (isDelete) {
                    "Delete $count Conversation${if (count > 1) "s" else ""}?"
                } else {
                    "Archive $count Conversation${if (count > 1) "s" else ""}?"
                }
            )
        },
        text = {
            Text(
                if (isDelete) {
                    "These conversations will be permanently deleted. This cannot be undone."
                } else {
                    "These conversations will be moved to the archive."
                }
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isDelete) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (isDelete) "Delete" else "Archive")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
