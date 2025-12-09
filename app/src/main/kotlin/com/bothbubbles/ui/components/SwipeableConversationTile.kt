package com.bothbubbles.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.ui.conversations.MessageStatus
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Available swipe actions for conversation tiles
 */
enum class SwipeActionType(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val color: Color
) {
    NONE("none", "None", Icons.Default.Block, Color.Gray),
    PIN("pin", "Pin", Icons.Default.PushPin, Color(0xFF1976D2)),
    UNPIN("unpin", "Unpin", Icons.Outlined.PushPin, Color(0xFF1976D2)),
    ARCHIVE("archive", "Archive", Icons.Default.Archive, Color(0xFF388E3C)),
    DELETE("delete", "Delete", Icons.Default.Delete, Color(0xFFD32F2F)),
    MUTE("mute", "Mute", Icons.Default.NotificationsOff, Color(0xFF7B1FA2)),
    UNMUTE("unmute", "Unmute", Icons.Default.Notifications, Color(0xFF7B1FA2)),
    MARK_READ("mark_read", "Mark as Read", Icons.Default.MarkEmailRead, Color(0xFF0097A7)),
    MARK_UNREAD("mark_unread", "Mark as Unread", Icons.Default.MarkEmailUnread, Color(0xFF0097A7)),
    SNOOZE("snooze", "Snooze", Icons.Default.Snooze, Color(0xFF9C27B0)),
    UNSNOOZE("unsnooze", "Unsnooze", Icons.Outlined.Snooze, Color(0xFF9C27B0));

    companion object {
        fun fromKey(key: String): SwipeActionType =
            entries.find { it.key == key } ?: NONE

        /**
         * Get the appropriate action based on current state
         */
        fun getContextualAction(
            baseAction: SwipeActionType,
            isPinned: Boolean,
            isMuted: Boolean,
            isRead: Boolean,
            isSnoozed: Boolean = false
        ): SwipeActionType {
            return when (baseAction) {
                PIN, UNPIN -> if (isPinned) UNPIN else PIN
                MUTE, UNMUTE -> if (isMuted) UNMUTE else MUTE
                MARK_READ, MARK_UNREAD -> if (isRead) MARK_UNREAD else MARK_READ
                SNOOZE, UNSNOOZE -> if (isSnoozed) UNSNOOZE else SNOOZE
                else -> baseAction
            }
        }
    }
}

/**
 * Data class to hold swipe configuration
 */
data class SwipeConfig(
    val enabled: Boolean = true,
    val leftAction: SwipeActionType = SwipeActionType.ARCHIVE,
    val rightAction: SwipeActionType = SwipeActionType.PIN,
    val sensitivity: Float = 0.4f
)

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
/**
 * Gesture intent for direction detection
 */
private enum class SwipeGestureIntent {
    UNDETERMINED,
    HORIZONTAL_SWIPE,
    VERTICAL_SCROLL
}

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

    // Track haptic feedback state
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

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
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    var cumulativeX = 0f
                    var cumulativeY = 0f
                    var gestureIntent = SwipeGestureIntent.UNDETERMINED
                    hasTriggeredHaptic = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break

                            if (!change.pressed) {
                                // Pointer released - handle action if horizontal swipe was active
                                if (gestureIntent == SwipeGestureIntent.HORIZONTAL_SWIPE) {
                                    coroutineScope.launch {
                                        val offset = swipeOffset.value
                                        when {
                                            offset > swipeThresholdPx && rightAction != SwipeActionType.NONE -> {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onSwipeAction(rightAction)
                                            }
                                            offset < -swipeThresholdPx && leftAction != SwipeActionType.NONE -> {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onSwipeAction(leftAction)
                                            }
                                        }
                                        // Animate back to settled
                                        swipeOffset.animateTo(
                                            0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                        hasTriggeredHaptic = false
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
                                if (totalDistance >= detectionDistancePx) {
                                    // Check if horizontal clearly dominates vertical
                                    gestureIntent = if (cumulativeX.absoluteValue > cumulativeY.absoluteValue * directionRatio) {
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
                                    val newOffset = swipeOffset.value + dragDelta.x
                                    // Constrain based on available actions
                                    val constrainedOffset = when {
                                        rightAction == SwipeActionType.NONE && newOffset > 0 -> 0f
                                        leftAction == SwipeActionType.NONE && newOffset < 0 -> 0f
                                        else -> newOffset
                                    }
                                    swipeOffset.snapTo(constrainedOffset)

                                    // Haptic feedback at threshold
                                    val isPastThreshold = constrainedOffset.absoluteValue > swipeThresholdPx
                                    if (isPastThreshold && !hasTriggeredHaptic) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        hasTriggeredHaptic = true
                                    } else if (!isPastThreshold) {
                                        hasTriggeredHaptic = false
                                    }
                                }
                            }
                            // If VERTICAL_SCROLL, don't consume - let LazyColumn handle it
                        }
                    } catch (_: Exception) {
                        // Gesture cancelled - reset
                        coroutineScope.launch {
                            swipeOffset.animateTo(0f)
                            hasTriggeredHaptic = false
                        }
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(
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
            .clip(RoundedCornerShape(16.dp))
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

@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    // Pop-in animation with scale + fade (snappy Android 16 style)
    AnimatedVisibility(
        visible = count > 0,
        enter = scaleIn(
            initialScale = 0.5f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
        ) + fadeIn(tween(100)),
        exit = scaleOut(
            targetScale = 0.5f,
            animationSpec = tween(100)
        ) + fadeOut(tween(100)),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            shape = CircleShape,
            modifier = Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Animated count changes with crossfade
                AnimatedContent(
                    targetState = count,
                    transitionSpec = {
                        fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                    },
                    label = "badgeCount"
                ) { targetCount ->
                    Text(
                        text = if (targetCount > 99) "99+" else targetCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Pinned conversation tile with fixed position indicator
 */
@Composable
fun PinnedConversationTile(
    title: String,
    avatarContent: @Composable () -> Unit,
    unreadCount: Int = 0,
    isTyping: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.size(80.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box {
                    avatarContent()

                    // Unread badge with pop-in animation
                    AnimatedVisibility(
                        visible = unreadCount > 0,
                        enter = scaleIn(
                            initialScale = 0.5f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
                        ) + fadeIn(tween(100)),
                        exit = scaleOut(targetScale = 0.5f, animationSpec = tween(100)) + fadeOut(tween(100)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                            modifier = Modifier.size(18.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AnimatedContent(
                                    targetState = unreadCount,
                                    transitionSpec = {
                                        fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                                    },
                                    label = "pinnedBadgeCount"
                                ) { count ->
                                    Text(
                                        text = if (count > 9) "9+" else count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Typing indicator overlay
            if (isTyping) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Indicator showing message delivery/read status
 */
@Composable
fun MessageStatusIndicator(
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    when (status) {
        MessageStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Sending",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(16.dp)
            )
        }
        MessageStatus.NONE -> { /* No indicator */ }
    }
}

