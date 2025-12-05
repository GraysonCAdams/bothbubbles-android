package com.bluebubbles.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Confirmation dialog for deleting a conversation
 */
@Composable
fun DeleteConversationDialog(
    chatDisplayName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Delete conversation?")
        },
        text = {
            Text(
                text = "This will permanently delete your conversation with $chatDisplayName. This action cannot be undone."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        modifier = modifier
    )
}

/**
 * Confirmation dialog for blocking and reporting spam
 */
@Composable
fun BlockAndReportDialog(
    chatDisplayName: String,
    isSmsChat: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Block & report spam?")
        },
        text = {
            Text(
                text = if (isSmsChat) {
                    "Block $chatDisplayName? You will no longer receive messages from this contact. This will add the number to your device's blocked list."
                } else {
                    "Blocking is only supported for SMS conversations. For iMessage conversations, you can block contacts directly on your Mac."
                }
            )
        },
        confirmButton = {
            if (isSmsChat) {
                TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Block")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            if (isSmsChat) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
        modifier = modifier
    )
}

/**
 * Dialog for selecting video call method
 */
@Composable
fun VideoCallMethodDialog(
    onGoogleMeet: () -> Unit,
    onWhatsApp: () -> Unit,
    onDismiss: () -> Unit,
    isWhatsAppAvailable: Boolean = true,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Start video call")
        },
        text = {
            Text(text = "Choose how you want to start the video call")
        },
        confirmButton = {
            TextButton(onClick = {
                onGoogleMeet()
                onDismiss()
            }) {
                Text("Google Meet")
            }
        },
        dismissButton = {
            if (isWhatsAppAvailable) {
                TextButton(onClick = {
                    onWhatsApp()
                    onDismiss()
                }) {
                    Text("WhatsApp")
                }
            }
        },
        modifier = modifier
    )
}
