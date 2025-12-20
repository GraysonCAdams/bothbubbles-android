package com.bothbubbles.ui.settings.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.conversation.SwipeActionType

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionSelectionCard(
    title: String,
    subtitle: String,
    selectedAction: SwipeActionType,
    onActionSelected: (SwipeActionType) -> Unit,
    availableActions: List<SwipeActionType>
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(selectedAction.color),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        selectedAction.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action chips
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableActions.forEach { action ->
                    val isSelected = action == selectedAction
                    FilterChip(
                        selected = isSelected,
                        onClick = { onActionSelected(action) },
                        label = { Text(action.label) },
                        leadingIcon = {
                            Icon(
                                action.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = action.color.copy(alpha = 0.2f),
                            selectedLabelColor = action.color,
                            selectedLeadingIconColor = action.color
                        )
                    )
                }
            }
        }
    }
}
