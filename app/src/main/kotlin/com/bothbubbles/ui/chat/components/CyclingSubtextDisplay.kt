package com.bothbubbles.ui.chat.components

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.chat.delegates.ChatHeaderIntegrationsDelegate
import com.bothbubbles.ui.chat.integration.ChatHeaderContent
import com.bothbubbles.ui.chat.integration.TapActionData
import com.bothbubbles.ui.components.common.MarqueeText
import com.bothbubbles.ui.theme.MotionTokens

/**
 * Animated cycling display for chat header subtext.
 *
 * Displays content from integrations (Life360, Calendar, etc.) with:
 * - Vertical slide animation when cycling between items
 * - Marquee scrolling for long text
 * - Icon prefix for each integration type
 *
 * @param delegate The integrations delegate managing content and cycling
 * @param onLife360Click Callback when Life360 content is tapped (navigate to details)
 * @param modifier Modifier for the container
 */
@Composable
fun CyclingSubtextDisplay(
    delegate: ChatHeaderIntegrationsDelegate,
    onLife360Click: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by delegate.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val currentContent = state.currentContent ?: return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clipToBounds()
            .clickable {
                handleTap(currentContent, onLife360Click) {
                    // Open calendar event
                    val uri = ContentUris.withAppendedId(
                        CalendarContract.Events.CONTENT_URI,
                        it.eventId
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = uri
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            }
            .semantics {
                contentDescription = currentContent.text
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        // Icon
        currentContent.icon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Animated text content
        AnimatedContent(
            targetState = currentContent,
            transitionSpec = {
                // Vertical slide animation
                (slideInVertically(
                    animationSpec = tween(
                        durationMillis = MotionTokens.Duration.MEDIUM_1,
                        easing = MotionTokens.Easing.EmphasizedDecelerate
                    ),
                    initialOffsetY = { fullHeight -> fullHeight }
                ) + fadeIn(
                    animationSpec = tween(MotionTokens.Duration.SHORT_4)
                )).togetherWith(
                    slideOutVertically(
                        animationSpec = tween(
                            durationMillis = MotionTokens.Duration.MEDIUM_1,
                            easing = MotionTokens.Easing.EmphasizedAccelerate
                        ),
                        targetOffsetY = { fullHeight -> -fullHeight }
                    ) + fadeOut(
                        animationSpec = tween(MotionTokens.Duration.SHORT_4)
                    )
                )
            },
            contentKey = { "${it.sourceId}:${it.text}" },
            label = "cyclingSubtext",
            modifier = Modifier.weight(1f, fill = false)
        ) { content ->
            MarqueeText(
                text = content.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Handle tap on content based on its type.
 */
private inline fun handleTap(
    content: ChatHeaderContent,
    onLife360Click: () -> Unit,
    onCalendarClick: (TapActionData.CalendarEvent) -> Unit
) {
    when (val data = content.tapActionData) {
        is TapActionData.Life360Location -> onLife360Click()
        is TapActionData.CalendarEvent -> onCalendarClick(data)
        null -> { /* No action */ }
    }
}
