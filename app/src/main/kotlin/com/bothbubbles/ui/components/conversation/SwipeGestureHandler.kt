package com.bothbubbles.ui.components.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.PointerInputScope
import com.bothbubbles.util.HapticUtils
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * Gesture intent for direction detection
 */
internal enum class SwipeGestureIntent {
    UNDETERMINED,
    HORIZONTAL_SWIPE,
    VERTICAL_SCROLL
}

/**
 * State holder for swipe gesture handling
 */
@Stable
internal class SwipeGestureState(
    val swipeOffset: Animatable<Float, *>,
    val swipeThresholdPx: Float,
    val detectionDistancePx: Float,
    val directionRatio: Float = 1.5f
) {
    var hasTriggeredHaptic by mutableStateOf(false)
        private set

    fun setHapticTriggered(triggered: Boolean) {
        hasTriggeredHaptic = triggered
    }

    suspend fun resetOffset() {
        swipeOffset.animateTo(
            0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
        hasTriggeredHaptic = false
    }

    suspend fun snapToOffset(offset: Float) {
        swipeOffset.snapTo(offset)
    }
}

/**
 * Handles swipe gesture detection and differentiation from vertical scrolling
 */
internal suspend fun PointerInputScope.handleSwipeGesture(
    state: SwipeGestureState,
    leftAction: SwipeActionType,
    rightAction: SwipeActionType,
    haptic: HapticFeedback,
    coroutineScope: CoroutineScope,
    onSwipeAction: (SwipeActionType) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)

        var cumulativeX = 0f
        var cumulativeY = 0f
        var gestureIntent = SwipeGestureIntent.UNDETERMINED
        state.setHapticTriggered(false)

        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break

                if (!change.pressed) {
                    // Pointer released - handle action if horizontal swipe was active
                    if (gestureIntent == SwipeGestureIntent.HORIZONTAL_SWIPE) {
                        coroutineScope.launch {
                            val offset = state.swipeOffset.value
                            when {
                                offset > state.swipeThresholdPx && rightAction != SwipeActionType.NONE -> {
                                    HapticUtils.onConfirm(haptic)
                                    onSwipeAction(rightAction)
                                }
                                offset < -state.swipeThresholdPx && leftAction != SwipeActionType.NONE -> {
                                    HapticUtils.onConfirm(haptic)
                                    onSwipeAction(leftAction)
                                }
                            }
                            // Animate back to settled
                            state.resetOffset()
                        }
                    }
                    break
                }

                val dragDelta = change.positionChange()
                cumulativeX += dragDelta.x
                cumulativeY += dragDelta.y

                // Determine intent once we've moved enough
                if (gestureIntent == SwipeGestureIntent.UNDETERMINED) {
                    val totalDistance = kotlin.math.sqrt(cumulativeX * cumulativeX + cumulativeY * cumulativeY)
                    if (totalDistance >= state.detectionDistancePx) {
                        // Check if horizontal clearly dominates vertical
                        gestureIntent = if (cumulativeX.absoluteValue > cumulativeY.absoluteValue * state.directionRatio) {
                            SwipeGestureIntent.HORIZONTAL_SWIPE
                        } else {
                            SwipeGestureIntent.VERTICAL_SCROLL
                        }
                    }
                }

                // Only handle swipe if we determined it's horizontal
                if (gestureIntent == SwipeGestureIntent.HORIZONTAL_SWIPE) {
                    change.consume()
                    coroutineScope.launch {
                        val newOffset = state.swipeOffset.value + dragDelta.x
                        // Constrain based on available actions
                        val constrainedOffset = when {
                            rightAction == SwipeActionType.NONE && newOffset > 0 -> 0f
                            leftAction == SwipeActionType.NONE && newOffset < 0 -> 0f
                            else -> newOffset
                        }
                        state.snapToOffset(constrainedOffset)

                        // Haptic feedback at threshold
                        val isPastThreshold = constrainedOffset.absoluteValue > state.swipeThresholdPx
                        if (isPastThreshold && !state.hasTriggeredHaptic) {
                            HapticUtils.onThresholdCrossed(haptic)
                            state.setHapticTriggered(true)
                        } else if (!isPastThreshold) {
                            state.setHapticTriggered(false)
                        }
                    }
                }
                // If VERTICAL_SCROLL, don't consume - let LazyColumn handle it
            }
        } catch (_: Exception) {
            // Gesture cancelled - reset
            coroutineScope.launch {
                state.resetOffset()
            }
        }
    }
}
