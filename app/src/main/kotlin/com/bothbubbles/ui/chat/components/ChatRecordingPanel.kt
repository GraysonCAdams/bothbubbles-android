package com.bothbubbles.ui.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.R
import com.bothbubbles.ui.theme.BubbleColors

/**
 * Expanded recording panel for voice memo recording.
 *
 * Displays:
 * - Recording duration with pulsing red indicator
 * - Waveform visualization from amplitude history
 * - Noise cancellation toggle
 * - Control buttons: Cancel, Restart, Stop, Done
 *
 * @param duration Recording duration in milliseconds
 * @param amplitudeHistory List of amplitude values for waveform visualization
 * @param isNoiseCancellationEnabled Whether noise cancellation is enabled
 * @param onNoiseCancellationToggle Callback to toggle noise cancellation
 * @param onStop Callback when recording is stopped (enters preview mode)
 * @param onRestart Callback to restart recording from beginning
 * @param onAttach Callback when recording should be attached to message
 * @param onCancel Callback to cancel and discard recording
 * @param inputColors Theme colors for styling
 */
@Composable
fun ChatRecordingPanel(
    duration: Long,
    amplitudeHistory: List<Float>,
    isNoiseCancellationEnabled: Boolean,
    onNoiseCancellationToggle: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onAttach: () -> Unit,
    onCancel: () -> Unit,
    inputColors: BubbleColors,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val formattedDuration = remember(duration) {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        shape = RoundedCornerShape(24.dp),
        color = inputColors.inputFieldBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Timer row with pulsing dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Pulsing red recording dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = Color.Red.copy(alpha = pulseAlpha),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.headlineMedium,
                    color = inputColors.inputText
                )
            }

            // Waveform visualization
            RecordingWaveform(
                amplitudeHistory = amplitudeHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            )

            // Noise cancellation toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = inputColors.inputText.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.noise_cancellation) + " " +
                            if (isNoiseCancellationEnabled) "ON" else "OFF",
                    style = MaterialTheme.typography.bodySmall,
                    color = inputColors.inputText.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val haptic = LocalHapticFeedback.current
                Switch(
                    checked = isNoiseCancellationEnabled,
                    onCheckedChange = {
                        HapticUtils.onConfirm(haptic)
                        onNoiseCancellationToggle()
                    },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            // Bottom controls row: Cancel, Restart, Stop, Attach
            RecordingControls(
                onCancel = onCancel,
                onRestart = onRestart,
                onStop = onStop,
                onAttach = onAttach,
                inputColors = inputColors
            )
        }
    }
}

/**
 * Waveform visualization showing amplitude history as animated bars.
 */
@Composable
private fun RecordingWaveform(
    amplitudeHistory: List<Float>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        amplitudeHistory.forEachIndexed { index, amplitude ->
            val targetHeight = (8f + amplitude * 48f).coerceIn(8f, 56f)
            val animatedHeight by animateFloatAsState(
                targetValue = targetHeight,
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = 400f
                ),
                label = "bar_$index"
            )

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(animatedHeight.dp)
                    .background(
                        color = Color.Red.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

/**
 * Control buttons for recording: Cancel, Restart, Stop, Attach/Done.
 */
@Composable
private fun RecordingControls(
    onCancel: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    inputColors: BubbleColors,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cancel button (icon-only)
        Surface(
            onClick = onCancel,
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Restart button (icon-only)
        Surface(
            onClick = onRestart,
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = stringResource(R.string.restart_recording),
                    tint = inputColors.inputIcon,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Red stop button (prominent)
        Surface(
            onClick = onStop,
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = Color.Red
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Stop square icon
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                )
            }
        }

        // Attach/Done button (pill shape with checkmark)
        Surface(
            onClick = onAttach,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primary
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Done",
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
