package com.bothbubbles.ui.chat.composer.gestures

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.composer.animations.SendModeGestureConfig
import com.bothbubbles.util.HapticUtils
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * State for the send mode toggle gesture.
 *
 * Encapsulates all state related to the vertical swipe gesture for toggling
 * between iMessage and SMS modes.
 */
@Stable
class SendModeGestureState internal constructor(
    private val scope: CoroutineScope,
    private val swipeRangePx: Float,
    private val detectionDistancePx: Float
) {
    /** Current drag offset in pixels */
    internal val dragOffset = Animatable(0f)

    /** Whether the threshold haptic has been triggered */
    internal var hasTriggeredThresholdHaptic by mutableStateOf(false)

    /** Current toggle state */
    var toggleState by mutableStateOf<ToggleState>(ToggleState.Idle)
        internal set

    /** Whether the button is pressed */
    var isPressed by mutableStateOf(false)
        internal set

    /** Normalized drag progress from -1.0 to 1.0 */
    val dragProgress: Float
        get() = (dragOffset.value / swipeRangePx).coerceIn(-1f, 1f)

    /** Whether the drag has passed the snap threshold */
    val hasPassedThreshold: Boolean
        get() = abs(dragProgress) >= SendModeGestureConfig.SNAP_THRESHOLD_PERCENT

    /**
     * Reset the gesture state and animate drag back to zero.
     */
    suspend fun reset() {
        dragOffset.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
        toggleState = ToggleState.Idle
        hasTriggeredThresholdHaptic = false
    }

    /**
     * Animate snap to target position.
     */
    suspend fun snapToTarget(willSwitch: Boolean, direction: Float) {
        toggleState = ToggleState.Snapping(willSwitch = willSwitch)

        if (willSwitch) {
            val targetOffset = direction * swipeRangePx
            dragOffset.animateTo(
                targetValue = targetOffset,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        } else {
            reset()
        }
    }

    /**
     * Complete the mode switch (snap back to center after mode change).
     */
    suspend fun completeSwitch() {
        dragOffset.snapTo(0f)
        toggleState = ToggleState.Idle
    }

    /**
     * Update drag offset during gesture.
     */
    internal suspend fun updateDrag(deltaY: Float) {
        val newOffset = (dragOffset.value + deltaY).coerceIn(-swipeRangePx, swipeRangePx)
        dragOffset.snapTo(newOffset)

        val progress = newOffset / swipeRangePx
        val hasPassedThreshold = abs(progress) >= SendModeGestureConfig.SNAP_THRESHOLD_PERCENT

        toggleState = ToggleState.Dragging(
            progress = progress,
            hasPassedThreshold = hasPassedThreshold
        )
    }
}

/**
 * State of the toggle gesture.
 */
sealed class ToggleState {
    /** No gesture active, button in resting state */
    data object Idle : ToggleState()

    /**
     * User is actively dragging the toggle.
     *
     * @param progress Normalized drag progress from -1.0 to 1.0
     * @param hasPassedThreshold Whether the drag has passed the snap threshold
     */
    data class Dragging(
        val progress: Float,
        val hasPassedThreshold: Boolean
    ) : ToggleState()

    /**
     * Animating to final position after gesture release.
     *
     * @param willSwitch True if the mode will switch, false if snapping back
     */
    data class Snapping(
        val willSwitch: Boolean
    ) : ToggleState()
}

/**
 * Intent for distinguishing between gesture types.
 */
internal enum class GestureIntent {
    UNDETERMINED,
    VERTICAL_SWIPE,
    TAP_OR_HOLD
}

/**
 * Remember and create a [SendModeGestureState].
 */
@Composable
fun rememberSendModeGestureState(): SendModeGestureState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val swipeRangePx = with(density) { SendModeGestureConfig.SWIPE_RANGE_DP.dp.toPx() }
    val detectionDistancePx = with(density) { SendModeGestureConfig.DETECTION_DISTANCE_DP.dp.toPx() }

    return remember {
        SendModeGestureState(
            scope = scope,
            swipeRangePx = swipeRangePx,
            detectionDistancePx = detectionDistancePx
        )
    }
}

/**
 * Callback interface for send mode gesture events.
 */
interface SendModeGestureCallbacks {
    /** Called when user taps the button (quick press) */
    fun onTap()

    /** Called when user long-presses the button */
    fun onLongPress()

    /**
     * Called when user swipes to toggle mode.
     * @return true if the mode switch was accepted, false otherwise
     */
    fun onModeToggle(newMode: ChatSendMode): Boolean
}

/**
 * Modifier that adds send mode toggle gesture detection.
 *
 * This handles:
 * - Vertical swipe detection for mode toggle
 * - Tap detection for send action
 * - Long press detection for effect picker
 * - Haptic feedback at threshold crossings
 * - Spring-based snap animations
 *
 * @param state Gesture state holder
 * @param currentMode Current send mode
 * @param canToggle Whether toggle is available
 * @param isEnabled Whether gestures are enabled (false when sending)
 * @param callbacks Callbacks for gesture events
 */
@Composable
fun Modifier.sendModeGesture(
    state: SendModeGestureState,
    currentMode: ChatSendMode,
    canToggle: Boolean,
    isEnabled: Boolean,
    callbacks: SendModeGestureCallbacks
): Modifier {
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val swipeRangePx = with(density) { SendModeGestureConfig.SWIPE_RANGE_DP.dp.toPx() }
    val detectionDistancePx = with(density) { SendModeGestureConfig.DETECTION_DISTANCE_DP.dp.toPx() }

    return this.pointerInput(isEnabled, canToggle, currentMode) {
        if (!isEnabled) {
            return@pointerInput
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()

            var cumulativeX = 0f
            var cumulativeY = 0f
            var gestureIntent: GestureIntent = GestureIntent.UNDETERMINED
            val pressStartTime = System.currentTimeMillis()
            state.hasTriggeredThresholdHaptic = false
            state.isPressed = true

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break

                    if (!change.pressed) {
                        // Handle release based on intent
                        state.isPressed = false
                        when (gestureIntent) {
                            GestureIntent.VERTICAL_SWIPE -> {
                                handleSwipeRelease(
                                    state = state,
                                    currentMode = currentMode,
                                    canToggle = canToggle,
                                    swipeRangePx = swipeRangePx,
                                    hapticFeedback = hapticFeedback,
                                    scope = scope,
                                    callbacks = callbacks
                                )
                            }

                            GestureIntent.TAP_OR_HOLD -> {
                                val elapsed = System.currentTimeMillis() - pressStartTime
                                if (elapsed < 400) {
                                    callbacks.onTap()
                                } else {
                                    callbacks.onLongPress()
                                }
                            }

                            GestureIntent.UNDETERMINED -> {
                                // Short tap
                                callbacks.onTap()
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
                            gestureIntent = if (abs(cumulativeY) > abs(cumulativeX) * SendModeGestureConfig.DIRECTION_RATIO) {
                                GestureIntent.VERTICAL_SWIPE
                            } else {
                                GestureIntent.TAP_OR_HOLD
                            }
                        }
                    }

                    // Handle vertical swipe
                    if (gestureIntent == GestureIntent.VERTICAL_SWIPE && canToggle) {
                        change.consume()

                        scope.launch {
                            state.updateDrag(dragDelta.y)

                            // Haptic at threshold crossing
                            val hasPassedThreshold = state.hasPassedThreshold
                            if (hasPassedThreshold && !state.hasTriggeredThresholdHaptic) {
                                HapticUtils.onThresholdCrossed(hapticFeedback)
                                state.hasTriggeredThresholdHaptic = true
                            } else if (!hasPassedThreshold && state.hasTriggeredThresholdHaptic) {
                                HapticUtils.onDragTransition(hapticFeedback)
                                state.hasTriggeredThresholdHaptic = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                state.isPressed = false
                scope.launch {
                    state.reset()
                }
                throw e
            }
        }
    }
}

/**
 * Handle the release of a vertical swipe gesture.
 */
private fun handleSwipeRelease(
    state: SendModeGestureState,
    currentMode: ChatSendMode,
    canToggle: Boolean,
    swipeRangePx: Float,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    scope: CoroutineScope,
    callbacks: SendModeGestureCallbacks
) {
    val normalizedProgress = state.dragProgress
    val hasPassedThreshold = abs(normalizedProgress) >= SendModeGestureConfig.SNAP_THRESHOLD_PERCENT

    if (hasPassedThreshold && canToggle) {
        // Switch to other mode
        val newMode = if (currentMode == ChatSendMode.SMS) {
            ChatSendMode.IMESSAGE
        } else {
            ChatSendMode.SMS
        }

        val direction = if (normalizedProgress < 0f) -1f else 1f

        HapticUtils.onConfirm(hapticFeedback)

        scope.launch {
            state.snapToTarget(willSwitch = true, direction = direction)

            val didSwitch = callbacks.onModeToggle(newMode)

            if (!didSwitch) {
                state.reset()
            } else {
                state.completeSwitch()
            }
        }
    } else {
        // Snap back
        scope.launch {
            state.reset()
        }
    }
}
