package com.bothbubbles.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.services.developer.ConnectionMode

/**
 * A small, non-intrusive overlay that shows the current connection mode.
 * Tapping it navigates to the event log viewer.
 */
@Composable
fun DeveloperConnectionOverlay(
    isVisible: Boolean,
    connectionMode: ConnectionMode,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onTap),
                color = getBackgroundColor(connectionMode).copy(alpha = 0.9f),
                tonalElevation = 4.dp,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = getIcon(connectionMode),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = getContentColor(connectionMode)
                    )
                    Text(
                        text = getLabel(connectionMode),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = getContentColor(connectionMode)
                    )
                }
            }
        }
    }
}

@Composable
private fun getBackgroundColor(mode: ConnectionMode): Color {
    return when (mode) {
        ConnectionMode.SOCKET -> MaterialTheme.colorScheme.primaryContainer
        ConnectionMode.FCM -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionMode.DISCONNECTED -> MaterialTheme.colorScheme.errorContainer
    }
}

@Composable
private fun getContentColor(mode: ConnectionMode): Color {
    return when (mode) {
        ConnectionMode.SOCKET -> MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionMode.FCM -> MaterialTheme.colorScheme.onTertiaryContainer
        ConnectionMode.DISCONNECTED -> MaterialTheme.colorScheme.onErrorContainer
    }
}

private fun getIcon(mode: ConnectionMode) = when (mode) {
    ConnectionMode.SOCKET -> Icons.Default.Wifi
    ConnectionMode.FCM -> Icons.Default.CloudQueue
    ConnectionMode.DISCONNECTED -> Icons.Default.WifiOff
}

private fun getLabel(mode: ConnectionMode) = when (mode) {
    ConnectionMode.SOCKET -> "Socket"
    ConnectionMode.FCM -> "FCM"
    ConnectionMode.DISCONNECTED -> "Offline"
}
