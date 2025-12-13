package com.bothbubbles.ui.components.message

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

/**
 * Type of swipe gesture being performed on a message bubble.
 */
internal enum class SwipeType {
    /** Swipe toward center to trigger reply (iMessage only) */
    REPLY,
    /** Swipe toward empty space to reveal date/type info */
    DATE_REVEAL
}

/**
 * Direction intent for gesture detection - determines if user is swiping or scrolling.
 */
internal enum class GestureIntent {
    UNDETERMINED,
    HORIZONTAL_SWIPE,
    VERTICAL_SCROLL
}

/**
 * Reply indicator shown behind message bubble during reply swipe.
 */
@Composable
internal fun ReplyIndicator(
    progress: Float,
    isFullyExposed: Boolean,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isFullyExposed) 1.1f else 0.6f + (progress * 0.4f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "replyScale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isFullyExposed)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "replyBg"
    )

    Box(
        modifier = modifier
            .size(40.dp)
            .scale(scale)
            .alpha(progress)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Reply,
            contentDescription = "Reply",
            tint = if (isFullyExposed)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Date and message type label shown during date reveal swipe.
 */
@Composable
internal fun DateTypeLabel(
    time: String,
    type: String,
    progress: Float,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    val offsetX = if (isFromMe) {
        // Slides in from left for sent messages
        (-80 + (progress * 80)).dp
    } else {
        // Slides in from right for received messages
        (80 - (progress * 80)).dp
    }

    Column(
        horizontalAlignment = if (isFromMe) Alignment.Start else Alignment.End,
        modifier = modifier
            .offset(x = offsetX)
            .alpha(progress)
            .padding(horizontal = 8.dp)
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = type,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
