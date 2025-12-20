package com.bothbubbles.ui.components.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.bothbubbles.util.HapticUtils

/**
 * A swipe wrapper using Material 3's SwipeToDismissBox for list items.
 * Provides configurable swipe actions that snap back after triggering.
 *
 * This uses the standard MD3 pattern for swipe gestures which ensures:
 * - Proper gesture detection that distinguishes from vertical scrolling
 * - Correct layout alignment between background and content
 * - Smooth animations with spring physics
 *
 * @param isPinned Current pin state for contextual action
 * @param isMuted Current mute state for contextual action
 * @param isRead Current read state for contextual action
 * @param isSnoozed Current snooze state for contextual action
 * @param onSwipeAction Callback when swipe action is triggered
 * @param swipeConfig Configuration for swipe behavior and actions
 * @param gesturesEnabled Whether swipe gestures are enabled
 * @param content The content to wrap, receives hasRoundedCorners flag
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // MD3 SwipeToDismissBox state with confirmValueChange callback
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (rightAction != SwipeActionType.NONE) {
                        HapticUtils.onConfirm(haptic)
                        onSwipeAction(rightAction)
                    }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (leftAction != SwipeActionType.NONE) {
                        HapticUtils.onConfirm(haptic)
                        onSwipeAction(leftAction)
                    }
                }
                SwipeToDismissBoxValue.Settled -> { /* No action */ }
            }
            // Return false to snap back after action (don't dismiss)
            false
        }
    )

    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = rightAction != SwipeActionType.NONE,
        enableDismissFromEndToStart = leftAction != SwipeActionType.NONE,
        backgroundContent = {
            SwipeBackground(
                state = swipeState,
                leftAction = leftAction,
                rightAction = rightAction
            )
        },
        content = {
            content(true)
        }
    )
}

/**
 * Background layer that shows swipe action icons and labels.
 * Uses MD3 design tokens for consistent styling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RowScope.SwipeBackground(
    state: SwipeToDismissBoxState,
    leftAction: SwipeActionType,
    rightAction: SwipeActionType
) {
    val dismissDirection = state.dismissDirection

    // Determine which action to show based on current swipe direction
    val action = when (dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> rightAction
        SwipeToDismissBoxValue.EndToStart -> leftAction
        SwipeToDismissBoxValue.Settled -> SwipeActionType.NONE
    }

    // Calculate if past the commit threshold based on progress
    val isPastThreshold = state.progress > 0.4f

    // Use MD3 surface container for subtle background
    val swipeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest

    val color by animateColorAsState(
        targetValue = if (dismissDirection != SwipeToDismissBoxValue.Settled) {
            swipeBackgroundColor
        } else {
            Color.Transparent
        },
        animationSpec = tween(200),
        label = "swipeColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (isPastThreshold) 1f else 0.8f,
        animationSpec = tween(200),
        label = "iconScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
            SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
            SwipeToDismissBoxValue.Settled -> Alignment.CenterStart
        }
    ) {
        if (action != SwipeActionType.NONE && dismissDirection != SwipeToDismissBoxValue.Settled) {
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
