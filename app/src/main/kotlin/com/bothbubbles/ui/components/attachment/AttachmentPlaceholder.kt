package com.bothbubbles.ui.components.attachment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.util.rememberBlurhashBitmap

/**
 * Placeholder shown for attachments that need to be downloaded.
 * Displays a preview (blurhash for images/videos) with a download button overlay.
 *
 * @param attachment The attachment to render
 * @param isFromMe Whether this message is from the current user
 * @param onDownloadClick Callback when download button is clicked
 * @param isDownloading Whether this attachment is currently being downloaded
 * @param downloadProgress Download progress (0.0 to 1.0) when isDownloading is true
 * @param modifier Modifier for the component
 */
@Composable
fun AttachmentPlaceholder(
    attachment: AttachmentUiModel,
    isFromMe: Boolean,
    onDownloadClick: () -> Unit,
    isDownloading: Boolean,
    downloadProgress: Float,
    modifier: Modifier = Modifier
) {
    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else if (attachment.isVideo) {
        16f / 9f
    } else {
        1f
    }

    val isMedia = attachment.isImage || attachment.isVideo

    // Decode blurhash for placeholder preview
    val blurhashBitmap = rememberBlurhashBitmap(
        blurhash = attachment.blurhash,
        aspectRatio = aspectRatio
    )

    if (isMedia) {
        // Image/Video placeholder with blurhash background
        Box(
            modifier = modifier
                .widthIn(max = 250.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(enabled = !isDownloading, onClick = onDownloadClick),
            contentAlignment = Alignment.Center
        ) {
            // Blurhash preview background (or fallback to solid color)
            Box(
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            ) {
                if (blurhashBitmap != null) {
                    Image(
                        bitmap = blurhashBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                }
            }

            // Download button or progress overlay with crossfade animation
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Download icon - fade out when downloading
                    AnimatedVisibility(
                        visible = !isDownloading,
                        enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.8f, animationSpec = tween(100)),
                        exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f, animationSpec = tween(100))
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Download attachment",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    // Progress indicator - fade in when downloading
                    AnimatedVisibility(
                        visible = isDownloading,
                        enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.8f, animationSpec = tween(100)),
                        exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f, animationSpec = tween(100))
                    ) {
                        if (downloadProgress > 0f) {
                            // Determinate progress when we have actual progress
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        } else {
                            // Indeterminate spinner when downloading but no progress yet
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }

            // Media type indicator
            if (attachment.isVideo) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Video",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    } else {
        // Non-media files (audio, documents, etc.)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isFromMe) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            modifier = modifier
                .widthIn(min = 180.dp, max = 250.dp)
                .clickable(enabled = !isDownloading, onClick = onDownloadClick)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // File icon with download indicator
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isDownloading) {
                                if (downloadProgress > 0f) {
                                    CircularProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            } else {
                                Icon(
                                    if (attachment.isAudio) Icons.Outlined.AudioFile
                                    else getFileIcon(attachment.fileExtension),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
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
                        if (isDownloading) {
                            Text(
                                text = "Downloading...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "Tap to download",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Download icon
                if (!isDownloading) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Placeholder for borderless media that needs to be downloaded.
 *
 * @param attachment The attachment to render
 * @param onDownloadClick Callback when download button is clicked
 * @param isDownloading Whether this attachment is currently being downloaded
 * @param downloadProgress Download progress (0.0 to 1.0)
 * @param maxWidth Maximum width constraint
 * @param modifier Modifier for the component
 */
@Composable
fun BorderlessAttachmentPlaceholder(
    attachment: AttachmentUiModel,
    onDownloadClick: () -> Unit,
    isDownloading: Boolean,
    downloadProgress: Float,
    maxWidth: Dp,
    modifier: Modifier = Modifier
) {
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else if (attachment.isVideo) {
        16f / 9f
    } else {
        1f
    }

    // Decode blurhash for placeholder preview
    val blurhashBitmap = rememberBlurhashBitmap(
        blurhash = attachment.blurhash,
        aspectRatio = aspectRatio
    )

    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(enabled = !isDownloading, onClick = onDownloadClick),
        contentAlignment = Alignment.Center
    ) {
        // Blurhash preview background (or fallback to solid color)
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
        ) {
            if (blurhashBitmap != null) {
                Image(
                    bitmap = blurhashBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Download button or progress overlay
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isDownloading) {
                    if (downloadProgress > 0f) {
                        // Determinate progress when we have actual progress
                        CircularProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.size(40.dp),
                            strokeWidth = 3.dp,
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f)
                        )
                    } else {
                        // Indeterminate spinner when downloading but no progress yet
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            strokeWidth = 3.dp,
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f)
                        )
                    }
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download attachment",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Video indicator
        if (attachment.isVideo) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "Video",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}
