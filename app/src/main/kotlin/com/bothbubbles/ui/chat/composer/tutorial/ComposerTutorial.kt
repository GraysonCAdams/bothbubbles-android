package com.bothbubbles.ui.chat.composer.tutorial

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.chat.composer.ComposerTutorialState
import com.bothbubbles.ui.chat.composer.GestureDirection
import com.bothbubbles.ui.chat.composer.TutorialStep
import kotlinx.coroutines.delay

/**
 * Main tutorial overlay for the send mode toggle feature.
 *
 * This component orchestrates the complete tutorial flow:
 * 1. INTRO: Shows spotlight on send button with bidirectional arrow hint
 * 2. CONFIRM: After first swipe, prompts user to swipe back
 * 3. COMPLETE: Shows success celebration before dismissing
 *
 * The tutorial is interactive - users must actually perform the gestures
 * to progress. The overlay cannot be dismissed until completed.
 *
 * Usage:
 * ```kotlin
 * Box {
 *     // Your content (ChatComposer)
 *
 *     ComposerTutorial(
 *         tutorialState = viewModel.tutorialState,
 *         sendButtonBounds = sendButtonBounds,
 *         onStepComplete = { step -> viewModel.onTutorialStepComplete(step) },
 *         onDismiss = { viewModel.dismissTutorial() }
 *     )
 * }
 * ```
 *
 * @param tutorialState Current state of the tutorial
 * @param sendButtonBounds Bounds of the send button for spotlight positioning
 * @param onStepComplete Called when user completes a tutorial step
 * @param onDismiss Called when tutorial should be dismissed (after completion)
 * @param modifier Modifier for this composable
 */
@Composable
fun ComposerTutorial(
    tutorialState: ComposerTutorialState,
    sendButtonBounds: Rect,
    onStepComplete: (TutorialStep) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = tutorialState.isVisible
    val currentStep = when (tutorialState) {
        is ComposerTutorialState.Active -> tutorialState.step
        is ComposerTutorialState.Completing -> TutorialStep.COMPLETE
        else -> null
    }
    val isCompleting = tutorialState is ComposerTutorialState.Completing

    // Auto-dismiss after completion animation
    LaunchedEffect(isCompleting) {
        if (isCompleting) {
            delay(1500) // Show celebration for 1.5 seconds
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Spotlight overlay with scrim
            TutorialSpotlight(
                isVisible = isVisible,
                targetBounds = sendButtonBounds,
                scrimAlpha = 0.7f,
                spotlightPadding = 12f,
                onDismiss = null // Tutorial cannot be skipped
            ) {
                // Content displayed on the spotlight
                currentStep?.let { step ->
                    TutorialOverlayContent(
                        step = step,
                        sendButtonBounds = sendButtonBounds,
                        isCompleting = isCompleting
                    )
                }
            }
        }
    }
}

/**
 * Content displayed within the tutorial overlay.
 *
 * Positions the instruction card and gesture hint arrows relative
 * to the send button spotlight.
 */
@Composable
private fun TutorialOverlayContent(
    step: TutorialStep,
    sendButtonBounds: Rect,
    isCompleting: Boolean
) {
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        // Gesture hint arrow positioned above the send button
        if (!isCompleting && step.gestureDirection != GestureDirection.NONE) {
            GestureHintArrow(
                direction = step.gestureDirection,
                showBidirectional = step == TutorialStep.INTRO,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = with(density) {
                            // Center arrow on button (26dp from right edge)
                            (sendButtonBounds.center.x - sendButtonBounds.width / 2 - 14.dp.toPx()).toDp()
                        }.coerceAtLeast(10.dp),
                        bottom = with(density) {
                            // Position above button spotlight
                            (sendButtonBounds.height + 60.dp.toPx()).toDp()
                        }
                    )
            )
        }

        // Instruction card positioned to the left of the button
        TutorialCard(
            step = step,
            isVisible = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = with(density) {
                        // Position above the button row
                        (sendButtonBounds.height + 100.dp.toPx()).toDp()
                    },
                    start = 32.dp,
                    end = 80.dp // Leave room for button
                )
        )

        // Progress dots at bottom center (only during active steps)
        if (!isCompleting) {
            StepProgressDots(
                currentStep = step.ordinal,
                totalSteps = TutorialStep.entries.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) {
                        (sendButtonBounds.height + 40.dp.toPx()).toDp()
                    })
            )
        }
    }
}

/**
 * Manager for tutorial state transitions.
 *
 * Provides logic for determining when and how to show the tutorial,
 * and handles step progression based on user interactions.
 */
object TutorialManager {

    /**
     * Determines if the tutorial should be shown for this chat.
     *
     * The tutorial is shown when:
     * - User has not completed the tutorial before
     * - The chat supports mode toggle (both iMessage and SMS available)
     * - This is the first message attempt in an eligible chat
     *
     * @param hasCompletedTutorial Whether the user has completed the tutorial before
     * @param canToggleSendMode Whether the chat supports mode switching
     * @return true if the tutorial should be shown
     */
    fun shouldShowTutorial(
        hasCompletedTutorial: Boolean,
        canToggleSendMode: Boolean
    ): Boolean {
        return !hasCompletedTutorial && canToggleSendMode
    }

    /**
     * Gets the next step after the current step is completed.
     *
     * @param currentStep The step that was just completed
     * @return The next step, or null if tutorial is complete
     */
    fun getNextStep(currentStep: TutorialStep): TutorialStep? {
        return when (currentStep) {
            TutorialStep.INTRO -> TutorialStep.CONFIRM
            TutorialStep.CONFIRM -> TutorialStep.COMPLETE
            TutorialStep.COMPLETE -> null
        }
    }

    /**
     * Determines if the given mode change matches the expected step direction.
     *
     * For INTRO step (starting in iMessage mode): swipe up to SMS
     * For CONFIRM step (now in SMS mode): swipe down back to iMessage
     *
     * @param step Current tutorial step
     * @param switchedToSms Whether the mode switched to SMS (true) or iMessage (false)
     * @return true if this completes the current step
     */
    fun doesModeChangeCompleteStep(
        step: TutorialStep,
        switchedToSms: Boolean
    ): Boolean {
        return when (step) {
            TutorialStep.INTRO -> switchedToSms // Expect switch to SMS
            TutorialStep.CONFIRM -> !switchedToSms // Expect switch back to iMessage
            TutorialStep.COMPLETE -> false // No mode change expected
        }
    }
}

/**
 * Composable helper to track send button bounds for the tutorial.
 *
 * Use this modifier on the send button to get its position for the spotlight.
 *
 * @param onBoundsChanged Called when the button's bounds change
 */
@Composable
fun Modifier.trackSendButtonBounds(
    onBoundsChanged: (Rect) -> Unit
): Modifier {
    return this.onGloballyPositioned { coordinates ->
        onBoundsChanged(coordinates.boundsInRoot())
    }
}
