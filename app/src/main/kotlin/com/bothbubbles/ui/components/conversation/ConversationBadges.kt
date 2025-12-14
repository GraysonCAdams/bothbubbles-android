package com.bothbubbles.ui.components.conversation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.conversations.MessageStatus

/**
 * Animated badge showing unread message count
 */
@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    // Pop-in animation with scale + fade (snappy Android 16 style)
    AnimatedVisibility(
        visible = count > 0,
        enter = scaleIn(
            initialScale = 0.5f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
        ) + fadeIn(tween(100)),
        exit = scaleOut(
            targetScale = 0.5f,
            animationSpec = tween(100)
        ) + fadeOut(tween(100)),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            shape = CircleShape,
            modifier = Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Animated count changes with crossfade
                AnimatedContent(
                    targetState = count,
                    transitionSpec = {
                        fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                    },
                    label = "badgeCount"
                ) { targetCount ->
                    Text(
                        text = if (targetCount > 99) "99+" else targetCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Indicator showing message delivery/read status
 */
@Composable
fun MessageStatusIndicator(
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    when (status) {
        MessageStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Sending",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.NONE -> { /* No indicator */ }
    }
}
