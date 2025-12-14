package com.bothbubbles.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.ui.chat.TutorialState

/**
 * MD3 Rich Tooltip for the SMS/iMessage toggle feature.
 *
 * Replaces the full-screen scrim with a less intrusive tooltip that:
 * - Points at the send button with a subtle arrow
 * - Shows clear instructions with animated icon
 * - Has a "Got it" dismiss action
 * - Allows the user to see the chat context behind it
 *
 * This is a two-step tutorial:
 * 1. Step 1: "Swipe up to switch to SMS"
 * 2. Step 2: "Now swipe back to iMessage"
 *
 * @param tutorialState Current state of the tutorial
 * @param onDismiss Called when user taps "Got it" or outside the tooltip
 * @param modifier Modifier for this composable
 */
@Composable
fun SendModeTutorialOverlay(
    tutorialState: TutorialState,
    onTutorialProgress: (TutorialState) -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = tutorialState == TutorialState.STEP_1_SWIPE_UP ||
            tutorialState == TutorialState.STEP_2_SWIPE_BACK

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(200)) + slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = tween(250)
        ),
        exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(
            targetOffsetY = { it / 4 },
            animationSpec = tween(200)
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Semi-transparent scrim - tappable to dismiss (optional)
            // Much lighter than before to let user see the chat
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // Don't dismiss on scrim tap - require user to complete or tap "Got it"
                    }
            )

            // Rich Tooltip positioned near the send button
            RichTooltipCard(
                tutorialState = tutorialState,
                onDismiss = {
                    // Skip to completed state when user dismisses
                    onTutorialProgress(TutorialState.COMPLETED)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 72.dp) // Position above send button
            )
        }
    }
}

/**
 * MD3-style Rich Tooltip card with instructions and dismiss action.
 */
@Composable
private fun RichTooltipCard(
    tutorialState: TutorialState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isStep1 = tutorialState == TutorialState.STEP_1_SWIPE_UP

    // Animated swipe indicator
    val infiniteTransition = rememberInfiniteTransition(label = "swipeAnimation")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "swipeOffset"
    )

    Surface(
        modifier = modifier.width(240.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Animated swipe icon
            Row(
                modifier = Modifier.height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset { IntOffset(0, offsetY.toInt()) }
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                    Icon(
                        Icons.Default.SwipeVertical,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Title
            Text(
                text = if (isStep1) {
                    stringResource(R.string.tutorial_send_mode_step1_title)
                } else {
                    stringResource(R.string.tutorial_send_mode_step2_title)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Description
            Text(
                text = if (isStep1) {
                    stringResource(R.string.tutorial_send_mode_step1_description)
                } else {
                    stringResource(R.string.tutorial_send_mode_step2_description)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action button
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inversePrimary,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Got it")
            }
        }
    }
}

/**
 * Compact version of the tutorial for inline display.
 * Shows just the hint text without the full overlay.
 */
@Composable
fun SendModeTutorialHint(
    tutorialState: TutorialState,
    modifier: Modifier = Modifier
) {
    val isVisible = tutorialState == TutorialState.STEP_1_SWIPE_UP ||
            tutorialState == TutorialState.STEP_2_SWIPE_BACK

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val text = if (tutorialState == TutorialState.STEP_1_SWIPE_UP) {
            stringResource(R.string.tutorial_send_mode_hint_swipe_up)
        } else {
            stringResource(R.string.tutorial_send_mode_hint_swipe_back)
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
