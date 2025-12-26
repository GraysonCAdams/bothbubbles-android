package com.bothbubbles.ui.components.message

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ForwardToInbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Quickreply
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bothbubbles.core.design.theme.AppTextStyles
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.util.rememberThrottledHaptic
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Unified Tapback Card - combines reactions and actions into a single surface.
 *
 * This is the "Single Collision Box" approach:
 * - One surface to position (no separate pill + menu)
 * - One flip decision (above or below message)
 * - Simplified positioning logic
 *
 * Layout:
 * ┌─────────────────────────────┐
 * │ [❤️] [👍] [👎] [😂] [‼️] [❓] │  ← Reactions row
 * ├─────────────────────────────┤
 * │ 💬 Reply                    │
 * │ 📋 Copy                     │  ← Actions list
 * │ ↗️ Forward                  │
 * └─────────────────────────────┘
 */
@Composable
fun TapbackCard(
    myReactions: Set<Tapback>,
    canReply: Boolean = false,
    canCopy: Boolean = true,
    canForward: Boolean = true,
    isPinned: Boolean = false,
    isStarred: Boolean = false,
    onReactionSelected: (Tapback) -> Unit,
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    onPin: () -> Unit = {},
    onStar: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val throttledHaptics = rememberThrottledHaptic(haptics)
    val density = LocalDensity.current

    // Track which emoji is being hovered during scrub
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    val emojiSize = 40.dp

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column {
            // === Reactions Row ===
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                throttledHaptics.reset()
                                val index = (offset.x / emojiSize.toPx()).toInt()
                                    .coerceIn(0, Tapback.entries.size - 1)
                                if (index != hoveredIndex) {
                                    hoveredIndex = index
                                    throttledHaptics.onDragTransition()
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val index = (change.position.x / emojiSize.toPx()).toInt()
                                    .coerceIn(0, Tapback.entries.size - 1)
                                if (index != hoveredIndex) {
                                    hoveredIndex = index
                                    throttledHaptics.onDragTransition()
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                                hoveredIndex?.let { index ->
                                    if (index < Tapback.entries.size) {
                                        HapticUtils.onConfirm(haptics)
                                        onReactionSelected(Tapback.entries[index])
                                    }
                                }
                                hoveredIndex = null
                            },
                            onDragCancel = {
                                isDragging = false
                                hoveredIndex = null
                            }
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tapback.entries.forEachIndexed { index, tapback ->
                    TapbackEmoji(
                        tapback = tapback,
                        isSelected = tapback in myReactions,
                        isHovered = hoveredIndex == index && isDragging,
                        onClick = {
                            HapticUtils.onConfirm(haptics)
                            onReactionSelected(tapback)
                        },
                        modifier = Modifier.size(emojiSize)
                    )
                }
            }

            // === Divider ===
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // === Actions List ===
            Column(
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                if (canReply) {
                    TapbackAction(
                        icon = Icons.Outlined.Quickreply,
                        label = "Reply",
                        onClick = onReply
                    )
                }

                if (canCopy) {
                    TapbackAction(
                        icon = Icons.Outlined.ContentCopy,
                        label = "Copy",
                        onClick = onCopy
                    )
                }

                if (canForward) {
                    TapbackAction(
                        icon = Icons.Outlined.ForwardToInbox,
                        label = "Forward",
                        onClick = onForward
                    )
                }

                TapbackAction(
                    icon = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    label = if (isPinned) "Unpin" else "Pin",
                    onClick = onPin
                )

                TapbackAction(
                    icon = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    label = if (isStarred) "Unstar" else "Star",
                    onClick = onStar
                )
            }
        }
    }
}

/**
 * Individual reaction emoji with selection, hover, and pressed states.
 */
@Composable
private fun TapbackEmoji(
    tapback: Tapback,
    isSelected: Boolean,
    isHovered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 1.2f
            isHovered -> 1.3f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "emojiScale"
    )

    val backgroundColor = when {
        isPressed -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        isHovered -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tapback.emoji,
            style = AppTextStyles.emojiReaction,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Individual action item with icon and label.
 */
@Composable
private fun TapbackAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Simplified card for non-tapback-eligible messages (e.g., local SMS).
 * Shows only actions without reactions.
 */
@Composable
fun ActionOnlyCard(
    canCopy: Boolean = true,
    canForward: Boolean = true,
    isPinned: Boolean = false,
    isStarred: Boolean = false,
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    onPin: () -> Unit = {},
    onStar: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            if (canCopy) {
                TapbackAction(
                    icon = Icons.Outlined.ContentCopy,
                    label = "Copy",
                    onClick = onCopy
                )
            }

            if (canForward) {
                TapbackAction(
                    icon = Icons.Outlined.ForwardToInbox,
                    label = "Forward",
                    onClick = onForward
                )
            }

            TapbackAction(
                icon = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                label = if (isPinned) "Unpin" else "Pin",
                onClick = onPin
            )

            TapbackAction(
                icon = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = if (isStarred) "Unstar" else "Star",
                onClick = onStar
            )
        }
    }
}
