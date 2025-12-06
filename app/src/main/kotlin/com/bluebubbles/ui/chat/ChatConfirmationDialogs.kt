package com.bluebubbles.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
 * Options selected in the BlockAndReportDialog.
 */
data class BlockReportOptions(
    val blockContact: Boolean = false,
    val markAsSpam: Boolean = false,
    val reportToCarrier: Boolean = false
)

/**
 * Confirmation dialog for blocking and reporting spam.
 *
 * For SMS chats, offers:
 * - Block contact (adds to device blocked list)
 * - Mark as spam (moves to spam folder)
 * - Report to carrier via 7726 (forwards message to carrier)
 *
 * For iMessage chats, only offers:
 * - Mark as spam (moves to spam folder)
 * - Note about blocking on Mac
 *
 * @param alreadyReportedToCarrier If true, disables the "Report to carrier" option
 */
@Composable
fun BlockAndReportDialog(
    chatDisplayName: String,
    isSmsChat: Boolean,
    onConfirm: (BlockReportOptions) -> Unit,
    onDismiss: () -> Unit,
    alreadyReportedToCarrier: Boolean = false,
    modifier: Modifier = Modifier
) {
    var blockContact by remember { mutableStateOf(isSmsChat) }
    var markAsSpam by remember { mutableStateOf(true) }
    var reportToCarrier by remember { mutableStateOf(false) }

    // At least one option must be selected for the confirm button to be enabled
    val canConfirm = blockContact || markAsSpam || reportToCarrier

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Block & report spam")
        },
        text = {
            Column {
                if (isSmsChat) {
                    // Block contact checkbox
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = blockContact,
                            onCheckedChange = { blockContact = it }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = "Block contact",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Add to device blocked list",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Mark as spam checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = markAsSpam,
                        onCheckedChange = { markAsSpam = it }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = "Mark as spam",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Move to spam folder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isSmsChat) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Report to carrier checkbox
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = reportToCarrier,
                            onCheckedChange = { reportToCarrier = it },
                            enabled = !alreadyReportedToCarrier
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = if (alreadyReportedToCarrier) "Already reported to carrier" else "Report to carrier",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (alreadyReportedToCarrier) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = "Forward spam to 7726 (SPAM)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "To block iMessage senders, block them on your Mac in the Messages app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        BlockReportOptions(
                            blockContact = blockContact,
                            markAsSpam = markAsSpam,
                            reportToCarrier = reportToCarrier
                        )
                    )
                },
                enabled = canConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Confirm")
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
 * Simple version of BlockAndReportDialog for backwards compatibility.
 * Just performs blocking without the new options.
 */
@Composable
fun BlockAndReportDialog(
    chatDisplayName: String,
    isSmsChat: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BlockAndReportDialog(
        chatDisplayName = chatDisplayName,
        isSmsChat = isSmsChat,
        onConfirm = { _ -> onConfirm() },
        onDismiss = onDismiss,
        alreadyReportedToCarrier = false,
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
