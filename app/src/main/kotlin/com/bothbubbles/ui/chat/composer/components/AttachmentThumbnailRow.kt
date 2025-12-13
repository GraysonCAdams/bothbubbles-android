package com.bothbubbles.ui.chat.composer.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bothbubbles.ui.chat.composer.AttachmentItem
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Attachment thumbnail row for the chat composer.
 *
 * Displays staged attachments as horizontally scrollable thumbnails with:
 * - Remove button on each thumbnail
 * - Edit button for images
 * - Upload progress indicator
 * - Error state with retry option
 * - Expand/collapse animation when attachments are added/removed
 *
 * Layout (Google Messages style):
 * ```
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 2 attachments                              [HD ▼] Clear All  │
 * ├──────────────────────────────────────────────────────────────┤
 * │ ┌──────┐ ┌──────┐                                            │
 * │ │ [X]  │ │ [X]  │                                            │
 * │ │      │ │      │                                            │
 * │ │ 2.3MB│ │ ▶ 15s│                                            │
 * │ └──────┘ └──────┘                                            │
 * └──────────────────────────────────────────────────────────────┘
 * ```
 *
 * @param attachments List of attachment items to display
 * @param onRemove Callback when the remove button is tapped on an attachment
 * @param onEdit Callback when the edit button is tapped on an image (optional)
 * @param onRetry Callback when the retry button is tapped on a failed upload
 * @param onClearAll Callback to clear all attachments
 * @param onQualityClick Callback to open quality selection sheet
 * @param currentQuality Current quality setting label (optional)
 * @param modifier Modifier for the row container
 */
@Composable
fun AttachmentThumbnailRow(
    attachments: List<AttachmentItem>,
    onRemove: (AttachmentItem) -> Unit,
    onEdit: ((AttachmentItem) -> Unit)? = null,
    onRetry: ((AttachmentItem) -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
    onQualityClick: (() -> Unit)? = null,
    currentQuality: String? = null,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors
    val hasImages = attachments.any { it.isImage }

    AnimatedContent(
        targetState = attachments.isNotEmpty(),
        transitionSpec = {
            (fadeIn(tween(ComposerMotionTokens.Duration.NORMAL)) + expandVertically(
                expandFrom = Alignment.Bottom,
                animationSpec = spring(dampingRatio = 0.85f)
            )).togetherWith(
                fadeOut(tween(ComposerMotionTokens.Duration.FAST)) + shrinkVertically(
                    shrinkTowards = Alignment.Bottom
                )
            )
        },
        modifier = modifier,
        label = "attachment_row"
    ) { hasAttachments ->
        if (hasAttachments) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header row with count, quality indicator, and clear all
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${attachments.size} attachment${if (attachments.size > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = inputColors.inputText.copy(alpha = 0.7f)
                        )

                        // Quality indicator for images
                        if (hasImages && onQualityClick != null && currentQuality != null) {
                            TextButton(
                                onClick = onQualityClick,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = currentQuality,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Clear all button
                    if (attachments.size > 1 && onClearAll != null) {
                        TextButton(
                            onClick = onClearAll,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Clear All",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // Attachment thumbnails
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(
                        items = attachments,
                        key = { it.id }
                    ) { attachment ->
                        AttachmentThumbnail(
                            attachment = attachment,
                            onRemove = { onRemove(attachment) },
                            onEdit = if (attachment.isImage && onEdit != null) {
                                { onEdit(attachment) }
                            } else null,
                            onRetry = if (attachment.hasError && onRetry != null) {
                                { onRetry(attachment) }
                            } else null,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(ComposerMotionTokens.Duration.NORMAL),
                                fadeOutSpec = tween(ComposerMotionTokens.Duration.FAST),
                                placementSpec = spring(dampingRatio = 0.8f)
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual attachment thumbnail with overlays for actions and status.
 */
@Composable
private fun AttachmentThumbnail(
    attachment: AttachmentItem,
    onRemove: () -> Unit,
    onEdit: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .size(ComposerMotionTokens.Dimension.AttachmentRowHeight)
            .clip(RoundedCornerShape(12.dp))
    ) {
        // Thumbnail image
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(attachment.uri)
                .crossfade(true)
                .build(),
            contentDescription = attachment.displayName ?: "Attachment",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Bottom gradient for text visibility
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(28.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        )

        // File size or caption indicator
        Text(
            text = when {
                attachment.caption != null -> "💬"
                attachment.sizeBytes != null -> formatFileSize(attachment.sizeBytes)
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 4.dp)
        )

        // Video duration badge
        if (attachment.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Upload progress overlay
        if (attachment.isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                if (attachment.uploadProgress != null) {
                    CircularProgressIndicator(
                        progress = { attachment.uploadProgress },
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // Error overlay with retry
        if (attachment.hasError && onRetry != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = 0.3f))
                    .clickable(onClick = onRetry),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry upload",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Edit button (top left, images only)
        if (onEdit != null && !attachment.isUploading && !attachment.hasError) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit attachment",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Remove button (top right)
        if (!attachment.isUploading) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove attachment",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Format file size in human-readable form.
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
