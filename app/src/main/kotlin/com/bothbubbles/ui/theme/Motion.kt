package com.bothbubbles.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Easing as ComposeEasing

/**
 * Material Design 3 Motion Tokens
 * https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
 */
object MotionTokens {

    /**
     * Duration tokens following MD3 specifications.
     * Includes both official MD3 names and semantic aliases.
     */
    object Duration {
        // MD3 standard names
        const val SHORT_1 = 50
        const val SHORT_2 = 100
        const val SHORT_3 = 150
        const val SHORT_4 = 200
        const val MEDIUM_1 = 250
        const val MEDIUM_2 = 300
        const val MEDIUM_3 = 350
        const val MEDIUM_4 = 400
        const val LONG_1 = 450
        const val LONG_2 = 500
        const val LONG_3 = 550
        const val LONG_4 = 600
        const val EXTRA_LONG_1 = 700
        const val EXTRA_LONG_2 = 800

        // Semantic aliases for convenience
        const val INSTANT = SHORT_1       // 50ms - micro-interactions
        const val FAST = SHORT_2          // 100ms - quick transitions
        const val QUICK = SHORT_3         // 150ms - slightly slower quick transitions
        const val NORMAL = SHORT_4        // 200ms - standard transitions
        const val MEDIUM = MEDIUM_1       // 250ms - medium transitions
        const val EMPHASIZED = MEDIUM_2   // 300ms - key transitions
        const val COMPLEX = MEDIUM_4      // 400ms - multi-element animations
        const val LONG = LONG_4           // 600ms - dramatic effects
        const val EXTENDED = EXTRA_LONG_2 // 800ms - extended animations
        const val STAGGER = SHORT_1       // 50ms - stagger delay between items
    }

    /**
     * Easing curves following MD3 specifications
     */
    object Easing {
        // Emphasized - for most transitions, asymmetric feel
        val Emphasized: ComposeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate: ComposeEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate: ComposeEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        // Standard - for subtle, utility animations
        val Standard: ComposeEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val StandardDecelerate: ComposeEasing = CubicBezierEasing(0f, 0f, 0f, 1f)
        val StandardAccelerate: ComposeEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

        /** Ease in-out sine for smooth looping animations */
        val EaseInOutSine: ComposeEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
    }

    /**
     * Spring-based animation configurations.
     */
    object Springs {
        /** Snappy spring for quick, responsive interactions */
        val Snappy: SpringSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 800f)

        /** Responsive spring for standard UI feedback */
        val Responsive: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 400f)

        /** Gentle spring for subtle, relaxed motion */
        val Gentle: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 200f)

        /** Bouncy spring for playful, attention-getting motion */
        val Bouncy: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 500f)

        /** Very bouncy spring for high-energy effects */
        val VeryBouncy: SpringSpec<Float> = spring(dampingRatio = 0.5f, stiffness = 400f)

        /** Medium stiffness spring for general purpose animations */
        val Medium: SpringSpec<Float> = spring(stiffness = Spring.StiffnessMedium)

        /** High stiffness spring for quick snapping */
        val Stiff: SpringSpec<Float> = spring(stiffness = Spring.StiffnessHigh)

        /** Medium bouncy spring - slightly bouncy with medium stiffness */
        val MediumBouncy: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    }

    /**
     * Scale values for press/hover effects.
     */
    object Scale {
        const val Normal = 1f
        const val Pressed = 0.88f
        const val Hover = 1.04f
        const val EntranceInitial = 0.8f
        const val PanelSwitch = 0.95f
    }

    /**
     * Alpha values for transparency effects.
     */
    object Alpha {
        const val Opaque = 1f
        const val Hover = 0.9f
        const val Disabled = 0.38f
        const val Overlay = 0.7f
        const val Hint = 0.5f
        const val Scrim = 0.32f
        const val HeavyOverlay = 0.85f
    }

    /**
     * Common dimension constants for animations.
     */
    object Dimension {
        val InputCornerRadius: Dp = 24.dp
        val SendButtonSize: Dp = 48.dp
        val ActionButtonSize: Dp = 32.dp
        val AttachmentRowHeight: Dp = 100.dp
        val SmartReplyRowHeight: Dp = 48.dp
        val PanelHeight: Dp = 280.dp
        val SpotlightRadius: Dp = 40.dp
        val PanelExpandedExtra: Dp = 200.dp
        val DragDismissThreshold: Dp = 120.dp
    }
}

/**
 * Reusable animation specs following MD3 guidelines
 */
object MotionSpecs {

    // Navigation transitions
    fun <T> forwardEnter() = tween<T>(
        durationMillis = MotionTokens.Duration.MEDIUM_4,
        easing = MotionTokens.Easing.EmphasizedDecelerate
    )

    fun <T> forwardExit() = tween<T>(
        durationMillis = MotionTokens.Duration.MEDIUM_2,
        easing = MotionTokens.Easing.EmphasizedAccelerate
    )

    fun <T> backwardEnter() = tween<T>(
        durationMillis = MotionTokens.Duration.MEDIUM_4,
        easing = MotionTokens.Easing.EmphasizedDecelerate
    )

    fun <T> backwardExit() = tween<T>(
        durationMillis = MotionTokens.Duration.MEDIUM_2,
        easing = MotionTokens.Easing.EmphasizedAccelerate
    )

    // List item stagger animation
    fun <T> staggeredItemEnter(index: Int, baseDelay: Int = 30, maxDelay: Int = 200) = tween<T>(
        durationMillis = MotionTokens.Duration.MEDIUM_2,
        delayMillis = (index * baseDelay).coerceAtMost(maxDelay),
        easing = MotionTokens.Easing.EmphasizedDecelerate
    )

    // State change animations
    fun <T> stateChange() = tween<T>(
        durationMillis = MotionTokens.Duration.MEDIUM_1,
        easing = MotionTokens.Easing.Standard
    )

    fun <T> fadeIn() = tween<T>(
        durationMillis = MotionTokens.Duration.SHORT_4,
        easing = MotionTokens.Easing.StandardDecelerate
    )

    fun <T> fadeOut() = tween<T>(
        durationMillis = MotionTokens.Duration.SHORT_3,
        easing = MotionTokens.Easing.StandardAccelerate
    )

    // Spring specs for physics-based animations
    val bouncySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    val gentleSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    )

    val snappySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val responsiveSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )
}
