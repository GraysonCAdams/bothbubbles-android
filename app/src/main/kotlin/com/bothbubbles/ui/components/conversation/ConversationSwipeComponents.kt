package com.bothbubbles.ui.components.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A generic swipe wrapper that adds configurable swipe actions to any content.
 * Use this to wrap conversation tiles or other list items with swipe gestures.
 *
 * @param isPinned Current pin state for contextual action
 * @param isMuted Current mute state for contextual action
 * @param isRead Current read state for contextual action
 * @param isSnoozed Current snooze state for contextual action
 * @param onSwipeAction Callback when swipe action is triggered
 * @param swipeConfig Configuration for swipe behavior and actions
 * @param content The content to wrap, receives hasRoundedCorners flag
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableConversationTile(
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    isRead: Boolean = true,
    isSnoozed: Boolean = false,
    onSwipeAction: (SwipeActionType) -> Unit,
    swipeConfig: SwipeConfig = SwipeConfig(),
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    content: @Composable (hasRoundedCorners: Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Get contextual actions based on current state
    val leftAction = SwipeActionType.getContextualAction(
        swipeConfig.leftAction,
        isPinned,
        isMuted,
        isRead,
        isSnoozed
    )
    val rightAction = SwipeActionType.getContextualAction(
        swipeConfig.rightAction,
        isPinned,
        isMuted,
        isRead,
        isSnoozed
    )

    // When gestures disabled (e.g., selection mode), keep tree structure but skip gesture handling
    if (!gesturesEnabled) {
        Box(modifier = modifier.fillMaxWidth()) {
            content(true)  // Always use rounded corners for consistent layout
        }
        return
    }

    if (!swipeConfig.enabled || (leftAction == SwipeActionType.NONE && rightAction == SwipeActionType.NONE)) {
        // No swipe actions, render content without swipe wrapper
        content(false)
        return
    }

    // Swipe offset animation
    val swipeOffset = remember { Animatable(0f) }

    // Direction detection thresholds
    val detectionDistancePx = with(density) { 15.dp.toPx() }
    val directionRatio = 1.5f // Horizontal must be 1.5x greater than vertical

    // Calculate swipe threshold (how far to swipe to trigger action)
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    val swipeThresholdPx by remember(containerWidthPx, swipeConfig.sensitivity) {
        derivedStateOf { containerWidthPx * swipeConfig.sensitivity }
    }

    // Create gesture state
    val gestureState = remember(swipeThresholdPx, detectionDistancePx, directionRatio) {
        SwipeGestureState(swipeOffset, swipeThresholdPx, detectionDistancePx, directionRatio)
    }

    // Determine swipe direction for background display
    val currentDirection by remember {
        derivedStateOf {
            when {
                swipeOffset.value > 20 -> SwipeToDismissBoxValue.StartToEnd
                swipeOffset.value < -20 -> SwipeToDismissBoxValue.EndToStart
                else -> SwipeToDismissBoxValue.Settled
            }
        }
    }

    // Determine if past threshold
    val targetValue by remember {
        derivedStateOf {
            when {
                swipeOffset.value > swipeThresholdPx -> SwipeToDismissBoxValue.StartToEnd
                swipeOffset.value < -swipeThresholdPx -> SwipeToDismissBoxValue.EndToStart
                else -> SwipeToDismissBoxValue.Settled
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size -> containerWidthPx = size.width.toFloat() }
            .pointerInput(swipeConfig.enabled, leftAction, rightAction) {
                handleSwipeGesture(
                    state = gestureState,
                    leftAction = leftAction,
                    rightAction = rightAction,
                    haptic = haptic,
                    coroutineScope = coroutineScope,
                    onSwipeAction = onSwipeAction
                )
            }
    ) {
        // Background with swipe action icons
        SwipeBackground(
            dismissDirection = currentDirection,
            targetValue = targetValue,
            leftAction = leftAction,
            rightAction = rightAction
        )

        // Foreground content with offset
        Box(
            modifier = Modifier.offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
        ) {
            content(true)
        }
    }
}

/**
 * Background layer that shows swipe action icons and labels
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeBackground(
    dismissDirection: SwipeToDismissBoxValue,
    targetValue: SwipeToDismissBoxValue,
    leftAction: SwipeActionType,
    rightAction: SwipeActionType
) {
    val action = when (targetValue) {
        SwipeToDismissBoxValue.StartToEnd -> rightAction
        SwipeToDismissBoxValue.EndToStart -> leftAction
        else -> SwipeActionType.NONE
    }

    // Use a single desaturated color for all swipe actions (MD3 style)
    val swipeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest

    val color by animateColorAsState(
        targetValue = if (targetValue != SwipeToDismissBoxValue.Settled) {
            swipeBackgroundColor
        } else {
            Color.Transparent
        },
        animationSpec = tween(200),
        label = "swipeColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (targetValue != SwipeToDismissBoxValue.Settled) 1f else 0.8f,
        animationSpec = tween(200),
        label = "iconScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
            // MD3: use 12.dp ("Large" shape token) for list items
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
            SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    ) {
        if (action != SwipeActionType.NONE) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .scale(scale)
                        .size(28.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.scale(scale)
                )
            }
        }
    }
}
