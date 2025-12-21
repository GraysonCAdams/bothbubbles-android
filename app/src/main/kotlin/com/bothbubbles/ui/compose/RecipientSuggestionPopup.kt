package com.bothbubbles.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.common.Avatar
import kotlinx.collections.immutable.ImmutableList

/**
 * Suggestion dropdown that appears below the recipient field.
 * Shows filtered contacts and groups in a compact list.
 *
 * @param visible Whether the popup is visible
 * @param suggestions List of filtered suggestions
 * @param selectedIndex Currently selected index (for keyboard navigation)
 * @param allowGroups Whether group suggestions should be shown
 * @param onSelect Callback when a suggestion is selected
 * @param modifier Modifier for the popup container
 */
@Composable
fun RecipientSuggestionPopup(
    visible: Boolean,
    suggestions: ImmutableList<RecipientSuggestion>,
    selectedIndex: Int,
    allowGroups: Boolean,
    onSelect: (RecipientSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && suggestions.isNotEmpty(),
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            LazyColumn {
                itemsIndexed(
                    items = suggestions,
                    key = { _, item ->
                        when (item) {
                            is RecipientSuggestion.Contact -> "contact_${item.address}"
                            is RecipientSuggestion.Group -> "group_${item.chatGuid}"
                        }
                    }
                ) { index, suggestion ->
                    // Filter out groups if not allowed
                    val shouldShow = when (suggestion) {
                        is RecipientSuggestion.Contact -> true
                        is RecipientSuggestion.Group -> allowGroups
                    }

                    if (shouldShow) {
                        when (suggestion) {
                            is RecipientSuggestion.Contact -> {
                                ContactSuggestionRow(
                                    suggestion = suggestion,
                                    isSelected = index == selectedIndex,
                                    onClick = { onSelect(suggestion) }
                                )
                            }
                            is RecipientSuggestion.Group -> {
                                GroupSuggestionRow(
                                    suggestion = suggestion,
                                    isSelected = index == selectedIndex,
                                    onClick = { onSelect(suggestion) }
                                )
                            }
                        }

                        if (index < suggestions.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Row displaying a contact suggestion.
 */
@Composable
private fun ContactSuggestionRow(
    suggestion: RecipientSuggestion.Contact,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Avatar(
            name = suggestion.displayName,
            avatarPath = suggestion.avatarPath,
            size = 40.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Name and address
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = suggestion.formattedAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Service indicator
        ServiceIndicator(service = suggestion.service)
    }
}

/**
 * Row displaying a group chat suggestion.
 */
@Composable
private fun GroupSuggestionRow(
    suggestion: RecipientSuggestion.Group,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Group avatar or icon
        if (suggestion.avatarPath != null) {
            Avatar(
                name = suggestion.displayName,
                avatarPath = suggestion.avatarPath,
                size = 40.dp
            )
        } else {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Group,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Name and member preview
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = suggestion.memberPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Chevron indicating it's expandable/selectable
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Small badge showing the service type (iMessage/SMS).
 */
@Composable
private fun ServiceIndicator(service: String) {
    val isIMessage = service.equals("iMessage", ignoreCase = true)
    val color = if (isIMessage) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }

    Text(
        text = if (isIMessage) "iMessage" else "SMS",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Medium
    )
}
