package com.bothbubbles.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * iMessage tapback reactions.
 * These reactions can only be sent on server-origin messages (iMessage or server-forwarded SMS).
 */
enum class Tapback(
    val apiName: String,
    val emoji: String,
    val label: String
) {
    LOVE("love", "❤️", "Love"),
    LIKE("like", "👍", "Like"),
    DISLIKE("dislike", "👎", "Dislike"),
    LAUGH("laugh", "😂", "Laugh"),
    EMPHASIZE("emphasize", "‼️", "Emphasize"),
    QUESTION("question", "❓", "Question");

    companion object {
        fun fromApiName(name: String): Tapback? {
            // Handle both regular and removal format (e.g., "-love" -> LOVE)
            val cleanName = name.removePrefix("-")
            return entries.find { it.apiName == cleanName }
        }
    }
}

/**
 * Represents a reaction on a message with sender info
 */
data class ReactionUiModel(
    val tapback: Tapback,
    val isFromMe: Boolean,
    val senderName: String?
)

/**
 * iOS-style tapback reaction menu that appears on long-press.
 * Shows all 6 iMessage reactions in a horizontal row with optional emoji picker.
 * Also includes action buttons for Copy and Forward.
 * Positioned above the message bubble, can float over top bar if needed.
 */
@Composable
fun TapbackMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    onReactionSelected: (Tapback) -> Unit,
    myReactions: Set<Tapback> = emptySet(),
    isFromMe: Boolean = false,
    onEmojiPickerClick: (() -> Unit)? = null,
    onCopyClick: (() -> Unit)? = null,
    onForwardClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    Popup(
        alignment = if (isFromMe) Alignment.TopEnd else Alignment.TopStart,
        offset = IntOffset(0, -100), // Position above the message
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            clippingEnabled = false // Allow floating over top bar
        )
    ) {
        TapbackMenuContent(
            onReactionSelected = { tapback ->
                onReactionSelected(tapback)
                onDismiss()
            },
            myReactions = myReactions,
            onEmojiPickerClick = onEmojiPickerClick,
            onCopyClick = onCopyClick?.let { { it(); onDismiss() } },
            onForwardClick = onForwardClick?.let { { it(); onDismiss() } },
            modifier = modifier
        )
    }
}

@Composable
private fun TapbackMenuContent(
    onReactionSelected: (Tapback) -> Unit,
    myReactions: Set<Tapback>,
    onEmojiPickerClick: (() -> Unit)? = null,
    onCopyClick: (() -> Unit)? = null,
    onForwardClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Animation for the menu appearing
    var animationProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) { value, _ ->
            animationProgress = value
        }
    }

    Surface(
        modifier = modifier
            .scale(animationProgress)
            .padding(4.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tapback reactions
            Tapback.entries.forEachIndexed { index, tapback ->
                TapbackButton(
                    tapback = tapback,
                    isSelected = tapback in myReactions,
                    onClick = { onReactionSelected(tapback) },
                    animationDelay = index * 20
                )
            }

            // Divider before action buttons
            if (onCopyClick != null || onForwardClick != null || onEmojiPickerClick != null) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }

            // Action buttons (icon-only)
            if (onCopyClick != null) {
                ActionButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    onClick = onCopyClick,
                    animationDelay = Tapback.entries.size * 20
                )
            }

            if (onForwardClick != null) {
                ActionButton(
                    icon = Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = "Forward",
                    onClick = onForwardClick,
                    animationDelay = (Tapback.entries.size + 1) * 20
                )
            }

            // Emoji picker button at the end
            if (onEmojiPickerClick != null) {
                EmojiPickerButton(
                    onClick = onEmojiPickerClick,
                    animationDelay = (Tapback.entries.size + 2) * 20
                )
            }
        }
    }
}

@Composable
private fun TapbackButton(
    tapback: Tapback,
    isSelected: Boolean,
    onClick: () -> Unit,
    animationDelay: Int,
    modifier: Modifier = Modifier
) {
    // Staggered animation for each button
    var scale by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(animationDelay.toLong())
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) { value, _ ->
            scale = value
        }
    }

    Box(
        modifier = modifier
            .scale(scale)
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tapback.emoji,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmojiPickerButton(
    onClick: () -> Unit,
    animationDelay: Int,
    modifier: Modifier = Modifier
) {
    // Staggered animation like tapback buttons
    var scale by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(animationDelay.toLong())
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) { value, _ ->
            scale = value
        }
    }

    Box(
        modifier = modifier
            .scale(scale)
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.AddReaction,
            contentDescription = "Add custom emoji",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Action button for message actions (Copy, Forward) - icon only
 */
@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    animationDelay: Int,
    modifier: Modifier = Modifier
) {
    // Staggered animation like tapback buttons
    var scale by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(animationDelay.toLong())
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) { value, _ ->
            scale = value
        }
    }

    Box(
        modifier = modifier
            .scale(scale)
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Displays reactions attached to a message bubble.
 * Positioned at the corner of the bubble, overlapping slightly.
 * Shows grouped reactions with counts.
 */
@Composable
fun ReactionsDisplay(
    reactions: List<ReactionUiModel>,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    if (reactions.isEmpty()) return

    // Group reactions by type and count
    val groupedReactions = reactions.groupBy { it.tapback }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            groupedReactions.forEach { (tapback, reactionsList) ->
                ReactionBadge(
                    tapback = tapback,
                    count = reactionsList.size,
                    hasMyReaction = reactionsList.any { it.isFromMe }
                )
            }
        }
    }
}

@Composable
private fun ReactionBadge(
    tapback: Tapback,
    count: Int,
    hasMyReaction: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = tapback.emoji,
            fontSize = 14.sp
        )
        if (count > 1) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (hasMyReaction) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
