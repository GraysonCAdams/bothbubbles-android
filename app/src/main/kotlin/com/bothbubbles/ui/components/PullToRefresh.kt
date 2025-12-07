package com.bothbubbles.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Pull-to-refresh container with Material 3 styling and haptic feedback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val state = rememberPullToRefreshState()

    // Track refresh trigger for haptic
    var lastRefreshingState by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing && !lastRefreshingState) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        lastRefreshingState = isRefreshing
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onRefresh()
        },
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                color = MaterialTheme.colorScheme.primary
            )
        }
    ) {
        content()
    }
}

/**
 * Connection status indicator for showing sync state
 */
@Composable
fun ConnectionStatusIndicator(
    isConnected: Boolean,
    isSyncing: Boolean,
    syncProgress: Float?,
    modifier: Modifier = Modifier
) {
    if (!isConnected || isSyncing) {
        Surface(
            color = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer,
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (!isConnected) {
                    Text(
                        text = "Connecting...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else if (isSyncing) {
                    if (syncProgress != null) {
                        LinearProgressIndicator(
                            progress = { syncProgress },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                        Text(
                            text = "${(syncProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.width(100.dp),
                            trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Syncing...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * Snackbar host with common message handling
 */
@Composable
fun BothBubblesSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
            shape = MaterialTheme.shapes.medium
        )
    }
}

/**
 * Error banner with dismiss and retry
 */
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )

            if (onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Retry")
                }
            }

            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * Loading overlay with blur effect
 */
@Composable
fun LoadingOverlay(
    isLoading: Boolean,
    message: String? = null,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        Surface(
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
            modifier = modifier.fillMaxSize()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        CircularProgressIndicator()
                        if (message != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Animated visibility wrapper for smooth content transitions
 */
@Composable
fun AnimatedContent(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(300),
        label = "contentAlpha"
    )

    if (alpha > 0f) {
        Box(modifier = modifier) {
            content()
        }
    }
}
