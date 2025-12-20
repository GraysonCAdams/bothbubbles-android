package com.bothbubbles.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.ui.theme.BothBubblesTheme

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
 * Tap on a reaction to see who reacted.
 *
 * My reactions are highlighted with iMessage blue background,
 * others' reactions use a gray background.
 */
@Composable
fun ReactionsDisplay(
    reactions: List<ReactionUiModel>,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    if (reactions.isEmpty()) return

    // Group reactions by type
    val groupedReactions = reactions.groupBy { it.tapback }

    // Track which reaction's detail sheet is shown
    var selectedReaction by remember { mutableStateOf<Tapback?>(null) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        groupedReactions.forEach { (tapback, reactionsList) ->
            ReactionBadge(
                tapback = tapback,
                reactions = reactionsList,
                onClick = { selectedReaction = tapback }
            )
        }
    }

    // Show bottom sheet when a reaction is selected
    selectedReaction?.let { tapback ->
        val reactionsList = groupedReactions[tapback] ?: emptyList()
        ReactionDetailsSheet(
            tapback = tapback,
            reactions = reactionsList,
            onDismiss = { selectedReaction = null }
        )
    }
}

/**
 * Individual reaction badge showing emoji and optional count.
 * My reactions use iMessage blue background, others use gray.
 * Clickable to show who reacted.
 */
@Composable
private fun ReactionBadge(
    tapback: Tapback,
    reactions: List<ReactionUiModel>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasMyReaction = reactions.any { it.isFromMe }
    val count = reactions.size

    val backgroundColor = if (hasMyReaction) {
        BothBubblesTheme.bubbleColors.iMessageSent
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val textColor = if (hasMyReaction) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = tapback.emoji,
                fontSize = 14.sp
            )
            if (count > 1) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
            }
        }
    }
}

/**
 * Bottom sheet showing who reacted with a specific tapback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionDetailsSheet(
    tapback: Tapback,
    reactions: List<ReactionUiModel>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Build comma-separated list of names
    val names = reactions.map { reaction ->
        when {
            reaction.isFromMe -> "You"
            reaction.senderName != null -> reaction.senderName
            else -> "Unknown"
        }
    }.joinToString(", ")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tapback.emoji,
                fontSize = 28.sp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = names,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
