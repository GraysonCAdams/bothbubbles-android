package com.bothbubbles.ui.media

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bothbubbles.data.local.db.entity.AttachmentEntity

@Composable
fun MediaInfoDialog(
    attachment: AttachmentEntity,
    senderName: String?,
    dateMillis: Long?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Media Info") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Filename
                attachment.transferName?.let { name ->
                    InfoRow(label = "Filename", value = name)
                }

                // Type
                attachment.mimeType?.let { type ->
                    InfoRow(label = "Type", value = type)
                }

                // Dimensions
                val width = attachment.width
                val height = attachment.height
                if (width != null && height != null && width > 0 && height > 0) {
                    InfoRow(
                        label = "Dimensions",
                        value = "$width × $height"
                    )
                }

                // File size
                attachment.totalBytes?.let { bytes ->
                    if (bytes > 0) {
                        InfoRow(
                            label = "Size",
                            value = Formatter.formatFileSize(context, bytes)
                        )
                    }
                }

                // Sender
                senderName?.let { name ->
                    InfoRow(label = "From", value = name)
                }

                // Date
                dateMillis?.let { millis ->
                    InfoRow(
                        label = "Date",
                        value = DateUtils.formatDateTime(
                            context,
                            millis,
                            DateUtils.FORMAT_SHOW_DATE or
                                DateUtils.FORMAT_SHOW_TIME or
                                DateUtils.FORMAT_SHOW_YEAR
                        )
                    )
                }

                // Download status
                val status = when {
                    attachment.localPath != null -> "Downloaded"
                    attachment.webUrl != null -> "Available for download"
                    else -> "Not available"
                }
                InfoRow(label = "Status", value = status)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
