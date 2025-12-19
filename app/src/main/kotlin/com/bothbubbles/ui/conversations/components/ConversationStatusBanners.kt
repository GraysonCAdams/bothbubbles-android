package com.bothbubbles.ui.conversations.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bothbubbles.ui.conversations.UnifiedSyncProgressBar
import com.bothbubbles.services.sync.UnifiedSyncProgress

/**
 * Sync progress bar that sits below the main content.
 *
 * Uses stacked layout - the main content area shrinks to accommodate this bar,
 * rather than this bar floating on top of content.
 */
@Composable
fun ConversationStatusBanners(
    unifiedSyncProgress: UnifiedSyncProgress?,
    isSearchActive: Boolean,
    onExpandToggle: () -> Unit,
    onRetrySync: () -> Unit,
    onDismissSyncError: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animate the entire container's size changes (for expand/collapse)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(300))
    ) {
        AnimatedVisibility(
            visible = unifiedSyncProgress != null && !isSearchActive,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            unifiedSyncProgress?.let { progress ->
                UnifiedSyncProgressBar(
                    progress = progress,
                    onExpandToggle = onExpandToggle,
                    onRetry = onRetrySync,
                    onDismiss = onDismissSyncError,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
