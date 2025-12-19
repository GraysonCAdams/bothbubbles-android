package com.bothbubbles.ui.components.message.focus

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ForwardToInbox
import androidx.compose.material.icons.outlined.Quickreply
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.ui.components.message.Tapback
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.util.rememberThrottledHaptic
import kotlinx.collections.immutable.ImmutableSet

/**
 * Combined card with reaction picker and action menu.
 *
 * Layout:
 * ```
 * ┌─────────────────────────────┐
 * │ [❤️] [👍] [👎] [😂] [‼️] [❓] │  ← Reactions row (scrub-to-select)
 * ├─────────────────────────────┤
 * │ 💬 Reply                    │
 * │ 📋 Copy                     │  ← Actions list
 * │ ↗️ Forward                  │
 * └─────────────────────────────┘
 * ```
 *
 * Features:
 * - Scrub-to-select gesture for reactions
 * - Haptic feedback on transitions
 * - Selected reaction highlighting
 * - Accessibility support with content descriptions
 */
@Composable
fun FocusMenuCard(
    showReactions: Boolean,
    myReactions: ImmutableSet<Tapback>,
    canReply: Boolean,
    canCopy: Boolean,
    canForward: Boolean,
    onReactionSelected: (Tapback) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val throttledHaptics = rememberThrottledHaptic(haptics)
    val density = LocalDensity.current

    // Scrub state
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    val emojiSize = 40.dp
    val emojiSizePx = with(density) { emojiSize.toPx() }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column {
            // === Reactions Row ===
            if (showReactions) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    throttledHaptics.reset()
                                    val index = (offset.x / emojiSizePx).toInt()
                                        .coerceIn(0, Tapback.entries.size - 1)
                                    if (index != hoveredIndex) {
                                        hoveredIndex = index
                                        throttledHaptics.onDragTransition()
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val index = (change.position.x / emojiSizePx).toInt()
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
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Tapback.entries.forEachIndexed { index, tapback ->
                        ReactionEmoji(
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

                // Divider between reactions and actions
                if (canReply || canCopy || canForward) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // === Actions List ===
            Column(
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                if (canReply) {
                    ActionItem(
                        icon = Icons.Outlined.Quickreply,
                        label = "Reply",
                        onClick = onReply
                    )
                }

                if (canCopy) {
                    ActionItem(
                        icon = Icons.Outlined.ContentCopy,
                        label = "Copy",
                        onClick = onCopy
                    )
                }

                if (canForward) {
                    ActionItem(
                        icon = Icons.Outlined.ForwardToInbox,
                        label = "Forward",
                        onClick = onForward
                    )
                }

                // Always show Select action to enter multi-select mode
                ActionItem(
                    icon = Icons.Outlined.CheckBox,
                    label = "Select",
                    onClick = onSelect
                )
            }
        }
    }
}

/**
 * Individual reaction emoji with selection and hover states.
 */
@Composable
private fun ReactionEmoji(
    tapback: Tapback,
    isSelected: Boolean,
    isHovered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    // Animated scale for interaction states
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

    // Background color for states
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
            .semantics {
                contentDescription = if (isSelected) {
                    "Selected. React with ${tapback.label}"
                } else {
                    "React with ${tapback.label}"
                }
                role = Role.Button
                selected = isSelected
            }
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
            fontSize = 22.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Individual action item with icon and label.
 */
@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // Handled by row semantics
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Simplified card showing only actions (for non-tapback-eligible messages).
 */
@Composable
fun ActionOnlyMenuCard(
    canCopy: Boolean,
    canForward: Boolean,
    onCopy: () -> Unit,
    onForward: () -> Unit,
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
                ActionItem(
                    icon = Icons.Outlined.ContentCopy,
                    label = "Copy",
                    onClick = onCopy
                )
            }

            if (canForward) {
                ActionItem(
                    icon = Icons.Outlined.ForwardToInbox,
                    label = "Forward",
                    onClick = onForward
                )
            }
        }
    }
}
