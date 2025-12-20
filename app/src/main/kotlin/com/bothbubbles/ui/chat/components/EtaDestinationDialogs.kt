package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Warning dialog shown when unable to detect destination while actively driving.
 * Has a countdown that auto-accepts when it reaches 0.
 *
 * Design specs:
 * - Large 48dp buttons for easy tapping while driving
 * - Clear countdown display
 * - Progress indicator showing remaining time
 *
 * @param countdownSeconds Current countdown value (5, 4, 3, 2, 1, 0)
 * @param onShareNow Called when user taps "Share Now" to accept immediately
 * @param onCancel Called when user cancels the share
 */
@Composable
fun EtaDrivingWarningDialog(
    countdownSeconds: Int,
    onShareNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = { /* Don't dismiss on outside tap while driving */ },
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Couldn't detect destination",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Share ETA without destination?",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Countdown with progress indicator
                CircularProgressIndicator(
                    progress = { countdownSeconds / 5f },
                    modifier = Modifier.size(80.dp),
                    strokeWidth = 6.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Sharing in $countdownSeconds...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onShareNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Share Now",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = "Cancel",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        modifier = modifier
    )
}

/**
 * Dialog for entering destination manually when not actively driving.
 * Shown when destination could not be detected and user is not driving.
 *
 * @param onShare Called with the destination (can be null/empty for no destination)
 * @param onCancel Called when user cancels
 */
@Composable
fun EtaDestinationInputDialog(
    onShare: (destination: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var destination by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = "Where are you heading?",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column {
                Text(
                    text = "Couldn't detect your navigation destination. You can enter it manually or share without one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text("Destination (optional)") },
                    placeholder = { Text("e.g., Home, Work, 123 Main St") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onShare(destination.takeIf { it.isNotBlank() }) },
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = "Share ETA",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.height(48.dp)
            ) {
                Text("Cancel")
            }
        },
        modifier = modifier
    )
}
