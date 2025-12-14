package com.bothbubbles.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
@Immutable
data class ReactionUiModel(
    val tapback: Tapback,
    val isFromMe: Boolean,
    val senderName: String?
)

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

/**
 * Individual reaction badge showing emoji and optional count.
 */
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
