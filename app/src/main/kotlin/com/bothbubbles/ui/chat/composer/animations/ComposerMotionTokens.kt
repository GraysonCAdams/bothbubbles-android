package com.bothbubbles.ui.chat.composer.animations

import com.bothbubbles.ui.theme.MotionTokens

/**
 * Motion tokens for the chat composer.
 *
 * @deprecated Use [MotionTokens] directly for new code.
 * This object re-exports from [MotionTokens] for backwards compatibility.
 */
@Deprecated("Use MotionTokens directly", ReplaceWith("MotionTokens", "com.bothbubbles.ui.theme.MotionTokens"))
object ComposerMotionTokens {

    /**
     * Duration constants.
     * @deprecated Use [MotionTokens.Duration] directly.
     */
    object Duration {
        const val INSTANT = MotionTokens.Duration.INSTANT
        const val FAST = MotionTokens.Duration.FAST
        const val NORMAL = MotionTokens.Duration.NORMAL
        const val EMPHASIZED = MotionTokens.Duration.EMPHASIZED
        const val COMPLEX = MotionTokens.Duration.COMPLEX
        const val STAGGER = MotionTokens.Duration.STAGGER

        /** Send button reveal animation total duration */
        const val REVEAL = 1800

        /** Hold duration for reveal split view */
        const val REVEAL_HOLD = 800

        /** Wave animation loop duration */
        const val WAVE_LOOP = 2000

        /** Fill/settle to single color duration */
        const val SETTLE = 1000

        /** Tutorial auto-dismiss delay */
        const val TUTORIAL_AUTO_DISMISS = 1500
    }

    /**
     * Spring configurations.
     * @deprecated Use [MotionTokens.Springs] directly.
     */
    object Spring {
        val Snappy = MotionTokens.Springs.Snappy
        val Responsive = MotionTokens.Springs.Responsive
        val Gentle = MotionTokens.Springs.Gentle
        val Bouncy = MotionTokens.Springs.Bouncy
        val VeryBouncy = MotionTokens.Springs.VeryBouncy
    }

    /**
     * Easing curves.
     * @deprecated Use [MotionTokens.Easing] directly.
     */
    object Easing {
        val Emphasized = MotionTokens.Easing.Emphasized
        val EmphasizedDecelerate = MotionTokens.Easing.EmphasizedDecelerate
        val EmphasizedAccelerate = MotionTokens.Easing.EmphasizedAccelerate
        val Standard = MotionTokens.Easing.Standard
        val StandardDecelerate = MotionTokens.Easing.StandardDecelerate
        val StandardAccelerate = MotionTokens.Easing.StandardAccelerate
        val EaseInOutSine = MotionTokens.Easing.EaseInOutSine
    }

    /**
     * Dimension constants.
     * @deprecated Use [MotionTokens.Dimension] directly.
     */
    object Dimension {
        val InputCornerRadius = MotionTokens.Dimension.InputCornerRadius
        val SendButtonSize = MotionTokens.Dimension.SendButtonSize
        val ActionButtonSize = MotionTokens.Dimension.ActionButtonSize
        val AttachmentRowHeight = MotionTokens.Dimension.AttachmentRowHeight
        val SmartReplyRowHeight = MotionTokens.Dimension.SmartReplyRowHeight
        val PanelHeight = MotionTokens.Dimension.PanelHeight
        val SpotlightRadius = MotionTokens.Dimension.SpotlightRadius
        val PanelExpandedExtra = MotionTokens.Dimension.PanelExpandedExtra
        val DragDismissThreshold = MotionTokens.Dimension.DragDismissThreshold

        /** GIF scroll threshold for keyboard dismiss (~100dp worth of scroll) */
        const val GifScrollThresholdPx: Int = 300
    }

    /**
     * Scale values.
     * @deprecated Use [MotionTokens.Scale] directly.
     */
    object Scale {
        const val Normal = MotionTokens.Scale.Normal
        const val Pressed = MotionTokens.Scale.Pressed
        const val Hover = MotionTokens.Scale.Hover
        const val EntranceInitial = MotionTokens.Scale.EntranceInitial
        const val PanelSwitch = MotionTokens.Scale.PanelSwitch
    }

    /**
     * Alpha values.
     * @deprecated Use [MotionTokens.Alpha] directly.
     */
    object Alpha {
        const val Opaque = MotionTokens.Alpha.Opaque
        const val Hover = MotionTokens.Alpha.Hover
        const val Disabled = MotionTokens.Alpha.Disabled
        const val Overlay = MotionTokens.Alpha.Overlay
        const val Hint = MotionTokens.Alpha.Hint
    }
}

/**
 * Configuration for the send mode toggle gesture.
 */
object SendModeGestureConfig {
    /** Total vertical swipe range in dp */
    const val SWIPE_RANGE_DP = 60f

    /** Percentage of swipe that triggers mode switch (75%) */
    const val SNAP_THRESHOLD_PERCENT = 0.75f

    /** Minimum dp to determine vertical vs horizontal gesture */
    const val DETECTION_DISTANCE_DP = 12f

    /** Vertical must exceed horizontal by this ratio */
    const val DIRECTION_RATIO = 1.5f

    /** Duration of reveal animation (ms) */
    const val REVEAL_DURATION_MS = 1800

    /** Hold duration for split view before settling (ms) */
    const val REVEAL_HOLD_MS = 800

    /** Wave animation loop duration (ms) */
    const val WAVE_LOOP_MS = 2000

    /** Fill animation duration (ms) */
    const val SETTLE_DURATION_MS = 1000
}
