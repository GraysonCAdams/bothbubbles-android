package com.bothbubbles.ui.components.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Group avatar showing multiple participants
 */
@Composable
fun GroupAvatar(
    names: List<String>,
    modifier: Modifier = Modifier,
    avatarPaths: List<String?> = emptyList(),
    size: Dp = 56.dp
) {
    val displayCount = minOf(names.size, 4)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        when (displayCount) {
            0, 1 -> {
                // Single or no participants, show group icon
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(size)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Group,
                            contentDescription = "Group",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(size * 0.5f)
                        )
                    }
                }
            }
            2 -> {
                // Two participants, offset circles
                val smallSize = size * 0.65f
                Box(modifier = Modifier.size(size)) {
                    Avatar(
                        name = names.getOrElse(0) { "?" },
                        avatarPath = avatarPaths.getOrNull(0),
                        size = smallSize,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    ) {
                        Avatar(
                            name = names.getOrElse(1) { "?" },
                            avatarPath = avatarPaths.getOrNull(1),
                            size = smallSize
                        )
                    }
                }
            }
            3 -> {
                // Three participants in triangle
                val smallSize = size * 0.5f
                Box(modifier = Modifier.size(size)) {
                    Avatar(
                        name = names.getOrElse(0) { "?" },
                        avatarPath = avatarPaths.getOrNull(0),
                        size = smallSize,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                    Avatar(
                        name = names.getOrElse(1) { "?" },
                        avatarPath = avatarPaths.getOrNull(1),
                        size = smallSize,
                        modifier = Modifier.align(Alignment.BottomStart)
                    )
                    Avatar(
                        name = names.getOrElse(2) { "?" },
                        avatarPath = avatarPaths.getOrNull(2),
                        size = smallSize,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }
            else -> {
                // Four participants in grid
                val smallSize = size * 0.48f
                Column(
                    modifier = Modifier.size(size),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Avatar(
                            name = names.getOrElse(0) { "?" },
                            avatarPath = avatarPaths.getOrNull(0),
                            size = smallSize
                        )
                        Avatar(
                            name = names.getOrElse(1) { "?" },
                            avatarPath = avatarPaths.getOrNull(1),
                            size = smallSize
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Avatar(
                            name = names.getOrElse(2) { "?" },
                            avatarPath = avatarPaths.getOrNull(2),
                            size = smallSize
                        )
                        if (names.size > 4) {
                            // Show +N indicator
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = CircleShape,
                                modifier = Modifier.size(smallSize)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "+${names.size - 3}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Avatar(
                                name = names.getOrElse(3) { "?" },
                                avatarPath = avatarPaths.getOrNull(3),
                                size = smallSize
                            )
                        }
                    }
                }
            }
        }
    }
}
