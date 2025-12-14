package com.bothbubbles.ui.chatcreator

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Displays a banner showing the group service type and participant count.
 */
@Composable
fun GroupServiceIndicator(
    groupService: GroupServiceType,
    participantCount: Int
) {
    val (text, color) = when (groupService) {
        GroupServiceType.IMESSAGE -> "iMessage group" to MaterialTheme.colorScheme.primary
        GroupServiceType.MMS -> "SMS/MMS group" to MaterialTheme.colorScheme.secondary
        GroupServiceType.UNDETERMINED -> "Add participants" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = color
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$participantCount selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Horizontal scrollable row of selected participant chips.
 */
@Composable
fun SelectedParticipantsRow(
    participants: List<GroupParticipant>,
    onRemove: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        participants.forEach { participant ->
            ParticipantChip(
                participant = participant,
                onRemove = { onRemove(participant.address) }
            )
        }
    }
}

/**
 * Individual participant chip with remove button.
 */
@Composable
fun ParticipantChip(
    participant: GroupParticipant,
    onRemove: () -> Unit
) {
    Surface(
        color = if (participant.service.equals("iMessage", ignoreCase = true)) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = participant.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = if (participant.service.equals("iMessage", ignoreCase = true)) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(16.dp),
                    tint = if (participant.service.equals("iMessage", ignoreCase = true)) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
        }
    }
}
