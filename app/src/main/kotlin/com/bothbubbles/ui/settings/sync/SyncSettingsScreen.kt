package com.bothbubbles.ui.settings.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.sync.PauseReason
import com.bothbubbles.services.sync.SyncState
import com.bothbubbles.ui.settings.components.SettingsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showCleanSyncDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        SyncSettingsContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            viewModel = viewModel,
            showCleanSyncDialog = showCleanSyncDialog,
            onShowCleanSyncDialog = { showCleanSyncDialog = it },
            showDisconnectDialog = showDisconnectDialog,
            onShowDisconnectDialog = { showDisconnectDialog = it }
        )
    }

    // Clean sync confirmation dialog
    if (showCleanSyncDialog) {
        AlertDialog(
            onDismissRequest = { showCleanSyncDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Clean sync?") },
            text = {
                Text(
                    "This will delete all local messages and conversations, then re-download everything from the server. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanSyncDialog = false
                        viewModel.cleanSync()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clean sync")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanSyncDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Disconnect server confirmation dialog
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            icon = { Icon(Icons.Default.CloudOff, contentDescription = null) },
            title = { Text("Disconnect from server?") },
            text = {
                Text(
                    "This will remove all iMessage conversations and messages synced from the BlueBubbles server. " +
                    "Local SMS/MMS messages will be preserved. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectDialog = false
                        viewModel.disconnectServer()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SyncSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
    uiState: SyncSettingsUiState = viewModel.uiState.collectAsStateWithLifecycle().value,
    showCleanSyncDialog: Boolean = false,
    onShowCleanSyncDialog: (Boolean) -> Unit = {},
    showDisconnectDialog: Boolean = false,
    onShowDisconnectDialog: (Boolean) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            // Last sync info card
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Last synced",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = uiState.lastSyncFormatted,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            // Syncing paused card
            val syncState = uiState.syncState
            if (syncState is SyncState.Paused) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PauseCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Syncing paused",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = when (syncState.reason) {
                                        PauseReason.WAITING_FOR_WIFI -> "Waiting for WiFi connection..."
                                        PauseReason.NO_CONNECTION -> "No internet connection"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                        // Progress bar (gray, showing previous progress)
                        LinearProgressIndicator(
                            progress = { syncState.previousProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.outline,
                            trackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.syncAnywayOneTime() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Sync Anyway")
                            }
                        }
                    }
                }
            }

            // Sync actions card
            SettingsCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Sync now
                    val isSyncing = syncState is SyncState.Syncing
                    ListItem(
                        headlineContent = {
                            Text(
                                text = if (isSyncing) "Syncing now..." else "Sync now",
                                color = if (isSyncing) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        },
                        supportingContent = {
                            Text(
                                text = "Download new messages from server",
                                color = if (isSyncing) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = null,
                                tint = if (isSyncing) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        },
                        modifier = Modifier.then(
                            if (!isSyncing) Modifier.clickable(onClick = viewModel::syncNow)
                            else Modifier
                        )
                    )

                    HorizontalDivider()

                    // Full sync
                    ListItem(
                        headlineContent = { Text("Full sync") },
                        supportingContent = { Text("Re-download all messages from server") },
                        leadingContent = {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                        },
                        modifier = Modifier.then(
                            if (!isSyncing) Modifier.clickable(onClick = viewModel::fullSync)
                            else Modifier
                        )
                    )

                    HorizontalDivider()

                    // Clean sync (destructive)
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "Clean sync",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        supportingContent = {
                            Text(
                                text = "Delete local data and re-sync everything",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.then(
                            if (syncState !is SyncState.Syncing) Modifier.clickable { onShowCleanSyncDialog(true) }
                            else Modifier
                        )
                    )
                }
            }

            // Attachment download settings
            SettingsCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Auto-download attachments") },
                        supportingContent = {
                            Text(
                                if (uiState.autoDownloadEnabled) {
                                    "Attachments download automatically when opening a chat"
                                } else {
                                    "Tap on attachments to download them manually"
                                }
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Image, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = uiState.autoDownloadEnabled,
                                onCheckedChange = { viewModel.setAutoDownloadEnabled(it) }
                            )
                        },
                        modifier = Modifier.clickable {
                            viewModel.setAutoDownloadEnabled(!uiState.autoDownloadEnabled)
                        }
                    )

                    HorizontalDivider()

                    // Sync via cellular toggle
                    ListItem(
                        headlineContent = { Text("Sync via cellular") },
                        supportingContent = {
                            Text(
                                if (uiState.syncOnCellular) {
                                    "Messages sync on any network"
                                } else {
                                    "Messages sync only on WiFi"
                                }
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.SignalCellularAlt, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = uiState.syncOnCellular,
                                onCheckedChange = { viewModel.setSyncOnCellular(it) }
                            )
                        },
                        modifier = Modifier.clickable {
                            viewModel.setSyncOnCellular(!uiState.syncOnCellular)
                        }
                    )
                }
            }

            // Disconnect server option
            SettingsCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "Disconnect from server",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        supportingContent = {
                            Text(
                                text = "Remove all iMessage data and keep only local SMS/MMS",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.then(
                            if (syncState !is SyncState.Syncing) Modifier.clickable { onShowDisconnectDialog(true) }
                            else Modifier
                        )
                    )
                }
            }

            // Sync completed message
            if (syncState is SyncState.Completed) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Sync completed successfully",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Sync error message
            if (syncState is SyncState.Error) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sync failed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = syncState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        IconButton(onClick = { viewModel.resetSyncState() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
        }
    }
