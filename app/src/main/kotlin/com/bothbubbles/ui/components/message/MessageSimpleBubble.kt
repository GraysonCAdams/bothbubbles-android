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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.bothbubbles.ui.components.common.Avatar
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextLayoutResult
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.data.local.db.entity.MessageSource
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
 * Simple single-bubble rendering for text-only messages (optimized path).
 */
@Composable
internal fun SimpleBubbleContent(
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
    onDateRevealProgress: ((Float) -> Unit)? = null,
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
    onReplyQuoteLongPress: (() -> Unit)? = null,
    // Avatar props - rendered inside this component for proper alignment with bubble (not subtext)
    shouldShowAvatarSpace: Boolean = false,
    showAvatar: Boolean = false,
    avatarSize: Dp = 28.dp,
    onAvatarClick: (() -> Unit)? = null
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

    // Report date reveal progress to parent for fading out external elements (e.g., EtaStopSharingLink)
    LaunchedEffect(dateRevealProgress.value) {
        onDateRevealProgress?.invoke(dateRevealProgress.value)
    }

    // Delivery status legend dialog state
    var showStatusLegend by remember { mutableStateOf(false) }

    // Failed message dialog state
    var showFailedMessageDialog by remember { mutableStateOf(false) }

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

    // Text layout result for handling clickable text without blocking long press
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Determine message type label for swipe reveal
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
            // Outer column that contains avatar+bubble row and subtext
            Column(
                horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start
            ) {
                // Inner row for avatar + bubble with bottom alignment
                // This ensures avatar aligns with bubble bottom, not with subtext
                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Avatar space for received messages in group chats
                    if (shouldShowAvatarSpace) {
                        if (showAvatar) {
                            Avatar(
                                name = message.senderName ?: "?",
                                avatarPath = message.senderAvatarPath,
                                size = avatarSize,
                                hasContactInfo = message.senderHasContactInfo,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .then(
                                        if (onAvatarClick != null) {
                                            Modifier
                                                .clickable(onClick = onAvatarClick)
                                                .semantics {
                                                    onClick(label = "View ${message.senderName ?: "sender"} contact details") { true }
                                                }
                                        } else {
                                            Modifier
                                        }
                                    )
                            )
                        } else {
                            // Empty space to maintain alignment
                            Spacer(modifier = Modifier.width(avatarSize + 8.dp))
                        }
                    }

                    // Bubble content column (no subtext - that goes outside this row)
                    Column(
                        horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start,
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .graphicsLayer { clip = false }
                            .onSizeChanged { size -> bubbleWidthPx = size.width }
                    ) {
                // Check for large emoji messages (1-3 emojis only) - need to compute before Surface
                val displayTextForEmoji = if (firstUrl != null && !message.text.isNullOrBlank()) {
                    message.text.replace(firstUrl.matchedText, "").trim()
                } else {
                    message.text
                }
                val emojiAnalysis = if (displayTextForEmoji == message.text && message.emojiAnalysis != null) {
                    message.emojiAnalysis!!
                } else {
                    remember(displayTextForEmoji) { analyzeEmojis(displayTextForEmoji ?: "") }
                }
                val isLargeEmoji = !displayTextForEmoji.isNullOrBlank() &&
                        emojiAnalysis.isEmojiOnly && emojiAnalysis.emojiCount in 1..3

                // Message bubble with floating reactions overlay
                // Use layout modifier to expand bounds for reactions, preventing clipping
                val hasReactions = message.reactions.isNotEmpty()
                val reactionOverflowPx = with(LocalDensity.current) { 17.dp.toPx().toInt() }

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
                ) {
                    // Select shape based on group position for visual grouping
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
                            .onGloballyPositioned { coordinates ->
                                onBoundsChanged?.invoke(coordinates.boundsInWindow())
                            }
                            .pointerInput(message.guid, isSelectionMode) {
                                detectTapGestures(
                                    onTap = {
                                        if (isSelectionMode) {
                                            // In selection mode, tap toggles selection
                                            onSelectionToggle?.invoke()
                                        } else if (gesturesEnabled) {
                                            // Normal mode: toggle timestamp visibility
                                            showTimestamp = !showTimestamp
                                        }
                                    },
                                    onLongPress = {
                                        HapticUtils.onLongPress(hapticFeedback)
                                        onLongPress()
                                    }
                                )
                            }
                    ) {
                    // Determine bubble color for passing to inline quote
                    val currentBubbleColor = if (isLargeEmoji) Color.Transparent else when {
                        message.isFromMe && isIMessage -> bubbleColors.iMessageSent
                        message.isFromMe -> bubbleColors.smsSent
                        else -> bubbleColors.received
                    }

                    Column(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 12.dp
                        )
                    ) {
                        // Inline reply quote at the top of the bubble
                        replyPreview?.let { preview ->
                            InlineReplyQuote(
                                replyPreview = preview,
                                isFromMe = message.isFromMe,
                                bubbleColor = currentBubbleColor,
                                onTap = onReplyQuoteTap,
                                onLongPress = onReplyQuoteLongPress,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        // Attachments
                        // TEMPORARILY DISABLED: Skip attachment rendering to focus on text-only performance
                        // if (message.attachments.isNotEmpty()) {
                        //     Column(
                        //         verticalArrangement = Arrangement.spacedBy(4.dp),
                        //         modifier = Modifier.padding(bottom = if (message.text.isNullOrBlank()) 0.dp else 8.dp)
                        //     ) {
                        //         message.attachments.forEach { attachment ->
                        //             val progressState = rememberDownloadProgress(attachmentDelegate, attachment.guid)
                        //             val progress = progressState.value
                        //             AttachmentContent(
                        //                 attachment = attachment,
                        //                 isFromMe = message.isFromMe,
                        //                 onMediaClick = onMediaClick,
                        //                 onDownloadClick = onDownloadClick,
                        //                 isDownloading = progress != null,
                        //                 downloadProgress = progress ?: 0f
                        //             )
                        //         }
                        //     }
                        // }

                        // Subject line (bold, before message text)
                        // For local MMS: hide empty subjects (carriers often set empty subject metadata)
                        // For server messages (iMessage/server SMS): show "(no subject)" if explicitly blank
                        message.subject?.let { subject ->
                            val shouldShow = if (message.isServerOrigin) {
                                true // Server messages: always show subject if field exists
                            } else {
                                subject.isNotBlank() // Local MMS: only show if has content
                            }
                            if (shouldShow) {
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

                            // Build annotated string with search highlighting (skip for large emoji and mentions)
                            val annotatedText = if (isLargeEmoji || hasMentions) {
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

                            // Use MentionText for messages with mentions
                            if (hasMentions && !isLargeEmoji) {
                                MentionText(
                                    text = displayText,
                                    mentions = message.mentions,
                                    onMentionClick = { address -> onMentionClick?.invoke(address) },
                                    textColor = textColor,
                                    style = textStyle
                                )
                            } else if (annotatedText != null) {
                                // Use BasicText with custom tap handling that doesn't block long press
                                // This allows the parent Surface's onLongPress to work for tapbacks
                                val clickableText = annotatedText
                                BasicText(
                                    text = clickableText,
                                    style = textStyle.copy(color = textColor),
                                    onTextLayout = { textLayoutResult = it },
                                    modifier = Modifier.pointerInput(clickableText, isSelectionMode) {
                                        detectTapGestures(
                                            onTap = { tapOffset ->
                                                // Disable all clickable interactions in selection mode
                                                if (isSelectionMode) {
                                                    onSelectionToggle?.invoke()
                                                    return@detectTapGestures
                                                }

                                                // Get character offset from tap position
                                                val layoutResult = textLayoutResult ?: return@detectTapGestures
                                                val offset = layoutResult.getOffsetForPosition(tapOffset)

                                                // Check for date clicks
                                                clickableText.getStringAnnotations(
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
                                                    return@detectTapGestures
                                                }

                                                // Check for phone number clicks - show context menu
                                                clickableText.getStringAnnotations(
                                                    tag = "PHONE",
                                                    start = offset,
                                                    end = offset
                                                ).firstOrNull()?.let { annotation ->
                                                    val phoneIndex = annotation.item.toIntOrNull()
                                                    if (phoneIndex != null && phoneIndex < detectedPhoneNumbers.size) {
                                                        selectedPhoneNumber = detectedPhoneNumbers[phoneIndex]
                                                        showPhoneMenu = true
                                                        HapticUtils.onTap(hapticFeedback)
                                                    }
                                                    return@detectTapGestures
                                                }

                                                // Check for code clicks - copy to clipboard
                                                clickableText.getStringAnnotations(
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
                                                        HapticUtils.onTap(hapticFeedback)
                                                    }
                                                    return@detectTapGestures
                                                }

                                                // Default tap behavior - toggle timestamp
                                                if (gesturesEnabled) showTimestamp = !showTimestamp
                                            },
                                            onLongPress = {
                                                // Propagate long press to parent for tapback menu
                                                HapticUtils.onLongPress(hapticFeedback)
                                                onLongPress()
                                            }
                                        )
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

                        // Edited indicator for edited messages
                        if (message.isEdited) {
                            EditedIndicator(
                                formattedEditTime = message.formattedEditTime,
                                editHistory = message.editHistory,
                                isFromMe = message.isFromMe,
                                isIMessage = isIMessage
                            )
                        }
                    }
                }

                // Display reactions floating at top corner outside the bubble
                // For outbound messages: top-left; for received: top-right
                // With layout modifier expanding bounds by 20dp, bubble starts at y=20dp.
                // Negative y offset pushes reaction higher to sit on the bubble's corner.
                if (hasReactions) {
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
                    } // Close inner Column (bubble content)
                } // Close inner Row (avatar + bubble)

                // Subtext elements - outside the avatar row so avatar aligns with bubble, not subtext
                // Calculate start padding to align with bubble when avatar space is present
                val subtextStartPadding = if (shouldShowAvatarSpace) avatarSize + 8.dp else 0.dp

            // Tap-to-reveal timestamp and message type below the bubble
            AnimatedVisibility(
                visible = showTimestamp,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Text(
                    text = "${message.formattedTime} · $messageTypeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(
                            start = if (message.isFromMe) 0.dp else (12.dp + subtextStartPadding),
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
                    // Tap to see details button
                    Surface(
                        onClick = { showFailedMessageDialog = true },
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

        } // Close Row (main content)
    } // Close Box (main content container)
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

/**
 * Indicator showing that a message has been edited.
 * Displayed as "Edited" text that can be tapped to show edit history.
 * When tapped, expands to show previous versions of the message (iOS-style).
 * If no history is available (for messages edited before this feature), shows the edit time.
 */
@Composable
private fun EditedIndicator(
    formattedEditTime: String?,
    editHistory: List<EditHistoryEntry>,
    isFromMe: Boolean,
    isIMessage: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val bubbleColors = BothBubblesTheme.bubbleColors

    // Text color matches the message text for the bubble type
    val textColor = when {
        isFromMe && isIMessage -> bubbleColors.iMessageSentText.copy(alpha = 0.7f)
        isFromMe -> bubbleColors.smsSentText.copy(alpha = 0.7f)
        else -> bubbleColors.receivedText.copy(alpha = 0.7f)
    }

    // Faded color for historical versions
    val historyTextColor = textColor.copy(alpha = 0.5f)

    val hasHistory = editHistory.isNotEmpty()

    Column(modifier = modifier.padding(top = 4.dp)) {
        // Edit history or "no history" message (shown when expanded)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                if (hasHistory) {
                    // Show previous versions in reverse chronological order (newest first)
                    editHistory.forEach { entry ->
                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                            // Previous message text (faded with strikethrough)
                            if (!entry.text.isNullOrBlank()) {
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = historyTextColor,
                                    textDecoration = TextDecoration.LineThrough
                                )
                            }
                        }
                    }
                } else {
                    // No history available - show edit time
                    Text(
                        text = "Edited${formattedEditTime?.let { " at $it" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = historyTextColor
                    )
                }
            }
        }

        // "Edited" indicator (or "Hide Edits" when expanded)
        Text(
            text = if (expanded && hasHistory) "Hide Edits" else if (expanded) "Hide" else "Edited",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Normal
            ),
            color = textColor,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}
