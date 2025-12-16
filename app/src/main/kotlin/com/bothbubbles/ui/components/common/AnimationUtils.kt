package com.bothbubbles.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bothbubbles.ui.theme.MotionTokens
import kotlinx.coroutines.delay

/**
 * Staggered list item wrapper for initial list load animations.
 * Items appear with a slight delay based on their index, creating a cascading effect.
 *
 * @param index The index of this item in the list
 * @param baseDelay Delay per item in milliseconds
 * @param maxDelay Maximum total delay in milliseconds
 * @param maxAnimatedItems Only animate items up to this index
 * @param content The composable content to animate
 */
@Composable
fun StaggeredListItem(
    index: Int,
    baseDelay: Int = 30,
    maxDelay: Int = 200,
    maxAnimatedItems: Int = 15,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    // Skip animation for items beyond maxAnimatedItems
    if (index > maxAnimatedItems) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(0)),
            content = content
        )
        return
    }

    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay((index * baseDelay).coerceAtMost(maxDelay).toLong())
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = MotionTokens.Duration.MEDIUM_2,
                easing = MotionTokens.Easing.EmphasizedDecelerate
            )
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = MotionTokens.Duration.MEDIUM_2,
                easing = MotionTokens.Easing.EmphasizedDecelerate
            ),
            initialOffsetY = { it / 4 }
        ),
        content = content
    )
}

/**
 * Animated entrance for new items that pop in with scale and fade.
 * Suitable for new messages, notifications, or indicators.
 *
 * @param visible Whether the content should be visible
 * @param content The composable content to animate
 */
@Composable
fun PopInAnimation(
    visible: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.8f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(
            animationSpec = tween(MotionTokens.Duration.SHORT_4)
        ),
        exit = scaleOut(
            targetScale = 0.9f,
            animationSpec = tween(MotionTokens.Duration.SHORT_3)
        ) + fadeOut(
            animationSpec = tween(MotionTokens.Duration.SHORT_3)
        ),
        content = content
    )
}

/**
 * Modifier that adds a subtle scale-down effect on press for immediate touch feedback.
 * Material Design 3 recommends subtle (0.95-0.98) scale for touch feedback.
 *
 * @param enabled Whether the effect is enabled
 * @param pressedScale The scale when pressed (default 0.94f)
 * @param includeHaptic Whether to include haptic feedback on press
 * @param onClick Callback when the item is clicked
 */
@Composable
fun Modifier.pressScale(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    includeHaptic: Boolean = true,
    onClick: (() -> Unit)? = null
): Modifier {
    if (!enabled) return this

    var isPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "pressScale"
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    if (includeHaptic) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    val released = tryAwaitRelease()
                    isPressed = false
                    if (released) {
                        onClick?.invoke()
                    }
                }
            )
        }
}

/**
 * Modifier for staggered entrance animation on first appearance.
 * Use this for list items that should animate in with delay.
 *
 * @param index The index of this item (used for stagger delay calculation)
 * @param enabled Whether animation is enabled. When false, items appear instantly.
 * @param baseDelay Delay per item in milliseconds
 * @param maxDelay Maximum total delay
 * @param duration Animation duration in milliseconds
 */
@Composable
fun Modifier.staggeredEntrance(
    index: Int,
    enabled: Boolean = true,
    baseDelay: Int = 30,
    maxDelay: Int = 200,
    duration: Int = MotionTokens.Duration.MEDIUM_2
): Modifier {
    // Skip animation entirely when disabled or for items beyond index 15
    if (!enabled || index > 15) return this

    var hasAppeared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay((index * baseDelay).coerceAtMost(maxDelay).toLong())
        hasAppeared = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (hasAppeared) 1f else 0f,
        animationSpec = tween(duration, easing = MotionTokens.Easing.EmphasizedDecelerate),
        label = "staggerAlpha"
    )

    val translationY by animateFloatAsState(
        targetValue = if (hasAppeared) 0f else 24f,
        animationSpec = tween(duration, easing = MotionTokens.Easing.EmphasizedDecelerate),
        label = "staggerTranslation"
    )

    return this.graphicsLayer {
        this.alpha = alpha
        this.translationY = translationY
    }
}

/**
 * Modifier for animating new messages as they appear in the chat.
 *
 * Only animates when:
 * - shouldAnimate is true (message is new and hasn't been seen)
 * - Message slides up and fades in from the bottom
 *
 * For sent messages: appears INSTANTLY (no animation delay) for responsive UX
 * For received messages: subtle slide-up animation
 *
 * @param shouldAnimate Whether this message should animate (new, unseen message)
 * @param isFromMe Whether this is an outgoing message (instant appearance)
 */
@Composable
fun Modifier.newMessageEntrance(
    shouldAnimate: Boolean,
    isFromMe: Boolean = false
): Modifier {
    // Skip animation entirely when not needed
    if (!shouldAnimate) return this

    // PERF FIX: Sent messages appear INSTANTLY for responsive UX
    // The user expects immediate feedback when they hit send
    // Only incoming messages get the subtle slide-up animation
    if (isFromMe) {
        val entranceTime = System.currentTimeMillis()
        android.util.Log.i("SEND_TRACE", "[ANIM] Outgoing message INSTANT appearance at $entranceTime")
        return this // No animation - instant appearance
    }

    var hasAppeared by remember { mutableStateOf(false) }

    // DEBUG: Log animation state
    android.util.Log.d("MessageAnim", "newMessageEntrance: shouldAnimate=$shouldAnimate hasAppeared=$hasAppeared isFromMe=$isFromMe")

    LaunchedEffect(Unit) {
        android.util.Log.d("MessageAnim", "newMessageEntrance: LaunchedEffect starting animation")
        // Minimal delay to ensure layout is ready
        delay(8)
        hasAppeared = true
        android.util.Log.d("MessageAnim", "newMessageEntrance: hasAppeared set to true")
    }

    // Faster fade-in for incoming messages (100ms instead of 250ms)
    val alpha by animateFloatAsState(
        targetValue = if (hasAppeared) 1f else 0f,
        animationSpec = tween(
            durationMillis = 100, // Fast fade-in
            easing = MotionTokens.Easing.EmphasizedDecelerate
        ),
        label = "newMessageAlpha"
    )

    // Subtle slide up from below (reduced from 40f to 24f for snappier feel)
    val translationY by animateFloatAsState(
        targetValue = if (hasAppeared) 0f else 24f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh // Snappier
        ),
        label = "newMessageTranslation"
    )

    // Subtle scale for incoming messages only
    val scale by animateFloatAsState(
        targetValue = if (hasAppeared) 1f else 0.96f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "newMessageScale"
    )

    return this.graphicsLayer {
        this.alpha = alpha
        this.translationY = translationY
        this.scaleX = scale
        this.scaleY = scale
    }
}

/**
 * Preset enter/exit transitions for popups and menus
 */
object PopupTransitions {
    val enter = scaleIn(
        initialScale = 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    ) + fadeIn(tween(MotionTokens.Duration.SHORT_4))

    val exit = scaleOut(
        targetScale = 0.9f,
        animationSpec = tween(MotionTokens.Duration.SHORT_3)
    ) + fadeOut(tween(MotionTokens.Duration.SHORT_3))
}
