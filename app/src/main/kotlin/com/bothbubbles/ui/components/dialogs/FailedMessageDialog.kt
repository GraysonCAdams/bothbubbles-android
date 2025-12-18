package com.bothbubbles.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bothbubbles.util.error.MessageErrorCode

/**
 * Dialog shown when a message fails to send.
 * Provides options to retry, retry as SMS (if applicable), or delete the message.
 *
 * @param visible Whether the dialog is visible
 * @param errorCode The numeric error code from the failed message
 * @param errorMessage The raw error message from the server (optional, for additional context)
 * @param canRetryAsSms Whether SMS retry is available (requires phone number recipient)
 * @param onRetry Callback when user taps "Retry" to resend via original method
 * @param onRetryAsSms Callback when user taps "Retry as SMS"
 * @param onDelete Callback when user taps "Delete" to remove the failed message
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
fun FailedMessageDialog(
    visible: Boolean,
    errorCode: Int,
    errorMessage: String? = null,
    canRetryAsSms: Boolean = false,
    onRetry: () -> Unit,
    onRetryAsSms: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val title = MessageErrorCode.getErrorTitle(errorCode)
    val userMessage = MessageErrorCode.getUserMessage(errorCode, errorMessage)
    val suggestsSms = MessageErrorCode.suggestsSmsRetry(errorCode)
    val isRetryable = MessageErrorCode.isRetryable(errorCode)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(title)
            }
        },
        text = {
            Column {
                // Error message
                Text(
                    text = userMessage,
                    style = MaterialTheme.typography.bodyMedium
                )

                // Show SMS suggestion if error code 22 and SMS is available
                if (suggestsSms && canRetryAsSms) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sms,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Try sending as SMS instead",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Show error code for debugging (in smaller text)
                if (errorCode > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Error code: $errorCode",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Retry as SMS button (prominent if error 22 and SMS available)
                if (canRetryAsSms && suggestsSms) {
                    TextButton(onClick = {
                        onRetryAsSms()
                        onDismiss()
                    }) {
                        Text("Retry as SMS")
                    }
                }

                // Regular retry button (if retryable)
                if (isRetryable) {
                    TextButton(onClick = {
                        onRetry()
                        onDismiss()
                    }) {
                        Text("Retry")
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    onDelete()
                    onDismiss()
                }) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Show "Retry as SMS" as secondary if not the primary suggestion
                if (canRetryAsSms && !suggestsSms) {
                    TextButton(onClick = {
                        onRetryAsSms()
                        onDismiss()
                    }) {
                        Text("Try SMS")
                    }
                }
            }
        }
    )
}
