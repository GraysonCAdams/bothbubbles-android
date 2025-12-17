package com.bothbubbles.ui.conversations.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.bothbubbles.ui.conversations.UnifiedSyncProgress

/**
 * Floating Action Button for starting a new chat.
 *
 * Automatically adjusts padding to stay above sync progress bar.
 * Expands/collapses text label based on scroll position.
 */
@Composable
fun ConversationFab(
    onClick: () -> Unit,
    isExpanded: Boolean,
    unifiedSyncProgress: UnifiedSyncProgress?,
    isSearchActive: Boolean,
    modifier: Modifier = Modifier
) {
    // Calculate bottom padding based on unified progress bar visibility
    val showProgressBar = unifiedSyncProgress != null && !isSearchActive
    val isProgressExpanded = unifiedSyncProgress?.isExpanded == true
    val stageCount = unifiedSyncProgress?.stages?.size ?: 0
    val progressBarHeight = if (isProgressExpanded && stageCount > 1) {
        80.dp + (32.dp * stageCount) // Base + per-stage rows
    } else {
        80.dp
    }
    val fabBottomPadding by animateDpAsState(
        targetValue = if (showProgressBar) progressBarHeight else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "fabPadding"
    )

    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = isExpanded,
        modifier = modifier.padding(bottom = fabBottomPadding),
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
