package com.bothbubbles.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.core.design.theme.AppTextStyles
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
import kotlin.math.sqrt

/**
 * MD3 Squircle corner radius for the send button.
 * Uses 16dp to match FAB styling per Material Design 3 guidelines.
 */
private val SquircleCornerRadius = 16.dp

/**
 * Enhanced send button with SMS/iMessage toggle capability.
 *
 * MD3 Features:
 * - Squircle shape (rounded rectangle with 16dp radius) matching FAB styling
 * - Visual affordance with chevron indicators hinting at vertical interaction
 * - Long-press menu for alternative mode switching
 * - Container transform animation with circular reveal
 * - Enhanced haptics with SEGMENT_FREQUENT_TICK during drag
 * - Mode-specific icon badging for accessibility (SMS badge on icon)
 *
 * Interaction patterns:
 * - Tap: Send message
 * - Vertical swipe: Toggle between iMessage/SMS modes
 * - Long press: Open dropdown menu with mode options
 *
 * @param onClick Called when user taps to send message
 * @param onLongPress Called when user long-presses (for effect picker in iMessage mode)
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

    // Colors from theme
    val bubbleColors = BothBubblesTheme.bubbleColors
    val iMessageColor = bubbleColors.sendButtonIMessage
    val smsColor = bubbleColors.sendButtonSms
    val contentColor = Color.White

    // Determine colors based on current mode
    val primaryColor = if (currentMode == ChatSendMode.SMS) smsColor else iMessageColor
    val secondaryColor = if (currentMode == ChatSendMode.SMS) iMessageColor else smsColor

    // Animation states
    val revealProgress = remember { Animatable(if ((showRevealAnimation || tutorialActive) && canToggle) 0f else 1f) }
    val dragOffset = remember { Animatable(0f) }
    var hasTriggeredThresholdHaptic by remember { mutableStateOf(false) }
    var toggleState by remember { mutableStateOf<SendModeToggleState>(SendModeToggleState.Idle) }

    // Long-press menu state
    var showModeMenu by remember { mutableStateOf(false) }

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

    // Chevron bounce animation for visual affordance
    val chevronOffset by animateFloatAsState(
        targetValue = if (canToggle && !isSending && revealProgress.value >= 1f) 2f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "chevronOffset"
    )

    // Reset revealProgress for tutorial
    LaunchedEffect(tutorialActive) {
        if (tutorialActive && canToggle && revealProgress.value > 0f) {
            revealProgress.snapTo(0f)
        }
    }

    // Reveal animation on first composition
    LaunchedEffect(showRevealAnimation, canToggle, tutorialActive) {
        if ((showRevealAnimation || tutorialActive) && canToggle && revealProgress.value < 1f) {
            onAnimationConfigChange(animationConfig.copy(phase = SendButtonAnimationPhase.LOADING_REVEAL))

            // Wait for tutorial to complete before filling
            if (tutorialActive) {
                return@LaunchedEffect
            }

            // Hold at split for reveal duration
            delay(SendModeToggleConstants.REVEAL_HOLD_MS.toLong())

            // Fill animation - circular reveal from center
            onAnimationConfigChange(animationConfig.copy(phase = SendButtonAnimationPhase.SETTLING))
            revealProgress.animateTo(
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
        if (!tutorialActive && revealProgress.value < 1f && revealProgress.value > 0f) {
            onAnimationConfigChange(animationConfig.copy(phase = SendButtonAnimationPhase.SETTLING))
            revealProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SendModeToggleConstants.REVEAL_DURATION_MS - SendModeToggleConstants.REVEAL_HOLD_MS,
                    easing = FastOutSlowInEasing
                )
            )
            onAnimationConfigChange(animationConfig.copy(phase = SendButtonAnimationPhase.IDLE, fillProgress = 1f))
        }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(40.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(SquircleCornerRadius))
                .drawBehind {
                    val dragProgress = (dragOffset.value / swipeRangePx).coerceIn(-1f, 1f)

                    if (canToggle && revealProgress.value < 1f) {
                        // Circular reveal from center during initial animation
                        val maxRadius = sqrt(size.width * size.width + size.height * size.height)
                        val currentRadius = maxRadius * revealProgress.value
                        val center = Offset(size.width / 2f, size.height / 2f)

                        // Draw secondary color as background
                        drawRect(color = secondaryColor)

                        // Draw primary color as circular reveal
                        drawCircle(
                            color = if (isSending) primaryColor.copy(alpha = 0.38f) else primaryColor,
                            radius = currentRadius,
                            center = center
                        )
                    } else if (canToggle && abs(dragProgress) > 0.001f) {
                        // Directional fill during drag
                        drawDirectionalReveal(
                            primaryColor = primaryColor,
                            secondaryColor = secondaryColor,
                            progress = dragProgress
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
                        var lastTickTime = 0L

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break

                                if (!change.pressed) {
                                    isPressed = false
                                    when (gestureIntent) {
                                        GestureIntent.VERTICAL_SWIPE -> {
                                            val normalizedProgress = (dragOffset.value / swipeRangePx).coerceIn(-1f, 1f)
                                            val hasPassedThreshold = abs(normalizedProgress) >= SendModeToggleConstants.SNAP_THRESHOLD_PERCENT

                                            if (hasPassedThreshold && canToggle) {
                                                val newMode = if (currentMode == ChatSendMode.SMS) {
                                                    ChatSendMode.IMESSAGE
                                                } else {
                                                    ChatSendMode.SMS
                                                }

                                                val direction = if (normalizedProgress < 0f) -1f else 1f
                                                val targetOffset = direction * swipeRangePx

                                                toggleState = SendModeToggleState.Snapping(willSwitch = true)
                                                // Use CONFIRM haptic for mode switch
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
                                            } else if (canToggle) {
                                                // Long press opens mode menu when toggle available
                                                showModeMenu = true
                                            } else {
                                                // Long press for effects in iMessage mode
                                                onLongPress()
                                            }
                                        }

                                        GestureIntent.UNDETERMINED -> {
                                            onClick()
                                        }
                                    }
                                    break
                                }

                                val dragDelta = change.positionChange()
                                cumulativeX += dragDelta.x
                                cumulativeY += dragDelta.y

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

                                if (gestureIntent == GestureIntent.VERTICAL_SWIPE && canToggle) {
                                    change.consume()

                                    coroutineScope.launch {
                                        val newOffset = (dragOffset.value + dragDelta.y)
                                            .coerceIn(-swipeRangePx, swipeRangePx)
                                        dragOffset.snapTo(newOffset)

                                        val progress = newOffset / swipeRangePx
                                        val hasPassedThreshold = abs(progress) >= SendModeToggleConstants.SNAP_THRESHOLD_PERCENT

                                        toggleState = SendModeToggleState.Dragging(
                                            progress = progress,
                                            hasPassedThreshold = hasPassedThreshold
                                        )

                                        // Tick haptic feedback during drag (every ~50ms)
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastTickTime > 50 && abs(dragDelta.y) > 1f) {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            lastTickTime = currentTime
                                        }

                                        // Stronger haptic at threshold crossing
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Visual affordance: subtle chevron above icon when toggle available
                    if (canToggle && revealProgress.value >= 1f) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.5f),
                            modifier = Modifier
                                .size(10.dp)
                                .offset { IntOffset(0, -chevronOffset.toInt()) }
                        )
                    }

                    // Main icon with mode indicator
                    AnimatedContent(
                        targetState = currentMode,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.8f))
                                .togetherWith(fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.8f))
                        },
                        label = "sendIcon"
                    ) { mode ->
                        if (isMmsMode) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.send_message),
                                    tint = contentColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "MMS",
                                    style = AppTextStyles.badgeTiny,
                                    color = contentColor
                                )
                            }
                        } else if (mode == ChatSendMode.SMS) {
                            // SMS mode: Send icon with small SMS badge for accessibility
                            Box {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.send_message),
                                    tint = contentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                // Small SMS indicator badge
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = 4.dp, y = 4.dp)
                                        .size(8.dp)
                                        .background(contentColor, RoundedCornerShape(2.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "S",
                                        style = AppTextStyles.badgeMinimal,
                                        color = smsColor
                                    )
                                }
                            }
                        } else {
                            // iMessage mode: Clean send icon
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send_message),
                                tint = contentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Visual affordance: subtle chevron below icon when toggle available
                    if (canToggle && revealProgress.value >= 1f) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.5f),
                            modifier = Modifier
                                .size(10.dp)
                                .offset { IntOffset(0, chevronOffset.toInt()) }
                        )
                    }
                }
            }
        }

        // Long-press dropdown menu for mode switching
        DropdownMenu(
            expanded = showModeMenu,
            onDismissRequest = { showModeMenu = false },
            offset = DpOffset(0.dp, (-48).dp)
        ) {
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = iMessageColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Send as iMessage")
                    }
                },
                onClick = {
                    showModeMenu = false
                    if (currentMode != ChatSendMode.IMESSAGE) {
                        onModeToggle(ChatSendMode.IMESSAGE)
                    }
                    onClick()
                }
            )
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Sms,
                            contentDescription = null,
                            tint = smsColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Send as SMS")
                    }
                },
                onClick = {
                    showModeMenu = false
                    if (currentMode != ChatSendMode.SMS) {
                        onModeToggle(ChatSendMode.SMS)
                    }
                    onClick()
                }
            )
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
 * Draws directional fill during drag using circular reveal.
 * Replaces the wavy seam with a cleaner MD3-style reveal.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDirectionalReveal(
    primaryColor: Color,
    secondaryColor: Color,
    progress: Float
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

    // Draw primary as base
    drawRect(color = primaryColor)

    // Calculate reveal from edge (top or bottom based on drag direction)
    val fromTop = clamped < 0f
    val revealHeight = size.height * coverage

    if (fromTop) {
        // Reveal from top
        drawRect(
            color = secondaryColor,
            size = size.copy(height = revealHeight)
        )
    } else {
        // Reveal from bottom
        drawRect(
            color = secondaryColor,
            topLeft = Offset(0f, size.height - revealHeight),
            size = size.copy(height = revealHeight)
        )
    }
}
