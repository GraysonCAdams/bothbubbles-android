package com.bothbubbles.ui.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.R
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.SendButtonAnimationPhase
import com.bothbubbles.ui.chat.SendModeAnimationConfig
import com.bothbubbles.ui.chat.SendModeToggleConstants
import com.bothbubbles.ui.chat.SendModeToggleState
import com.bothbubbles.ui.theme.BothBubblesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SMS Green color for the send button.
 */
private val SmsGreen = Color(0xFF34C759)

/**
 * Enhanced send button with SMS/iMessage toggle capability.
 *
 * Features:
 * - Pepsi-like dual-color animation on chat load showing both options
 * - Bidirectional swipe gesture to toggle between modes
 * - Visual feedback that follows finger during drag
 * - Spring-based snap animation when threshold is crossed
 *
 * @param onClick Called when user taps to send message
 * @param onLongPress Called when user long-presses (for effect picker)
 * @param currentMode Current send mode (SMS or IMESSAGE)
 * @param canToggle Whether toggle is available (both modes supported)
 * @param onModeToggle Called when user swipes to toggle mode, returns true if switch allowed
 * @param isSending Whether a message is currently being sent
 * @param isMmsMode Whether MMS label should be shown
 * @param showRevealAnimation Whether to show the initial dual-color reveal animation
 * @param animationConfig Current animation configuration
 * @param onAnimationConfigChange Called to update animation config
 * @param modifier Modifier for this composable
 */
@Composable
fun SendModeToggleButton(
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    currentMode: ChatSendMode,
    canToggle: Boolean,
    onModeToggle: (ChatSendMode) -> Boolean,
    isSending: Boolean,
    isMmsMode: Boolean,
    showRevealAnimation: Boolean = true,
    tutorialActive: Boolean = false,
    animationConfig: SendModeAnimationConfig = SendModeAnimationConfig(),
    onAnimationConfigChange: (SendModeAnimationConfig) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // Colors
    val bubbleColors = BothBubblesTheme.bubbleColors
    val iMessageBlue = bubbleColors.iMessageSent
    val contentColor = Color.White

    // Determine colors based on current mode
    val primaryColor = if (currentMode == ChatSendMode.SMS) SmsGreen else iMessageBlue
    val secondaryColor = if (currentMode == ChatSendMode.SMS) iMessageBlue else SmsGreen

    // Animation states
    val fillProgress = remember { Animatable(if ((showRevealAnimation || tutorialActive) && canToggle) 0f else 1f) }
    val wavePhase = remember { Animatable(0f) }
    val dragOffset = remember { Animatable(0f) }
    var hasTriggeredThresholdHaptic by remember { mutableStateOf(false) }
    var toggleState by remember { mutableStateOf<SendModeToggleState>(SendModeToggleState.Idle) }

    // Reset fillProgress to show pepsi effect when tutorial becomes active
    // This handles cases where:
    // 1. fillProgress was initialized to 1f because conditions weren't met initially
    // 2. The fill animation already completed before tutorial state was set
    LaunchedEffect(tutorialActive) {
        if (tutorialActive && canToggle && fillProgress.value > 0f) {
            // Reset to show the dual-color split during tutorial
            fillProgress.snapTo(0f)
        }
    }

    // Thresholds in pixels
    val swipeRangePx = with(density) { SendModeToggleConstants.SWIPE_RANGE_DP.dp.toPx() }
    val detectionDistancePx = with(density) { SendModeToggleConstants.DETECTION_DISTANCE_DP.dp.toPx() }

    // Press feedback animation
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !isSending) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "sendButtonScale"
    )

    // Reveal animation on first composition - pauses during tutorial
    LaunchedEffect(showRevealAnimation, canToggle, tutorialActive) {
        if ((showRevealAnimation || tutorialActive) && canToggle && fillProgress.value < 1f) {
            onAnimationConfigChange(animationConfig.copy(phase = SendButtonAnimationPhase.LOADING_REVEAL))

            // Animate wave during hold/tutorial phase
            launch {
                while (fillProgress.value < 1f) {
                    wavePhase.animateTo(
                        targetValue = wavePhase.value + (Math.PI * 2).toFloat(),
                        animationSpec = tween(2000)
                    )
                }
            }

            // Wait for tutorial to complete before filling
            if (tutorialActive) {
                // Keep the split visible while tutorial is active
                // The fill will continue when tutorialActive becomes false
                return@LaunchedEffect
            }

            // Hold at split for reveal duration
            delay(SendModeToggleConstants.REVEAL_HOLD_MS.toLong())

            // Fill animation
            onAnimationConfigChange(animationConfig.copy(phase = SendButtonAnimationPhase.SETTLING))
            fillProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SendModeToggleConstants.REVEAL_DURATION_MS - SendModeToggleConstants.REVEAL_HOLD_MS,
                    easing = FastOutSlowInEasing
                )
            )

            onAnimationConfigChange(animationConfig.copy(phase = SendButtonAnimationPhase.IDLE, fillProgress = 1f))
        }
    }

    // Continue fill animation when tutorial completes
    LaunchedEffect(tutorialActive) {
        if (!tutorialActive && fillProgress.value < 1f && fillProgress.value > 0f) {
            // Tutorial just completed, fill the button
            onAnimationConfigChange(animationConfig.copy(phase = SendButtonAnimationPhase.SETTLING))
            fillProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SendModeToggleConstants.REVEAL_DURATION_MS - SendModeToggleConstants.REVEAL_HOLD_MS,
                    easing = FastOutSlowInEasing
                )
            )
            onAnimationConfigChange(animationConfig.copy(phase = SendButtonAnimationPhase.IDLE, fillProgress = 1f))
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
                val dragProgress = (dragOffset.value / swipeRangePx).coerceIn(-1f, 1f)
                if (canToggle && fillProgress.value < 1f) {
                    // Draw dual-color split during reveal/drag
                    drawPepsiSplit(
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        fillProgress = fillProgress.value,
                        wavePhase = wavePhase.value,
                        dragOffset = dragOffset.value / swipeRangePx
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
                        color = if (isSending) primaryColor.copy(alpha = 0.38f) else primaryColor
                    )
                }
            }
            .pointerInput(isSending, canToggle) {
                if (isSending) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    var cumulativeX = 0f
                    var cumulativeY = 0f
                    var gestureIntent: GestureIntent = GestureIntent.UNDETERMINED
                    val pressStartTime = System.currentTimeMillis()
                    hasTriggeredThresholdHaptic = false
                    isPressed = true

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break

                            if (!change.pressed) {
                                // Handle release based on intent
                                isPressed = false
                                when (gestureIntent) {
                                    GestureIntent.VERTICAL_SWIPE -> {
                                        val normalizedProgress = (dragOffset.value / swipeRangePx).coerceIn(-1f, 1f)
                                        val hasPassedThreshold = abs(normalizedProgress) >= SendModeToggleConstants.SNAP_THRESHOLD_PERCENT

                                        if (hasPassedThreshold && canToggle) {
                                            // Switch to other mode
                                            val newMode = if (currentMode == ChatSendMode.SMS) {
                                                ChatSendMode.IMESSAGE
                                            } else {
                                                ChatSendMode.SMS
                                            }

                                            val direction = if (normalizedProgress < 0f) -1f else 1f
                                            val targetOffset = direction * swipeRangePx

                                            toggleState = SendModeToggleState.Snapping(willSwitch = true)
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)

                                            coroutineScope.launch {
                                                dragOffset.animateTo(
                                                    targetValue = targetOffset,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessMedium
                                                    )
                                                )

                                                val didSwitch = onModeToggle(newMode)

                                                if (!didSwitch) {
                                                    dragOffset.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = spring(
                                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                                            stiffness = Spring.StiffnessMedium
                                                        )
                                                    )
                                                } else {
                                                    dragOffset.snapTo(0f)
                                                }

                                                toggleState = SendModeToggleState.Idle
                                            }
                                        } else {
                                            // Snap back
                                            toggleState = SendModeToggleState.Snapping(willSwitch = false)
                                            coroutineScope.launch {
                                                dragOffset.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessMedium
                                                    )
                                                )
                                                toggleState = SendModeToggleState.Idle
                                            }
                                        }
                                    }

                                    GestureIntent.TAP_OR_HOLD -> {
                                        val elapsed = System.currentTimeMillis() - pressStartTime
                                        if (elapsed < 400) {
                                            onClick()
                                        } else {
                                            onLongPress()
                                        }
                                    }

                                    GestureIntent.UNDETERMINED -> {
                                        // Short tap
                                        onClick()
                                    }
                                }
                                break
                            }

                            val dragDelta = change.positionChange()
                            cumulativeX += dragDelta.x
                            cumulativeY += dragDelta.y

                            // Determine intent once moved enough
                            if (gestureIntent == GestureIntent.UNDETERMINED) {
                                val totalDistance = sqrt(cumulativeX * cumulativeX + cumulativeY * cumulativeY)
                                if (totalDistance >= detectionDistancePx) {
                                    gestureIntent = if (abs(cumulativeY) > abs(cumulativeX) * SendModeToggleConstants.DIRECTION_RATIO) {
                                        GestureIntent.VERTICAL_SWIPE
                                    } else {
                                        GestureIntent.TAP_OR_HOLD
                                    }
                                }
                            }

                            // Handle vertical swipe
                            if (gestureIntent == GestureIntent.VERTICAL_SWIPE && canToggle) {
                                change.consume()

                                coroutineScope.launch {
                                    val newOffset = (dragOffset.value + dragDelta.y)
                                        .coerceIn(-swipeRangePx, swipeRangePx)
                                    dragOffset.snapTo(newOffset)

                                    // Update toggle state
                                    val progress = newOffset / swipeRangePx
                                    val hasPassedThreshold = abs(progress) >= SendModeToggleConstants.SNAP_THRESHOLD_PERCENT

                                    toggleState = SendModeToggleState.Dragging(
                                        progress = progress,
                                        hasPassedThreshold = hasPassedThreshold
                                    )

                                    // Haptic at threshold crossing
                                    if (hasPassedThreshold && !hasTriggeredThresholdHaptic) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        hasTriggeredThresholdHaptic = true
                                    } else if (!hasPassedThreshold && hasTriggeredThresholdHaptic) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        hasTriggeredThresholdHaptic = false
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        isPressed = false
                        coroutineScope.launch {
                            dragOffset.animateTo(0f, spring())
                            toggleState = SendModeToggleState.Idle
                        }
                        throw e
                    }
                }
            },
        contentAlignment = Alignment.Center
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
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        fontSize = 8.sp
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
}

/**
 * Gesture intent for distinguishing vertical swipe from tap/hold.
 */
private enum class GestureIntent {
    UNDETERMINED,
    VERTICAL_SWIPE,
    TAP_OR_HOLD
}

/**
 * Draws the Pepsi-style dual-color split with wavy divider.
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
