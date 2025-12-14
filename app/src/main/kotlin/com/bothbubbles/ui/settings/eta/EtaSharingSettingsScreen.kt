package com.bothbubbles.ui.settings.eta

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtaSharingSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: EtaSharingSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Refresh notification access state when screen is shown
    LaunchedEffect(Unit) {
        viewModel.refreshNotificationAccess()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ETA Sharing") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        EtaSharingSettingsContent(
            modifier = Modifier.padding(paddingValues),
            uiState = uiState,
            onEnableChanged = viewModel::setEnabled,
            onUpdateIntervalChanged = viewModel::setUpdateInterval,
            onChangeThresholdChanged = viewModel::setChangeThreshold,
            onOpenNotificationSettings = {
                context.startActivity(viewModel.getNotificationAccessSettingsIntent())
            }
        )
    }
}

@Composable
fun EtaSharingSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: EtaSharingSettingsViewModel = hiltViewModel(),
    uiState: EtaSharingSettingsUiState = viewModel.uiState.collectAsStateWithLifecycle().value,
    onEnableChanged: (Boolean) -> Unit = viewModel::setEnabled,
    onUpdateIntervalChanged: (Int) -> Unit = viewModel::setUpdateInterval,
    onChangeThresholdChanged: (Int) -> Unit = viewModel::setChangeThreshold,
    onOpenNotificationSettings: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Info card
        InfoCard()

        // Permission status card
        PermissionStatusCard(
            hasNotificationAccess = uiState.hasNotificationAccess,
            onOpenSettings = onOpenNotificationSettings
        )

        // Battery optimization card (Samsung/Xiaomi devices aggressively kill background services)
        val context = LocalContext.current
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)

        if (!isIgnoringBatteryOptimizations) {
            BatteryOptimizationCard(
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            )
        }

        // Enable toggle
        SettingsSection(title = null) {
            SettingsToggleItem(
                title = "Enable ETA Sharing",
                subtitle = "Share your arrival time while navigating with Google Maps or Waze",
                checked = uiState.enabled,
                onCheckedChange = onEnableChanged,
                enabled = uiState.hasNotificationAccess
            )
        }

        // Settings when enabled
        if (uiState.enabled && uiState.hasNotificationAccess) {
            // Status indicator
            if (uiState.isNavigationActive) {
                StatusCard(
                    isSharing = uiState.isCurrentlySharing,
                    isNavigationActive = true,
                    etaMinutes = uiState.currentEtaMinutes,
                    destination = uiState.destination
                )
            }

            // Update interval
            SettingsSection(title = "Update Frequency") {
                IntervalSlider(
                    label = "Send updates every ${uiState.updateIntervalMinutes} minutes",
                    description = "Minimum time between automatic ETA updates",
                    value = uiState.updateIntervalMinutes,
                    onValueChange = onUpdateIntervalChanged,
                    valueRange = 5f..30f,
                    steps = 4
                )
            }

            // Change threshold
            SettingsSection(title = "Change Sensitivity") {
                IntervalSlider(
                    label = "Update when ETA changes by ${uiState.changeThresholdMinutes}+ minutes",
                    description = "Send updates immediately when arrival time changes significantly",
                    value = uiState.changeThresholdMinutes,
                    onValueChange = onChangeThresholdChanged,
                    valueRange = 2f..15f,
                    steps = 12
                )
            }
        }

        // Developer debug section (only visible in developer mode)
        if (uiState.isDeveloperMode) {
            DebugSection(
                isNavigationActive = uiState.isNavigationActive,
                currentEtaMinutes = uiState.currentEtaMinutes,
                onSimulateNavigation = viewModel::debugSimulateNavigation,
                onUpdateEta = viewModel::debugUpdateEta,
                onStopNavigation = viewModel::debugStopNavigation,
                onResetTerminalState = viewModel::debugResetTerminalState
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun InfoCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.Navigation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Share your arrival time",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "When navigating with Google Maps or Waze, you can share your " +
                        "estimated arrival time with a contact. They'll receive automatic " +
                        "updates as your ETA changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    hasNotificationAccess: Boolean,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (hasNotificationAccess) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = !hasNotificationAccess, onClick = onOpenSettings)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (hasNotificationAccess) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = if (hasNotificationAccess) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasNotificationAccess) {
                        "Notification access granted"
                    } else {
                        "Notification access required"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (hasNotificationAccess) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
                if (!hasNotificationAccess) {
                    Text(
                        text = "Tap to open settings and enable for BothBubbles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
            }
            if (!hasNotificationAccess) {
                TextButton(onClick = onOpenSettings) {
                    Text("Enable")
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard(
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenSettings)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Disable battery optimization",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "Recommended for reliable ETA updates on Samsung/Xiaomi devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text("Fix")
            }
        }
    }
}

@Composable
private fun StatusCard(
    isSharing: Boolean,
    isNavigationActive: Boolean,
    etaMinutes: Int = 0,
    destination: String? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSharing) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Navigation,
                contentDescription = null,
                tint = if (isSharing) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isSharing) {
                        "Currently sharing ETA" + if (etaMinutes > 0) " ($etaMinutes min)" else ""
                    } else {
                        "Navigation detected" + if (etaMinutes > 0) " ($etaMinutes min)" else ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isSharing) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = if (isSharing) {
                        "Sharing your arrival time" + (destination?.let { " to $it" } ?: "")
                    } else {
                        "Open a chat to share your ETA"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSharing) {
                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String?,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        content()
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun IntervalSlider(
    label: String,
    description: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange,
            steps = steps
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Debug section for testing ETA sharing without actually driving
 */
@Composable
private fun DebugSection(
    isNavigationActive: Boolean,
    currentEtaMinutes: Int,
    onSimulateNavigation: (Int, String) -> Unit,
    onUpdateEta: (Int) -> Unit,
    onStopNavigation: () -> Unit,
    onResetTerminalState: () -> Unit
) {
    var simulatedEta by remember { mutableIntStateOf(15) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        HorizontalDivider()

        SettingsSection(title = "Developer Testing") {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Debug Controls",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Simulate navigation without driving",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // ETA slider
                    Text(
                        text = "Simulated ETA: $simulatedEta min",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = simulatedEta.toFloat(),
                        onValueChange = { simulatedEta = it.roundToInt() },
                        valueRange = 0f..60f,
                        steps = 11
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isNavigationActive) {
                            Button(
                                onClick = { onSimulateNavigation(simulatedEta, "Test Location") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Start Nav")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onUpdateEta(simulatedEta) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Update ETA")
                            }
                            OutlinedButton(
                                onClick = onStopNavigation,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Stop Nav")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = onResetTerminalState,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset Terminal State")
                    }

                    if (isNavigationActive) {
                        Text(
                            text = "Current ETA: $currentEtaMinutes min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
