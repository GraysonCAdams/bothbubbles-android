package com.bothbubbles.ui.components.message

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.util.rememberThrottledHaptic
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MD3-styled reaction pill for selecting tapback reactions.
 *
 * Features:
 * - Fully rounded pill shape (CircleShape ends)
 * - Scrub-to-select gesture: drag finger over emojis to select
 * - Haptic feedback on each emoji transition
 * - Fluid center expansion animation
 * - Selection state with primaryContainer highlight
 */
@Composable
fun ReactionPill(
    visible: Boolean,
    myReactions: Set<Tapback>,
    onReactionSelected: (Tapback) -> Unit,
    onEmojiPickerClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val throttledHaptics = rememberThrottledHaptic(haptics)
    val density = LocalDensity.current

    // Track which emoji is being hovered during scrub
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    // Pill dimensions
    val emojiCount = Tapback.entries.size + if (onEmojiPickerClick != null) 1 else 0
    val emojiSize = 40.dp
    val pillPadding = 6.dp
    val fullWidth = with(density) { (emojiSize * emojiCount + pillPadding * 2).toPx() }

    // Animation: fluid center expansion
    val animatedWidth by animateDpAsState(
        targetValue = if (visible) emojiSize * emojiCount + pillPadding * 2 else 0.dp,
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
            .width(animatedWidth)
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
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            throttledHaptics.reset()
                            // Calculate initial hovered index
                            val index = (offset.x / emojiSize.toPx()).toInt()
                                .coerceIn(0, Tapback.entries.size - 1)
                            if (index != hoveredIndex) {
                                hoveredIndex = index
                                throttledHaptics.onDragTransition()
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            // Calculate which emoji is under finger
                            val index = (change.position.x / emojiSize.toPx()).toInt()
                                .coerceIn(0, Tapback.entries.size - 1)
                            if (index != hoveredIndex) {
                                hoveredIndex = index
                                throttledHaptics.onDragTransition()
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            // Select the hovered reaction with haptic feedback
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
            // Tapback reaction emojis
            Tapback.entries.forEachIndexed { index, tapback ->
                ReactionEmoji(
                    tapback = tapback,
                    isSelected = tapback in myReactions,
                    isHovered = hoveredIndex == index && isDragging,
                    onClick = {
                        // Haptic feedback on tap selection
                        HapticUtils.onConfirm(haptics)
                        onReactionSelected(tapback)
                    },
                    modifier = Modifier.size(emojiSize)
                )
            }

            // Emoji picker button at the end
            if (onEmojiPickerClick != null) {
                EmojiPickerIcon(
                    onClick = onEmojiPickerClick,
                    modifier = Modifier.size(emojiSize)
                )
            }
        }
    }
}

/**
 * Individual reaction emoji button with selection, hover, and pressed states.
 */
@Composable
private fun ReactionEmoji(
    tapback: Tapback,
    isSelected: Boolean,
    isHovered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Track pressed state for tap animation
    var isPressed by remember { mutableStateOf(false) }

    // Scale up when hovered during scrub, or briefly when tapped
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 1.2f
            isHovered -> 1.3f
            isSelected -> 1.0f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "emojiScale"
    )

    // Background color for selection state
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
                // Tap fallback for accessibility and non-scrub interactions
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
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Emoji picker button to add custom reactions.
 */
@Composable
private fun EmojiPickerIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.AddReaction,
            contentDescription = "Add custom emoji",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}
