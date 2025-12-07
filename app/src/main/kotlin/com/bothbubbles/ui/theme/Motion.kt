package com.bothbubbles.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Material Design 3 Motion Tokens
 * https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
 */
object MotionTokens {

    /**
     * Duration tokens following MD3 specifications
     */
    object Duration {
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
    }

    /**
     * Easing curves following MD3 specifications
     */
    object Easing {
        // Emphasized - for most transitions, asymmetric feel
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        // Standard - for subtle, utility animations
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
        val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
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
