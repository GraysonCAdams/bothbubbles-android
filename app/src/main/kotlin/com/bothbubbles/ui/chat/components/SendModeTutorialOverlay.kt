package com.bothbubbles.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.ui.chat.TutorialState
import kotlinx.coroutines.delay

/**
 * Interactive tutorial overlay for the SMS/iMessage toggle feature.
 *
 * This is a two-step tutorial that requires the user to actually perform the gestures:
 * 1. Step 1: Prompts user to "Swipe up to switch modes"
 * 2. Step 2: After first swipe, prompts "Now swipe back"
 *
 * The tutorial can ONLY be dismissed by completing both steps.
 *
 * @param tutorialState Current state of the tutorial
 * @param onTutorialProgress Called when user completes a step (to update state)
 * @param sendButtonPosition Offset position of the send button (for spotlight alignment)
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
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Scrim with spotlight cutout for the send button
            // The spotlight is centered on the send button (may clip at edge)
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Required for BlendMode.Clear to work properly
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
            ) {
                // Draw semi-transparent scrim
                drawRect(
                    color = Color.Black.copy(alpha = 0.7f),
                    size = size
                )

                // Cut out a spotlight circle for the send button
                // Position: centered on send button at bottom-right
                // Button (40dp) is in a Row with 6dp horizontal padding
                // Button center from right edge = 6dp + 20dp = 26dp
                // From bottom: nav bar + input row - tuned for device
                val spotlightRadius = 36.dp.toPx()
                val spotlightCenter = androidx.compose.ui.geometry.Offset(
                    x = size.width - 26.dp.toPx(),  // 6dp row padding + 20dp button center
                    y = size.height - 58.dp.toPx()  // Tuned for nav bar + input row
                )

                // Draw transparent circle (spotlight) using BlendMode.Clear
                drawCircle(
                    color = Color.Transparent,
                    radius = spotlightRadius,
                    center = spotlightCenter,
                    blendMode = androidx.compose.ui.graphics.BlendMode.Clear
                )

                // Draw a subtle glow ring around the spotlight
                drawCircle(
                    color = Color.White.copy(alpha = 0.4f),
                    radius = spotlightRadius + 4.dp.toPx(),
                    center = spotlightCenter,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
            }

            // Arrow indicator positioned directly above the send button
            // Aligned with spotlight: 26dp from right edge, positioned above the 58dp button center
            SwipeUpArrowIndicator(
                swipeUp = tutorialState == TutorialState.STEP_1_SWIPE_UP,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 100.dp) // Centered above the button spotlight
            )

            // Tutorial card positioned in center-bottom area
            TutorialCard(
                tutorialState = tutorialState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 140.dp, start = 32.dp, end = 80.dp) // Offset from button
            )
        }
    }
}

/**
 * Animated bidirectional arrow indicator showing swipe up/down gesture.
 */
@Composable
private fun SwipeUpArrowIndicator(
    swipeUp: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "arrowBounce")

    // Alternating up/down bounce animation
    val offsetY by infiniteTransition.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowOffset"
    )

    // Pulsing alpha
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowAlpha"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Up arrows
        repeat(2) { index ->
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .offset { IntOffset(0, (-offsetY.coerceAtLeast(0f) * (1f - index * 0.4f)).toInt()) }
                    .alpha(alpha * (1f - index * 0.3f)),
                tint = Color.White
            )
        }
        // Down arrows
        repeat(2) { index ->
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .offset { IntOffset(0, (offsetY.coerceAtLeast(0f) * (1f - index * 0.4f)).toInt()) }
                    .alpha(alpha * (1f - index * 0.3f)),
                tint = Color.White
            )
        }
    }
}

/**
 * Tutorial instruction card.
 */
@Composable
private fun TutorialCard(
    tutorialState: TutorialState,
    modifier: Modifier = Modifier
) {
    val isStep1 = tutorialState == TutorialState.STEP_1_SWIPE_UP
    val titleText = if (isStep1) {
        stringResource(R.string.tutorial_send_mode_step1_title)
    } else {
        stringResource(R.string.tutorial_send_mode_step2_title)
    }
    val descriptionText = if (isStep1) {
        stringResource(R.string.tutorial_send_mode_step1_description)
    } else {
        stringResource(R.string.tutorial_send_mode_step2_description)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = descriptionText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Animated hand/finger icon showing the swipe gesture.
 */
@Composable
private fun AnimatedSwipeGesture(
    swipeUp: Boolean,
    modifier: Modifier = Modifier
) {
    // Infinite animation for the hand movement
    val infiniteTransition = rememberInfiniteTransition(label = "swipeGesture")

    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (swipeUp) -30f else 30f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 800,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "swipeOffset"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Pulsing circle background
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
        )

        // Hand icon
        Icon(
            imageVector = Icons.Default.TouchApp,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .offset { IntOffset(0, offsetY.toInt()) },
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Progress dots showing tutorial step progress.
 */
@Composable
private fun TutorialProgressDots(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(totalSteps) { index ->
            val isActive = index < currentStep
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        }
                    )
            )
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

        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
