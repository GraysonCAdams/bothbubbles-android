package com.bothbubbles.ui.settings.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.services.developer.ConnectionMode
import com.bothbubbles.services.developer.DeveloperEvent
import com.bothbubbles.services.developer.EventSource
import com.bothbubbles.services.fcm.FcmTokenState

@Composable
internal fun ConnectionStatusHeader(
    connectionMode: ConnectionMode,
    isAppInForeground: Boolean
) {
    Surface(
        color = when (connectionMode) {
            ConnectionMode.SOCKET -> MaterialTheme.colorScheme.primaryContainer
            ConnectionMode.FCM -> MaterialTheme.colorScheme.tertiaryContainer
            ConnectionMode.DISCONNECTED -> MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when (connectionMode) {
                    ConnectionMode.SOCKET -> Icons.Default.Wifi
                    ConnectionMode.FCM -> Icons.Default.CloudQueue
                    ConnectionMode.DISCONNECTED -> Icons.Default.WifiOff
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (connectionMode) {
                        ConnectionMode.SOCKET -> "Socket Connected"
                        ConnectionMode.FCM -> "FCM Mode"
                        ConnectionMode.DISCONNECTED -> "Disconnected"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when (connectionMode) {
                        ConnectionMode.SOCKET -> "Real-time events via Socket.IO"
                        ConnectionMode.FCM -> "Background push via Firebase"
                        ConnectionMode.DISCONNECTED -> "Not connected to server"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Surface(
                color = if (isAppInForeground)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (isAppInForeground) "FG" else "BG",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAppInForeground)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}

@Composable
internal fun FcmStatusSection(
    fcmTokenState: FcmTokenState,
    fcmToken: String?
) {
    val (statusText, statusColor, icon) = when (fcmTokenState) {
        is FcmTokenState.Registered -> Triple(
            "Registered",
            MaterialTheme.colorScheme.primary,
            Icons.Default.CheckCircle
        )
        is FcmTokenState.Available -> Triple(
            "Token Available (not registered)",
            MaterialTheme.colorScheme.tertiary,
            Icons.Default.Pending
        )
        is FcmTokenState.Loading -> Triple(
            "Loading...",
            MaterialTheme.colorScheme.outline,
            Icons.Default.Refresh
        )
        is FcmTokenState.NotConfigured -> Triple(
            "Not Configured",
            MaterialTheme.colorScheme.error,
            Icons.Default.Warning
        )
        is FcmTokenState.Disabled -> Triple(
            "Disabled",
            MaterialTheme.colorScheme.outline,
            Icons.Default.Block
        )
        is FcmTokenState.Error -> Triple(
            "Error: ${fcmTokenState.message}",
            MaterialTheme.colorScheme.error,
            Icons.Default.Error
        )
        FcmTokenState.Unknown -> Triple(
            "Unknown",
            MaterialTheme.colorScheme.outline,
            Icons.Default.HelpOutline
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "FCM Status:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor
            )
        }

        if (fcmToken != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Token:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Text(
                    text = fcmToken,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small
                        )
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
internal fun EventLogItem(
    event: DeveloperEvent,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Source indicator
        Surface(
            color = when (event.source) {
                EventSource.SOCKET -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                EventSource.FCM -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
            },
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = when (event.source) {
                    EventSource.SOCKET -> "WS"
                    EventSource.FCM -> "FCM"
                },
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = when (event.source) {
                    EventSource.SOCKET -> MaterialTheme.colorScheme.primary
                    EventSource.FCM -> MaterialTheme.colorScheme.tertiary
                }
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.eventType,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
            if (event.details != null) {
                Text(
                    text = event.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Text(
            text = event.formattedTime,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun DeveloperModeToggle(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Developer Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Disable to hide this overlay on next restart",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val haptic = LocalHapticFeedback.current
            Switch(
                checked = isEnabled,
                onCheckedChange = {
                    HapticUtils.onConfirm(haptic)
                    onToggle()
                }
            )
        }
    }
}
