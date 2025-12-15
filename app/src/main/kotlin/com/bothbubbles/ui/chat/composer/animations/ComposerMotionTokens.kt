package com.bothbubbles.ui.chat.composer.animations

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Easing as ComposeEasing

/**
 * Motion tokens for the chat composer, aligned with Material Design 3 specifications.
 *
 * These tokens provide consistent animation behavior across all composer components,
 * ensuring a cohesive, polished user experience that matches Google Messages.
 *
 * @see <a href="https://m3.material.io/styles/motion">Material Design 3 Motion</a>
 */
object ComposerMotionTokens {

    /**
     * Duration constants for various animation types.
     * Based on MD3 duration scale.
     */
    object Duration {
        /** Micro-interactions like button state changes (50ms) */
        const val INSTANT = 50

        /** Quick transitions like icon swaps (100ms) */
        const val FAST = 100

        /** Standard component transitions (200ms) */
        const val NORMAL = 200

        /** Key emphasized transitions (300ms) */
        const val EMPHASIZED = 300

        /** Complex multi-element animations (400ms) */
        const val COMPLEX = 400

        /** Delay between staggered items (50ms) */
        const val STAGGER = 50

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
     * Spring-based animation configurations.
     * Springs provide natural, physics-based motion.
     */
    object Spring {
        /**
         * Snappy spring for quick, responsive interactions.
         * Use for: button presses, quick toggles.
         */
        val Snappy: SpringSpec<Float> = spring(
            dampingRatio = 0.7f,
            stiffness = 800f
        )

        /**
         * Responsive spring for standard UI feedback.
         * Use for: most component transitions, panel slides.
         */
        val Responsive: SpringSpec<Float> = spring(
            dampingRatio = 0.8f,
            stiffness = 400f
        )

        /**
         * Gentle spring for subtle, relaxed motion.
         * Use for: background elements, secondary animations.
         */
        val Gentle: SpringSpec<Float> = spring(
            dampingRatio = 0.9f,
            stiffness = 200f
        )

        /**
         * Bouncy spring for playful, attention-getting motion.
         * Use for: celebration effects, staggered chip entrance.
         */
        val Bouncy: SpringSpec<Float> = spring(
            dampingRatio = 0.6f,
            stiffness = 500f
        )

        /**
         * Very bouncy spring for high-energy effects.
         * Use for: tutorial completion celebration.
         */
        val VeryBouncy: SpringSpec<Float> = spring(
            dampingRatio = 0.5f,
            stiffness = 400f
        )
    }

    /**
     * Easing curves aligned with MD3 motion specifications.
     */
    object Easing {
        /**
         * Emphasized easing for important transitions.
         * Slow start, fast middle, slow end.
         */
        val Emphasized: ComposeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /**
         * Emphasized decelerate for elements entering the screen.
         * Fast start, very slow end.
         */
        val EmphasizedDecelerate: ComposeEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /**
         * Emphasized accelerate for elements leaving the screen.
         * Slow start, fast end.
         */
        val EmphasizedAccelerate: ComposeEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        /**
         * Standard easing for common transitions.
         */
        val Standard: ComposeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /**
         * Standard decelerate for straightforward enters.
         */
        val StandardDecelerate: ComposeEasing = CubicBezierEasing(0f, 0f, 0f, 1f)

        /**
         * Standard accelerate for straightforward exits.
         */
        val StandardAccelerate: ComposeEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

        /**
         * Ease in-out sine for smooth looping animations.
         * Use for: bouncing arrows, pulsing indicators.
         */
        val EaseInOutSine: ComposeEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
    }

    /**
     * Size and dimension constants for animations.
     */
    object Dimension {
        /** Corner radius for pill/stadium shaped input field */
        val InputCornerRadius: Dp = 24.dp

        /** Size of the send button */
        val SendButtonSize: Dp = 48.dp

        /** Size of inline action buttons (emoji, camera) */
        val ActionButtonSize: Dp = 32.dp

        /** Size of attachment thumbnails (larger for better button visibility) */
        val AttachmentRowHeight: Dp = 100.dp

        /** Height of smart reply chip row */
        val SmartReplyRowHeight: Dp = 48.dp

        /** Height of expandable panels */
        val PanelHeight: Dp = 280.dp

        /** Spotlight radius for tutorial overlay */
        val SpotlightRadius: Dp = 40.dp

        /** Extra height when panel expands (keyboard hidden) */
        val PanelExpandedExtra: Dp = 200.dp

        /** Drag-to-dismiss threshold for panels */
        val DragDismissThreshold: Dp = 120.dp

        /** GIF scroll threshold for keyboard dismiss (~100dp worth of scroll) */
        const val GifScrollThresholdPx: Int = 300
    }

    /**
     * Scale values for press/hover effects.
     */
    object Scale {
        /** Normal scale (no effect) */
        const val Normal = 1f

        /** Pressed state scale */
        const val Pressed = 0.88f

        /** Hover state scale */
        const val Hover = 1.04f

        /** Initial scale for spring entrance */
        const val EntranceInitial = 0.8f

        /** Scale for panel switch transition */
        const val PanelSwitch = 0.95f
    }

    /**
     * Alpha values for transparency effects.
     */
    object Alpha {
        /** Fully opaque */
        const val Opaque = 1f

        /** Slightly transparent for hover states */
        const val Hover = 0.9f

        /** Disabled state */
        const val Disabled = 0.38f

        /** Semi-transparent overlay */
        const val Overlay = 0.7f

        /** Subtle hint transparency */
        const val Hint = 0.5f
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
