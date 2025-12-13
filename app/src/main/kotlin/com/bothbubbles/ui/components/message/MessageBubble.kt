package com.bothbubbles.ui.components.message

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.components.message.EmojiAnalysis
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.message.ReplyPreviewData
import com.bothbubbles.ui.components.message.ThreadChain
import com.bothbubbles.util.parsing.DateParsingUtils
import com.bothbubbles.util.parsing.DetectedCode
import com.bothbubbles.util.parsing.DetectedDate
import com.bothbubbles.util.parsing.DetectedPhoneNumber
import com.bothbubbles.util.parsing.DetectedUrl
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import com.bothbubbles.util.parsing.UrlParsingUtils
import com.bothbubbles.ui.theme.BothBubblesTheme
import com.bothbubbles.ui.theme.MessageShapes
import com.bothbubbles.util.EmojiUtils.analyzeEmojis
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp

/**
 * Type of swipe gesture being performed on a message bubble.
 */
private enum class SwipeType {
    /** Swipe toward center to trigger reply (iMessage only) */
    REPLY,
    /** Swipe toward empty space to reveal date/type info */
    DATE_REVEAL
}

/**
 * Direction intent for gesture detection - determines if user is swiping or scrolling.
 */
private enum class GestureIntent {
    UNDETERMINED,
    HORIZONTAL_SWIPE,
    VERTICAL_SCROLL
}

@Composable
fun MessageBubble(
    message: MessageUiModel,
    onLongPress: () -> Unit,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupPosition: MessageGroupPosition = MessageGroupPosition.SINGLE,
    searchQuery: String? = null,
    isCurrentSearchMatch: Boolean = false,
    // Attachment download support (for manual download mode)
    onDownloadClick: ((String) -> Unit)? = null,
    downloadingAttachments: Map<String, Float> = emptyMap(),
    // Whether to show delivery indicator (iMessage-style: only on last message in sequence)
    showDeliveryIndicator: Boolean = true,
    // Callback for swipe-to-reply (iMessage only). Pass message GUID when triggered.
    onReply: ((String) -> Unit)? = null,
    // Callback when reply indicator is tapped. Pass the threadOriginatorGuid to open thread overlay.
    onReplyIndicatorClick: ((String) -> Unit)? = null,
    // Callback when swipe gesture starts/ends. Used to hide stickers during swipe.
    onSwipeStateChanged: ((Boolean) -> Unit)? = null,
    // Callback for retrying a failed message. Pass message GUID when triggered.
    onRetry: ((String) -> Unit)? = null,
    // Group chat avatar support
    isGroupChat: Boolean = false,
    // Show avatar only on last message in a consecutive group from same sender
    showAvatar: Boolean = false
) {
    // Detect first URL in message text for link preview
    val firstUrl = remember(message.text) {
        UrlParsingUtils.getFirstUrl(message.text)
    }

    // Check if this message needs segmented rendering
    // (has media attachments OR has link preview with text)
    val needsSegmentation = remember(message, firstUrl) {
        MessageSegmentParser.needsSegmentation(message, firstUrl != null)
    }

    // Show avatar for received messages in group chats
    val shouldShowAvatarSpace = isGroupChat && !message.isFromMe
    val avatarSize = 28.dp

    // Wrap content in a column to show reply indicator above the bubble
    Column(modifier = modifier) {
        // Reply quote indicator (shown above reply messages)
        message.replyPreview?.let { preview ->
            message.threadOriginatorGuid?.let { originGuid ->
                // Add left padding to align with message bubble when avatar space is present
                val replyPadding = if (shouldShowAvatarSpace) (avatarSize + 8.dp) else 0.dp
                ReplyQuoteIndicator(
                    replyPreview = preview,
                    isFromMe = message.isFromMe,
                    onClick = { onReplyIndicatorClick?.invoke(originGuid) },
                    modifier = Modifier.padding(start = replyPadding)
                )
            }
        }

        // Main content row with optional avatar
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Avatar space for received messages in group chats
            if (shouldShowAvatarSpace) {
                if (showAvatar) {
                    Avatar(
                        name = message.senderName ?: "?",
                        avatarPath = message.senderAvatarPath,
                        size = avatarSize,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                } else {
                    // Empty space to maintain alignment
                    Spacer(modifier = Modifier.width(avatarSize + 8.dp))
                }
            }

            // Message content
            if (needsSegmentation) {
                // Use segmented rendering for messages with media/links
                SegmentedMessageBubble(
                    message = message,
                    firstUrl = firstUrl,
                    onLongPress = onLongPress,
                    onMediaClick = onMediaClick,
                    groupPosition = groupPosition,
                    searchQuery = searchQuery,
                    isCurrentSearchMatch = isCurrentSearchMatch,
                    onDownloadClick = onDownloadClick,
                    downloadingAttachments = downloadingAttachments,
                    showDeliveryIndicator = showDeliveryIndicator,
                    onReply = onReply,
                    onSwipeStateChanged = onSwipeStateChanged,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Use optimized single-bubble rendering for simple text messages
                SimpleBubbleContent(
                    message = message,
                    firstUrl = firstUrl,
                    onLongPress = onLongPress,
                    onMediaClick = onMediaClick,
                    groupPosition = groupPosition,
                    searchQuery = searchQuery,
                    isCurrentSearchMatch = isCurrentSearchMatch,
                    onDownloadClick = onDownloadClick,
                    downloadingAttachments = downloadingAttachments,
                    showDeliveryIndicator = showDeliveryIndicator,
                    onReply = onReply,
                    onSwipeStateChanged = onSwipeStateChanged,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Segmented message rendering for messages with media or link previews.
 * Renders media and links outside bubbles as standalone elements.
 */
@Composable
private fun SegmentedMessageBubble(
    message: MessageUiModel,
    firstUrl: DetectedUrl?,
    onLongPress: () -> Unit,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupPosition: MessageGroupPosition = MessageGroupPosition.SINGLE,
    searchQuery: String? = null,
    isCurrentSearchMatch: Boolean = false,
    onDownloadClick: ((String) -> Unit)? = null,
    downloadingAttachments: Map<String, Float> = emptyMap(),
    showDeliveryIndicator: Boolean = true,
    onReply: ((String) -> Unit)? = null,
    onSwipeStateChanged: ((Boolean) -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null
) {
    val bubbleColors = BothBubblesTheme.bubbleColors
    val isIMessage = message.messageSource == MessageSource.IMESSAGE.name
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Parse message into segments
    val segments = remember(message, firstUrl) {
        MessageSegmentParser.parse(message, firstUrl)
    }

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

    // Tap-to-show timestamp state
    var showTimestamp by remember { mutableStateOf(false) }

    // Delivery status legend dialog state
    var showStatusLegend by remember { mutableStateOf(false) }

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
            .onSizeChanged { size -> containerWidthPx = size.width },
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

        // Main content row - offset during REPLY swipe and DATE_REVEAL (when clearance needed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset((replyDragOffset.value + adaptiveBubbleOffsetPx).roundToInt(), 0) }
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
            horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .onSizeChanged { size -> bubbleWidthPx = size.width }
            ) {
                // Render segments with reactions on first segment
                Box {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        segments.forEachIndexed { index, segment ->
                            when (segment) {
                                is MessageSegment.MediaSegment -> {
                                    BorderlessMediaContent(
                                        attachment = segment.attachment,
                                        isFromMe = message.isFromMe,
                                        onMediaClick = onMediaClick,
                                        maxWidth = 300.dp,
                                        onDownloadClick = onDownloadClick,
                                        isDownloading = segment.attachment.guid in downloadingAttachments,
                                        downloadProgress = downloadingAttachments[segment.attachment.guid] ?: 0f,
                                        isPlacedSticker = message.isPlacedSticker,
                                        messageGuid = message.guid,
                                        modifier = Modifier
                                            .pointerInput(message.guid) {
                                                detectTapGestures(
                                                    onTap = { if (gesturesEnabled) showTimestamp = !showTimestamp },
                                                    onLongPress = {
                                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        onLongPress()
                                                    }
                                                )
                                            }
                                    )
                                }

                                is MessageSegment.TextSegment -> {
                                    val isFirstTextSegment = index == segments.indexOfFirst { it is MessageSegment.TextSegment }
                                    TextBubbleSegment(
                                        message = message,
                                        text = segment.text,
                                        groupPosition = groupPosition,
                                        searchQuery = searchQuery,
                                        isCurrentSearchMatch = isCurrentSearchMatch && isFirstTextSegment,
                                        onLongPress = onLongPress,
                                        onTimestampToggle = { if (gesturesEnabled) showTimestamp = !showTimestamp },
                                        showSubject = isFirstTextSegment
                                    )
                                }

                                is MessageSegment.LinkPreviewSegment -> {
                                    BorderlessLinkPreview(
                                        url = segment.url,
                                        isFromMe = message.isFromMe,
                                        maxWidth = 300.dp,
                                        modifier = Modifier
                                            .pointerInput(message.guid) {
                                                detectTapGestures(
                                                    onLongPress = {
                                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        onLongPress()
                                                    }
                                                )
                                            }
                                    )
                                }

                                is MessageSegment.FileSegment -> {
                                    AttachmentContent(
                                        attachment = segment.attachment,
                                        isFromMe = message.isFromMe,
                                        onMediaClick = onMediaClick,
                                        onDownloadClick = onDownloadClick,
                                        isDownloading = segment.attachment.guid in downloadingAttachments,
                                        downloadProgress = downloadingAttachments[segment.attachment.guid] ?: 0f
                                    )
                                }
                            }
                        }
                    }

                    // Reactions overlay on top-corner
                    if (message.reactions.isNotEmpty()) {
                        ReactionsDisplay(
                            reactions = message.reactions,
                            isFromMe = message.isFromMe,
                            modifier = Modifier
                                .align(if (message.isFromMe) Alignment.TopStart else Alignment.TopEnd)
                                .offset(
                                    x = if (message.isFromMe) (-20).dp else 20.dp,
                                    y = (-14).dp
                                )
                        )
                    }
                }

                // Tap-to-reveal timestamp
                if (showTimestamp) {
                    Text(
                        text = "${message.formattedTime} · $messageTypeLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = if (message.isFromMe) 0.dp else 12.dp,
                            end = if (message.isFromMe) 12.dp else 0.dp,
                            top = 2.dp
                        )
                    )
                }

                // Delivery indicator (hide when hasError since "Not Delivered" is shown)
                if (message.isFromMe && showDeliveryIndicator && !message.hasError) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = 4.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DeliveryIndicator(
                            isSent = message.isSent,
                            isDelivered = message.isDelivered,
                            isRead = message.isRead,
                            hasError = message.hasError,
                            onClick = { showStatusLegend = true }
                        )
                    }
                }

                // Retry button for failed outbound messages
                if (message.hasError && message.isFromMe && onRetry != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = 4.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Not Delivered",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Surface(
                            onClick = { onRetry(message.guid) },
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Retry",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                // Status legend dialog
                if (showStatusLegend) {
                    DeliveryStatusLegend(
                        onDismiss = { showStatusLegend = false }
                    )
                }
            }
        }
    }
}

/**
 * Text bubble segment for use in segmented message rendering.
 */
@Composable
private fun TextBubbleSegment(
    message: MessageUiModel,
    text: String,
    groupPosition: MessageGroupPosition,
    searchQuery: String?,
    isCurrentSearchMatch: Boolean,
    onLongPress: () -> Unit,
    onTimestampToggle: () -> Unit,
    showSubject: Boolean = false
) {
    val bubbleColors = BothBubblesTheme.bubbleColors
    val isIMessage = message.messageSource == MessageSource.IMESSAGE.name
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current

    // Detect clickables in text
    val detectedDates = remember(text) { DateParsingUtils.detectDates(text) }
    val detectedPhoneNumbers = remember(text) { PhoneAndCodeParsingUtils.detectPhoneNumbers(text) }
    val detectedCodes = remember(text) { PhoneAndCodeParsingUtils.detectCodes(text) }

    // Phone number context menu state
    var showPhoneMenu by remember { mutableStateOf(false) }
    var selectedPhoneNumber by remember { mutableStateOf<DetectedPhoneNumber?>(null) }

    val bubbleShape = when (groupPosition) {
        MessageGroupPosition.SINGLE -> if (message.isFromMe) MessageShapes.sentSingle else MessageShapes.receivedSingle
        MessageGroupPosition.FIRST -> if (message.isFromMe) MessageShapes.sentFirst else MessageShapes.receivedFirst
        MessageGroupPosition.MIDDLE -> if (message.isFromMe) MessageShapes.sentMiddle else MessageShapes.receivedMiddle
        MessageGroupPosition.LAST -> if (message.isFromMe) MessageShapes.sentLast else MessageShapes.receivedLast
    }

    Surface(
        shape = bubbleShape,
        color = when {
            message.isFromMe && isIMessage -> bubbleColors.iMessageSent
            message.isFromMe -> bubbleColors.smsSent
            else -> bubbleColors.received
        },
        tonalElevation = 0.dp,
        modifier = Modifier
            .then(
                if (isCurrentSearchMatch) {
                    Modifier.border(
                        width = 2.dp,
                        color = Color(0xFFFF9800),
                        shape = bubbleShape
                    )
                } else {
                    Modifier
                }
            )
            .pointerInput(message.guid) {
                detectTapGestures(
                    onTap = { onTimestampToggle() },
                    onLongPress = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val textColor = when {
                message.isFromMe && isIMessage -> bubbleColors.iMessageSentText
                message.isFromMe -> bubbleColors.smsSentText
                else -> bubbleColors.receivedText
            }

            // Subject line (bold, before message text)
            if (showSubject) {
                message.subject?.let { subject ->
                    val displaySubject = if (subject.isBlank()) "(no subject)" else subject
                    Text(
                        text = displaySubject,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = textColor
                    )
                }
            }

            // Check for emoji-only messages with 3 or fewer emojis
            // Use pre-computed value if available and text matches, otherwise compute
            val emojiAnalysis = message.emojiAnalysis ?: remember(text) { analyzeEmojis(text) }
            val isLargeEmoji = emojiAnalysis.isEmojiOnly && emojiAnalysis.emojiCount in 1..3
            val textStyle = if (isLargeEmoji) {
                MaterialTheme.typography.bodyLarge.copy(fontSize = 48.sp)
            } else {
                MaterialTheme.typography.bodyLarge
            }

            val hasClickableContent = detectedDates.isNotEmpty() ||
                    detectedPhoneNumbers.isNotEmpty() ||
                    detectedCodes.isNotEmpty()

            // Build annotated string (skip for large emoji)
            val annotatedText = if (isLargeEmoji) {
                null
            } else if (!searchQuery.isNullOrBlank() && text.contains(searchQuery, ignoreCase = true)) {
                buildSearchHighlightedText(text, searchQuery, textColor, detectedDates)
            } else if (hasClickableContent) {
                buildAnnotatedStringWithClickables(text, detectedDates, detectedPhoneNumbers, detectedCodes, textColor)
            } else {
                null
            }

            if (annotatedText != null) {
                ClickableText(
                    text = annotatedText,
                    style = textStyle.copy(color = textColor),
                    onClick = { offset ->
                        // Handle date clicks
                        annotatedText.getStringAnnotations("DATE", offset, offset).firstOrNull()?.let { annotation ->
                            val dateIndex = annotation.item.toIntOrNull()
                            if (dateIndex != null && dateIndex < detectedDates.size) {
                                openCalendarIntent(context, detectedDates[dateIndex], text, detectedDates)
                            }
                            return@ClickableText
                        }

                        // Handle phone clicks
                        annotatedText.getStringAnnotations("PHONE", offset, offset).firstOrNull()?.let { annotation ->
                            val phoneIndex = annotation.item.toIntOrNull()
                            if (phoneIndex != null && phoneIndex < detectedPhoneNumbers.size) {
                                selectedPhoneNumber = detectedPhoneNumbers[phoneIndex]
                                showPhoneMenu = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            return@ClickableText
                        }

                        // Handle code clicks
                        annotatedText.getStringAnnotations("CODE", offset, offset).firstOrNull()?.let { annotation ->
                            val codeIndex = annotation.item.toIntOrNull()
                            if (codeIndex != null && codeIndex < detectedCodes.size) {
                                copyToClipboard(context, detectedCodes[codeIndex].code, "Code copied")
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            return@ClickableText
                        }

                        onTimestampToggle()
                    }
                )

                // Phone number context menu
                DropdownMenu(
                    expanded = showPhoneMenu,
                    onDismissRequest = {
                        showPhoneMenu = false
                        selectedPhoneNumber = null
                    }
                ) {
                    selectedPhoneNumber?.let { phone ->
                        DropdownMenuItem(
                            text = { Text("Send message") },
                            onClick = {
                                openSmsIntent(context, phone.normalizedNumber)
                                showPhoneMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Message, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Call") },
                            onClick = {
                                openDialerIntent(context, phone.normalizedNumber)
                                showPhoneMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Phone, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to contacts") },
                            onClick = {
                                openAddContactIntent(context, phone.normalizedNumber)
                                showPhoneMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.PersonAdd, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            onClick = {
                                copyToClipboard(context, phone.matchedText, "Phone number copied")
                                showPhoneMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                        )
                    }
                }
            } else {
                Text(
                    text = text,
                    style = textStyle,
                    color = textColor
                )
            }
        }
    }
}

/**
 * Simple single-bubble rendering for text-only messages (optimized path).
 */
@Composable
private fun SimpleBubbleContent(
    message: MessageUiModel,
    firstUrl: DetectedUrl?,
    onLongPress: () -> Unit,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupPosition: MessageGroupPosition = MessageGroupPosition.SINGLE,
    searchQuery: String? = null,
    isCurrentSearchMatch: Boolean = false,
    onDownloadClick: ((String) -> Unit)? = null,
    downloadingAttachments: Map<String, Float> = emptyMap(),
    showDeliveryIndicator: Boolean = true,
    onReply: ((String) -> Unit)? = null,
    onSwipeStateChanged: ((Boolean) -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null
) {
    val bubbleColors = BothBubblesTheme.bubbleColors
    val isIMessage = message.messageSource == MessageSource.IMESSAGE.name
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
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

    // Tap-to-show timestamp state (default hidden)
    var showTimestamp by remember { mutableStateOf(false) }

    // Delivery status legend dialog state
    var showStatusLegend by remember { mutableStateOf(false) }

    // Detect dates in message text for underlining
    val detectedDates = remember(message.text) {
        if (message.text.isNullOrBlank()) emptyList()
        else DateParsingUtils.detectDates(message.text)
    }

    // Detect phone numbers in message text for underlining
    val detectedPhoneNumbers = remember(message.text) {
        if (message.text.isNullOrBlank()) emptyList()
        else PhoneAndCodeParsingUtils.detectPhoneNumbers(message.text)
    }

    // Detect verification codes in message text for underlining
    val detectedCodes = remember(message.text) {
        if (message.text.isNullOrBlank()) emptyList()
        else PhoneAndCodeParsingUtils.detectCodes(message.text)
    }

    // Phone number context menu state
    var showPhoneMenu by remember { mutableStateOf(false) }
    var selectedPhoneNumber by remember { mutableStateOf<DetectedPhoneNumber?>(null) }
    var phoneMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    // Determine message type label for swipe reveal
    val messageTypeLabel = when (message.messageSource) {
        MessageSource.LOCAL_SMS.name -> "SMS"
        MessageSource.LOCAL_MMS.name -> "MMS"
        MessageSource.SERVER_SMS.name -> "SMS"
        else -> "iMessage"
    }

    // Swipe container
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size -> containerWidthPx = size.width },
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

        // Main content row - offset during REPLY swipe and DATE_REVEAL (when clearance needed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset((replyDragOffset.value + adaptiveBubbleOffsetPx).roundToInt(), 0) }
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
            horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .onSizeChanged { size -> bubbleWidthPx = size.width }
            ) {
                // Message bubble with floating reactions overlay
                Box {
                    // Select shape based on group position for visual grouping
                    val bubbleShape = when (groupPosition) {
                        MessageGroupPosition.SINGLE -> if (message.isFromMe) MessageShapes.sentSingle else MessageShapes.receivedSingle
                        MessageGroupPosition.FIRST -> if (message.isFromMe) MessageShapes.sentFirst else MessageShapes.receivedFirst
                        MessageGroupPosition.MIDDLE -> if (message.isFromMe) MessageShapes.sentMiddle else MessageShapes.receivedMiddle
                        MessageGroupPosition.LAST -> if (message.isFromMe) MessageShapes.sentLast else MessageShapes.receivedLast
                    }

                    Surface(
                        shape = bubbleShape,
                        color = when {
                            message.isFromMe && isIMessage -> bubbleColors.iMessageSent
                            message.isFromMe -> bubbleColors.smsSent
                            else -> bubbleColors.received
                        },
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .then(
                                if (isCurrentSearchMatch) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = Color(0xFFFF9800),
                                        shape = bubbleShape
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .pointerInput(message.guid) {
                                detectTapGestures(
                                    onTap = { if (gesturesEnabled) showTimestamp = !showTimestamp },
                                    onLongPress = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onLongPress()
                                    }
                                )
                            }
                    ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 12.dp
                        )
                    ) {
                        // Attachments
                        // TEMPORARILY DISABLED: Skip attachment rendering to focus on text-only performance
                        // if (message.attachments.isNotEmpty()) {
                        //     Column(
                        //         verticalArrangement = Arrangement.spacedBy(4.dp),
                        //         modifier = Modifier.padding(bottom = if (message.text.isNullOrBlank()) 0.dp else 8.dp)
                        //     ) {
                        //         message.attachments.forEach { attachment ->
                        //             val isDownloading = attachment.guid in downloadingAttachments
                        //             val downloadProgress = downloadingAttachments[attachment.guid] ?: 0f
                        //             AttachmentContent(
                        //                 attachment = attachment,
                        //                 isFromMe = message.isFromMe,
                        //                 onMediaClick = onMediaClick,
                        //                 onDownloadClick = onDownloadClick,
                        //                 isDownloading = isDownloading,
                        //                 downloadProgress = downloadProgress
                        //             )
                        //         }
                        //     }
                        // }

                        // Subject line (bold, before message text)
                        message.subject?.let { subject ->
                            val textColor = when {
                                message.isFromMe && isIMessage -> bubbleColors.iMessageSentText
                                message.isFromMe -> bubbleColors.smsSentText
                                else -> bubbleColors.receivedText
                            }
                            val displaySubject = if (subject.isBlank()) "(no subject)" else subject
                            Text(
                                text = displaySubject,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                color = textColor
                            )
                        }

                        // Text content with clickable dates, phone numbers, codes, and search highlighting
                        // If there's a link preview, strip the URL from the displayed text
                        val displayText = if (firstUrl != null && !message.text.isNullOrBlank()) {
                            message.text.replace(firstUrl.matchedText, "").trim()
                        } else {
                            message.text
                        }

                        if (!displayText.isNullOrBlank()) {
                            val textColor = when {
                                message.isFromMe && isIMessage -> bubbleColors.iMessageSentText
                                message.isFromMe -> bubbleColors.smsSentText
                                else -> bubbleColors.receivedText
                            }

                            // Check for emoji-only messages with 3 or fewer emojis
                            // Use pre-computed value if text unchanged, otherwise compute
                            val emojiAnalysis = if (displayText == message.text && message.emojiAnalysis != null) {
                                message.emojiAnalysis!!
                            } else {
                                remember(displayText) { analyzeEmojis(displayText) }
                            }
                            val isLargeEmoji = emojiAnalysis.isEmojiOnly && emojiAnalysis.emojiCount in 1..3
                            val textStyle = if (isLargeEmoji) {
                                MaterialTheme.typography.bodyLarge.copy(fontSize = 48.sp)
                            } else {
                                MaterialTheme.typography.bodyLarge
                            }

                            val hasClickableContent = detectedDates.isNotEmpty() ||
                                    detectedPhoneNumbers.isNotEmpty() ||
                                    detectedCodes.isNotEmpty()

                            // Build annotated string with search highlighting (skip for large emoji)
                            val annotatedText = if (isLargeEmoji) {
                                null
                            } else if (!searchQuery.isNullOrBlank() && displayText.contains(searchQuery, ignoreCase = true)) {
                                buildSearchHighlightedText(
                                    text = displayText,
                                    searchQuery = searchQuery,
                                    textColor = textColor,
                                    detectedDates = detectedDates
                                )
                            } else if (hasClickableContent) {
                                buildAnnotatedStringWithClickables(
                                    text = displayText,
                                    detectedDates = detectedDates,
                                    detectedPhoneNumbers = detectedPhoneNumbers,
                                    detectedCodes = detectedCodes,
                                    textColor = textColor
                                )
                            } else {
                                null
                            }

                            if (annotatedText != null) {
                                ClickableText(
                                    text = annotatedText,
                                    style = textStyle.copy(color = textColor),
                                    onClick = { offset ->
                                        // Check for date clicks
                                        annotatedText.getStringAnnotations(
                                            tag = "DATE",
                                            start = offset,
                                            end = offset
                                        ).firstOrNull()?.let { annotation ->
                                            val dateIndex = annotation.item.toIntOrNull()
                                            if (dateIndex != null && dateIndex < detectedDates.size) {
                                                openCalendarIntent(
                                                    context,
                                                    detectedDates[dateIndex],
                                                    message.text ?: "",
                                                    detectedDates
                                                )
                                            }
                                            return@ClickableText
                                        }

                                        // Check for phone number clicks - show context menu
                                        annotatedText.getStringAnnotations(
                                            tag = "PHONE",
                                            start = offset,
                                            end = offset
                                        ).firstOrNull()?.let { annotation ->
                                            val phoneIndex = annotation.item.toIntOrNull()
                                            if (phoneIndex != null && phoneIndex < detectedPhoneNumbers.size) {
                                                selectedPhoneNumber = detectedPhoneNumbers[phoneIndex]
                                                showPhoneMenu = true
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                            return@ClickableText
                                        }

                                        // Check for code clicks - copy to clipboard
                                        annotatedText.getStringAnnotations(
                                            tag = "CODE",
                                            start = offset,
                                            end = offset
                                        ).firstOrNull()?.let { annotation ->
                                            val codeIndex = annotation.item.toIntOrNull()
                                            if (codeIndex != null && codeIndex < detectedCodes.size) {
                                                copyToClipboard(
                                                    context,
                                                    detectedCodes[codeIndex].code,
                                                    "Code copied"
                                                )
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            return@ClickableText
                                        }

                                        // Default tap behavior - toggle timestamp (disabled for placed stickers)
                                        if (gesturesEnabled) showTimestamp = !showTimestamp
                                    }
                                )

                                // Phone number context menu
                                DropdownMenu(
                                    expanded = showPhoneMenu,
                                    onDismissRequest = {
                                        showPhoneMenu = false
                                        selectedPhoneNumber = null
                                    },
                                    offset = phoneMenuOffset
                                ) {
                                    val phone = selectedPhoneNumber
                                    if (phone != null) {
                                        DropdownMenuItem(
                                            text = { Text("Send message") },
                                            onClick = {
                                                openSmsIntent(context, phone.normalizedNumber)
                                                showPhoneMenu = false
                                                selectedPhoneNumber = null
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Message, contentDescription = null)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Call") },
                                            onClick = {
                                                openDialerIntent(context, phone.normalizedNumber)
                                                showPhoneMenu = false
                                                selectedPhoneNumber = null
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Phone, contentDescription = null)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add to contacts") },
                                            onClick = {
                                                openAddContactIntent(context, phone.normalizedNumber)
                                                showPhoneMenu = false
                                                selectedPhoneNumber = null
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Copy") },
                                            onClick = {
                                                copyToClipboard(context, phone.matchedText, "Phone number copied")
                                                showPhoneMenu = false
                                                selectedPhoneNumber = null
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                            }
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = displayText ?: "",
                                    style = textStyle,
                                    color = textColor
                                )
                            }
                        }

                        // Link preview for the first URL in the message
                        // TEMPORARILY DISABLED: Skip link preview rendering to focus on text-only performance
                        // firstUrl?.let { detectedUrl ->
                        //     LinkPreview(
                        //         url = detectedUrl.url,
                        //         isFromMe = message.isFromMe
                        //     )
                        // }
                    }
                }

                // Display reactions floating at top corner outside the bubble
                // For outbound messages: top-left; for received: top-right
                if (message.reactions.isNotEmpty()) {
                    ReactionsDisplay(
                        reactions = message.reactions,
                        isFromMe = message.isFromMe,
                        modifier = Modifier
                            .align(if (message.isFromMe) Alignment.TopStart else Alignment.TopEnd)
                            .offset(
                                x = if (message.isFromMe) (-20).dp else 20.dp,
                                y = (-14).dp
                            )
                    )
                }
            }

            // Tap-to-reveal timestamp and message type below the bubble
            if (showTimestamp) {
                Text(
                    text = "${message.formattedTime} · $messageTypeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(
                            start = if (message.isFromMe) 0.dp else 12.dp,
                            end = if (message.isFromMe) 12.dp else 0.dp,
                            top = 2.dp
                        )
                )
            }

            // Delivery status indicator for outbound messages
            // iPhone-style: only show on the last outgoing message in the conversation
            // Hide when hasError since "Not Delivered" is already shown
            if (message.isFromMe && showDeliveryIndicator && !message.hasError) {
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 4.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DeliveryIndicator(
                        isSent = message.isSent,
                        isDelivered = message.isDelivered,
                        isRead = message.isRead,
                        hasError = message.hasError,
                        onClick = { showStatusLegend = true }
                    )
                }
            }

            // Retry button for failed outbound messages
            if (message.hasError && message.isFromMe && onRetry != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 4.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Not Delivered",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Surface(
                        onClick = { onRetry(message.guid) },
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Retry",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Status legend dialog
            if (showStatusLegend) {
                DeliveryStatusLegend(
                    onDismiss = { showStatusLegend = false }
                )
            }
        }

    }
    } // Close Box
}

@Composable
private fun DeliveryIndicator(
    isSent: Boolean,
    isDelivered: Boolean,
    isRead: Boolean,
    hasError: Boolean,
    onClick: (() -> Unit)? = null
) {
    // Determine status for animation key
    val status = when {
        hasError -> "error"
        isRead -> "read"
        isDelivered -> "delivered"
        isSent -> "sent"
        else -> "sending"
    }

    val icon = when {
        hasError -> Icons.Default.Error
        isRead -> Icons.Default.DoneAll
        isDelivered -> Icons.Default.DoneAll
        isSent -> Icons.Default.Check
        else -> Icons.Default.Schedule
    }

    // Animated color transition (150ms for snappy Android 16 feel)
    val color by animateColorAsState(
        targetValue = when {
            hasError -> MaterialTheme.colorScheme.error
            isRead -> Color(0xFF34B7F1)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "statusColor"
    )

    // Animated scale for status changes
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "statusScale"
    )

    AnimatedContent(
        targetState = status,
        transitionSpec = {
            fadeIn(tween(100)) togetherWith fadeOut(tween(100))
        },
        label = "statusIcon",
        modifier = Modifier
            .size(14.dp)
            .scale(scale)
            .then(
                if (onClick != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onClick() })
                    }
                } else {
                    Modifier
                }
            )
    ) { _ ->
        Icon(
            imageVector = icon,
            contentDescription = when {
                hasError -> "Failed"
                isRead -> "Read"
                isDelivered -> "Delivered"
                isSent -> "Sent"
                else -> "Sending"
            },
            tint = color,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * Legend dialog explaining message status icons.
 * Shown when user taps on a delivery status indicator.
 */
@Composable
private fun DeliveryStatusLegend(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message Status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendRow(
                    icon = Icons.Default.Schedule,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Sending"
                )
                LegendRow(
                    icon = Icons.Default.Check,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Sent"
                )
                LegendRow(
                    icon = Icons.Default.DoneAll,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Delivered"
                )
                LegendRow(
                    icon = Icons.Default.DoneAll,
                    color = Color(0xFF34B7F1),
                    label = "Read"
                )
                LegendRow(
                    icon = Icons.Default.Error,
                    color = MaterialTheme.colorScheme.error,
                    label = "Failed"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

@Composable
private fun LegendRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Reply indicator shown behind message bubble during reply swipe.
 */
@Composable
private fun ReplyIndicator(
    progress: Float,
    isFullyExposed: Boolean,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isFullyExposed) 1.1f else 0.6f + (progress * 0.4f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "replyScale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isFullyExposed)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "replyBg"
    )

    Box(
        modifier = modifier
            .size(40.dp)
            .scale(scale)
            .alpha(progress)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Reply,
            contentDescription = "Reply",
            tint = if (isFullyExposed)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Date and message type label shown during date reveal swipe.
 */
@Composable
private fun DateTypeLabel(
    time: String,
    type: String,
    progress: Float,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    val offsetX = if (isFromMe) {
        // Slides in from left for sent messages
        (-80 + (progress * 80)).dp
    } else {
        // Slides in from right for received messages
        (80 - (progress * 80)).dp
    }

    Column(
        horizontalAlignment = if (isFromMe) Alignment.Start else Alignment.End,
        modifier = modifier
            .offset(x = offsetX)
            .alpha(progress)
            .padding(horizontal = 8.dp)
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = type,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * Message type chip indicator (SMS/MMS/iMessage)
 */
@Composable
fun MessageTypeChip(
    messageSource: String,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (messageSource) {
        MessageSource.LOCAL_SMS.name -> "SMS" to Color(0xFF34C759)
        MessageSource.LOCAL_MMS.name -> "MMS" to Color(0xFF34C759)
        else -> "iMessage" to MaterialTheme.colorScheme.primary
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ====================
// Preview Functions
// ====================

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Incoming Message")
@Composable
private fun MessageBubbleIncomingPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Outgoing Message")
@Composable
private fun MessageBubbleOutgoingPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessageFromMe,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Long Message")
@Composable
private fun MessageBubbleLongPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleLongMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    name = "Dark Mode",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun MessageBubbleDarkPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper(darkTheme = true) {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "SMS Message")
@Composable
private fun MessageBubbleSmsPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleSmsMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Emoji Only")
@Composable
private fun MessageBubbleEmojiOnlyPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleEmojiOnlyMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Reply Message")
@Composable
private fun MessageBubbleReplyPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleReplyMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Failed Message")
@Composable
private fun MessageBubbleFailedPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleFailedMessage,
            onLongPress = {},
            onMediaClick = {},
            onRetry = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Group - First")
@Composable
private fun MessageBubbleGroupFirstPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {},
            groupPosition = MessageGroupPosition.FIRST
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Group - Middle")
@Composable
private fun MessageBubbleGroupMiddlePreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {},
            groupPosition = MessageGroupPosition.MIDDLE
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Group - Last")
@Composable
private fun MessageBubbleGroupLastPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {},
            groupPosition = MessageGroupPosition.LAST
        )
    }
}

/**
 * Quote-style indicator shown above a message that is a reply.
 * Displays a preview of the original message being replied to.
 * Tapping opens the thread overlay.
 */
@Composable
fun ReplyQuoteIndicator(
    replyPreview: ReplyPreviewData,
    isFromMe: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current

    Surface(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .padding(
                start = if (isFromMe) 48.dp else 0.dp,
                end = if (isFromMe) 0.dp else 48.dp,
                bottom = 4.dp
            )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vertical accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(
                        if (replyPreview.isFromMe)
                            BothBubblesTheme.bubbleColors.iMessageSent
                        else
                            MaterialTheme.colorScheme.primary
                    )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Sender name
                Text(
                    text = if (replyPreview.isFromMe) "You" else (replyPreview.senderName ?: "Unknown"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (replyPreview.isFromMe)
                        BothBubblesTheme.bubbleColors.iMessageSent
                    else
                        MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                // Preview text or placeholder
                val displayText = when {
                    replyPreview.isNotLoaded -> "Tap to view thread"
                    replyPreview.previewText.isNullOrBlank() && replyPreview.hasAttachment -> "[Attachment]"
                    replyPreview.previewText.isNullOrBlank() -> "[Message]"
                    else -> replyPreview.previewText
                }

                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}
