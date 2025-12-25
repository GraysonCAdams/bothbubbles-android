package com.bothbubbles.ui.components.message

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ForwardToInbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.bothbubbles.util.HapticUtils

/**
 * MD3-styled horizontal action pill for message operations.
 *
 * Displays a horizontally scrollable row of icon actions matching the ReactionPill style:
 * - Reply (if supported)
 * - Copy
 * - Forward
 * - Add to Tasks (opens Google Tasks with pre-filled task)
 *
 * Uses the same visual styling as ReactionPill for consistency:
 * - CircleShape pill
 * - 40.dp icons with 6.dp padding
 * - surfaceContainerHigh background
 * - Horizontal scroll for overflow on smaller screens
 */
@Composable
fun MessageActionMenu(
    visible: Boolean,
    canReply: Boolean = false,
    canCopy: Boolean = true,
    canForward: Boolean = true,
    canAddToTasks: Boolean = true,
    canPin: Boolean = true,
    isPinned: Boolean = false,
    canStar: Boolean = true,
    isStarred: Boolean = false,
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    onAddToTasks: () -> Unit = {},
    onPin: () -> Unit = {},
    onStar: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    // Calculate visible action count
    val actionCount = listOf(canReply, canCopy, canForward, canAddToTasks, canPin, canStar).count { it }

    // Match ReactionPill dimensions
    val iconSize = 40.dp
    val pillPadding = 6.dp

    // Maximum width matches the reaction pill (6 reactions + emoji picker = 7 items)
    val maxReactionPillWidth = iconSize * 7 + pillPadding * 2

    // Animation: fluid center expansion
    val animatedWidth by animateDpAsState(
        targetValue = if (visible) iconSize * actionCount + pillPadding * 2 else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pillWidth"
    )

    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 150,
            delayMillis = if (visible) 50 else 0
        ),
        label = "contentAlpha"
    )

    if (!visible && animatedWidth == 0.dp) return

    Surface(
        modifier = modifier
            .widthIn(max = maxReactionPillWidth)
            .clip(CircleShape),
        shape = CircleShape,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .padding(pillPadding)
                .alpha(contentAlpha)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canReply) {
                ActionIcon(
                    icon = Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = "Reply",
                    onClick = onReply,
                    modifier = Modifier.size(iconSize)
                )
            }

            if (canCopy) {
                ActionIcon(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    onClick = onCopy,
                    modifier = Modifier.size(iconSize)
                )
            }

            if (canForward) {
                ActionIcon(
                    icon = Icons.Outlined.ForwardToInbox,
                    contentDescription = "Forward",
                    onClick = onForward,
                    modifier = Modifier.size(iconSize)
                )
            }

            if (canAddToTasks) {
                ActionIcon(
                    icon = Icons.Outlined.TaskAlt,
                    contentDescription = "Add to Tasks",
                    onClick = onAddToTasks,
                    modifier = Modifier.size(iconSize)
                )
            }

            if (canPin) {
                ActionIcon(
                    icon = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (isPinned) "Unpin message" else "Pin message",
                    onClick = onPin,
                    modifier = Modifier.size(iconSize)
                )
            }

            if (canStar) {
                ActionIcon(
                    icon = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (isStarred) "Unstar message" else "Star message",
                    onClick = onStar,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

/**
 * Individual action icon button with tap animation and haptic feedback.
 */
@Composable
private fun ActionIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.2f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "iconScale"
    )

    val backgroundColor = if (isPressed) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
    } else {
        Color.Transparent
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
                    onTap = {
                        HapticUtils.onConfirm(haptics)
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}
