package com.bothbubbles.ui.conversations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
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
import com.bothbubbles.ui.conversations.delegates.SelectionState

/**
 * Header shown when in selection mode.
 * Supports Gmail-style "Select All" with total count display.
 */
@Composable
internal fun SelectionModeHeader(
    selectedCount: Int,
    totalSelectableCount: Int,
    majorityUnread: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPin: () -> Unit,
    onSnooze: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onBlock: () -> Unit,
    onAddContact: (() -> Unit)? = null,
    isPinEnabled: Boolean = true,
    isLoadingCount: Boolean = false,
    isSelectAllMode: Boolean = false,
    totalMatchingCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val allSelected = isSelectAllMode || (selectedCount >= totalSelectableCount && totalSelectableCount > 0)
    var showMoreMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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

        // Selection count with loading indicator
        if (isLoadingCount) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = if (isSelectAllMode && totalMatchingCount > 0) {
                    // Show "X of Y" format when in select-all mode
                    "$selectedCount of $totalMatchingCount"
                } else {
                    selectedCount.toString()
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Pin button
        IconButton(
            onClick = onPin,
            enabled = isPinEnabled
        ) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = "Pin",
                tint = if (isPinEnabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }

        // Delete button
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // More options with dropdown (all other actions)
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
                // Select all / Deselect all option
                DropdownMenuItem(
                    text = { Text(if (allSelected) "Deselect all" else "Select all") },
                    onClick = {
                        showMoreMenu = false
                        onSelectAll()
                    }
                )

                DropdownMenuItem(
                    text = { Text("Archive") },
                    onClick = {
                        showMoreMenu = false
                        onArchive()
                    }
                )

                DropdownMenuItem(
                    text = { Text("Snooze") },
                    onClick = {
                        showMoreMenu = false
                        onSnooze()
                    }
                )

                DropdownMenuItem(
                    text = { Text("Mark as read") },
                    onClick = {
                        showMoreMenu = false
                        onMarkAsRead()
                    }
                )

                DropdownMenuItem(
                    text = { Text("Mark as unread") },
                    onClick = {
                        showMoreMenu = false
                        onMarkAsUnread()
                    }
                )

                // Show "Add contact" only for single selection without existing contact
                if (onAddContact != null) {
                    DropdownMenuItem(
                        text = { Text("Add contact") },
                        onClick = {
                            showMoreMenu = false
                            onAddContact()
                        }
                    )
                }

                DropdownMenuItem(
                    text = { Text("Block") },
                    onClick = {
                        showMoreMenu = false
                        onBlock()
                    }
                )
            }
        }
    }
}
