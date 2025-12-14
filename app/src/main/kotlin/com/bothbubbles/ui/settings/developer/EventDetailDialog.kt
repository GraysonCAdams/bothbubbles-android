package com.bothbubbles.ui.settings.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.services.developer.DeveloperEvent
import com.bothbubbles.services.developer.EventSource

@Composable
internal fun EventDetailDialog(
    event: DeveloperEvent,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = when (event.source) {
                        EventSource.SOCKET -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        EventSource.FCM -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = when (event.source) {
                            EventSource.SOCKET -> "WS"
                            EventSource.FCM -> "FCM"
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = when (event.source) {
                            EventSource.SOCKET -> MaterialTheme.colorScheme.primary
                            EventSource.FCM -> MaterialTheme.colorScheme.tertiary
                        }
                    )
                }
                Text(
                    text = event.eventType,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            SelectionContainer {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Timestamp
                    Row {
                        Text(
                            text = "Time: ",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = event.formattedTime,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Details
                    if (event.details != null) {
                        Column {
                            Text(
                                text = "Details:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = event.details,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.small
                                    )
                                    .padding(8.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "No additional details",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
