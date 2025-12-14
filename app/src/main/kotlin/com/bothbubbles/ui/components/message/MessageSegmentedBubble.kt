package com.bothbubbles.ui.components.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.data.local.db.entity.MessageSource
import com.bothbubbles.ui.components.attachment.AttachmentContent
import com.bothbubbles.ui.components.attachment.BorderlessMediaContent
import com.bothbubbles.ui.components.common.BorderlessLinkPreview
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
import com.bothbubbles.util.parsing.DateParsingUtils
import com.bothbubbles.util.parsing.DetectedPhoneNumber
import com.bothbubbles.util.parsing.DetectedUrl
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
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
internal fun TextBubbleSegment(
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
