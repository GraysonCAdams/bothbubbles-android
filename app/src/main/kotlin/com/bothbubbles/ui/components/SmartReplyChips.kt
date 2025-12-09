package com.bothbubbles.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Represents a suggestion item that can be either an ML-generated smart reply
 * or a user-saved template.
 */
data class SuggestionItem(
    val text: String,
    val isSmartSuggestion: Boolean,  // true = ML Kit generated, false = user template
    val templateId: Long? = null      // For tracking user template usage
)

/**
 * A right-aligned horizontal row of suggestion chips (Google Messages style).
 *
 * Shows a maximum of 3 suggestions combining ML Kit smart replies and user templates.
 * Tapping a chip inserts its text into the message input.
 *
 * @param suggestions List of suggestion items (max 3)
 * @param onSuggestionClick Called when a suggestion chip is tapped
 * @param modifier Modifier for the row
 */
@Composable
fun SmartReplyChips(
    suggestions: List<SuggestionItem>,
    onSuggestionClick: (SuggestionItem) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = suggestions.isNotEmpty(),
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it }
    ) {
        LazyRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.End,  // Right-aligned
            contentPadding = PaddingValues(horizontal = 12.dp),
            reverseLayout = true  // Most relevant appears rightmost
        ) {
            itemsIndexed(
                items = suggestions.take(3),  // Max 3 suggestions
                key = { _, item -> item.text }
            ) { index, suggestion ->
                // Staggered entrance animation for each chip
                var appeared by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(index * 50L)  // 50ms stagger
                    appeared = true
                }

                val scale by animateFloatAsState(
                    targetValue = if (appeared) 1f else 0.8f,
                    animationSpec = spring(
                        dampingRatio = 0.7f,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "chipScale"
                )
                val alpha by animateFloatAsState(
                    targetValue = if (appeared) 1f else 0f,
                    animationSpec = tween(100),
                    label = "chipAlpha"
                )

                SuggestionChip(
                    onClick = { onSuggestionClick(suggestion) },
                    label = {
                        Text(
                            text = suggestion.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}
