package com.bothbubbles.ui.chat.composer.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.chat.composer.RecordingState
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens

/**
 * Voice memo recording panel.
 *
 * Features:
 * - Real-time waveform visualization from amplitude data
 * - Recording duration display with pulsing indicator
 * - Noise cancellation toggle
 * - Controls: Restart, Stop (prominent), Attach
 * - Preview mode with playback controls
 *
 * This panel is shown when the user holds the microphone button
 * and expands to provide full recording controls.
 *
 * @param visible Whether the panel is visible
 * @param recordingState Current recording state with duration and amplitude data
 * @param isRecording Whether currently recording (vs. preview mode)
 * @param onStop Callback to stop recording
 * @param onRestart Callback to restart recording
 * @param onAttach Callback to attach the recording to the message
 * @param onCancel Callback to cancel and discard the recording
 * @param onNoiseCancellationToggle Callback to toggle noise cancellation
 * @param onPlayPause Callback to toggle preview playback (preview mode only)
 * @param modifier Modifier for the panel
 */
@Composable
fun VoiceMemoPanel(
    visible: Boolean,
    recordingState: RecordingState,
    isRecording: Boolean,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onAttach: () -> Unit,
    onCancel: () -> Unit,
    onNoiseCancellationToggle: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = ComposerMotionTokens.Spring.Responsive.dampingRatio,
                stiffness = ComposerMotionTokens.Spring.Responsive.stiffness
            )
        ) + fadeIn(tween(ComposerMotionTokens.Duration.FAST)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(ComposerMotionTokens.Duration.NORMAL)
        ) + fadeOut(tween(ComposerMotionTokens.Duration.FAST)),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )

                if (isRecording) {
                    RecordingContent(
                        recordingState = recordingState,
                        onStop = onStop,
                        onRestart = onRestart,
                        onAttach = onAttach,
                        onNoiseCancellationToggle = onNoiseCancellationToggle
                    )
                } else {
                    PreviewContent(
                        recordingState = recordingState,
                        onPlayPause = onPlayPause,
                        onReRecord = onRestart,
                        onAttach = onAttach,
                        onCancel = onCancel
                    )
                }
            }
        }
    }
}

/**
 * Recording mode content with waveform and controls.
 */
@Composable
private fun RecordingContent(
    recordingState: RecordingState,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onAttach: () -> Unit,
    onNoiseCancellationToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val formattedDuration = remember(recordingState.durationMs) {
        val seconds = (recordingState.durationMs / 1000) % 60
        val minutes = (recordingState.durationMs / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Timer with pulsing dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Pulsing red recording dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = Color.Red.copy(alpha = pulseAlpha),
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Waveform visualization
        WaveformVisualization(
            amplitudeHistory = recordingState.amplitudeHistory,
            isRecording = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 16.dp)
        )

        // Noise cancellation toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Noise cancellation ${if (recordingState.isNoiseCancellationEnabled) "ON" else "OFF"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = recordingState.isNoiseCancellationEnabled,
                onCheckedChange = { onNoiseCancellationToggle() },
                modifier = Modifier.height(24.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Restart button
            TextButton(onClick = onRestart) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = "Restart",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Restart")
            }

            // Stop button (prominent red circle)
            Surface(
                onClick = onStop,
                modifier = Modifier.size(64.dp),
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
                            .size(24.dp)
                            .background(Color.White, RoundedCornerShape(4.dp))
                    )
                }
            }

            // Attach button
            Surface(
                onClick = onAttach,
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Attach",
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/**
 * Preview mode content with playback controls.
 */
@Composable
private fun PreviewContent(
    recordingState: RecordingState,
    onPlayPause: () -> Unit,
    onReRecord: () -> Unit,
    onAttach: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedDuration = remember(recordingState.durationMs) {
        val seconds = (recordingState.durationMs / 1000) % 60
        val minutes = (recordingState.durationMs / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    val formattedPosition = remember(recordingState.playbackPositionMs) {
        val seconds = (recordingState.playbackPositionMs / 1000) % 60
        val minutes = (recordingState.playbackPositionMs / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    val progress = if (recordingState.durationMs > 0) {
        (recordingState.playbackPositionMs.toFloat() / recordingState.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title
        Text(
            text = "Voice Message",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Playback controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Pause button
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (recordingState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (recordingState.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Time display
            Text(
                text = if (recordingState.isPlaying) formattedPosition else formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp)
            )
        }

        // Waveform visualization (static in preview mode)
        WaveformVisualization(
            amplitudeHistory = recordingState.amplitudeHistory,
            isRecording = false,
            playbackProgress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 16.dp)
        )

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel button
            TextButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Cancel",
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Re-record button
            TextButton(onClick = onReRecord) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Re-record")
            }

            // Send button
            Surface(
                onClick = onAttach,
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Send",
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/**
 * Waveform visualization using amplitude data.
 */
@Composable
private fun WaveformVisualization(
    amplitudeHistory: List<Float>,
    isRecording: Boolean,
    playbackProgress: Float = 0f,
    modifier: Modifier = Modifier
) {
    val barColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    // Animate bar heights for smooth visualization
    Canvas(modifier = modifier) {
        val barWidth = 4.dp.toPx()
        val barSpacing = 3.dp.toPx()
        val totalBarWidth = barWidth + barSpacing
        val maxBars = ((size.width - barSpacing) / totalBarWidth).toInt()

        // Use amplitude history or generate placeholder bars
        val bars = if (amplitudeHistory.isNotEmpty()) {
            // Sample from amplitude history to fit available space
            val step = (amplitudeHistory.size.toFloat() / maxBars).coerceAtLeast(1f)
            (0 until maxBars).map { i ->
                val index = (i * step).toInt().coerceIn(0, amplitudeHistory.lastIndex)
                amplitudeHistory[index]
            }
        } else {
            // Generate placeholder bars
            List(maxBars) { 0.1f }
        }

        val centerY = size.height / 2
        val maxBarHeight = size.height - 8.dp.toPx()

        bars.forEachIndexed { index, amplitude ->
            val barHeight = (8.dp.toPx() + amplitude * maxBarHeight).coerceIn(8.dp.toPx(), maxBarHeight)
            val x = index * totalBarWidth + barSpacing
            val y = centerY - barHeight / 2

            // In preview mode, color bars based on playback progress
            val color = if (!isRecording && playbackProgress > 0) {
                val barProgress = index.toFloat() / maxBars
                if (barProgress <= playbackProgress) barColor else inactiveColor
            } else {
                barColor.copy(alpha = if (isRecording) 0.8f else 0.6f)
            }

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}
