package com.bothbubbles.ui.chat.composer.tutorial

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.ui.chat.composer.GestureDirection
import com.bothbubbles.ui.chat.composer.TutorialStep
import kotlin.math.roundToInt

/**
 * Instruction card for tutorial steps.
 *
 * Displays the step title and description in a floating card with
 * Material Design 3 styling. The card includes smooth entrance/exit
 * animations and adapts its position based on the spotlight location.
 *
 * @param step Current tutorial step to display
 * @param isVisible Whether the card should be visible
 * @param modifier Modifier for positioning
 */
@Composable
fun TutorialCard(
    step: TutorialStep,
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(200)) + scaleIn(
            initialScale = 0.9f,
            animationSpec = tween(200)
        ),
        exit = fadeOut(tween(150)) + scaleOut(
            targetScale = 0.9f,
            animationSpec = tween(150)
        ),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Step icon for completion state
                if (step == TutorialStep.COMPLETE) {
                    CompletionIcon()
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = step.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Animated checkmark icon for completion state.
 */
@Composable
private fun CompletionIcon() {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(300),
        label = "completionScale"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(28.dp)
        )
    }
}

/**
 * Animated arrow indicator showing the gesture direction.
 *
 * Displays bouncing arrows that guide the user to perform the
 * correct swipe gesture. Shows bidirectional arrows for the
 * initial step, and single direction arrows for subsequent steps.
 *
 * @param direction The direction to indicate (UP, DOWN, or NONE)
 * @param showBidirectional Whether to show both up and down arrows
 * @param modifier Modifier for positioning
 */
@Composable
fun GestureHintArrow(
    direction: GestureDirection,
    showBidirectional: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (direction == GestureDirection.NONE) return

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

    if (showBidirectional) {
        // Bidirectional arrows for first step
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
                        .offset { IntOffset(0, (-offsetY.coerceAtLeast(0f) * (1f - index * 0.4f)).roundToInt()) }
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
                        .offset { IntOffset(0, (offsetY.coerceAtLeast(0f) * (1f - index * 0.4f)).roundToInt()) }
                        .alpha(alpha * (1f - index * 0.3f)),
                    tint = Color.White
                )
            }
        }
    } else {
        // Single direction arrow
        val icon = if (direction == GestureDirection.UP) {
            Icons.Filled.KeyboardArrowUp
        } else {
            Icons.Filled.KeyboardArrowDown
        }

        val bounceOffset = if (direction == GestureDirection.UP) {
            -offsetY.coerceAtLeast(0f)
        } else {
            offsetY.coerceAtLeast(0f)
        }

        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            repeat(2) { index ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .offset { IntOffset(0, (bounceOffset * (1f - index * 0.4f)).roundToInt()) }
                        .alpha(alpha * (1f - index * 0.3f)),
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * Step progress dots showing tutorial progress.
 *
 * Displays a horizontal row of dots indicating the current step
 * in the tutorial sequence. Completed steps are shown as filled,
 * while remaining steps are shown as outlined.
 *
 * @param currentStep Current step index (0-based)
 * @param totalSteps Total number of steps
 * @param modifier Modifier for positioning
 */
@Composable
fun StepProgressDots(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(totalSteps) { index ->
            val isCompleted = index < currentStep
            val isCurrent = index == currentStep

            val scale by animateFloatAsState(
                targetValue = if (isCurrent) 1.2f else 1f,
                animationSpec = tween(200),
                label = "dotScale$index"
            )

            Box(
                modifier = Modifier
                    .size((8 * scale).dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isCompleted -> MaterialTheme.colorScheme.primary
                            isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}
