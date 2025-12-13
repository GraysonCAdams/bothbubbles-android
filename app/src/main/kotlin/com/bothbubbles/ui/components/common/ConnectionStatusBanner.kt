package com.bothbubbles.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bothbubbles.services.socket.ConnectionState

/**
 * Connection status banner types for iMessage
 */
sealed class ConnectionBannerState {
    /** Server not configured - show setup CTA (dismissible) */
    object NotConfigured : ConnectionBannerState()

    /** Actively connecting/reconnecting (not dismissible) */
    data class Reconnecting(val retryAttempt: Int = 0) : ConnectionBannerState()

    /** Connected successfully - hide banner */
    object Connected : ConnectionBannerState()

    /** Banner dismissed by user (for NotConfigured state only) */
    object Dismissed : ConnectionBannerState()
}

/**
 * SMS status banner types
 */
sealed class SmsBannerState {
    /** SMS is enabled but app is not the default SMS app (dismissible) */
    object NotDefaultApp : SmsBannerState()

    /** SMS is working normally - hide banner */
    object Connected : SmsBannerState()

    /** Banner dismissed by user */
    object Dismissed : SmsBannerState()

    /** SMS is not enabled - hide banner */
    object Disabled : SmsBannerState()
}

/**
 * Bottom banner that displays connection status with appropriate actions.
 *
 * Behavior:
 * - When server is not configured: Shows dismissible banner with "Set up iMessage" CTA
 * - When reconnecting: Shows non-dismissible banner with loading indicator
 * - When connected: Banner slides out
 * - Dismissed state persists until connection is established and then lost
 *
 * @param state The current banner state
 * @param onSetupClick Called when user taps setup CTA
 * @param onDismiss Called when user dismisses the banner (only for NotConfigured state)
 * @param onRetryClick Called when user taps retry button
 * @param modifier Modifier for the banner
 */
@Composable
fun ConnectionStatusBanner(
    state: ConnectionBannerState,
    onSetupClick: () -> Unit,
    onDismiss: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = when (state) {
        is ConnectionBannerState.NotConfigured -> true
        is ConnectionBannerState.Reconnecting -> true
        is ConnectionBannerState.Connected -> false
        is ConnectionBannerState.Dismissed -> false
    }

    // Animated color transition for state changes (snappy Android 16 style)
    val bannerColor by animateColorAsState(
        targetValue = when (state) {
            is ConnectionBannerState.Reconnecting -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "bannerColor"
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(durationMillis = 200)  // Faster for snappy feel
        ),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(durationMillis = 150)
        ),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = bannerColor,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp
        ) {
            when (state) {
                is ConnectionBannerState.NotConfigured -> NotConfiguredBanner(
                    onSetupClick = onSetupClick,
                    onDismiss = onDismiss
                )
                is ConnectionBannerState.Reconnecting -> ReconnectingBanner(
                    retryAttempt = state.retryAttempt,
                    onRetryClick = onRetryClick
                )
                else -> { /* Hidden states */ }
            }
        }
    }
}

@Composable
private fun NotConfiguredBanner(
    onSetupClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = "iMessage not connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onSetupClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Set up",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ReconnectingBanner(
    retryAttempt: Int,
    onRetryClick: () -> Unit
) {
    // Rotating animation for the refresh icon when manually retrying
    val infiniteTransition = rememberInfiniteTransition(label = "rotate")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Small loading indicator
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = if (retryAttempt > 1) {
                    "iMessage reconnecting... (attempt $retryAttempt)"
                } else {
                    "iMessage reconnecting..."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Medium
            )
        }

        // Retry button for manual retry
        IconButton(
            onClick = onRetryClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Retry now",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(rotation)
            )
        }
    }
}

/**
 * Helper function to determine the banner state from connection info
 */
fun determineConnectionBannerState(
    connectionState: ConnectionState,
    retryAttempt: Int,
    isSetupBannerDismissed: Boolean,
    wasEverConnected: Boolean
): ConnectionBannerState {
    return when (connectionState) {
        ConnectionState.CONNECTED -> ConnectionBannerState.Connected

        ConnectionState.NOT_CONFIGURED -> {
            // If user dismissed the setup banner and we haven't connected since, stay dismissed
            if (isSetupBannerDismissed && !wasEverConnected) {
                ConnectionBannerState.Dismissed
            } else {
                ConnectionBannerState.NotConfigured
            }
        }

        ConnectionState.CONNECTING,
        ConnectionState.ERROR,
        ConnectionState.DISCONNECTED -> {
            // Show reconnecting banner if we were ever connected (meaning server was configured)
            if (wasEverConnected) {
                ConnectionBannerState.Reconnecting(retryAttempt)
            } else {
                // Not configured yet
                if (isSetupBannerDismissed) {
                    ConnectionBannerState.Dismissed
                } else {
                    ConnectionBannerState.NotConfigured
                }
            }
        }
    }
}

/**
 * Helper function to determine the SMS banner state
 */
fun determineSmsBannerState(
    smsEnabled: Boolean,
    isFullyFunctional: Boolean,
    isSmsBannerDismissed: Boolean
): SmsBannerState {
    return when {
        !smsEnabled -> SmsBannerState.Disabled
        isFullyFunctional -> SmsBannerState.Connected
        isSmsBannerDismissed -> SmsBannerState.Dismissed
        else -> SmsBannerState.NotDefaultApp
    }
}

/**
 * Bottom banner that displays SMS status with appropriate actions.
 *
 * Behavior:
 * - When SMS is enabled but app is not default: Shows dismissible banner with "Set as default" CTA
 * - When SMS is disabled or app is default: Banner is hidden
 * - Dismissed state persists until app becomes/stops being the default SMS app
 *
 * @param state The current SMS banner state
 * @param onSetAsDefaultClick Called when user taps "Set as default" CTA
 * @param onDismiss Called when user dismisses the banner
 * @param modifier Modifier for the banner
 */
@Composable
fun SmsStatusBanner(
    state: SmsBannerState,
    onSetAsDefaultClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = state is SmsBannerState.NotDefaultApp

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(durationMillis = 300)
        ),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(durationMillis = 300)
        ),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp
        ) {
            SmsNotDefaultBanner(
                onSetAsDefaultClick = onSetAsDefaultClick,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun SmsNotDefaultBanner(
    onSetAsDefaultClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.SignalCellularOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = "SMS not connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onSetAsDefaultClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Set up",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
