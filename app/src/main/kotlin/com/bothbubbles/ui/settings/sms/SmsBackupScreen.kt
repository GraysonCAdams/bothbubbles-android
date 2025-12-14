package com.bothbubbles.ui.settings.sms

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.bothbubbles.services.export.SmsBackupProgress
import com.bothbubbles.services.export.SmsRestoreProgress
import com.bothbubbles.ui.settings.components.SettingsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsBackupScreen(
    onNavigateBack: () -> Unit,
    viewModel: SmsBackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // File picker for restore
    val restoreFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.startRestore(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS/MMS Backup") },
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
        SmsBackupContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            viewModel = viewModel,
            onStartRestore = { uri -> viewModel.startRestore(uri) }
        )
    }

    // Backup progress/result dialogs
    when (val progress = uiState.backupProgress) {
        is SmsBackupProgress.Exporting,
        is SmsBackupProgress.Saving -> {
            BackupProgressDialog(
                progress = progress,
                onCancel = { viewModel.cancelBackup() }
            )
        }
        is SmsBackupProgress.Complete -> {
            BackupCompleteDialog(
                result = progress,
                onDismiss = { viewModel.resetBackupProgress() }
            )
        }
        is SmsBackupProgress.Error -> {
            ErrorDialog(
                title = "Backup Failed",
                message = progress.message,
                onDismiss = { viewModel.resetBackupProgress() }
            )
        }
        SmsBackupProgress.Cancelled,
        SmsBackupProgress.Idle -> { /* No dialog */ }
    }

    // Restore progress/result dialogs
    when (val progress = uiState.restoreProgress) {
        is SmsRestoreProgress.Reading,
        is SmsRestoreProgress.Restoring -> {
            RestoreProgressDialog(
                progress = progress,
                onCancel = { viewModel.cancelRestore() }
            )
        }
        is SmsRestoreProgress.Complete -> {
            RestoreCompleteDialog(
                result = progress,
                onDismiss = { viewModel.resetRestoreProgress() }
            )
        }
        is SmsRestoreProgress.Error -> {
            ErrorDialog(
                title = "Restore Failed",
                message = progress.message,
                onDismiss = { viewModel.resetRestoreProgress() }
            )
        }
        SmsRestoreProgress.Cancelled,
        SmsRestoreProgress.Idle -> { /* No dialog */ }
    }
}

@Composable
fun SmsBackupContent(
    modifier: Modifier = Modifier,
    viewModel: SmsBackupViewModel = hiltViewModel(),
    uiState: SmsBackupUiState = viewModel.uiState.collectAsStateWithLifecycle().value,
    onStartRestore: (Uri) -> Unit = {}
) {
    val restoreFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onStartRestore(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            // Info card
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Backup & Restore",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Create a backup of your local SMS/MMS messages or restore from a previous backup. " +
                                "Duplicate messages will be automatically skipped during restore.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Backup section
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Backup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Backup Messages",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Export all SMS and MMS messages to a JSON file in your Downloads folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.startBackup() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.backupProgress is SmsBackupProgress.Idle ||
                                uiState.backupProgress is SmsBackupProgress.Complete ||
                                uiState.backupProgress is SmsBackupProgress.Error ||
                                uiState.backupProgress is SmsBackupProgress.Cancelled
                    ) {
                        Icon(
                            Icons.Default.Upload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Backup")
                    }
                }
            }

            // Restore section
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Restore Messages",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Restore SMS and MMS messages from a BlueBubbles backup file. " +
                                "Messages that already exist will be skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { restoreFilePicker.launch(arrayOf("application/json")) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.restoreProgress is SmsRestoreProgress.Idle ||
                                uiState.restoreProgress is SmsRestoreProgress.Complete ||
                                uiState.restoreProgress is SmsRestoreProgress.Error ||
                                uiState.restoreProgress is SmsRestoreProgress.Cancelled
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Backup File")
                    }
                }
            }

            // Important notice
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Important",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "BlueBubbles must be set as the default SMS app to restore messages. " +
                                "MMS attachments are included in the backup as Base64 encoded data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
