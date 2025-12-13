package com.bothbubbles.ui.chat.composer.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import kotlinx.coroutines.delay

/**
 * Smart reply suggestion row for the chat composer.
 *
 * Displays up to 3 ML-generated reply suggestions as Material 3 chips.
 * Features a staggered entrance animation where each chip fades in and scales
 * up with a slight delay, creating a pleasing ripple effect.
 *
 * Layout (Google Messages style):
 * ```
 * ┌──────────────────────────────────────────────────────────────┐
 * │                    [Thanks!]  [Sure]  [On my way]            │
 * └──────────────────────────────────────────────────────────────┘
 * ```
 *
 * @param replies List of smart reply suggestions (max 3 shown)
 * @param visible Whether the row should be visible
 * @param onReplyClick Callback when a reply chip is tapped
 * @param modifier Modifier for the row container
 */
@Composable
fun SmartReplyRow(
    replies: List<String>,
    visible: Boolean,
    onReplyClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && replies.isNotEmpty(),
        enter = fadeIn(tween(ComposerMotionTokens.Duration.NORMAL)) + expandVertically(
            animationSpec = spring(dampingRatio = 0.8f),
            expandFrom = Alignment.Bottom
        ),
        exit = fadeOut(tween(ComposerMotionTokens.Duration.FAST)) + shrinkVertically(
            shrinkTowards = Alignment.Bottom
        ),
        modifier = modifier
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            reverseLayout = true
        ) {
            itemsIndexed(
                items = replies.take(3),
                key = { _, reply -> reply }
            ) { index, reply ->
                StaggeredReplyChip(
                    text = reply,
                    delayMs = index * ComposerMotionTokens.Duration.STAGGER,
                    onClick = { onReplyClick(reply) }
                )
            }
        }
    }
}

/**
 * Individual smart reply chip with staggered entrance animation.
 *
 * Each chip animates in with a scale and fade effect, delayed based on
 * its position in the list to create a cascading appearance.
 *
 * @param text The reply text to display
 * @param delayMs Delay before the entrance animation starts
 * @param onClick Callback when the chip is tapped
 */
@Composable
private fun StaggeredReplyChip(
    text: String,
    delayMs: Int,
    onClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        visible = true
    }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else ComposerMotionTokens.Scale.EntranceInitial,
        animationSpec = spring(
            dampingRatio = ComposerMotionTokens.Spring.Bouncy.dampingRatio,
            stiffness = ComposerMotionTokens.Spring.Bouncy.stiffness
        ),
        label = "chipScale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(ComposerMotionTokens.Duration.NORMAL),
        label = "chipAlpha"
    )

    SuggestionChip(
        onClick = onClick,
        label = {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = Modifier.graphicsLayer {
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
