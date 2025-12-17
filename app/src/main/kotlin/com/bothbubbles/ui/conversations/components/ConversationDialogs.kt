package com.bothbubbles.ui.conversations.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
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
 * Confirmation dialog for swipe actions (archive/delete).
 */
@Composable
fun SwipeActionConfirmationDialog(
    action: SwipeActionType,
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
            Text(if (isDelete) "Delete Conversation?" else "Archive Conversation?")
        },
        text = {
            Text(
                if (isDelete) {
                    "This conversation will be permanently deleted. This cannot be undone."
                } else {
                    "This conversation will be moved to the archive."
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
