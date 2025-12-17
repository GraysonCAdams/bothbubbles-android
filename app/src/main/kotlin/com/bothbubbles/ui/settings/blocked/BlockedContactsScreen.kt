package com.bothbubbles.ui.settings.blocked

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.settings.components.SettingsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedContactsScreen(
    onNavigateBack: () -> Unit,
    viewModel: BlockedContactsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var numberToAdd by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocked contacts") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.isDefaultSmsApp && uiState.canBlock) {
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Block number")
                        }
                    }
                }
            )
        }
    ) { padding ->
        BlockedContactsContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            viewModel = viewModel
        )
    }

    // Add blocked number dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                numberToAdd = ""
            },
            title = { Text("Block a number") },
            text = {
                OutlinedTextField(
                    value = numberToAdd,
                    onValueChange = { numberToAdd = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (numberToAdd.isNotBlank()) {
                            viewModel.blockNumber(numberToAdd)
                            showAddDialog = false
                            numberToAdd = ""
                        }
                    },
                    enabled = numberToAdd.isNotBlank()
                ) {
                    Text("Block")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    numberToAdd = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BlockedContactsContent(
    modifier: Modifier = Modifier,
    viewModel: BlockedContactsViewModel = hiltViewModel(),
    uiState: BlockedContactsUiState = viewModel.uiState.collectAsStateWithLifecycle().value
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var numberToAdd by remember { mutableStateOf("") }

    when {
        !uiState.isDefaultSmsApp -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Default SMS app required",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Set BothBubbles as your default SMS app to manage blocked contacts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        !uiState.canBlock -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Blocking not available",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Number blocking is not available on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        uiState.isLoading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        uiState.blockedNumbers.isEmpty() -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No blocked numbers",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Numbers you block will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Block a number")
                    }
                }
            }
        }
        else -> {
            LazyColumn(modifier = modifier.fillMaxSize()) {
                items(uiState.blockedNumbers) { number ->
                    ListItem(
                        headlineContent = { Text(number) },
                        leadingContent = {
                            Icon(Icons.Default.Block, contentDescription = null)
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { viewModel.unblockNumber(number) }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Unblock"
                                )
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                numberToAdd = ""
            },
            title = { Text("Block a number") },
            text = {
                OutlinedTextField(
                    value = numberToAdd,
                    onValueChange = { numberToAdd = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (numberToAdd.isNotBlank()) {
                            viewModel.blockNumber(numberToAdd)
                            showAddDialog = false
                            numberToAdd = ""
                        }
                    },
                    enabled = numberToAdd.isNotBlank()
                ) {
                    Text("Block")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    numberToAdd = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
