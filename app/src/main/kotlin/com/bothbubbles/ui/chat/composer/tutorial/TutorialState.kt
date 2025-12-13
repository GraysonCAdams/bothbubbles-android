package com.bothbubbles.ui.chat.composer.tutorial

import com.bothbubbles.ui.chat.TutorialState
import com.bothbubbles.ui.chat.composer.ComposerTutorialState
import com.bothbubbles.ui.chat.composer.TutorialStep

/**
 * Extension functions and utilities for bridging between the legacy
 * [TutorialState] enum and the new [ComposerTutorialState] sealed class.
 *
 * This allows gradual migration while maintaining backwards compatibility
 * with existing ChatViewModel code.
 */

/**
 * Convert a legacy [TutorialState] enum to the new [ComposerTutorialState].
 */
fun TutorialState.toComposerTutorialState(): ComposerTutorialState {
    return when (this) {
        TutorialState.NOT_SHOWN -> ComposerTutorialState.Hidden
        TutorialState.STEP_1_SWIPE_UP -> ComposerTutorialState.Active(TutorialStep.INTRO)
        TutorialState.STEP_2_SWIPE_BACK -> ComposerTutorialState.Active(TutorialStep.CONFIRM)
        TutorialState.COMPLETED -> ComposerTutorialState.Completing
    }
}

/**
 * Convert the new [ComposerTutorialState] back to a legacy [TutorialState] enum.
 */
fun ComposerTutorialState.toLegacyTutorialState(): TutorialState {
    return when (this) {
        is ComposerTutorialState.Hidden -> TutorialState.NOT_SHOWN
        is ComposerTutorialState.Active -> when (step) {
            TutorialStep.INTRO -> TutorialState.STEP_1_SWIPE_UP
            TutorialStep.CONFIRM -> TutorialState.STEP_2_SWIPE_BACK
            TutorialStep.COMPLETE -> TutorialState.COMPLETED
        }
        is ComposerTutorialState.Completing -> TutorialState.COMPLETED
    }
}

/**
 * Get the next legacy [TutorialState] after the current state.
 *
 * @return The next state, or COMPLETED if already at the end
 */
fun TutorialState.nextState(): TutorialState {
    return when (this) {
        TutorialState.NOT_SHOWN -> TutorialState.STEP_1_SWIPE_UP
        TutorialState.STEP_1_SWIPE_UP -> TutorialState.STEP_2_SWIPE_BACK
        TutorialState.STEP_2_SWIPE_BACK -> TutorialState.COMPLETED
        TutorialState.COMPLETED -> TutorialState.COMPLETED
    }
}

/**
 * Check if the tutorial is currently showing (either step active).
 */
val TutorialState.isActive: Boolean
    get() = this == TutorialState.STEP_1_SWIPE_UP || this == TutorialState.STEP_2_SWIPE_BACK
