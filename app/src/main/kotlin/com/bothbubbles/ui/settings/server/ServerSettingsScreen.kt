package com.bothbubbles.ui.settings.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.core.model.ServerCapabilities
import com.bothbubbles.ui.components.settings.StitchColorPalette
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
                // Build server capabilities for the dialog
                val capabilities = remember(uiState.serverOsVersion, serverVersion, uiState.serverPrivateApiEnabled, uiState.helperConnected) {
                    ServerCapabilities.fromServerInfo(
                        osVersion = uiState.serverOsVersion,
                        serverVersion = serverVersion,
                        privateApiEnabled = uiState.serverPrivateApiEnabled,
                        helperConnected = uiState.helperConnected
                    )
                }

                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Server",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Combined version + OS display
                            val osDisplay = capabilities.macOsName?.let { name ->
                                uiState.serverOsVersion?.let { version -> "$version ($name)" }
                            } ?: uiState.serverOsVersion

                            Text(
                                text = buildString {
                                    append("v$serverVersion")
                                    osDisplay?.let { append(" • macOS $it") }
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        // Info button to show capabilities
                        IconButton(onClick = viewModel::showCapabilitiesDialog) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "View server capabilities",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Capabilities Dialog
                if (uiState.showCapabilitiesDialog) {
                    ServerCapabilitiesDialog(
                        capabilities = capabilities,
                        onDismiss = viewModel::hideCapabilitiesDialog
                    )
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

            // Appearance Section - Bubble Color Customization
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )

            StitchColorPalette(
                currentColor = uiState.currentBubbleColor,
                defaultColor = viewModel.getDefaultColor(),
                isUsingDefault = uiState.isUsingDefaultColor,
                onColorSelected = viewModel::setCustomColor,
                onResetToDefault = viewModel::resetColorToDefault
            )
        }
    }

/**
 * Dialog showing server capabilities and available features.
 * Uses Material Design 3 AlertDialog with custom content.
 */
@Composable
private fun ServerCapabilitiesDialog(
    capabilities: ServerCapabilities,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = "Server Capabilities",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Server Info Section
                ServerInfoSection(capabilities)

                HorizontalDivider()

                // Features Section
                Text(
                    text = "Features",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                FeaturesList(capabilities)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ServerInfoSection(capabilities: ServerCapabilities) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // macOS Version
        capabilities.macOsName?.let { name ->
            capabilities.osVersion?.let { version ->
                InfoRow(
                    label = "macOS",
                    value = "$version ($name)"
                )
            }
        } ?: capabilities.osVersion?.let { version ->
            InfoRow(label = "macOS", value = version)
        }

        // Server Version
        capabilities.serverVersion?.let { version ->
            InfoRow(label = "Server", value = "v$version")
        }

        // Private API Status
        InfoRow(
            label = "Private API",
            value = if (capabilities.privateApiEnabled) "Enabled" else "Disabled",
            valueColor = if (capabilities.privateApiEnabled) ConnectedGreen else MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Helper Status
        InfoRow(
            label = "Helper",
            value = if (capabilities.helperConnected) "Connected" else "Not connected",
            valueColor = if (capabilities.helperConnected) ConnectedGreen else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor
        )
    }
}

@Composable
private fun FeaturesList(capabilities: ServerCapabilities) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Core features (always available)
        FeatureItem(
            name = "Send & receive messages",
            available = true
        )
        FeatureItem(
            name = "Attachments",
            available = true
        )

        // Private API features
        FeatureItem(
            name = "Tapbacks (reactions)",
            available = capabilities.canSendTapbacks,
            requirement = if (!capabilities.canSendTapbacks) "Requires Private API" else null
        )
        FeatureItem(
            name = "Reply to messages",
            available = capabilities.canSendReplies,
            requirement = if (!capabilities.canSendReplies) "Requires Private API" else null
        )
        FeatureItem(
            name = "Message effects",
            available = capabilities.canSendWithEffects,
            requirement = if (!capabilities.canSendWithEffects) "Requires Private API" else null
        )
        FeatureItem(
            name = "Typing indicators",
            available = capabilities.canSendTypingIndicators,
            requirement = if (!capabilities.canSendTypingIndicators) "Requires Private API" else null
        )
        FeatureItem(
            name = "Mark as read",
            available = capabilities.canMarkAsRead,
            requirement = if (!capabilities.canMarkAsRead) "Requires Private API" else null
        )

        // macOS version + Private API features
        FeatureItem(
            name = "Edit messages",
            available = capabilities.canEditMessages,
            requirement = when {
                !capabilities.privateApiEnabled -> "Requires Private API"
                capabilities.macOsVersion?.let { (major, _) -> major < 13 } == true -> "Requires macOS 13+"
                else -> null
            }
        )
        FeatureItem(
            name = "Unsend messages",
            available = capabilities.canUnsendMessages,
            requirement = when {
                !capabilities.privateApiEnabled -> "Requires Private API"
                capabilities.macOsVersion?.let { (major, _) -> major < 13 } == true -> "Requires macOS 13+"
                else -> null
            }
        )

        // macOS version features
        FeatureItem(
            name = "FindMy devices",
            available = capabilities.canUseFindMyDevices,
            requirement = if (!capabilities.canUseFindMyDevices) "Requires macOS 11+" else null
        )
    }
}

@Composable
private fun FeatureItem(
    name: String,
    available: Boolean,
    requirement: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (available) Icons.Default.Check else Icons.Default.Close,
            contentDescription = if (available) "Available" else "Not available",
            modifier = Modifier.size(18.dp),
            tint = if (available) ConnectedGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            if (!available && requirement != null) {
                Text(
                    text = requirement,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}
