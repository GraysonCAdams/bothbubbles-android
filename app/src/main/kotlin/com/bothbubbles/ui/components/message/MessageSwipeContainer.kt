package com.bothbubbles.ui.components.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.bothbubbles.data.local.db.entity.MessageSource
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * Container that handles swipe gestures for message bubbles.
 * Provides two swipe modes:
 * - REPLY: Swipe toward center to trigger reply (iMessage only)
 * - DATE_REVEAL: Swipe toward empty space to reveal date/type info
 */
@Composable
internal fun MessageSwipeContainer(
    message: MessageUiModel,
    onReply: ((String) -> Unit)?,
    onSwipeStateChanged: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (
        containerWidthPx: Int,
        bubbleWidthPx: Int,
        onBubbleWidthChanged: (Int) -> Unit,
        adaptiveBubbleOffsetPx: Float
    ) -> Unit
) {
    val isIMessage = message.messageSource == MessageSource.IMESSAGE.name
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Reply swipe state (toward center - moves bubble)
    val replyDragOffset = remember { Animatable(0f) }
    val replyThresholdPx = with(density) { 60.dp.toPx() }
    val replyMaxPx = replyThresholdPx * 1.2f

    // Date reveal state (toward empty space - NO bubble movement)
    val dateRevealProgress = remember { Animatable(0f) }
    val maxDragPx = with(density) { 80.dp.toPx() }

    // Track which gesture is active and haptic state
    var activeSwipe by remember { mutableStateOf<SwipeType?>(null) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    // Direction detection thresholds
    val detectionDistancePx = with(density) { 15.dp.toPx() }
    val directionRatio = 2.0f  // Horizontal must be 2x vertical to trigger swipe (favors scrolling)

    // Measurement state for adaptive clearance during date reveal
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }

    // Clearance calculation - only offset bubble when insufficient space
    val minClearancePx = with(density) { 12.dp.toPx() }
    val labelWidthPx = with(density) { 80.dp.toPx() }
    val requiredSpacePx = labelWidthPx + minClearancePx

    val adaptiveBubbleOffsetPx by remember(containerWidthPx, bubbleWidthPx) {
        derivedStateOf {
            if (containerWidthPx > 0 && bubbleWidthPx > 0) {
                val availableClearancePx = (containerWidthPx - bubbleWidthPx).toFloat()
                val clearanceDeficitPx = (requiredSpacePx - availableClearancePx).coerceAtLeast(0f)
                if (clearanceDeficitPx > 0f) {
                    val direction = if (message.isFromMe) 1f else -1f
                    clearanceDeficitPx * dateRevealProgress.value * direction
                } else {
                    0f
                }
            } else {
                0f
            }
        }
    }

    // Check if reply is available (iMessage only, not for placed stickers)
    // Disable swipe-to-reply for failed messages and placed stickers
    val canReply = isIMessage && onReply != null && !message.isPlacedSticker && !message.hasError

    // Check if swipe/tap gestures should be enabled (disabled for placed stickers)
    val gesturesEnabled = !message.isPlacedSticker

    // Message type label
    val messageTypeLabel = when (message.messageSource) {
        MessageSource.LOCAL_SMS.name -> "SMS"
        MessageSource.LOCAL_MMS.name -> "MMS"
        MessageSource.SERVER_SMS.name -> "SMS"
        else -> "iMessage"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size -> containerWidthPx = size.width }
            .pointerInput(message.guid, canReply, gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    var cumulativeX = 0f
                    var cumulativeY = 0f
                    var gestureIntent = GestureIntent.UNDETERMINED
                    var currentSwipe: SwipeType? = null
                    hasTriggeredHaptic = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break

                            if (!change.pressed) {
                                // Pointer released - handle action if horizontal swipe was active
                                if (gestureIntent == GestureIntent.HORIZONTAL_SWIPE && currentSwipe != null) {
                                    coroutineScope.launch {
                                        when (currentSwipe) {
                                            SwipeType.REPLY -> {
                                                val shouldTriggerReply = replyDragOffset.value.absoluteValue >= replyThresholdPx
                                                if (shouldTriggerReply && canReply) {
                                                    onReply?.invoke(message.guid)
                                                }
                                                replyDragOffset.animateTo(
                                                    0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessLow
                                                    )
                                                )
                                                onSwipeStateChanged?.invoke(false)
                                            }
                                            SwipeType.DATE_REVEAL -> {
                                                dateRevealProgress.animateTo(
                                                    0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessLow
                                                    )
                                                )
                                            }
                                            null -> {}
                                        }
                                        activeSwipe = null
                                        hasTriggeredHaptic = false
                                    }
                                }
                                break
                            }

                            val dragDelta = change.positionChange()
                            cumulativeX += dragDelta.x
                            cumulativeY += dragDelta.y

                            // Determine intent once we've moved enough
                            if (gestureIntent == GestureIntent.UNDETERMINED) {
                                val totalDistance = kotlin.math.sqrt(cumulativeX * cumulativeX + cumulativeY * cumulativeY)
                                if (totalDistance >= detectionDistancePx) {
                                    // Check if horizontal clearly dominates vertical (2x ratio favors scrolling)
                                    gestureIntent = if (cumulativeX.absoluteValue > cumulativeY.absoluteValue * directionRatio) {
                                        GestureIntent.HORIZONTAL_SWIPE
                                    } else {
                                        GestureIntent.VERTICAL_SCROLL
                                    }

                                    // If horizontal, determine swipe type
                                    if (gestureIntent == GestureIntent.HORIZONTAL_SWIPE) {
                                        val isTowardCenter = if (message.isFromMe) cumulativeX < 0 else cumulativeX > 0
                                        currentSwipe = if (isTowardCenter && canReply) SwipeType.REPLY else SwipeType.DATE_REVEAL
                                        activeSwipe = currentSwipe
                                        if (currentSwipe == SwipeType.REPLY) {
                                            onSwipeStateChanged?.invoke(true)
                                        }
                                    }
                                }
                            }

                            // Only handle swipe if we determined it's horizontal
                            if (gestureIntent == GestureIntent.HORIZONTAL_SWIPE) {
                                change.consume()
                                coroutineScope.launch {
                                    when (currentSwipe) {
                                        SwipeType.REPLY -> {
                                            val newOffset = if (message.isFromMe) {
                                                (replyDragOffset.value + dragDelta.x).coerceIn(-replyMaxPx, 0f)
                                            } else {
                                                (replyDragOffset.value + dragDelta.x).coerceIn(0f, replyMaxPx)
                                            }
                                            replyDragOffset.snapTo(newOffset)

                                            // Haptic at threshold
                                            if (replyDragOffset.value.absoluteValue >= replyThresholdPx && !hasTriggeredHaptic) {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                hasTriggeredHaptic = true
                                            } else if (replyDragOffset.value.absoluteValue < replyThresholdPx) {
                                                hasTriggeredHaptic = false
                                            }
                                        }
                                        SwipeType.DATE_REVEAL -> {
                                            val progressDelta = dragDelta.x / maxDragPx
                                            val newProgress = if (message.isFromMe) {
                                                (dateRevealProgress.value + progressDelta).coerceIn(0f, 1f)
                                            } else {
                                                (dateRevealProgress.value - progressDelta).coerceIn(0f, 1f)
                                            }
                                            dateRevealProgress.snapTo(newProgress)
                                        }
                                        null -> {}
                                    }
                                }
                            }
                            // If VERTICAL_SCROLL, don't consume - let LazyColumn handle it
                        }
                    } catch (_: Exception) {
                        // Gesture cancelled - reset
                        coroutineScope.launch {
                            replyDragOffset.animateTo(0f)
                            dateRevealProgress.animateTo(0f)
                            if (activeSwipe == SwipeType.REPLY) {
                                onSwipeStateChanged?.invoke(false)
                            }
                            activeSwipe = null
                            hasTriggeredHaptic = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Reply indicator - behind bubble at screen edge (only during reply swipe)
        if (activeSwipe == SwipeType.REPLY && canReply) {
            val progress = (replyDragOffset.value.absoluteValue / replyThresholdPx).coerceIn(0f, 1f)
            val isFullyExposed = replyDragOffset.value.absoluteValue >= replyThresholdPx
            ReplyIndicator(
                progress = progress,
                isFullyExposed = isFullyExposed,
                modifier = Modifier
                    .align(if (message.isFromMe) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 16.dp)
            )
        }

        // Date/type info - slides in from empty space side (only during date swipe)
        if (activeSwipe == SwipeType.DATE_REVEAL || dateRevealProgress.value > 0f) {
            DateTypeLabel(
                time = message.formattedTime,
                type = messageTypeLabel,
                progress = dateRevealProgress.value,
                isFromMe = message.isFromMe,
                modifier = Modifier.align(
                    if (message.isFromMe) Alignment.CenterStart else Alignment.CenterEnd
                )
            )
        }

        // Content with offset
        content(
            containerWidthPx,
            bubbleWidthPx,
            { bubbleWidthPx = it },
            replyDragOffset.value + adaptiveBubbleOffsetPx
        )
    }
}
