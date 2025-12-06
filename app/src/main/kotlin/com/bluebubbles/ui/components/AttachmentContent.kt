package com.bluebubbles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

/**
 * Renders an attachment within a message bubble.
 * Supports images, videos, audio, and generic files.
 */
@Composable
fun AttachmentContent(
    attachment: AttachmentUiModel,
    isFromMe: Boolean,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        attachment.isImage -> ImageAttachment(
            attachment = attachment,
            onClick = { onMediaClick(attachment.guid) },
            modifier = modifier
        )
        attachment.isVideo -> VideoAttachment(
            attachment = attachment,
            onClick = { onMediaClick(attachment.guid) },
            modifier = modifier
        )
        attachment.isAudio -> AudioAttachment(
            attachment = attachment,
            onClick = { onMediaClick(attachment.guid) },
            isFromMe = isFromMe,
            modifier = modifier
        )
        else -> FileAttachment(
            attachment = attachment,
            onClick = { onMediaClick(attachment.guid) },
            isFromMe = isFromMe,
            modifier = modifier
        )
    }
}

@Composable
private fun ImageAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    val imageUrl = attachment.localPath ?: attachment.webUrl

    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            // Calculate aspect ratio for proper sizing
            val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
                attachment.width.toFloat() / attachment.height.toFloat()
            } else {
                1f
            }

            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = attachment.transferName ?: "Image",
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f)),
                contentScale = ContentScale.Fit,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else {
            isError = true
        }

        // Loading indicator
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Error state
        if (isError) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                    if (attachment.friendlySize.isNotEmpty()) {
                        Text(
                            text = attachment.friendlySize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // For videos, use webUrl for thumbnail (server generates thumbnails)
    val thumbnailUrl = attachment.localPath ?: attachment.webUrl

    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Calculate aspect ratio
        val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
            attachment.width.toFloat() / attachment.height.toFloat()
        } else {
            16f / 9f // Default video aspect ratio
        }

        if (thumbnailUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = attachment.transferName ?: "Video",
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f)),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else {
            // No thumbnail available, show placeholder
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        }

        // Loading indicator
        if (isLoading && thumbnailUrl != null) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Play button overlay (always show for videos)
        if (!isLoading || isError || thumbnailUrl == null) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // File size badge
        if (attachment.friendlySize.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            ) {
                Text(
                    text = attachment.friendlySize,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AudioAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isFromMe) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = modifier
            .widthIn(min = 180.dp, max = 250.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Play button
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play audio",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Audio info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.transferName ?: "Audio",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (attachment.friendlySize.isNotEmpty()) {
                    Text(
                        text = attachment.friendlySize,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Audio icon
            Icon(
                Icons.Outlined.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FileAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isFromMe) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = modifier
            .widthIn(min = 180.dp, max = 250.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // File icon
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        getFileIcon(attachment.fileExtension),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // File info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.transferName ?: "File",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    attachment.fileExtension?.uppercase()?.let { ext ->
                        Text(
                            text = ext,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (attachment.friendlySize.isNotEmpty()) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = attachment.friendlySize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Download/open icon
            Icon(
                Icons.Default.Download,
                contentDescription = "Open file",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Returns an appropriate icon based on file extension
 */
private fun getFileIcon(extension: String?) = when (extension?.lowercase()) {
    "pdf" -> Icons.Default.PictureAsPdf
    "doc", "docx" -> Icons.Default.Description
    "xls", "xlsx" -> Icons.Default.TableChart
    "ppt", "pptx" -> Icons.Default.Slideshow
    "zip", "rar", "7z", "tar", "gz" -> Icons.Default.FolderZip
    "txt", "rtf" -> Icons.Default.TextSnippet
    "html", "htm", "xml", "json" -> Icons.Default.Code
    else -> Icons.Outlined.InsertDriveFile
}
