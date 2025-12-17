package com.bothbubbles.ui.settings.crashlogs

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.settings.components.SettingsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogsScreen(
    onNavigateBack: () -> Unit,
    viewModel: CrashLogsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Crash Reports?") },
            text = { Text("This will permanently delete all stored crash reports. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllReports()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Report detail dialog
    uiState.selectedReport?.let { report ->
        AlertDialog(
            onDismissRequest = { viewModel.clearSelectedReport() },
            title = {
                Column {
                    Text("Crash Report")
                    Text(
                        text = viewModel.formatTimestamp(report.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = uiState.selectedReportContent ?: "Loading...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            // Share via email
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    report.file
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_SUBJECT, "BothBubbles Crash Report")
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Crash Report"))
                            } catch (e: Exception) {
                                // Fall back to text sharing
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "BothBubbles Crash Report")
                                    putExtra(Intent.EXTRA_TEXT, uiState.selectedReportContent)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Crash Report"))
                            }
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share")
                    }
                    TextButton(
                        onClick = {
                            viewModel.deleteReport(report)
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { viewModel.clearSelectedReport() }) {
                        Text("Close")
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crash Logs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.crashReports.isNotEmpty()) {
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Delete all")
                        }
                    }
                    IconButton(onClick = { viewModel.loadCrashReports() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.crashReports.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "No crash reports",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "The app hasn't crashed recently",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        SettingsCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Privacy Notice",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Crash reports are stored locally on your device. " +
                                            "They are never sent automatically. You can choose to share " +
                                            "individual reports to help us fix issues.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(uiState.crashReports, key = { it.id }) { report ->
                        SettingsCard {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = viewModel.formatTimestamp(report.timestamp),
                                        maxLines = 1
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = report.preview,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.selectReport(report) }
                            )
                        }
                    }
                }
            }
        }
    }
}
