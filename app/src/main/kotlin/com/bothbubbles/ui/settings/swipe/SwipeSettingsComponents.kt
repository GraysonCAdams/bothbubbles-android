package com.bothbubbles.ui.settings.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.bothbubbles.util.HapticUtils

@Composable
internal fun SwitchSettingCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = { newValue ->
                    HapticUtils.onConfirm(haptic)
                    onCheckedChange(newValue)
                },
                enabled = enabled
            )
        }
    }
}

@Composable
internal fun SensitivityCard(
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    // Track the last step to detect when we cross to a new step
    var lastStep by remember { mutableFloatStateOf(sensitivity) }

    // Calculate step size: range is 0.15f to 0.5f with 6 steps = 7 positions
    val stepSize = (0.5f - 0.15f) / 7

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Swipe Sensitivity", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "How far you need to swipe to trigger the action",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column {
                Slider(
                    value = sensitivity,
                    onValueChange = { newValue ->
                        // Calculate which step we're on (snap to nearest)
                        val currentStep = ((newValue - 0.15f) / stepSize).toInt()
                        val previousStep = ((lastStep - 0.15f) / stepSize).toInt()

                        // Trigger haptic when crossing to a new step
                        if (currentStep != previousStep) {
                            HapticUtils.onDragTransition(haptic)
                            lastStep = newValue
                        }

                        onSensitivityChange(newValue)
                    },
                    valueRange = 0.15f..0.5f,
                    steps = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Less sensitive",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "More sensitive",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun InfoCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    "Contextual Actions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Some actions adapt to the conversation state:\n" +
                    "\u2022 Pin becomes Unpin for pinned conversations\n" +
                    "\u2022 Mute becomes Unmute for muted conversations\n" +
                    "\u2022 Mark Read becomes Mark Unread for read conversations",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}
