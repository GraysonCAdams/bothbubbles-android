package com.bothbubbles.ui.chat.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DangerZoneSection(
    isArchived: Boolean,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
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
                icon = if (isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                label = if (isArchived) "Unarchive" else "Archive",
                onClick = onArchiveClick
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            OptionRow(
                icon = Icons.Outlined.Delete,
                label = "Delete conversation",
                onClick = onDeleteClick,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
