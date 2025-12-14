package com.bothbubbles.ui.settings.export

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.export.ExportProgress

/**
 * Main export screen that allows users to configure and export their messages
 */
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
        ExportContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            viewModel = viewModel,
            onShowChatPicker = { showChatPicker = true },
            onShowStartDatePicker = { showStartDatePicker = true },
            onShowEndDatePicker = { showEndDatePicker = true }
        )
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

    // Progress/Result dialogs
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
            LaunchedEffect(Unit) {
                viewModel.resetExportState()
            }
        }
        ExportProgress.Idle -> { /* No dialog */ }
    }
}

/**
 * Main content area with export configuration options
 */
@Composable
fun ExportContent(
    modifier: Modifier = Modifier,
    viewModel: ExportViewModel,
    uiState: ExportUiState,
    onShowChatPicker: () -> Unit,
    onShowStartDatePicker: () -> Unit,
    onShowEndDatePicker: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Format selection
        ExportFormatSelection(
            selectedFormat = uiState.format,
            onFormatSelected = { viewModel.setFormat(it) }
        )

        // Style selection
        ExportStyleSelection(
            selectedStyle = uiState.style,
            onStyleSelected = { viewModel.setStyle(it) }
        )

        // Conversations selection
        ConversationsSelection(
            exportAllChats = uiState.exportAllChats,
            availableChatsCount = uiState.availableChats.size,
            selectedChatGuidsCount = uiState.selectedChatGuids.size,
            onExportAllChatsChanged = { viewModel.setExportAllChats(it) },
            onShowChatPicker = onShowChatPicker
        )

        // Date range selection
        DateRangeSelection(
            dateRangeEnabled = uiState.dateRangeEnabled,
            startDate = uiState.startDate,
            endDate = uiState.endDate,
            onDateRangeEnabledChanged = { viewModel.setDateRangeEnabled(it) },
            onShowStartDatePicker = onShowStartDatePicker,
            onShowEndDatePicker = onShowEndDatePicker
        )

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
