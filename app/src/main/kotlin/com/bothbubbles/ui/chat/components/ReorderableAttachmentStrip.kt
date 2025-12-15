package com.bothbubbles.ui.chat.components

import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bothbubbles.data.model.PendingAttachmentInput
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A reorderable horizontal strip of attachment previews.
 *
 * Supports drag-and-drop reordering via long-press gesture.
 * Visual feedback includes elevation, scale, and position animation.
 *
 * @param attachments List of pending attachments to display
 * @param onRemove Callback when an attachment should be removed
 * @param onEdit Callback when an attachment should be edited
 * @param onReorder Callback when attachments are reordered (provides new list)
 * @param modifier Modifier for the container
 */
@Composable
fun ReorderableAttachmentStrip(
    attachments: List<PendingAttachmentInput>,
    onRemove: (Uri) -> Unit,
    onEdit: (Uri) -> Unit,
    onReorder: (List<PendingAttachmentInput>) -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Drag state
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Working copy of items for reordering
    var workingItems by remember(attachments) { mutableStateOf(attachments) }

    // Item width for calculating swap positions
    val itemWidth = 108.dp // 100dp item + 8dp spacing

    LazyRow(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            items = workingItems,
            key = { _, item -> item.uri.toString() }
        ) { index, attachment ->
            val isBeingDragged = index == draggedIndex && isDragging

            // Animate scale and elevation for dragged item
            val scale by animateFloatAsState(
                targetValue = if (isBeingDragged) 1.1f else 1f,
                animationSpec = spring(),
                label = "scale"
            )
            val elevation by animateDpAsState(
                targetValue = if (isBeingDragged) 8.dp else 0.dp,
                animationSpec = spring(),
                label = "elevation"
            )

            // Calculate horizontal offset for dragged item
            val offsetX = if (isBeingDragged) dragOffset else 0f

            Box(
                modifier = Modifier
                    .zIndex(if (isBeingDragged) 1f else 0f)
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .scale(scale)
                    .shadow(elevation, RoundedCornerShape(12.dp))
                    .pointerInput(attachment.uri) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggedIndex = index
                                isDragging = true
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.x

                                // Calculate target index based on drag offset
                                val itemWidthPx = itemWidth.toPx()
                                val offsetItems = (dragOffset / itemWidthPx).roundToInt()
                                val targetIndex = (draggedIndex + offsetItems)
                                    .coerceIn(0, workingItems.lastIndex)

                                // Swap items if target changed
                                if (targetIndex != draggedIndex && targetIndex in workingItems.indices) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    workingItems = workingItems.toMutableList().apply {
                                        val item = removeAt(draggedIndex)
                                        add(targetIndex, item)
                                    }
                                    // Reset offset and update dragged index
                                    dragOffset -= (targetIndex - draggedIndex) * itemWidthPx
                                    draggedIndex = targetIndex
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                                dragOffset = 0f
                                // Notify parent of reorder
                                if (workingItems != attachments) {
                                    onReorder(workingItems)
                                }
                                draggedIndex = -1
                            },
                            onDragCancel = {
                                isDragging = false
                                dragOffset = 0f
                                workingItems = attachments // Reset to original
                                draggedIndex = -1
                            }
                        )
                    }
            ) {
                AttachmentPreview(
                    uri = attachment.uri,
                    onRemove = { onRemove(attachment.uri) },
                    onEdit = { onEdit(attachment.uri) },
                    caption = attachment.caption
                )

                // Show drag handle indicator when dragging
                if (isBeingDragged) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
