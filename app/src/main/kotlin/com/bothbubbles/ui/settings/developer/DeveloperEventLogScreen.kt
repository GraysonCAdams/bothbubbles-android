package com.bothbubbles.ui.settings.developer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.bothbubbles.services.developer.DeveloperEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperEventLogScreen(
    onNavigateBack: () -> Unit,
    viewModel: DeveloperEventLogViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var selectedEvent by remember { mutableStateOf<DeveloperEvent?>(null) }

    // Event detail dialog
    selectedEvent?.let { event ->
        EventDetailDialog(
            event = event,
            onDismiss = { selectedEvent = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Log") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearEvents() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection status header
            ConnectionStatusHeader(
                connectionMode = uiState.connectionMode,
                isAppInForeground = uiState.isAppInForeground
            )

            HorizontalDivider()

            // FCM status section
            FcmStatusSection(
                fcmTokenState = uiState.fcmTokenState,
                fcmToken = uiState.fcmToken
            )

            HorizontalDivider()

            // Event list
            if (uiState.events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.ListAlt,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No events yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Events will appear here as they are received",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.events, key = { it.id }) { event ->
                        EventLogItem(
                            event = event,
                            onClick = { selectedEvent = event }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }

                    // Developer mode toggle at the bottom
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        DeveloperModeToggle(
                            isEnabled = uiState.developerModeEnabled,
                            onToggle = { viewModel.toggleDeveloperMode() }
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }

            // Show toggle even when events are empty
            if (uiState.events.isEmpty()) {
                Spacer(modifier = Modifier.weight(1f))
                DeveloperModeToggle(
                    isEnabled = uiState.developerModeEnabled,
                    onToggle = { viewModel.toggleDeveloperMode() }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
