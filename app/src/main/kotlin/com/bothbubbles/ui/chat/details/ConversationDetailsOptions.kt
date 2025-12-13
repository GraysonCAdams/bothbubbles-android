package com.bothbubbles.ui.chat.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.common.SnoozeDuration

@Composable
fun ChatOptionsSection(
    isPinned: Boolean,
    isMuted: Boolean,
    isSnoozed: Boolean,
    snoozeUntil: Long?,
    onSnoozeClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onBlockReportClick: () -> Unit
) {
    val snoozeLabel = if (isSnoozed && snoozeUntil != null) {
        "Snoozed ${SnoozeDuration.formatRemainingTime(snoozeUntil)}"
    } else {
        "Snooze chat"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            OptionRow(
                icon = if (isSnoozed) Icons.Filled.Snooze else Icons.Outlined.Snooze,
                label = snoozeLabel,
                onClick = onSnoozeClick,
                tint = if (isSnoozed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            OptionRow(
                icon = if (isMuted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                label = if (isMuted) "Unmute notifications" else "Notifications",
                onClick = onNotificationsClick
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            OptionRow(
                icon = Icons.Outlined.Block,
                label = "Block & report spam",
                onClick = onBlockReportClick,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun OptionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface
        )
    }
}
