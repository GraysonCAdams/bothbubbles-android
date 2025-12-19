package com.bothbubbles.ui.conversations

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bothbubbles.services.sync.StageProgress
import com.bothbubbles.services.sync.StageStatus
import com.bothbubbles.services.sync.SyncStageType
import com.bothbubbles.services.sync.UnifiedSyncProgress

/**
 * Unified sync progress bar that consolidates multiple sync operations into one component.
 * Supports collapsed/expanded states and inline error handling.
 *
 * @param progress The unified sync progress state
 * @param onExpandToggle Called when user taps to expand/collapse details
 * @param onRetry Called when user taps retry on a failed stage
 * @param onDismiss Called when user dismisses an error
 * @param modifier Modifier for the component
 */
@Composable
internal fun UnifiedSyncProgressBar(
    progress: UnifiedSyncProgress,
    onExpandToggle: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Bottom padding for gesture bar - content padding only, Surface extends to edge
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Animate progress smoothly
    val animatedProgress by animateFloatAsState(
        targetValue = progress.overallProgress,
        animationSpec = tween(durationMillis = 300),
        label = "progress"
    )

    Surface(
        modifier = modifier,
        color = if (progress.hasError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = 200))
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + navBarPadding)
        ) {
            AnimatedContent(
                targetState = progress.hasError,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "error_state"
            ) { hasError ->
                if (hasError) {
                    // Error state
                    ErrorContent(
                        progress = progress,
                        onRetry = onRetry,
                        onDismiss = onDismiss
                    )
                } else {
                    // Normal progress state
                    ProgressContent(
                        progress = progress,
                        animatedProgress = animatedProgress,
                        onExpandToggle = onExpandToggle
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressContent(
    progress: UnifiedSyncProgress,
    animatedProgress: Float,
    onExpandToggle: () -> Unit
) {
    // Rotation animation for active sync icon
    val infiniteTransition = rememberInfiniteTransition(label = "syncRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpandToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Always use sync icon for main progress bar - rotates when active
                val isActive = progress.activeStage?.status == StageStatus.IN_PROGRESS
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .then(if (isActive) Modifier.rotate(rotation) else Modifier),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = progress.currentStage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Show detail text if available and not expanded
                    if (!progress.isExpanded && progress.activeStage?.detail != null) {
                        Text(
                            text = progress.activeStage!!.detail!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Percentage and expand button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (progress.stages.size > 1) {
                    Icon(
                        imageVector = if (progress.isExpanded) {
                            Icons.Default.ExpandLess
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = if (progress.isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Progress bar
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        // Expanded stage list
        if (progress.isExpanded && progress.stages.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                progress.stages.forEach { stage ->
                    StageRow(stage = stage)
                }
            }
        }
    }
}

@Composable
private fun StageRow(stage: StageProgress) {
    // Rotation animation for in-progress stages
    val infiniteTransition = rememberInfiniteTransition(label = "stageRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "stageRowRotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Status icon - rotates when in progress
            val isActive = stage.status == StageStatus.IN_PROGRESS
            Icon(
                imageVector = stage.status.toIcon(),
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .then(if (isActive) Modifier.rotate(rotation) else Modifier),
                tint = stage.status.toColor()
            )
            Text(
                text = stage.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Status text
        Text(
            text = when (stage.status) {
                StageStatus.COMPLETE -> "Complete"
                StageStatus.IN_PROGRESS -> stage.detail ?: "${(stage.progress * 100).toInt()}%"
                StageStatus.WAITING -> "Waiting..."
                StageStatus.ERROR -> "Failed"
                StageStatus.SKIPPED -> "Skipped"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (stage.status == StageStatus.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun ErrorContent(
    progress: UnifiedSyncProgress,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = progress.activeStage?.let { "${it.name} failed" } ?: "Sync failed",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            progress.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (progress.canRetry) {
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// Extension functions for UI mapping

@Composable
private fun SyncStageType.toIcon(): ImageVector = when (this) {
    SyncStageType.IMESSAGE -> Icons.Default.Sync
    SyncStageType.SMS_IMPORT -> Icons.Default.Sms
    SyncStageType.CATEGORIZATION -> Icons.AutoMirrored.Filled.Sort
}

@Composable
private fun StageStatus.toIcon(): ImageVector = when (this) {
    StageStatus.COMPLETE -> Icons.Default.Check
    StageStatus.IN_PROGRESS -> Icons.Default.Sync
    StageStatus.WAITING -> Icons.Default.Schedule
    StageStatus.ERROR -> Icons.Default.Error
    StageStatus.SKIPPED -> Icons.Default.Check
}

@Composable
private fun StageStatus.toColor() = when (this) {
    StageStatus.COMPLETE -> MaterialTheme.colorScheme.primary
    StageStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
    StageStatus.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
    StageStatus.ERROR -> MaterialTheme.colorScheme.error
    StageStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}
