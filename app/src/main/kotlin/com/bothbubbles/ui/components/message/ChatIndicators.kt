package com.bothbubbles.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

/**
 * Chat indicator composables extracted from MessageBubble.kt.
 * These are public composables used by ChatScreen and other UI components.
 */

/**
 * Typing indicator bubble with animated dots.
 */
@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.padding(end = 48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) { index ->
                TypingDot(
                    delay = index * 150,
                    modifier = Modifier.size(8.dp)
                )
            }
        }
    }
}

@Composable
private fun TypingDot(
    delay: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "typingDot")

    // Google Messages style: fade pulse animation (0.3 -> 1.0 -> 0.3)
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.3f at delay using LinearEasing
                1f at (delay + 200) using FastOutSlowInEasing
                0.3f at (delay + 400) using FastOutSlowInEasing
                0.3f at 1200 using LinearEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dotFade"
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    )
}

/**
 * Date separator between message groups.
 */
@Composable
fun DateSeparator(
    date: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = date,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Group event indicator (participant joined/left, name changed, photo changed).
 * Styled as a centered, muted system message similar to DateSeparator.
 */
@Composable
fun GroupEventIndicator(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * Calendar event indicator for displaying contact calendar events in 1:1 chats.
 *
 * Styled similarly to GroupEventIndicator but with a calendar icon.
 * Format: "WFH - All Day" or "Coffee (started 5m ago)"
 */
@Composable
fun CalendarEventIndicator(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Event,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * Floating button to jump to the bottom of the message list.
 * Shows "X new messages" if there are unread messages, otherwise "Jump to latest".
 *
 * @param visible Whether the button should be visible (typically when scrolled away from bottom)
 * @param newMessageCount Number of new unread messages (0 = no new messages)
 * @param onClick Callback when button is clicked
 */
@Composable
fun JumpToBottomIndicator(
    visible: Boolean,
    newMessageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasNewMessages = newMessageCount > 0

    // Pulsing animation for new messages indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.8f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(animationSpec = tween(150)),
        exit = scaleOut(
            targetScale = 0.9f,
            animationSpec = tween(100)
        ) + fadeOut(animationSpec = tween(100)),
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            // No new messages: near-white in light mode, dark gray in dark mode
            // New messages: blue from theme palette
            color = if (hasNewMessages)
                MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
            else
                MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 4.dp,
            modifier = if (hasNewMessages) Modifier.scale(pulseScale) else Modifier
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll down",
                    tint = if (hasNewMessages)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = when {
                        newMessageCount == 1 -> "1 new message"
                        newMessageCount > 1 -> "$newMessageCount new messages"
                        else -> "Jump to latest"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasNewMessages)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
