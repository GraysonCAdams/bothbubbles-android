package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.ui.theme.BubbleColors

/**
 * Preview panel for reviewing a recorded voice memo before sending.
 *
 * Displays:
 * - Play/pause button
 * - Progress bar showing playback position
 * - Duration or current position
 * - Cancel and re-record buttons
 *
 * @param duration Total duration of recording in milliseconds
 * @param playbackPosition Current playback position in milliseconds
 * @param isPlaying Whether audio is currently playing
 * @param onPlayPause Callback to toggle playback
 * @param onReRecord Callback to discard and start new recording
 * @param onCancel Callback to cancel and discard recording
 * @param inputColors Theme colors for styling
 */
@Composable
fun ChatPreviewPanel(
    duration: Long,
    playbackPosition: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onReRecord: () -> Unit,
    onCancel: () -> Unit,
    inputColors: BubbleColors,
    modifier: Modifier = Modifier
) {
    val formattedDuration = remember(duration) {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    val formattedPosition = remember(playbackPosition) {
        val seconds = (playbackPosition / 1000) % 60
        val minutes = (playbackPosition / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    val progress = if (duration > 0) (playbackPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        color = inputColors.inputFieldBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Pause button
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying)
                        stringResource(R.string.action_pause)
                    else
                        stringResource(R.string.action_play),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
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
                text = if (isPlaying) formattedPosition else formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = inputColors.inputText,
                modifier = Modifier.width(36.dp)
            )

            // Cancel button
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Re-record button
            IconButton(
                onClick = onReRecord,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = stringResource(R.string.restart_recording),
                    tint = inputColors.inputIcon,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
