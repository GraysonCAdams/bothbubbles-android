package com.bothbubbles.ui.conversations

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.components.common.GroupAvatar
import com.bothbubbles.ui.components.conversation.UnreadBadge
import com.bothbubbles.util.HapticUtils
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PinnedConversationsRow(
    conversations: List<ConversationUiModel>,
    onConversationClick: (ConversationUiModel) -> Unit,
    onConversationLongClick: (String) -> Unit = {},
    onUnpin: (String) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
    onAvatarClick: (ConversationUiModel) -> Unit = {},
    selectedConversations: Set<String> = emptySet(),
    isSelectionMode: Boolean = false,
    onDragOverlayStart: (ConversationUiModel, Offset) -> Unit = { _, _ -> },
    onDragOverlayMove: (Offset) -> Unit = {},
    onDragOverlayEnd: () -> Unit = {},
    bumpResetKey: Int = 0, // Increment to reset bump animation (e.g., when returning to screen)
    modifier: Modifier = Modifier
) {
    // Item width including spacing (100dp item + 12dp spacing)
    val itemWidth = 112.dp
    val density = LocalDensity.current
    val itemWidthPx = with(density) { itemWidth.toPx() }
    val haptic = LocalHapticFeedback.current

    // Threshold for drag-to-unpin (drag downward past this to unpin)
    val unpinThresholdPx = with(density) { 60.dp.toPx() }

    // Drag state
    var draggedItemIndex by remember { mutableIntStateOf(-1) }
    var draggedItemGuid by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var overlayDragOffsetX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Track item positions for overlay
    val itemPositions = remember { mutableStateMapOf<String, Offset>() }

    // Mutable list for reordering during drag
    var currentOrder by remember(conversations) { mutableStateOf(conversations.map { it.guid }) }

    // Reset order when conversations change
    LaunchedEffect(conversations) {
        if (!isDragging) {
            currentOrder = conversations.map { it.guid }
        }
    }

    // Map guid to conversation for lookup - don't use remember so updates are always reflected
    val conversationMap = conversations.associateBy { it.guid }

    // Build ordered list of actual conversations (not just GUIDs) so LazyRow detects data changes
    val orderedConversations = currentOrder.mapNotNull { guid -> conversationMap[guid] }

    // Scroll state for tracking visible items
    val listState = rememberLazyListState()

    // Pre-compute which indices have unread messages (stable for derivedStateOf)
    val unreadIndices = remember(orderedConversations) {
        orderedConversations.mapIndexedNotNull { index, conv ->
            if (conv.unreadCount > 0) index else null
        }.toSet()
    }

    // Minimum fraction of item that must be visible to count as "in view"
    // User wants 70% visible to still count as "outside", so threshold is > 70%
    // Using 1.0 means only 100% visible counts as "in view"
    val minVisibleFraction = 1.0f

    // Calculate which unread items are off-screen on each side
    val hasUnreadOffscreenLeft by remember(unreadIndices) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) return@derivedStateOf false

            val firstVisibleItem = visibleItemsInfo.firstOrNull() ?: return@derivedStateOf false
            val firstVisibleIndex = firstVisibleItem.index

            // Check if any unread items are completely off-screen to the left
            val hasUnreadCompletelyOffscreen = unreadIndices.any { it < firstVisibleIndex }
            if (hasUnreadCompletelyOffscreen) return@derivedStateOf true

            // Check if any visible unread item is insufficiently visible (clipped on left)
            val viewportStart = layoutInfo.viewportStartOffset
            val viewportEnd = layoutInfo.viewportEndOffset

            visibleItemsInfo.any { itemInfo ->
                if (itemInfo.index !in unreadIndices) return@any false

                val itemStart = itemInfo.offset
                // Only check left-side clipping
                if (itemStart >= viewportStart) return@any false

                // Calculate visible fraction
                val itemEnd = itemStart + itemInfo.size
                val visibleStart = maxOf(itemStart, viewportStart)
                val visibleEnd = minOf(itemEnd, viewportEnd)
                val visibleWidth = (visibleEnd - visibleStart).coerceAtLeast(0)
                val visibleFraction = if (itemInfo.size > 0) visibleWidth.toFloat() / itemInfo.size else 1f

                visibleFraction < minVisibleFraction
            }
        }
    }

    val hasUnreadOffscreenRight by remember(unreadIndices) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) return@derivedStateOf false

            val lastVisibleItem = visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val lastVisibleIndex = lastVisibleItem.index

            // Check if any unread items are completely off-screen to the right
            val hasUnreadCompletelyOffscreen = unreadIndices.any { it > lastVisibleIndex }
            if (hasUnreadCompletelyOffscreen) return@derivedStateOf true

            // Check if any visible unread item is insufficiently visible (clipped on right)
            val viewportStart = layoutInfo.viewportStartOffset
            val viewportEnd = layoutInfo.viewportEndOffset

            visibleItemsInfo.any { itemInfo ->
                if (itemInfo.index !in unreadIndices) return@any false

                val itemEnd = itemInfo.offset + itemInfo.size
                // Only check right-side clipping
                if (itemEnd <= viewportEnd) return@any false

                // Calculate visible fraction
                val itemStart = itemInfo.offset
                val visibleStart = maxOf(itemStart, viewportStart)
                val visibleEnd = minOf(itemEnd, viewportEnd)
                val visibleWidth = (visibleEnd - visibleStart).coerceAtLeast(0)
                val visibleFraction = if (itemInfo.size > 0) visibleWidth.toFloat() / itemInfo.size else 1f

                visibleFraction < minVisibleFraction
            }
        }
    }

    // Scroll bump animation state
    // Key on bumpResetKey so animation resets when returning to screen
    var bumpAnimationShown by remember(bumpResetKey) { mutableStateOf(false) }
    var userHasScrolledAway by remember(bumpResetKey) { mutableStateOf(false) }

    // Track if user has scrolled significantly (to reset bump animation trigger)
    val hasScrolledAway by remember {
        derivedStateOf {
            // Consider "scrolled away" if scrolled more than 2 items from start
            listState.firstVisibleItemIndex >= 2 ||
                (listState.firstVisibleItemIndex >= 1 && listState.firstVisibleItemScrollOffset > itemWidthPx * 0.5f)
        }
    }

    // Update userHasScrolledAway when they scroll away
    LaunchedEffect(hasScrolledAway) {
        if (hasScrolledAway) {
            userHasScrolledAway = true
        }
    }

    // Reset bump animation when user scrolls back to top after scrolling away
    LaunchedEffect(hasScrolledAway, userHasScrolledAway) {
        if (!hasScrolledAway && userHasScrolledAway) {
            // User scrolled back to the start after scrolling away - reset the bump trigger
            bumpAnimationShown = false
            userHasScrolledAway = false
        }
    }

    // Bump animation parameters
    val bumpDistance = itemWidthPx * 0.4f // Bump by ~40% of item width
    val bumpDuration = 200 // ms for each bump direction
    val pauseBetweenBumps = 400L // ms pause between alternating bumps

    // Perform scroll bump animation when there are unread items offscreen
    // Use snapshotFlow to properly observe layout changes after initial composition
    LaunchedEffect(unreadIndices) {
        if (unreadIndices.isEmpty()) return@LaunchedEffect

        // Use snapshotFlow to observe layout info changes
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) return@snapshotFlow Pair(false, false)

            val viewportStart = layoutInfo.viewportStartOffset
            val viewportEnd = layoutInfo.viewportEndOffset
            val firstVisibleIndex = visibleItemsInfo.firstOrNull()?.index ?: 0
            val lastVisibleIndex = visibleItemsInfo.lastOrNull()?.index ?: 0

            // Check left side
            val hasUnreadLeft = unreadIndices.any { it < firstVisibleIndex } ||
                visibleItemsInfo.any { itemInfo ->
                    if (itemInfo.index !in unreadIndices) return@any false
                    val itemStart = itemInfo.offset
                    if (itemStart >= viewportStart) return@any false
                    val itemEnd = itemStart + itemInfo.size
                    val visibleStart = maxOf(itemStart, viewportStart)
                    val visibleEnd = minOf(itemEnd, viewportEnd)
                    val visibleWidth = (visibleEnd - visibleStart).coerceAtLeast(0)
                    val visibleFraction = if (itemInfo.size > 0) visibleWidth.toFloat() / itemInfo.size else 1f
                    visibleFraction < minVisibleFraction
                }

            // Check right side
            val hasUnreadRight = unreadIndices.any { it > lastVisibleIndex } ||
                visibleItemsInfo.any { itemInfo ->
                    if (itemInfo.index !in unreadIndices) return@any false
                    val itemEnd = itemInfo.offset + itemInfo.size
                    if (itemEnd <= viewportEnd) return@any false
                    val itemStart = itemInfo.offset
                    val visibleStart = maxOf(itemStart, viewportStart)
                    val visibleEnd = minOf(itemEnd, viewportEnd)
                    val visibleWidth = (visibleEnd - visibleStart).coerceAtLeast(0)
                    val visibleFraction = if (itemInfo.size > 0) visibleWidth.toFloat() / itemInfo.size else 1f
                    visibleFraction < minVisibleFraction
                }

            Pair(hasUnreadLeft, hasUnreadRight)
        }
        .filter { (left, right) -> (left || right) && !bumpAnimationShown && !isDragging }
        .first() // Wait for first occurrence of unread items offscreen

        // Small delay to let the UI settle
        delay(300)

        // Double-check conditions haven't changed
        if (bumpAnimationShown || isDragging) return@LaunchedEffect

        // Re-check which sides have unread
        val layoutInfo = listState.layoutInfo
        val visibleItemsInfo = layoutInfo.visibleItemsInfo
        val viewportStart = layoutInfo.viewportStartOffset
        val viewportEnd = layoutInfo.viewportEndOffset
        val firstVisibleIndex = visibleItemsInfo.firstOrNull()?.index ?: 0
        val lastVisibleIndex = visibleItemsInfo.lastOrNull()?.index ?: 0

        val bumpLeft = unreadIndices.any { it < firstVisibleIndex } ||
            visibleItemsInfo.any { itemInfo ->
                if (itemInfo.index !in unreadIndices) return@any false
                val itemStart = itemInfo.offset
                if (itemStart >= viewportStart) return@any false
                val itemEnd = itemStart + itemInfo.size
                val visibleStart = maxOf(itemStart, viewportStart)
                val visibleEnd = minOf(itemEnd, viewportEnd)
                val visibleWidth = (visibleEnd - visibleStart).coerceAtLeast(0)
                val visibleFraction = if (itemInfo.size > 0) visibleWidth.toFloat() / itemInfo.size else 1f
                visibleFraction < minVisibleFraction
            }

        val bumpRight = unreadIndices.any { it > lastVisibleIndex } ||
            visibleItemsInfo.any { itemInfo ->
                if (itemInfo.index !in unreadIndices) return@any false
                val itemEnd = itemInfo.offset + itemInfo.size
                if (itemEnd <= viewportEnd) return@any false
                val itemStart = itemInfo.offset
                val visibleStart = maxOf(itemStart, viewportStart)
                val visibleEnd = minOf(itemEnd, viewportEnd)
                val visibleWidth = (visibleEnd - visibleStart).coerceAtLeast(0)
                val visibleFraction = if (itemInfo.size > 0) visibleWidth.toFloat() / itemInfo.size else 1f
                visibleFraction < minVisibleFraction
            }

        if (!bumpLeft && !bumpRight) return@LaunchedEffect

        bumpAnimationShown = true

        if (bumpLeft && bumpRight) {
            // Both directions have unread - alternate with pauses
            // First bump right (to show there's more to the right)
            listState.animateScrollBy(
                bumpDistance,
                tween(bumpDuration, easing = EaseInOutSine)
            )
            listState.animateScrollBy(
                -bumpDistance,
                tween(bumpDuration, easing = EaseInOutSine)
            )

            delay(pauseBetweenBumps)

            // Then bump left (to show there's more to the left)
            listState.animateScrollBy(
                -bumpDistance,
                tween(bumpDuration, easing = EaseInOutSine)
            )
            listState.animateScrollBy(
                bumpDistance,
                tween(bumpDuration, easing = EaseInOutSine)
            )
        } else if (bumpRight) {
            // Only right has unread - bump right then back
            listState.animateScrollBy(
                bumpDistance,
                tween(bumpDuration, easing = EaseInOutSine)
            )
            listState.animateScrollBy(
                -bumpDistance,
                tween(bumpDuration, easing = EaseInOutSine)
            )
        } else if (bumpLeft) {
            // Only left has unread - bump left then back
            listState.animateScrollBy(
                -bumpDistance,
                tween(bumpDuration, easing = EaseInOutSine)
            )
            listState.animateScrollBy(
                bumpDistance,
                tween(bumpDuration, easing = EaseInOutSine)
            )
        }
    }

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            userScrollEnabled = !isDragging
        ) {
            itemsIndexed(
                items = orderedConversations,
                key = { _, conv -> conv.guid }
            ) { index, conversation ->
                val guid = conversation.guid

            val isBeingDragged = index == draggedItemIndex && isDragging

            // Calculate visual offset for dragged item
            val offsetX = if (isBeingDragged) dragOffsetX else 0f
            val offsetY = if (isBeingDragged) dragOffsetY.coerceAtLeast(0f) else 0f

            // Calculate progress toward unpin threshold (0 to 1)
            val unpinProgress = if (isBeingDragged) (dragOffsetY / unpinThresholdPx).coerceIn(0f, 1f) else 0f

            // Scale up dragged item for visual feedback, reduce when approaching unpin
            val scale by animateFloatAsState(
                targetValue = if (isBeingDragged) 1.08f - (unpinProgress * 0.15f) else 1f,
                animationSpec = tween(150),
                label = "dragScale"
            )

            // Fade out when approaching unpin threshold
            val alpha by animateFloatAsState(
                targetValue = if (isBeingDragged) 1f - (unpinProgress * 0.5f) else 1f,
                animationSpec = tween(100),
                label = "dragAlpha"
            )

            // Elevation for shadow effect during drag
            val elevation by animateDpAsState(
                targetValue = if (isBeingDragged) 8.dp else 0.dp,
                animationSpec = tween(150),
                label = "dragElevation"
            )

            Box(
                modifier = Modifier
                    .animateItem()
                    .onGloballyPositioned { coordinates ->
                        itemPositions[guid] = coordinates.positionInRoot()
                    }
                    .zIndex(if (isBeingDragged) 1f else 0f)
                    .offset { androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()) }
                    .scale(scale)
                    // Make invisible when being dragged (overlay handles rendering)
                    .alpha(if (isBeingDragged) 0f else alpha)
                    .shadow(elevation, RoundedCornerShape(12.dp))
            ) {
                PinnedConversationItem(
                    conversation = conversation,
                    onClick = { if (!isDragging) onConversationClick(conversation) },
                    onAvatarClick = { onAvatarClick(conversation) },
                    isSelected = conversation.guid in selectedConversations,
                    isSelectionMode = isSelectionMode,
                    isDragging = isBeingDragged,
                    onDragStart = {
                        if (!isSelectionMode) {
                            draggedItemIndex = index
                            draggedItemGuid = guid
                            isDragging = true
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            overlayDragOffsetX = 0f
                            // Notify overlay with initial position
                            val position = itemPositions[guid] ?: Offset.Zero
                            onDragOverlayStart(conversation, position)
                            HapticUtils.onLongPress(haptic)
                        }
                    },
                    onDrag = { dragAmountX, dragAmountY ->
                        if (isDragging && draggedItemIndex >= 0) {
                            dragOffsetX += dragAmountX
                            dragOffsetY += dragAmountY
                            overlayDragOffsetX += dragAmountX

                            // Update overlay position
                            onDragOverlayMove(Offset(overlayDragOffsetX, dragOffsetY))

                            // Calculate if we should swap with neighbor (horizontal reordering)
                            val draggedPosition = draggedItemIndex
                            val offsetInItems = (dragOffsetX / itemWidthPx).roundToInt()
                            val newPosition = (draggedPosition + offsetInItems).coerceIn(0, currentOrder.size - 1)

                            if (newPosition != draggedPosition) {
                                // Swap items in current order
                                val mutableList = currentOrder.toMutableList()
                                val item = mutableList.removeAt(draggedPosition)
                                mutableList.add(newPosition, item)
                                currentOrder = mutableList

                                // Update dragged index and reset offset for smooth movement
                                draggedItemIndex = newPosition
                                dragOffsetX -= (newPosition - draggedPosition) * itemWidthPx

                                HapticUtils.onDragTransition(haptic)
                            }
                        }
                    },
                    onDragEnd = {
                        if (isDragging && draggedItemIndex >= 0) {
                            // Check if dragged past unpin threshold (downward)
                            if (dragOffsetY >= unpinThresholdPx) {
                                draggedItemGuid?.let { onUnpin(it) }
                            } else {
                                // Only call onReorder if the order actually changed
                                val originalOrder = conversations.map { it.guid }
                                if (currentOrder != originalOrder) {
                                    onReorder(currentOrder)
                                }
                            }
                        }
                        onDragOverlayEnd()
                        isDragging = false
                        draggedItemIndex = -1
                        draggedItemGuid = null
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        overlayDragOffsetX = 0f
                    },
                    onDragCancel = {
                        // Reset to original order on cancel
                        currentOrder = conversations.map { it.guid }
                        onDragOverlayEnd()
                        isDragging = false
                        draggedItemIndex = -1
                        draggedItemGuid = null
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        overlayDragOffsetX = 0f
                    }
                )
            }
        }
        }
    }
}

/**
 * Drag overlay that renders a pinned conversation item floating above everything
 * during drag-to-reorder or drag-to-unpin operations.
 *
 * @param conversation The conversation being dragged (null if not dragging)
 * @param isDragging Whether a drag is in progress
 * @param startPosition The initial position of the dragged item in root coordinates
 * @param dragOffset The current drag offset from the start position
 * @param containerRootPosition The position of the containing Box in root coordinates
 * @param unpinThresholdPx Distance in pixels to drag down before unpinning
 */
@Composable
internal fun PinnedDragOverlay(
    conversation: ConversationUiModel?,
    isDragging: Boolean,
    startPosition: Offset,
    dragOffset: Offset,
    containerRootPosition: Offset,
    unpinThresholdPx: Float,
    modifier: Modifier = Modifier
) {
    if (!isDragging || conversation == null) return

    val unpinProgress = (dragOffset.y / unpinThresholdPx).coerceIn(0f, 1f)
    val scale = 1.08f - (unpinProgress * 0.15f)
    val overlayAlpha = 1f - (unpinProgress * 0.5f)
    
    // Slight rotation for visual feedback
    val rotation = -2f * (1f - unpinProgress)

    // Calculate position relative to the container (not root)
    val relativeX = startPosition.x - containerRootPosition.x
    val relativeY = startPosition.y - containerRootPosition.y

    Box(
        modifier = modifier
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    (relativeX + dragOffset.x).toInt(),
                    (relativeY + dragOffset.y.coerceAtLeast(0f)).toInt()
                )
            }
            .zIndex(100f)
            .scale(scale)
            .rotate(rotation)
            .alpha(overlayAlpha)
            .shadow(8.dp, RoundedCornerShape(12.dp))
    ) {
        // Render the pinned item visually
        Column(
            modifier = Modifier
                .width(100.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Avatar with unread badge
            // Priority: chatAvatarPath (group photo) > GroupAvatar (collage) > Avatar (contact)
            Box(modifier = Modifier.size(60.dp)) {
                if (conversation.chatAvatarPath != null) {
                    // Group photo from server (or custom) takes precedence
                    Avatar(
                        name = conversation.displayName,
                        avatarPath = conversation.chatAvatarPath,
                        size = 56.dp,
                        hasContactInfo = conversation.hasContact
                    )
                } else if (conversation.isGroup) {
                    GroupAvatar(
                        names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                        avatarPaths = conversation.participantAvatarPaths,
                        size = 56.dp
                    )
                } else {
                    Avatar(
                        name = conversation.displayName,
                        avatarPath = conversation.avatarPath,
                        size = 56.dp,
                        hasContactInfo = conversation.hasContact
                    )
                }

                // Unread badge at top-right
                if (conversation.unreadCount > 0) {
                    UnreadBadge(
                        count = conversation.unreadCount,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = conversation.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PinnedConversationItem(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit = {},
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    isDragging: Boolean = false,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(100.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(isSelectionMode) {
                    if (!isSelectionMode) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragCancel() }
                        )
                    }
                }
                .clickable(enabled = !isDragging) { onClick() }
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar with selection checkmark
            // Outer Box sized for avatar + badge overflow (72dp + 4dp)
            Box(modifier = Modifier.size(76.dp)) {
                // Avatar content
                Box(
                    modifier = Modifier
                        .size(76.dp) // Size includes badge overflow
                        .clickable(enabled = !isDragging) { onClick() }
                ) {
                    if (isSelected) {
                        // Show checkmark when selected - use muted color
                        Surface(
                            color = MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    } else {
                        // Priority: chatAvatarPath (group photo) > GroupAvatar (collage) > Avatar (contact)
                        if (conversation.chatAvatarPath != null) {
                            // Group photo from server (or custom) takes precedence
                            Avatar(
                                name = conversation.displayName,
                                avatarPath = conversation.chatAvatarPath,
                                size = 72.dp,
                                hasContactInfo = conversation.hasContact
                            )
                        } else if (conversation.isGroup) {
                            GroupAvatar(
                                names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                                avatarPaths = conversation.participantAvatarPaths,
                                size = 72.dp
                            )
                        } else {
                            Avatar(
                                name = conversation.displayName,
                                avatarPath = conversation.avatarPath,
                                size = 72.dp,
                                hasContactInfo = conversation.hasContact
                            )
                        }
                    }
                }

                // Unread badge at top-right
                if (conversation.unreadCount > 0 && !isSelected) {
                    UnreadBadge(
                        count = conversation.unreadCount,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                    )
                }

                // Typing indicator
                if (conversation.isTyping && !isSelected) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-2).dp, y = 2.dp)
                            .size(20.dp)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            AnimatedTypingDots()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Name with bold weight when unread (badge is now on avatar)
            val formattedName = formatDisplayName(conversation.displayName)
            val hasUnread = conversation.unreadCount > 0 && !isSelected

            Text(
                text = formattedName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 92.dp)
            )
        }
    }
}
