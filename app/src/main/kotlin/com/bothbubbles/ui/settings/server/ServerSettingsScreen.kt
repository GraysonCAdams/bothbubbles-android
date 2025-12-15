package com.bothbubbles.ui.settings.server

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.socket.ConnectionState
import com.bothbubbles.ui.settings.components.SettingsCard

private val ConnectedGreen = Color(0xFF34A853)
private val DisconnectedRed = Color(0xFFEA4335)
private val ConnectingOrange = Color(0xFFFBBC04)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Settings") },
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
        ServerSettingsContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            viewModel = viewModel
        )
    }
}

@Composable
fun ServerSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: ServerSettingsViewModel = hiltViewModel(),
    uiState: ServerSettingsUiState = viewModel.uiState.collectAsStateWithLifecycle().value
) {
    Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Status Card
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val (statusColor, statusText, statusIcon) = when (uiState.connectionState) {
                        ConnectionState.CONNECTED -> Triple(ConnectedGreen, "Connected", Icons.Default.CheckCircle)
                        ConnectionState.CONNECTING -> Triple(ConnectingOrange, "Connecting...", Icons.Default.Sync)
                        ConnectionState.DISCONNECTED -> Triple(DisconnectedRed, "Disconnected", Icons.Default.CloudOff)
                        ConnectionState.ERROR -> Triple(DisconnectedRed, "Connection Error", Icons.Default.Error)
                        ConnectionState.NOT_CONFIGURED -> Triple(DisconnectedRed, "Not Configured", Icons.Default.CloudOff)
                    }

                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = statusColor
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor
                    )

                    if (uiState.connectionState != ConnectionState.CONNECTED) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { viewModel.reconnect() },
                            enabled = !uiState.isReconnecting
                        ) {
                            if (uiState.isReconnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Reconnect")
                        }
                    }
                }
            }

            // Server URL Card
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Server URL",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = uiState.serverUrl.ifEmpty { "Not configured" },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Server Info Card (when connected)
            val serverVersion = uiState.serverVersion
            if (uiState.connectionState == ConnectionState.CONNECTED && serverVersion != null) {
                SettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Server Version",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = serverVersion,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            // Actions Card
            SettingsCard {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = { Text("Test Connection") },
                        supportingContent = { Text("Verify server is reachable") },
                        leadingContent = {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        },
                        trailingContent = if (uiState.isTesting) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else null,
                        modifier = Modifier.then(
                            if (!uiState.isTesting) Modifier.clickable { viewModel.testConnection() }
                            else Modifier
                        )
                    )

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("Disconnect") },
                        supportingContent = { Text("Disconnect from server") },
                        leadingContent = {
                            Icon(
                                Icons.Default.LinkOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.then(
                            if (uiState.connectionState == ConnectionState.CONNECTED) Modifier.clickable { viewModel.disconnect() }
                            else Modifier
                        )
                    )
                }
            }

            // Error message
            uiState.error?.let { error ->
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
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }

            // Test result
            uiState.testResult?.let { result ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.success) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (result.success) ConnectedGreen else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (result.success) "Connection successful" else "Connection failed",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (result.latencyMs != null) {
                                Text(
                                    text = "Latency: ${result.latencyMs}ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!result.success && result.error != null) {
                                Text(
                                    text = result.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.clearTestResult() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
        }
    }
