package com.bluebubbles.ui.settings.sms

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SmsSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onPermissionsResult()
    }

    // Default SMS app launcher
    val defaultSmsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onDefaultSmsAppResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS/MMS Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device capability status
            item {
                SmsCapabilityCard(
                    status = uiState.capabilityStatus,
                    onRequestPermissions = {
                        permissionLauncher.launch(viewModel.getMissingPermissions())
                    },
                    onRequestDefaultSmsApp = {
                        defaultSmsLauncher.launch(viewModel.getDefaultSmsAppIntent())
                    }
                )
            }

            // Enable SMS toggle
            item {
                SwitchSettingCard(
                    title = "Enable Local SMS",
                    subtitle = "Use this device to send/receive SMS messages",
                    icon = Icons.Default.Sms,
                    checked = uiState.smsEnabled,
                    onCheckedChange = viewModel::setSmsEnabled,
                    enabled = uiState.capabilityStatus?.canSendSms == true
                )
            }

            // SMS preferences
            item {
                AnimatedVisibility(visible = uiState.smsEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SwitchSettingCard(
                            title = "Prefer SMS",
                            subtitle = "Send SMS by default instead of iMessage",
                            icon = Icons.Default.PhoneAndroid,
                            checked = uiState.preferSmsOverIMessage,
                            onCheckedChange = viewModel::setPreferSmsOverIMessage
                        )

                        // SIM selection (if dual SIM)
                        if (uiState.availableSims.size > 1) {
                            SimSelectionCard(
                                sims = uiState.availableSims,
                                selectedSlot = uiState.selectedSimSlot,
                                defaultSimId = uiState.defaultSimId,
                                onSimSelected = viewModel::setSelectedSimSlot
                            )
                        }
                    }
                }
            }

            // Import SMS
            item {
                ImportSmsCard(
                    isImporting = uiState.isImporting,
                    progress = uiState.importProgress,
                    lastCount = uiState.lastImportCount,
                    enabled = uiState.capabilityStatus?.canReadSms == true,
                    onStartImport = viewModel::startSmsImport
                )
            }

            // Error display
            uiState.error?.let { error ->
                item {
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
                                error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = viewModel::clearError) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmsCapabilityCard(
    status: com.bluebubbles.services.sms.SmsCapabilityStatus?,
    onRequestPermissions: () -> Unit,
    onRequestDefaultSmsApp: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                status?.isFullyFunctional == true -> MaterialTheme.colorScheme.primaryContainer
                status?.deviceSupportsSms == false -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        status?.isFullyFunctional == true -> Icons.Default.CheckCircle
                        status?.deviceSupportsSms == false -> Icons.Default.PhonelinkOff
                        else -> Icons.Default.Warning
                    },
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    when {
                        status?.isFullyFunctional == true -> "SMS Fully Configured"
                        status?.deviceSupportsSms == false -> "Device Doesn't Support SMS"
                        else -> "SMS Setup Required"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (status?.needsSetup == true) {
                // Show what's missing
                if (status.missingPermissions.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant SMS Permissions (${status.missingPermissions.size} needed)")
                    }
                }

                if (!status.isDefaultSmsApp && status.hasReceivePermission) {
                    OutlinedButton(
                        onClick = onRequestDefaultSmsApp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sms, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Set as Default SMS App")
                    }
                }
            }

            // Status indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusIndicator("Read", status?.canReadSms == true)
                StatusIndicator("Send", status?.canSendSms == true)
                StatusIndicator("Receive", status?.canReceiveSms == true)
            }
        }
    }
}

@Composable
private fun StatusIndicator(label: String, enabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            if (enabled) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun SwitchSettingCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun SimSelectionCard(
    sims: List<com.bluebubbles.services.sms.SimInfo>,
    selectedSlot: Int,
    defaultSimId: Int,
    onSimSelected: (Int) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SimCard, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("SIM Selection", style = MaterialTheme.typography.titleSmall)
            }

            sims.forEach { sim ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedSlot == sim.subscriptionId ||
                                (selectedSlot == -1 && sim.subscriptionId == defaultSimId),
                        onClick = { onSimSelected(sim.subscriptionId) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(sim.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            sim.carrierName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (sim.subscriptionId == defaultSimId) {
                        AssistChip(
                            onClick = { },
                            label = { Text("Default") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportSmsCard(
    isImporting: Boolean,
    progress: Float,
    lastCount: Int?,
    enabled: Boolean,
    onStartImport: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Import SMS History", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Import existing SMS/MMS messages into BothBubbles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isImporting) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${(progress * 100).toInt()}% complete",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                lastCount?.let {
                    Text(
                        "Last import: $it conversations",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onStartImport,
                    enabled = enabled && !isImporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import SMS Messages")
                }
            }
        }
    }
}
