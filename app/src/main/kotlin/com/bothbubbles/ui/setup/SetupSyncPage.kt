package com.bothbubbles.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.bothbubbles.util.HapticUtils
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun SyncPage(
    uiState: SetupUiState,
    onSkipEmptyChatsChange: (Boolean) -> Unit,
    onStartSync: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            // Syncing view
            uiState.isSyncing -> {
                Spacer(modifier = Modifier.weight(1f))

                CircularProgressIndicator(
                    progress = { uiState.syncProgress },
                    modifier = Modifier.size(120.dp),
                    strokeWidth = 8.dp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "${(uiState.syncProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Syncing your messages...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.weight(1f))
            }

            // Error view
            uiState.syncError != null -> {
                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Sync Failed",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = uiState.syncError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(onClick = onStartSync) {
                    Text("Retry")
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            // Settings view (before sync)
            else -> {
                Text(
                    text = "Sync Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Configure how iMessages are synced from your server",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Skip empty chats toggle
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Skip empty conversations",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Don't sync conversations with no messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        val haptic = LocalHapticFeedback.current
                        Switch(
                            checked = uiState.skipEmptyChats,
                            onCheckedChange = {
                                HapticUtils.onConfirm(haptic)
                                onSkipEmptyChatsChange(it)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Navigation buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back")
                    }

                    Button(
                        onClick = onStartSync,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Sync")
                    }
                }
            }
        }
    }
}
