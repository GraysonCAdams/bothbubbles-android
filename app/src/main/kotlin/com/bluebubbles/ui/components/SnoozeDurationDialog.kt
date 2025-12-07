package com.bluebubbles.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dialog for selecting a snooze duration for a conversation.
 * If the conversation is already snoozed, it shows the current snooze status
 * and provides an option to unsnooze.
 */
@Composable
fun SnoozeDurationDialog(
    visible: Boolean,
    currentSnoozeUntil: Long?,
    onDurationSelected: (SnoozeDuration) -> Unit,
    onUnsnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val isSnoozed = currentSnoozeUntil != null &&
            (currentSnoozeUntil == -1L || currentSnoozeUntil > System.currentTimeMillis())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isSnoozed) "Snoozed" else "Snooze notifications"
            )
        },
        text = {
            Column {
                // Show current snooze status if snoozed
                if (isSnoozed && currentSnoozeUntil != null) {
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
                                Icons.Outlined.Snooze,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Snoozed ${SnoozeDuration.formatRemainingTime(currentSnoozeUntil)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Change snooze duration:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Duration options
                SnoozeDuration.entries.forEach { duration ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDurationSelected(duration)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = duration.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isSnoozed) {
                TextButton(onClick = onUnsnooze) {
                    Text("Unsnooze")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
