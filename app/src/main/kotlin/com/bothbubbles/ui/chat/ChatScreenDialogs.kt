package com.bothbubbles.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Dialog for confirming conversation deletion.
 * Shown when user selects "Delete" from overflow menu.
 */
@Composable
fun DeleteConversationDialog(
    chatDisplayName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete conversation") },
        text = { Text("This will delete your copy of the conversation with $chatDisplayName.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
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
 * Options for blocking and reporting spam
 */
data class BlockOptions(
    val blockContact: Boolean = false,
    val markAsSpam: Boolean = false,
    val reportToCarrier: Boolean = false
)

/**
 * Dialog for blocking contact and reporting spam.
 * Shows different options based on whether it's an SMS chat.
 * For SMS: Block contact (native Android), mark as spam, report to carrier
 * For iMessage: Mark as spam only
 */
@Composable
fun BlockAndReportDialog(
    chatDisplayName: String,
    isSmsChat: Boolean,
    onConfirm: (BlockOptions) -> Unit,
    onDismiss: () -> Unit,
    alreadyReportedToCarrier: Boolean = false
) {
    var blockContact by remember { mutableStateOf(isSmsChat) }
    var markAsSpam by remember { mutableStateOf(true) }
    var reportToCarrier by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block & report spam") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This will prevent $chatDisplayName from contacting you.")

                if (isSmsChat) {
                    // SMS chat: show all options
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = blockContact,
                            onCheckedChange = { blockContact = it }
                        )
                        Text("Block contact")
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = markAsSpam,
                        onCheckedChange = { markAsSpam = it }
                    )
                    Text("Mark as spam")
                }

                if (isSmsChat && !alreadyReportedToCarrier) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = reportToCarrier,
                            onCheckedChange = { reportToCarrier = it }
                        )
                        Text("Report to carrier")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        BlockOptions(
                            blockContact = blockContact && isSmsChat,
                            markAsSpam = markAsSpam,
                            reportToCarrier = reportToCarrier && isSmsChat && !alreadyReportedToCarrier
                        )
                    )
                }
            ) {
                Text("Confirm", color = MaterialTheme.colorScheme.error)
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
 * Dialog shown when user tries to send SMS but BothBubbles is not the default SMS app.
 * Provides a button to open system settings.
 */
@Composable
fun SmsBlockedDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cannot Send SMS") },
        text = {
            Text("BothBubbles must be set as the default SMS app to send SMS messages.\n\nGo to Settings → Apps → Default apps → SMS app and select BothBubbles.")
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings")
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
 * Dialog for choosing video call method (Google Meet or WhatsApp).
 * WhatsApp option is only shown if the app is installed.
 */
@Composable
fun VideoCallMethodDialog(
    onGoogleMeet: () -> Unit,
    onWhatsApp: () -> Unit,
    onDismiss: () -> Unit,
    isWhatsAppAvailable: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video call") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        onGoogleMeet()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Google Meet", modifier = Modifier.fillMaxWidth())
                }

                if (isWhatsAppAvailable) {
                    TextButton(
                        onClick = {
                            onWhatsApp()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("WhatsApp", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Bottom sheet for retrying failed messages.
 * Shows options to retry as iMessage or SMS based on availability.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetryMessageBottomSheet(
    messageGuid: String,
    canRetryAsSms: Boolean,
    contactIMessageAvailable: Boolean,
    onRetryAsIMessage: () -> Unit,
    onRetryAsSms: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "Message Not Delivered",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Retry as iMessage option (if contact supports it)
            if (contactIMessageAvailable) {
                Surface(
                    onClick = {
                        onRetryAsIMessage()
                        onDismiss()
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            color = Color(0xFF007AFF).copy(alpha = 0.1f),
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = Color(0xFF007AFF),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Try Again as iMessage",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Send via BlueBubbles server",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Retry as SMS option
            if (canRetryAsSms) {
                Surface(
                    onClick = {
                        onRetryAsSms()
                        onDismiss()
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            color = Color(0xFF34C759).copy(alpha = 0.1f),
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.Sms,
                                    contentDescription = null,
                                    tint = Color(0xFF34C759),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Send as Text Message",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Send via your phone's SMS",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Cancel option
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Surface(
                onClick = onDismiss,
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }
        }
    }
}
