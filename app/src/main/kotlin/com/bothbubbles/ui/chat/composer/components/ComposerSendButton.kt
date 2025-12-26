package com.bothbubbles.ui.chat.composer.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bothbubbles.util.HapticUtils
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.core.design.theme.AppTextStyles
import com.bothbubbles.R
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.ComposerTutorialState
import com.bothbubbles.ui.chat.composer.SendButtonPhase
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.chat.composer.animations.SendModeGestureConfig
import com.bothbubbles.ui.chat.composer.gestures.SendModeGestureCallbacks
import com.bothbubbles.ui.chat.composer.gestures.rememberSendModeGestureState
import com.bothbubbles.ui.chat.composer.gestures.sendModeGesture
import com.bothbubbles.ui.theme.BothBubblesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sin

/**
 * SMS Green color for the send button.
 */
private val SmsGreen = Color(0xFF34C759)

/**
 * Unified send button component with iMessage/SMS mode toggle support.
 *
 * Features:
 * - Pepsi-like dual-color animation on chat load showing both options
 * - Bidirectional swipe gesture to toggle between modes
 * - Visual feedback that follows finger during drag
 * - Spring-based snap animation when threshold is crossed
 * - Tutorial support with persistent split view
 * - MMS label display when applicable
 *
 * Layout follows Google Messages style with the send button outside the
 * input field, providing a clear tap target and room for the mode toggle gesture.
 *
 * @param onClick Called when user taps to send message
 * @param onLongPress Called when user long-presses (for effect picker)
 * @param currentMode Current send mode (SMS or IMESSAGE)
 * @param canToggle Whether toggle is available (both modes supported)
 * @param onModeToggle Called when user swipes to toggle mode, returns true if switch allowed
 * @param isSending Whether a message is currently being sent
 * @param isMmsMode Whether MMS label should be shown
 * @param showRevealAnimation Whether to show the initial dual-color reveal animation
 * @param tutorialState Current tutorial state
 * @param onAnimationPhaseChange Called when animation phase changes
 * @param modifier Modifier for this composable
 */
@Composable
fun ComposerSendButton(
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    currentMode: ChatSendMode,
    canToggle: Boolean,
    onModeToggle: (ChatSendMode) -> Boolean,
    isSending: Boolean,
    isMmsMode: Boolean,
    showRevealAnimation: Boolean = true,
    tutorialState: ComposerTutorialState = ComposerTutorialState.Hidden,
    onAnimationPhaseChange: (SendButtonPhase) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    // Colors from theme
    val bubbleColors = BothBubblesTheme.bubbleColors
    val iMessageBlue = bubbleColors.iMessageSent
    val contentColor = Color.White

    // Determine colors based on current mode
    val primaryColor = if (currentMode == ChatSendMode.SMS) SmsGreen else iMessageBlue
    val secondaryColor = if (currentMode == ChatSendMode.SMS) iMessageBlue else SmsGreen

    // Gesture state
    val gestureState = rememberSendModeGestureState()
    val tutorialActive = tutorialState.isVisible

    // Animation states
    val fillProgress = remember {
        Animatable(if ((showRevealAnimation || tutorialActive) && canToggle) 0f else 1f)
    }
    val wavePhase = remember { Animatable(0f) }

    // Thresholds in pixels
    val swipeRangePx = with(density) { SendModeGestureConfig.SWIPE_RANGE_DP.dp.toPx() }

    // Press feedback animation
    val scale by animateFloatAsState(
        targetValue = if (gestureState.isPressed && !isSending) {
            ComposerMotionTokens.Scale.Pressed
        } else {
            ComposerMotionTokens.Scale.Normal
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "sendButtonScale"
    )

    // Reset fillProgress to show pepsi effect when tutorial becomes active
    LaunchedEffect(tutorialActive) {
        if (tutorialActive && canToggle && fillProgress.value > 0f) {
            fillProgress.snapTo(0f)
        }
    }

    // Reveal animation on first composition
    LaunchedEffect(showRevealAnimation, canToggle, tutorialActive) {
        if ((showRevealAnimation || tutorialActive) && canToggle && fillProgress.value < 1f) {
            onAnimationPhaseChange(SendButtonPhase.LOADING_REVEAL)

            // Animate wave during hold/tutorial phase
            launch {
                while (fillProgress.value < 1f) {
                    wavePhase.animateTo(
                        targetValue = wavePhase.value + (Math.PI * 2).toFloat(),
                        animationSpec = tween(ComposerMotionTokens.Duration.WAVE_LOOP)
                    )
                }
            }

            // Wait for tutorial to complete before filling
            if (tutorialActive) {
                return@LaunchedEffect
            }

            // Hold at split for reveal duration
            delay(SendModeGestureConfig.REVEAL_HOLD_MS.toLong())

            // Fill animation
            onAnimationPhaseChange(SendButtonPhase.SETTLING)
            fillProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SendModeGestureConfig.REVEAL_DURATION_MS - SendModeGestureConfig.REVEAL_HOLD_MS,
                    easing = FastOutSlowInEasing
                )
            )

            onAnimationPhaseChange(SendButtonPhase.IDLE)
        }
    }

    // Continue fill animation when tutorial completes
    LaunchedEffect(tutorialActive) {
        if (!tutorialActive && fillProgress.value < 1f && fillProgress.value > 0f) {
            onAnimationPhaseChange(SendButtonPhase.SETTLING)
            fillProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SendModeGestureConfig.REVEAL_DURATION_MS - SendModeGestureConfig.REVEAL_HOLD_MS,
                    easing = FastOutSlowInEasing
                )
            )
            onAnimationPhaseChange(SendButtonPhase.IDLE)
        }
    }

    // Gesture callbacks
    val gestureCallbacks = remember(onClick, onLongPress, onModeToggle, hapticFeedback) {
        object : SendModeGestureCallbacks {
            override fun onTap() {
                HapticUtils.onTap(hapticFeedback)
                onClick()
            }
            override fun onLongPress() {
                onLongPress()
            }
            override fun onModeToggle(newMode: ChatSendMode): Boolean = onModeToggle(newMode)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .drawBehind {
                val dragProgress = gestureState.dragProgress
                if (canToggle && fillProgress.value < 1f) {
                    // Draw dual-color split during reveal/drag
                    drawPepsiSplit(
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        fillProgress = fillProgress.value,
                        wavePhase = wavePhase.value,
                        dragOffset = gestureState.dragOffset.value / swipeRangePx
                    )
                } else if (canToggle && abs(dragProgress) > 0.001f) {
                    drawDirectionalFill(
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        progress = dragProgress,
                        wavePhase = wavePhase.value
                    )
                } else {
                    // Solid color
                    drawRect(
                        color = if (isSending) primaryColor.copy(alpha = ComposerMotionTokens.Alpha.Disabled) else primaryColor
                    )
                }
            }
            .sendModeGesture(
                state = gestureState,
                currentMode = currentMode,
                canToggle = canToggle,
                isEnabled = !isSending,
                callbacks = gestureCallbacks
            ),
        contentAlignment = Alignment.Center
    ) {
        SendButtonContent(
            isSending = isSending,
            isMmsMode = isMmsMode,
            contentColor = contentColor
        )
    }
}

/**
 * Send button content (icon or progress indicator).
 */
@Composable
private fun SendButtonContent(
    isSending: Boolean,
    isMmsMode: Boolean,
    contentColor: Color
) {
    if (isSending) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = contentColor
        )
    } else {
        if (isMmsMode) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message),
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "MMS",
                    style = AppTextStyles.badgeMicro,
                    color = contentColor
                )
            }
        } else {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.send_message),
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Draws the Pepsi-style dual-color split with wavy divider.
 *
 * This creates the distinctive reveal animation that shows both
 * iMessage and SMS colors before settling to the current mode.
 */
private fun DrawScope.drawPepsiSplit(
    primaryColor: Color,
    secondaryColor: Color,
    fillProgress: Float,
    wavePhase: Float,
    dragOffset: Float
) {
    val width = size.width
    val height = size.height

    // Calculate split position based on fill progress
    // 0 = 50/50 split, 1 = fully filled with primary
    val splitY = height * (0.5f - (fillProgress * 0.5f))

    // Pepsi-inspired continuous wave: cosine arc plus subtle liquid ripple
    val normalizedDrag = dragOffset.coerceIn(-1f, 1f)
    val baseAmplitude = height * 0.28f
    val rippleAmplitude = height * 0.05f
    val dragTilt = height * 0.12f * normalizedDrag
    val rippleFrequency = Math.PI.toFloat() * 2f

    fun liquidWaveY(x: Float): Float {
        val t = (x / width).coerceIn(0f, 1f)
        val centered = t - 0.5f

        // Compose a constant S-curve: slight slope + crest + counter-curve for the Pepsi vibe
        val slopeComponent = centered * baseAmplitude * 0.6f
        val crestComponent = sin(t * Math.PI.toFloat()) * baseAmplitude * 0.9f
        val sBendComponent = sin(t * Math.PI.toFloat() * 2f) * baseAmplitude * 0.35f
        val baseCurve = slopeComponent + crestComponent + sBendComponent

        // Gentle liquid ripple that drifts across the seam
        val ripple = sin(t * rippleFrequency + wavePhase) * rippleAmplitude
        // Drag tilt lets the seam lean toward the direction of the user's swipe
        val tilt = centered * dragTilt

        return (splitY + baseCurve + ripple + tilt).coerceIn(0f, height)
    }

    // Draw secondary color (top)
    val topPath = Path().apply {
        moveTo(0f, 0f)
        lineTo(width, 0f)
        lineTo(width, liquidWaveY(width))

        // Smooth wavy bottom edge with more frequent sampling
        for (x in width.toInt() downTo 0) {
            lineTo(x.toFloat(), liquidWaveY(x.toFloat()))
        }

        close()
    }
    drawPath(topPath, secondaryColor)

    // Draw primary color (bottom)
    val bottomPath = Path().apply {
        moveTo(0f, height)
        lineTo(width, height)
        lineTo(width, liquidWaveY(width))

        // Wavy top edge (matching the top path)
        for (x in width.toInt() downTo 0) {
            lineTo(x.toFloat(), liquidWaveY(x.toFloat()))
        }

        close()
    }
    drawPath(bottomPath, primaryColor)
}

/**
 * Draws the rolling toggle effect during drag.
 *
 * This shows the secondary color emerging from the direction
 * of the drag, creating a fluid mode switch preview.
 */
private fun DrawScope.drawDirectionalFill(
    primaryColor: Color,
    secondaryColor: Color,
    progress: Float,
    wavePhase: Float
) {
    val clamped = progress.coerceIn(-1f, 1f)
    val coverage = abs(clamped).coerceIn(0f, 1f)

    if (coverage <= 0.001f) {
        drawRect(primaryColor)
        return
    }

    if (coverage >= 0.999f) {
        drawRect(secondaryColor)
        return
    }

    val width = size.width
    val height = size.height

    // Paint base layer with current color
    drawRect(color = primaryColor)

    val fromTop = clamped < 0f
    val baseLine = if (fromTop) height * coverage else height * (1f - coverage)
    val amplitude = height * (0.05f + 0.3f * coverage)
    val tilt = clamped * height * 0.06f
    val rippleFrequency = Math.PI.toFloat() * 2f

    fun seamY(x: Float): Float {
        val t = (x / width).coerceIn(0f, 1f)
        val centered = t - 0.5f
        val slopeComponent = centered * 0.6f
        val crestComponent = sin(t * Math.PI.toFloat()) * 0.9f
        val sCurveComponent = sin(t * Math.PI.toFloat() * 2f) * 0.35f
        val structuralShape = (slopeComponent + crestComponent + sCurveComponent) / 1.85f
        val ripple = sin(t * rippleFrequency + wavePhase) * 0.5f
        val offset = (structuralShape + ripple) * amplitude + tilt
        val rawY = baseLine + offset
        return rawY.coerceIn(0f, height)
    }

    val overlayPath = Path().apply {
        if (fromTop) {
            moveTo(0f, 0f)
            lineTo(width, 0f)
            for (x in width.toInt() downTo 0) {
                lineTo(x.toFloat(), seamY(x.toFloat()))
            }
        } else {
            moveTo(0f, height)
            lineTo(width, height)
            for (x in width.toInt() downTo 0) {
                lineTo(x.toFloat(), seamY(x.toFloat()))
            }
        }
        close()
    }

    drawPath(overlayPath, secondaryColor)
}
