package com.bothbubbles.ui.conversations.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Floating Action Button for starting a new chat.
 *
 * Expands/collapses text label based on scroll position.
 * No padding calculations needed - sync progress bar uses stacked layout.
 */
@Composable
fun ConversationFab(
    onClick: () -> Unit,
    isExpanded: Boolean,
    modifier: Modifier = Modifier
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = isExpanded,
        modifier = modifier,
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.Message,
                contentDescription = "Start chat"
            )
        },
        text = {
            Text(
                text = "Start chat",
                style = MaterialTheme.typography.labelLarge
            )
        },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
}
