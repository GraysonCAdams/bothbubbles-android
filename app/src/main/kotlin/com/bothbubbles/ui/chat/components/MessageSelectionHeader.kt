package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Header displayed when in message selection mode.
 * Shows selection count and actions: Copy, Share, Forward, Delete.
 *
 * Layout: [X Close] [count "selected"]  ---spacer---  [Copy] [Share] [Forward] [Delete] [More]
 */
@Composable
internal fun MessageSelectionHeader(
    selectedCount: Int,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Close button
        IconButton(onClick = onClose) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Cancel selection",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // Selection count with "selected" text
        Text(
            text = "$selectedCount selected",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Copy button
        IconButton(
            onClick = onCopy,
            enabled = selectedCount > 0
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy",
                tint = if (selectedCount > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }

        // Share button (System share sheet)
        IconButton(
            onClick = onShare,
            enabled = selectedCount > 0
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = "Share",
                tint = if (selectedCount > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }

        // Forward button
        IconButton(
            onClick = onForward,
            enabled = selectedCount > 0
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Forward,
                contentDescription = "Forward",
                tint = if (selectedCount > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }

        // Delete button
        IconButton(
            onClick = onDelete,
            enabled = selectedCount > 0
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = if (selectedCount > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }

        // More options
        Box {
            IconButton(onClick = { showMoreMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Select all") },
                    onClick = {
                        showMoreMenu = false
                        onSelectAll()
                    }
                )
            }
        }
    }
}
