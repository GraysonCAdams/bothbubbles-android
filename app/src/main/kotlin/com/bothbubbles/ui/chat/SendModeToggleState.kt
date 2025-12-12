package com.bothbubbles.ui.chat

/**
 * Represents the current state of the send mode toggle gesture on the send button.
 * Used to track user interaction and drive animations.
 */
sealed class SendModeToggleState {
    /**
     * No gesture active, button in resting state.
     */
    data object Idle : SendModeToggleState()

    /**
     * User is actively dragging the toggle.
     *
     * @param progress Normalized drag progress from -1.0 to 1.0
     *                 Negative = dragging up, Positive = dragging down
     * @param hasPassedThreshold Whether the drag has passed the 75% snap threshold
     */
    data class Dragging(
        val progress: Float,
        val hasPassedThreshold: Boolean
    ) : SendModeToggleState()

    /**
     * Animating to final position after gesture release.
     *
     * @param willSwitch True if the mode will switch, false if snapping back
     */
    data class Snapping(
        val willSwitch: Boolean
    ) : SendModeToggleState()
}

/**
 * Animation phase for the send button's visual state.
 */
enum class SendButtonAnimationPhase {
    /**
     * Resting state, showing solid current mode color.
     */
    IDLE,

    /**
     * Initial dual-color split animation showing both options.
     * Displayed on chat open for eligible chats.
     */
    LOADING_REVEAL,

    /**
     * Current mode color "filling" from bottom after reveal.
     */
    SETTLING,

    /**
     * User is actively dragging - colors roll with finger movement.
     */
    DRAGGING,

    /**
     * Animating to final position after threshold crossed.
     */
    SNAPPING,

    /**
     * Tutorial overlay is active (first time only).
     */
    TUTORIAL
}

/**
 * State for the interactive tutorial overlay.
 */
enum class TutorialState {
    /**
     * Tutorial has never been shown.
     */
    NOT_SHOWN,

    /**
     * Tutorial active, showing "swipe up" instruction.
     * User must swipe up to proceed.
     */
    STEP_1_SWIPE_UP,

    /**
     * User completed first swipe, now showing "swipe back" instruction.
     * User must swipe back to complete tutorial.
     */
    STEP_2_SWIPE_BACK,

    /**
     * Tutorial completed, will never show again.
     */
    COMPLETED
}

/**
 * Configuration for the send mode toggle animation.
 *
 * @param phase Current animation phase
 * @param fillProgress Progress of the fill animation (0 = split, 1 = filled)
 * @param wavePhase Phase of the wavy divider animation (radians)
 * @param dragProgress Current drag offset normalized (-1 to 1)
 */
data class SendModeAnimationConfig(
    val phase: SendButtonAnimationPhase = SendButtonAnimationPhase.IDLE,
    val fillProgress: Float = 1f,
    val wavePhase: Float = 0f,
    val dragProgress: Float = 0f
)

/**
 * Threshold constants for the toggle gesture.
 */
object SendModeToggleConstants {
    /**
     * Total swipe range in dp for the toggle gesture.
     */
    const val SWIPE_RANGE_DP = 60f

    /**
     * Percentage of swipe range that triggers mode switch (75%).
     */
    const val SNAP_THRESHOLD_PERCENT = 0.75f

    /**
     * Minimum movement in dp to determine gesture intent (vertical vs horizontal).
     */
    const val DETECTION_DISTANCE_DP = 12f

    /**
     * Ratio for vertical dominance detection (vertical > horizontal * ratio).
     */
    const val DIRECTION_RATIO = 1.5f

    /**
     * Duration for the initial reveal animation in milliseconds.
     */
    const val REVEAL_DURATION_MS = 1800

    /**
     * Duration to hold the split view before filling.
     */
    const val REVEAL_HOLD_MS = 800

    /**
     * Duration for the placeholder text flip animation (per half).
     */
    const val TEXT_FLIP_HALF_MS = 125
}
