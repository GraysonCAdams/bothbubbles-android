package com.bothbubbles.ui.conversations

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.remember
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

    // Map guid to conversation for lookup
    val conversationMap = remember(conversations) { conversations.associateBy { it.guid } }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        userScrollEnabled = !isDragging
    ) {
        itemsIndexed(
            items = currentOrder,
            key = { _, guid -> guid }
        ) { index, guid ->
            val conversation = conversationMap[guid] ?: return@itemsIndexed

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
            if (conversation.isGroup) {
                GroupAvatar(
                    names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                    avatarPaths = conversation.participantAvatarPaths,
                    size = 56.dp
                )
            } else {
                Avatar(
                    name = conversation.displayName,
                    avatarPath = conversation.avatarPath,
                    size = 56.dp
                )
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
                        if (conversation.isGroup) {
                            GroupAvatar(
                                names = conversation.participantNames.ifEmpty { listOf(conversation.displayName) },
                                avatarPaths = conversation.participantAvatarPaths,
                                size = 72.dp
                            )
                        } else {
                            Avatar(
                                name = conversation.displayName,
                                avatarPath = conversation.avatarPath,
                                size = 72.dp
                            )
                        }
                    }
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

            // Name row with unread badge to the left
            val formattedName = formatDisplayName(conversation.displayName)
            val hasUnread = conversation.unreadCount > 0 && !isSelected

            Row(
                modifier = Modifier.widthIn(max = 92.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Unread badge to the left of the name
                if (hasUnread) {
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Text(
                    text = formattedName,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
