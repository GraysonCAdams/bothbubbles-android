package com.bothbubbles.ui.settings.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.export.ExportFormat
import com.bothbubbles.services.export.ExportProgress
import com.bothbubbles.services.export.ExportStyle
import com.bothbubbles.ui.settings.components.SettingsCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onNavigateBack: () -> Unit,
    viewModel: ExportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showChatPicker by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    // Handle export completion
    LaunchedEffect(uiState.exportProgress) {
        when (uiState.exportProgress) {
            is ExportProgress.Complete, is ExportProgress.Error, ExportProgress.Cancelled -> {
                // Keep showing the result dialog
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export Messages") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Format selection
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Format",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = uiState.format == ExportFormat.HTML,
                            onClick = { viewModel.setFormat(ExportFormat.HTML) },
                            label = { Text("HTML") },
                            leadingIcon = if (uiState.format == ExportFormat.HTML) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = uiState.format == ExportFormat.PDF,
                            onClick = { viewModel.setFormat(ExportFormat.PDF) },
                            label = { Text("PDF") },
                            leadingIcon = if (uiState.format == ExportFormat.PDF) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Style selection
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Style",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = uiState.style == ExportStyle.CHAT_BUBBLES,
                            onClick = { viewModel.setStyle(ExportStyle.CHAT_BUBBLES) },
                            label = { Text("Chat Bubbles") },
                            leadingIcon = if (uiState.style == ExportStyle.CHAT_BUBBLES) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = uiState.style == ExportStyle.PLAIN_TEXT,
                            onClick = { viewModel.setStyle(ExportStyle.PLAIN_TEXT) },
                            label = { Text("Plain Text") },
                            leadingIcon = if (uiState.style == ExportStyle.PLAIN_TEXT) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Conversations selection
            SettingsCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Conversations",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // All conversations option
                    ListItem(
                        headlineContent = { Text("All conversations") },
                        supportingContent = { Text("${uiState.availableChats.size} conversations") },
                        leadingContent = {
                            RadioButton(
                                selected = uiState.exportAllChats,
                                onClick = { viewModel.setExportAllChats(true) }
                            )
                        },
                        modifier = Modifier.clickable { viewModel.setExportAllChats(true) }
                    )

                    HorizontalDivider()

                    // Selected conversations option
                    ListItem(
                        headlineContent = { Text("Selected conversations") },
                        supportingContent = {
                            Text(
                                if (uiState.selectedChatGuids.isEmpty()) {
                                    "None selected"
                                } else {
                                    "${uiState.selectedChatGuids.size} selected"
                                }
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected = !uiState.exportAllChats,
                                onClick = { viewModel.setExportAllChats(false) }
                            )
                        },
                        trailingContent = if (!uiState.exportAllChats) {
                            {
                                IconButton(onClick = { showChatPicker = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Select")
                                }
                            }
                        } else null,
                        modifier = Modifier.clickable { viewModel.setExportAllChats(false) }
                    )
                }
            }

            // Date range selection
            SettingsCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Date Range",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // All time option
                    ListItem(
                        headlineContent = { Text("All time") },
                        leadingContent = {
                            RadioButton(
                                selected = !uiState.dateRangeEnabled,
                                onClick = { viewModel.setDateRangeEnabled(false) }
                            )
                        },
                        modifier = Modifier.clickable { viewModel.setDateRangeEnabled(false) }
                    )

                    HorizontalDivider()

                    // Custom range option
                    ListItem(
                        headlineContent = { Text("Custom range") },
                        leadingContent = {
                            RadioButton(
                                selected = uiState.dateRangeEnabled,
                                onClick = { viewModel.setDateRangeEnabled(true) }
                            )
                        },
                        modifier = Modifier.clickable { viewModel.setDateRangeEnabled(true) }
                    )

                    // Date pickers (shown when custom range is selected)
                    if (uiState.dateRangeEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showStartDatePicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = uiState.startDate?.let { dateFormatter.format(Date(it)) } ?: "Start date",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = { showEndDatePicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = uiState.endDate?.let { dateFormatter.format(Date(it)) } ?: "End date",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Export button
            Button(
                onClick = { viewModel.startExport() },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.canExport
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Messages")
            }
        }
    }

    // Chat picker dialog
    if (showChatPicker) {
        ChatSelectionDialog(
            chats = uiState.availableChats,
            selectedChatGuids = uiState.selectedChatGuids,
            onChatToggle = { viewModel.toggleChatSelection(it) },
            onSelectAll = { viewModel.setAllChatsSelected(true) },
            onDeselectAll = { viewModel.setAllChatsSelected(false) },
            onDismiss = { showChatPicker = false }
        )
    }

    // Date picker dialogs
    if (showStartDatePicker) {
        DatePickerDialog(
            title = "Start Date",
            selectedDate = uiState.startDate,
            onDateSelected = { viewModel.setStartDate(it) },
            onDismiss = { showStartDatePicker = false }
        )
    }

    if (showEndDatePicker) {
        DatePickerDialog(
            title = "End Date",
            selectedDate = uiState.endDate,
            onDateSelected = { viewModel.setEndDate(it) },
            onDismiss = { showEndDatePicker = false }
        )
    }

    // Progress/Result dialog
    when (val progress = uiState.exportProgress) {
        is ExportProgress.Loading,
        is ExportProgress.Generating,
        is ExportProgress.Saving -> {
            ExportProgressDialog(
                progress = progress,
                onCancel = { viewModel.cancelExport() }
            )
        }
        is ExportProgress.Complete -> {
            ExportCompleteDialog(
                result = progress,
                onDismiss = { viewModel.resetExportState() }
            )
        }
        is ExportProgress.Error -> {
            ExportErrorDialog(
                message = progress.message,
                onDismiss = { viewModel.resetExportState() }
            )
        }
        ExportProgress.Cancelled -> {
            // Just reset, no dialog needed
            LaunchedEffect(Unit) {
                viewModel.resetExportState()
            }
        }
        ExportProgress.Idle -> { /* No dialog */ }
    }
}

@Composable
private fun ChatSelectionDialog(
    chats: List<ExportableChatInfo>,
    selectedChatGuids: Set<String>,
    onChatToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredChats = remember(chats, searchQuery) {
        if (searchQuery.isBlank()) chats
        else chats.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Conversations",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search conversations") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                // Select all / Deselect all buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onSelectAll) {
                        Text("Select All")
                    }
                    TextButton(onClick = onDeselectAll) {
                        Text("Deselect All")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${selectedChatGuids.size} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Chat list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredChats, key = { it.guid }) { chat ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = chat.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = if (chat.isGroup) {
                                { Text("Group") }
                            } else null,
                            leadingContent = {
                                Checkbox(
                                    checked = selectedChatGuids.contains(chat.guid),
                                    onCheckedChange = { onChatToggle(chat.guid) }
                                )
                            },
                            modifier = Modifier.clickable { onChatToggle(chat.guid) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialog(
    title: String,
    selectedDate: Long?,
    onDateSelected: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate ?: System.currentTimeMillis()
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { onDateSelected(it) }
                    onDismiss()
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(
            state = datePickerState,
            title = { Text(title, modifier = Modifier.padding(16.dp)) }
        )
    }
}

@Composable
private fun ExportProgressDialog(
    progress: ExportProgress,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Non-dismissible during export */ },
        icon = {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        },
        title = { Text("Exporting Messages") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (progress) {
                    is ExportProgress.Loading -> {
                        Text(
                            text = "Processing: ${progress.chatName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { progress.progressFraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${progress.chatIndex} of ${progress.totalChats} conversations",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is ExportProgress.Generating -> {
                        Text(
                            text = progress.stage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is ExportProgress.Saving -> {
                        Text(
                            text = "Saving ${progress.fileName}...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ExportCompleteDialog(
    result: ExportProgress.Complete,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = { Text("Export Complete") },
        text = {
            Column {
                Text("Your messages have been saved to Downloads.")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${result.messageCount} messages from ${result.chatCount} conversations",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun ExportErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = { Text("Export Failed") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
