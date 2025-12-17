package com.bothbubbles.ui.conversations.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bothbubbles.ui.components.common.ConnectionBannerState
import com.bothbubbles.ui.components.common.ConnectionStatusBanner
import com.bothbubbles.ui.components.common.SmsBannerState
import com.bothbubbles.ui.components.common.SmsStatusBanner
import com.bothbubbles.ui.conversations.UnifiedSyncProgressBar
import com.bothbubbles.services.sync.UnifiedSyncProgress

/**
 * Stacked status banners at bottom of screen.
 *
 * Displays (from bottom to top):
 * - Connection status banner
 * - SMS status banner
 * - Unified sync progress bar
 */
@Composable
fun ConversationStatusBanners(
    unifiedSyncProgress: UnifiedSyncProgress?,
    smsBannerState: SmsBannerState,
    connectionBannerState: ConnectionBannerState,
    isSearchActive: Boolean,
    onExpandToggle: () -> Unit,
    onRetrySync: () -> Unit,
    onDismissSyncError: () -> Unit,
    onSetAsDefaultClick: () -> Unit,
    onDismissSmsBanner: () -> Unit,
    onSetupClick: () -> Unit,
    onDismissSetupBanner: () -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Bottom
    ) {
        // Unified sync progress bar (combines iMessage sync, SMS import, and categorization)
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

        // SMS status banner (on top when both visible)
        SmsStatusBanner(
            state = smsBannerState,
            onSetAsDefaultClick = onSetAsDefaultClick,
            onDismiss = onDismissSmsBanner
        )

        // Connection status banner (at the bottom)
        ConnectionStatusBanner(
            state = connectionBannerState,
            onSetupClick = onSetupClick,
            onDismiss = onDismissSetupBanner,
            onRetryClick = onRetryConnection
        )
    }
}
