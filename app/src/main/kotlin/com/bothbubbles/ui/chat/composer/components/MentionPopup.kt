package com.bothbubbles.ui.chat.composer.components

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bothbubbles.ui.chat.composer.MentionPopupState
import com.bothbubbles.ui.chat.composer.MentionSuggestion
import java.io.File

/**
 * Popup showing participant suggestions when typing a mention.
 * Appears above the composer when '@' is typed or when text matches a participant's first name.
 *
 * @param state The current mention popup state
 * @param onSelectSuggestion Callback when a suggestion is selected
 * @param onDismiss Callback when the popup should be dismissed
 * @param modifier Modifier for the popup container
 */
@Composable
fun MentionPopup(
    state: MentionPopupState,
    onSelectSuggestion: (MentionSuggestion) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.isVisible && state.suggestions.isNotEmpty(),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            LazyColumn {
                itemsIndexed(
                    items = state.suggestions,
                    key = { _, item -> item.address }
                ) { index, suggestion ->
                    MentionSuggestionRow(
                        suggestion = suggestion,
                        isSelected = index == state.selectedIndex,
                        onClick = { onSelectSuggestion(suggestion) }
                    )
                    if (index < state.suggestions.size - 1) {
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

/**
 * A single row in the mention suggestion list.
 *
 * @param suggestion The participant suggestion to display
 * @param isSelected Whether this row is currently highlighted (for keyboard navigation)
 * @param onClick Callback when this row is clicked
 */
@Composable
private fun MentionSuggestionRow(
    suggestion: MentionSuggestion,
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        ParticipantAvatar(
            avatarPath = suggestion.avatarPath,
            displayName = suggestion.fullName,
            modifier = Modifier.size(40.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Name and address
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.fullName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = suggestion.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Avatar for a mention suggestion.
 * Shows cached photo if available, otherwise initials.
 */
@Composable
private fun ParticipantAvatar(
    avatarPath: String?,
    displayName: String,
    modifier: Modifier = Modifier
) {
    if (avatarPath != null && File(avatarPath).exists()) {
        AsyncImage(
            model = File(avatarPath),
            contentDescription = "Avatar for $displayName",
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        // Initials avatar
        val initials = displayName
            .split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString("")
            .ifEmpty { "?" }

        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
