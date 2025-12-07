package com.bothbubbles.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bothbubbles.services.socket.ConnectionState

// Google Messages-style status colors
private val ConnectedGreen = Color(0xFF34A853)
private val DisconnectedRed = Color(0xFFEA4335)
private val ConnectingOrange = Color(0xFFFBBC04)

/**
 * Profile header section matching Google Messages design.
 * Shows server URL and connection status. Tapping navigates to server settings.
 */
@Composable
fun ProfileHeader(
    serverUrl: String,
    connectionState: ConnectionState,
    onManageServerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onManageServerClick)
            .padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar area with BlueBubbles icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = "Manage Server",
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Server URL
        Text(
            text = serverUrl.ifEmpty { "Not configured" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Connection status with dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val statusColor = when (connectionState) {
                ConnectionState.CONNECTED -> ConnectedGreen
                ConnectionState.CONNECTING -> ConnectingOrange
                ConnectionState.DISCONNECTED, ConnectionState.ERROR, ConnectionState.NOT_CONFIGURED -> DisconnectedRed
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "iMessage",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor
            )
        }
    }
}

/**
 * Card container for settings items matching Google Messages design.
 * Uses 28dp corner radius to match Google's design language.
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(content = content)
    }
}

/**
 * Individual settings menu item matching Google Messages design.
 * Features a simple icon, title, subtitle, and optional trailing content.
 */
@Composable
fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = trailingContent,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

/**
 * Section title for settings groups
 */
@Composable
fun SettingsSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
