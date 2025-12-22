package com.bothbubbles.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.chat.delegates.ChatAttachmentDelegate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.ui.components.attachment.AttachmentContent
import com.bothbubbles.ui.components.attachment.BorderlessMediaContent
import com.bothbubbles.ui.components.attachment.YouTubeAttachment
import com.bothbubbles.ui.components.common.BorderlessLinkPreview
import com.bothbubbles.ui.components.common.SmartLinkPreview
import com.bothbubbles.ui.components.common.buildAnnotatedStringWithClickables
import com.bothbubbles.ui.components.common.buildSearchHighlightedText
import com.bothbubbles.ui.components.common.copyToClipboard
import com.bothbubbles.ui.components.common.openAddContactIntent
import com.bothbubbles.ui.components.common.openCalendarIntent
import com.bothbubbles.ui.components.common.openDialerIntent
import com.bothbubbles.ui.components.common.openSmsIntent
import com.bothbubbles.ui.theme.BothBubblesTheme
import com.bothbubbles.ui.theme.MessageShapes
import com.bothbubbles.util.EmojiUtils.analyzeEmojis
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.util.parsing.DateParsingUtils
import com.bothbubbles.util.parsing.DetectedPhoneNumber
import com.bothbubbles.util.parsing.DetectedUrl
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import com.bothbubbles.ui.components.dialogs.FailedMessageDialog
import com.bothbubbles.util.error.MessageErrorCode
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Segmented message rendering for messages with media or link previews.
 * Renders media and links outside bubbles as standalone elements.
 */
@Composable
internal fun SegmentedMessageBubble(
    message: MessageUiModel,
    firstUrl: DetectedUrl?,
    onLongPress: () -> Unit,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupPosition: MessageGroupPosition = MessageGroupPosition.SINGLE,
    searchQuery: String? = null,
    isCurrentSearchMatch: Boolean = false,
    onDownloadClick: ((String) -> Unit)? = null,
    attachmentDelegate: ChatAttachmentDelegate? = null,
    showDeliveryIndicator: Boolean = true,
    onReply: ((String) -> Unit)? = null,
    onSwipeStateChanged: ((Boolean) -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null,
    onRetryAsSms: ((String) -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    canRetryAsSms: Boolean = false,
    onBoundsChanged: ((Rect) -> Unit)? = null,
    // Callback when a mention is clicked (opens contact details)
    onMentionClick: ((String) -> Unit)? = null,
    // Multi-message selection support
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectionToggle: (() -> Unit)? = null,
    // Inline reply quote support
    replyPreview: ReplyPreviewData? = null,
    onReplyQuoteTap: (() -> Unit)? = null,
    onReplyQuoteLongPress: (() -> Unit)? = null
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

    // Failed message dialog state
    var showFailedMessageDialog by remember { mutableStateOf(false) }

    // Message type label
    val messageTypeLabel = when (message.messageSource) {
        MessageSource.LOCAL_SMS.name -> "SMS"
        MessageSource.LOCAL_MMS.name -> "MMS"
        MessageSource.SERVER_SMS.name -> "SMS"
        else -> "iMessage"
    }

    // Main content container
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { clip = false }
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
                .graphicsLayer { clip = false }
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
                                                    HapticUtils.onThresholdCrossed(hapticFeedback)
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
                    .widthIn(max = 240.dp)
                    .graphicsLayer { clip = false }
                    .onSizeChanged { size -> bubbleWidthPx = size.width }
            ) {
                // Render segments with reactions on first segment
                // Use layout modifier to expand bounds for reactions, preventing clipping
                val hasReactions = message.reactions.isNotEmpty()
                val reactionOverflowPx = with(LocalDensity.current) { 17.dp.toPx().toInt() }

                // Check if first segment is media - if so, reply preview becomes a card header
                val firstSegmentIsMedia = segments.firstOrNull() is MessageSegment.MediaSegment

                Box(
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            // Expand height to include reaction overflow space at top
                            val extraHeight = if (hasReactions) reactionOverflowPx else 0
                            layout(placeable.width, placeable.height + extraHeight) {
                                // Place content shifted down by overflow amount
                                placeable.place(0, extraHeight)
                            }
                        }
                        .onGloballyPositioned { coordinates ->
                            onBoundsChanged?.invoke(coordinates.boundsInWindow())
                        }
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Reply quote as standalone bubble when first segment is NOT media
                        if (replyPreview != null && !firstSegmentIsMedia) {
                            val quoteBubbleColor = when {
                                message.isFromMe && isIMessage -> bubbleColors.iMessageSent
                                message.isFromMe -> bubbleColors.smsSent
                                else -> bubbleColors.received
                            }
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = quoteBubbleColor,
                                modifier = Modifier.widthIn(max = 240.dp)
                            ) {
                                Box(modifier = Modifier.padding(12.dp)) {
                                    InlineReplyQuote(
                                        replyPreview = replyPreview,
                                        isFromMe = message.isFromMe,
                                        bubbleColor = quoteBubbleColor,
                                        onTap = onReplyQuoteTap,
                                        onLongPress = onReplyQuoteLongPress
                                    )
                                }
                            }
                        }

                        segments.forEachIndexed { index, segment ->
                            when (segment) {
                                is MessageSegment.MediaSegment -> {
                                    val progressState = rememberDownloadProgress(attachmentDelegate, segment.attachment.guid)
                                    val progress = progressState.value
                                    val isFirstMedia = index == 0

                                    // For first media with reply preview: render as card with header
                                    if (isFirstMedia && replyPreview != null) {
                                        val cardColor = when {
                                            message.isFromMe && isIMessage -> bubbleColors.iMessageSent
                                            message.isFromMe -> bubbleColors.smsSent
                                            else -> bubbleColors.received
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            color = cardColor,
                                            modifier = Modifier.widthIn(max = 240.dp)
                                        ) {
                                            Column {
                                                // Reply header with card-appropriate colors
                                                Box(modifier = Modifier.padding(12.dp)) {
                                                    InlineReplyQuote(
                                                        replyPreview = replyPreview,
                                                        isFromMe = message.isFromMe,
                                                        bubbleColor = cardColor,
                                                        onTap = onReplyQuoteTap,
                                                        onLongPress = onReplyQuoteLongPress,
                                                        isCardHeader = true
                                                    )
                                                }
                                                // Media with only bottom corners rounded (top connects to header)
                                                Box(
                                                    modifier = Modifier.clip(
                                                        RoundedCornerShape(
                                                            topStart = 0.dp,
                                                            topEnd = 0.dp,
                                                            bottomStart = 18.dp,
                                                            bottomEnd = 18.dp
                                                        )
                                                    )
                                                ) {
                                                    BorderlessMediaContent(
                                                        attachment = segment.attachment,
                                                        isFromMe = message.isFromMe,
                                                        onMediaClick = onMediaClick,
                                                        onTimestampToggle = { if (gesturesEnabled && !isSelectionMode) showTimestamp = !showTimestamp },
                                                        maxWidth = 240.dp,
                                                        onDownloadClick = onDownloadClick,
                                                        isDownloading = progress != null,
                                                        downloadProgress = progress ?: 0f,
                                                        isPlacedSticker = message.isPlacedSticker,
                                                        messageGuid = message.guid,
                                                        onLongPress = {
                                                            HapticUtils.onLongPress(hapticFeedback)
                                                            onLongPress()
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // Standalone media (no reply or not first)
                                        BorderlessMediaContent(
                                            attachment = segment.attachment,
                                            isFromMe = message.isFromMe,
                                            onMediaClick = onMediaClick,
                                            onTimestampToggle = { if (gesturesEnabled && !isSelectionMode) showTimestamp = !showTimestamp },
                                            maxWidth = 240.dp,
                                            onDownloadClick = onDownloadClick,
                                            isDownloading = progress != null,
                                            downloadProgress = progress ?: 0f,
                                            isPlacedSticker = message.isPlacedSticker,
                                            messageGuid = message.guid,
                                            onLongPress = {
                                                HapticUtils.onLongPress(hapticFeedback)
                                                onLongPress()
                                            }
                                        )
                                    }
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
                                        onTimestampToggle = { if (gesturesEnabled && !isSelectionMode) showTimestamp = !showTimestamp },
                                        showSubject = isFirstTextSegment,
                                        onMentionClick = onMentionClick,
                                        isSelectionMode = isSelectionMode,
                                        onSelectionToggle = onSelectionToggle
                                    )
                                }

                                is MessageSegment.LinkPreviewSegment -> {
                                    SmartLinkPreview(
                                        url = segment.url,
                                        messageGuid = message.guid,
                                        isFromMe = message.isFromMe,
                                        maxWidth = 240.dp,
                                        onLongPress = {
                                            HapticUtils.onLongPress(hapticFeedback)
                                            onLongPress()
                                        }
                                    )
                                }

                                is MessageSegment.YouTubeVideoSegment -> {
                                    YouTubeAttachment(
                                        videoId = segment.videoId,
                                        originalUrl = segment.originalUrl,
                                        thumbnailUrl = segment.thumbnailUrl,
                                        startTimeSeconds = segment.startTimeSeconds,
                                        isShort = segment.isShort,
                                        maxWidth = 240.dp,
                                        onLongPress = {
                                            HapticUtils.onLongPress(hapticFeedback)
                                            onLongPress()
                                        }
                                    )
                                }

                                is MessageSegment.FileSegment -> {
                                    val progressState = rememberDownloadProgress(attachmentDelegate, segment.attachment.guid)
                                    val progress = progressState.value
                                    AttachmentContent(
                                        attachment = segment.attachment,
                                        isFromMe = message.isFromMe,
                                        onMediaClick = onMediaClick,
                                        onTimestampToggle = { if (gesturesEnabled && !isSelectionMode) showTimestamp = !showTimestamp },
                                        onDownloadClick = onDownloadClick,
                                        isDownloading = progress != null,
                                        downloadProgress = progress ?: 0f,
                                        onLongPress = {
                                            HapticUtils.onLongPress(hapticFeedback)
                                            onLongPress()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Reactions overlay on top-corner
                    // With layout modifier expanding bounds by 20dp, bubble starts at y=20dp.
                    // Negative y offset pushes reaction higher to sit on the bubble's corner.
                    if (message.reactions.isNotEmpty()) {
                        ReactionsDisplay(
                            reactions = message.reactions,
                            isFromMe = message.isFromMe,
                            modifier = Modifier
                                .align(if (message.isFromMe) Alignment.TopStart else Alignment.TopEnd)
                                .offset(
                                    x = if (message.isFromMe) (-14).dp else 14.dp,
                                    y = (-15).dp
                                )
                        )
                    }
                }

                // Tap-to-reveal timestamp
                AnimatedVisibility(
                    visible = showTimestamp,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
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
                            .padding(end = 4.dp),
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

                // Error indicator and actions for failed outbound messages
                if (message.hasError && message.isFromMe) {
                    val errorTitle = MessageErrorCode.getErrorTitle(message.errorCode)
                    val suggestsSms = MessageErrorCode.suggestsSmsRetry(message.errorCode)

                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = 4.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Show specific error title for error 22, otherwise generic "Not Delivered"
                        Text(
                            text = if (suggestsSms) errorTitle else "Not Delivered",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        // Tap to see details button - 48dp touch target for accessibility
                        Surface(
                            onClick = { showFailedMessageDialog = true },
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .padding(vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Retry options",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (suggestsSms && canRetryAsSms) "Try SMS" else "Retry",
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

                // Failed message dialog with detailed error info and retry options
                FailedMessageDialog(
                    visible = showFailedMessageDialog,
                    errorCode = message.errorCode,
                    errorMessage = message.errorMessage,
                    canRetryAsSms = canRetryAsSms,
                    onRetry = { onRetry?.invoke(message.guid) },
                    onRetryAsSms = { onRetryAsSms?.invoke(message.guid) },
                    onDelete = { onDeleteMessage?.invoke(message.guid) },
                    onDismiss = { showFailedMessageDialog = false }
                )
            }
        }
    } // Close Box (main content container)
}

/**
 * Text bubble segment for use in segmented message rendering.
 */
@Composable
internal fun TextBubbleSegment(
    message: MessageUiModel,
    text: String,
    groupPosition: MessageGroupPosition,
    searchQuery: String?,
    isCurrentSearchMatch: Boolean,
    onLongPress: () -> Unit,
    onTimestampToggle: () -> Unit,
    showSubject: Boolean = false,
    onMentionClick: ((String) -> Unit)? = null,
    // Multi-message selection support
    isSelectionMode: Boolean = false,
    onSelectionToggle: (() -> Unit)? = null
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

    // Check for emoji-only messages with 3 or fewer emojis - compute before Surface
    val emojiAnalysis = message.emojiAnalysis ?: remember(text) { analyzeEmojis(text) }
    val isLargeEmoji = emojiAnalysis.isEmojiOnly && emojiAnalysis.emojiCount in 1..3

    val bubbleShape = when (groupPosition) {
        MessageGroupPosition.SINGLE -> if (message.isFromMe) MessageShapes.sentSingle else MessageShapes.receivedSingle
        MessageGroupPosition.FIRST -> if (message.isFromMe) MessageShapes.sentFirst else MessageShapes.receivedFirst
        MessageGroupPosition.MIDDLE -> if (message.isFromMe) MessageShapes.sentMiddle else MessageShapes.receivedMiddle
        MessageGroupPosition.LAST -> if (message.isFromMe) MessageShapes.sentLast else MessageShapes.receivedLast
    }

    Surface(
        shape = bubbleShape,
        color = if (isLargeEmoji) Color.Transparent else when {
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
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = bubbleShape
                    )
                } else {
                    Modifier
                }
            )
            .pointerInput(message.guid, isSelectionMode) {
                detectTapGestures(
                    onTap = {
                        if (isSelectionMode) {
                            // In selection mode, tap toggles selection
                            onSelectionToggle?.invoke()
                        } else {
                            // Normal mode: toggle timestamp visibility
                            onTimestampToggle()
                        }
                    },
                    onLongPress = {
                        HapticUtils.onLongPress(hapticFeedback)
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
            // For local MMS: hide empty subjects (carriers often set empty subject metadata)
            // For server messages (iMessage/server SMS): show "(no subject)" if explicitly blank
            if (showSubject) {
                message.subject?.let { subject ->
                    val shouldShow = if (message.isServerOrigin) {
                        true // Server messages: always show subject if field exists
                    } else {
                        subject.isNotBlank() // Local MMS: only show if has content
                    }
                    if (shouldShow) {
                        val displaySubject = if (subject.isBlank()) "(no subject)" else subject
                        Text(
                            text = displaySubject,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = textColor
                        )
                    }
                }
            }

            // Text style based on emoji analysis (computed before Surface)
            val textStyle = if (isLargeEmoji) {
                MaterialTheme.typography.bodyLarge.copy(fontSize = 48.sp)
            } else {
                MaterialTheme.typography.bodyLarge
            }

            // Check if message has mentions
            val hasMentions = message.mentions.isNotEmpty() && onMentionClick != null

            val hasClickableContent = detectedDates.isNotEmpty() ||
                    detectedPhoneNumbers.isNotEmpty() ||
                    detectedCodes.isNotEmpty()

            // Build annotated string (skip for large emoji and mentions)
            val annotatedText = if (isLargeEmoji || hasMentions) {
                null
            } else if (!searchQuery.isNullOrBlank() && text.contains(searchQuery, ignoreCase = true)) {
                buildSearchHighlightedText(
                    text = text,
                    searchQuery = searchQuery,
                    textColor = textColor,
                    detectedDates = detectedDates
                )
            } else if (hasClickableContent) {
                buildAnnotatedStringWithClickables(text, detectedDates, detectedPhoneNumbers, detectedCodes, textColor)
            } else {
                null
            }

            // Use MentionText for messages with mentions
            if (hasMentions && !isLargeEmoji) {
                MentionText(
                    text = text,
                    mentions = message.mentions,
                    onMentionClick = { address -> onMentionClick?.invoke(address) },
                    textColor = textColor,
                    style = textStyle
                )
            } else if (annotatedText != null) {
                ClickableText(
                    text = annotatedText,
                    style = textStyle.copy(color = textColor),
                    onClick = { offset ->
                        // Disable all clickable interactions in selection mode
                        if (isSelectionMode) return@ClickableText

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
                                HapticUtils.onTap(hapticFeedback)
                            }
                            return@ClickableText
                        }

                        // Handle code clicks
                        annotatedText.getStringAnnotations("CODE", offset, offset).firstOrNull()?.let { annotation ->
                            val codeIndex = annotation.item.toIntOrNull()
                            if (codeIndex != null && codeIndex < detectedCodes.size) {
                                copyToClipboard(context, detectedCodes[codeIndex].code, "Code copied")
                                HapticUtils.onTap(hapticFeedback)
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

@Composable
private fun rememberDownloadProgress(
    delegate: ChatAttachmentDelegate?,
    guid: String
): androidx.compose.runtime.State<Float?> {
    return androidx.compose.runtime.remember(delegate, guid) {
        delegate?.downloadProgress
            ?.map { it[guid] }
            ?.distinctUntilChanged()
    }?.collectAsStateWithLifecycle(initialValue = null)
        ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(null) }
}
