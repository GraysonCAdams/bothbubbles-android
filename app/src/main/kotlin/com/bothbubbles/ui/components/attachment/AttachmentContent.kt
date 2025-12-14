package com.bothbubbles.ui.components.attachment

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import com.bothbubbles.services.media.ExoPlayerPool
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.util.rememberBlurhashBitmap

/**
 * CompositionLocal for providing ExoPlayerPool to video composables.
 * When provided, videos will use pooled players with automatic eviction
 * to limit memory usage and prevent multiple videos playing simultaneously.
 */
val LocalExoPlayerPool = staticCompositionLocalOf<ExoPlayerPool?> { null }

/**
 * Renders an attachment within a message bubble.
 * Supports images, videos, audio, and generic files.
 *
 * @param attachment The attachment to render
 * @param isFromMe Whether this message is from the current user
 * @param onMediaClick Callback when media is clicked for viewing
 * @param onDownloadClick Optional callback for manual download mode. When provided and attachment
 *                        needs download, shows a placeholder with download button instead of
 *                        streaming from webUrl.
 * @param isDownloading Whether this attachment is currently being downloaded
 * @param downloadProgress Download progress (0.0 to 1.0) when isDownloading is true
 * @param uploadProgress Upload progress (0.0 to 1.0) for outbound attachments being uploaded
 * @param onRetryClick Optional callback for retrying failed downloads
 * @param isRetrying Whether a retry is currently in progress
 */
@Composable
fun AttachmentContent(
    attachment: AttachmentUiModel,
    isFromMe: Boolean,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadClick: ((String) -> Unit)? = null,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
    uploadProgress: Float = 0f,
    onRetryClick: ((String) -> Unit)? = null,
    isRetrying: Boolean = false
) {
    // Show error overlay for failed attachments with error details
    val showError = attachment.hasError && !isDownloading && !isRetrying

    // Show placeholder for inbound attachments that need download
    // This provides blurhash preview while downloading, regardless of auto/manual mode
    // For stickers, ALWAYS show placeholder when not downloaded (they need HEIC→PNG conversion)
    val showPlaceholder = !showError && (attachment.needsDownload || attachment.isDownloading ||
        (attachment.isSticker && attachment.localPath == null))

    // Determine effective downloading state
    val effectiveIsDownloading = isDownloading || attachment.isDownloading ||
        (attachment.isSticker && attachment.localPath == null && onDownloadClick == null)

    // Determine if we're uploading (outbound, not yet uploaded)
    val isUploading = attachment.isUploading
    val effectiveUploadProgress = if (isUploading) uploadProgress.coerceIn(0f, 1f) else 0f

    Column(modifier = modifier) {
        when {
            showError -> AttachmentErrorOverlay(
                attachment = attachment,
                onRetryClick = { onRetryClick?.invoke(attachment.guid) },
                isRetrying = isRetrying
            )
            showPlaceholder -> AttachmentPlaceholder(
                attachment = attachment,
                isFromMe = isFromMe,
                onDownloadClick = { onDownloadClick?.invoke(attachment.guid) },
                isDownloading = effectiveIsDownloading,
                downloadProgress = downloadProgress
            )
            attachment.isGif -> GifAttachment(
                attachment = attachment,
                onClick = { onMediaClick(attachment.guid) },
                isUploading = isUploading,
                uploadProgress = effectiveUploadProgress
            )
            attachment.isImage -> ImageAttachment(
                attachment = attachment,
                onClick = { onMediaClick(attachment.guid) },
                isUploading = isUploading,
                uploadProgress = effectiveUploadProgress
            )
            attachment.isVideo -> InlineVideoAttachment(
                attachment = attachment,
                onFullscreenClick = { onMediaClick(attachment.guid) },
                isUploading = isUploading,
                uploadProgress = effectiveUploadProgress
            )
            attachment.isAudio -> AudioAttachment(
                attachment = attachment,
                onClick = { onMediaClick(attachment.guid) },
                isFromMe = isFromMe
            )
            attachment.isVCard -> VCardAttachment(
                attachment = attachment,
                onClick = { onMediaClick(attachment.guid) },
                isFromMe = isFromMe
            )
            else -> FileAttachment(
                attachment = attachment,
                onClick = { onMediaClick(attachment.guid) },
                isFromMe = isFromMe
            )
        }

        // Caption display (if present)
        attachment.caption?.let { captionText ->
            Text(
                text = captionText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isFromMe) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Placeholder shown for attachments that need to be downloaded.
 * Displays a preview (blurhash for images/videos) with a download button overlay.
 */
@Composable
private fun AttachmentPlaceholder(
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

@Composable
private fun ImageAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isUploading: Boolean = false,
    uploadProgress: Float = 0f
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // Use full-resolution image for crisp display in chat (thumbnails are only 300px)
    // Coil handles memory-efficient loading via size() constraint
    val imageUrl = attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // For transparent images, don't clip corners or add background
    val isTransparent = attachment.mayHaveTransparency

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val maxWidthPx = with(density) { 250.dp.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
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

        // Loading indicator with fade animation - minimal for transparent images
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(tween(150, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(100, easing = FastOutSlowInEasing))
        ) {
            if (isTransparent) {
                // No background for transparent images - just show spinner
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
                        .clip(RoundedCornerShape(12.dp))
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
        }

        // Error state with fade animation - minimal for transparent images
        AnimatedVisibility(
            visible = isError,
            enter = fadeIn(tween(150, easing = FastOutSlowInEasing)) + scaleIn(initialScale = 0.92f, animationSpec = tween(150)),
            exit = fadeOut(tween(100, easing = FastOutSlowInEasing))
        ) {
            if (isTransparent) {
                // Compact error for transparent images
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = "Failed to load",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Upload progress overlay for outbound attachments
        AnimatedVisibility(
            visible = isUploading,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (uploadProgress > 0f) {
                            CircularProgressIndicator(
                                progress = { uploadProgress },
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        } else {
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
        }
    }
}

/**
 * GIF attachment that loops automatically and opens previewer on tap.
 * GIFs can have transparency, so we don't clip corners or add background.
 */
@Composable
private fun GifAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isUploading: Boolean = false,
    uploadProgress: Float = 0f
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    val imageUrl = attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // GIFs can have transparency - don't clip corners or add background
    val isTransparent = attachment.mayHaveTransparency

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val maxWidthPx = with(density) { 250.dp.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "GIF",
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

        // Loading indicator - minimal for transparent GIFs
        if (isLoading) {
            if (isTransparent) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
                        .clip(RoundedCornerShape(12.dp))
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
        }

        // GIF badge - only show for non-transparent GIFs to avoid clutter
        if (!isLoading && !isError && !isTransparent) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = "GIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Error state with fade animation - minimal for transparent GIFs
        AnimatedVisibility(
            visible = isError,
            enter = fadeIn(tween(150, easing = FastOutSlowInEasing)) + scaleIn(initialScale = 0.92f, animationSpec = tween(150)),
            exit = fadeOut(tween(100, easing = FastOutSlowInEasing))
        ) {
            if (isTransparent) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = "Failed to load",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Upload progress overlay for outbound attachments
        AnimatedVisibility(
            visible = isUploading,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (uploadProgress > 0f) {
                            CircularProgressIndicator(
                                progress = { uploadProgress },
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        } else {
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
        }
    }
}

/**
 * Inline video attachment that shows thumbnail until user taps play.
 * Only acquires ExoPlayer when actively playing to minimize resource usage.
 * Double-tap opens fullscreen media viewer.
 */
@Composable
private fun InlineVideoAttachment(
    attachment: AttachmentUiModel,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier,
    isUploading: Boolean = false,
    uploadProgress: Float = 0f
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exoPlayerPool = LocalExoPlayerPool.current

    val videoUrl = attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        16f / 9f // Default video aspect ratio
    }

    // Player state - only acquire when actively playing
    var isPlayerActive by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // Function to start playback - acquires player lazily
    fun startPlayback() {
        if (videoUrl == null) return

        val player = if (exoPlayerPool != null) {
            exoPlayerPool.acquire(attachment.guid)
        } else {
            ExoPlayer.Builder(context).build()
        }

        player.apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = if (isMuted) 0f else 1f
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            play()
        }

        exoPlayer = player
        isPlayerActive = true
    }

    // Function to stop playback and release player
    fun stopPlayback() {
        exoPlayer?.let { player ->
            player.stop()
            if (exoPlayerPool != null) {
                exoPlayerPool.release(attachment.guid)
            } else {
                player.release()
            }
        }
        exoPlayer = null
        isPlayerActive = false
    }

    // Handle lifecycle - pause on background, release on dispose
    DisposableEffect(lifecycleOwner, attachment.guid) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // Stop playback when app goes to background
                    stopPlayback()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopPlayback()
        }
    }

    // Update volume when mute state changes
    LaunchedEffect(isMuted, exoPlayer) {
        exoPlayer?.volume = if (isMuted) 0f else 1f
    }

    if (videoUrl == null) {
        // Fallback to thumbnail view if no URL
        VideoThumbnailAttachment(
            attachment = attachment,
            onClick = onFullscreenClick,
            modifier = modifier
        )
        return
    }

    // Show thumbnail when not playing, video player when active
    if (!isPlayerActive || exoPlayer == null) {
        // Thumbnail mode - show preview with play button
        VideoThumbnailWithControls(
            attachment = attachment,
            aspectRatio = aspectRatio,
            onPlayClick = { startPlayback() },
            onFullscreenClick = onFullscreenClick,
            isUploading = isUploading,
            uploadProgress = uploadProgress,
            modifier = modifier
        )
    } else {
        // Active playback mode
        Box(
            modifier = modifier
                .widthIn(max = 250.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Pause and return to thumbnail on tap
                            stopPlayback()
                        },
                        onDoubleTap = {
                            // Open fullscreen on double tap
                            stopPlayback()
                            onFullscreenClick()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Video player
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { playerView ->
                    playerView.player = exoPlayer
                },
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            )

            // Mute/Unmute button (top-left corner)
            Surface(
                onClick = { isMuted = !isMuted },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Fullscreen button (top-right corner)
            Surface(
                onClick = {
                    stopPlayback()
                    onFullscreenClick()
                },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Pause indicator in center (tap to pause)
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = "Tap to pause",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

/**
 * Video thumbnail with play button and fullscreen control.
 * Used as the default state before user starts playback.
 */
@Composable
private fun VideoThumbnailWithControls(
    attachment: AttachmentUiModel,
    aspectRatio: Float,
    onPlayClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    isUploading: Boolean,
    uploadProgress: Float,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    val thumbnailUrl = attachment.thumbnailPath ?: attachment.localPath ?: attachment.webUrl

    val density = LocalDensity.current
    val maxWidthPx = with(density) { 250.dp.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (!isUploading) onPlayClick() },
                    onDoubleTap = { onFullscreenClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
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

        // Play button overlay (shown when not loading and not uploading)
        if (!isLoading && !isUploading) {
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

        // Fullscreen button (top-right corner)
        Surface(
            onClick = onFullscreenClick,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Upload progress overlay
        if (isUploading) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (uploadProgress > 0f) {
                            CircularProgressIndicator(
                                progress = { uploadProgress },
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        } else {
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
        }
    }
}

/**
 * Video thumbnail attachment (fallback when video URL not available).
 */
@Composable
private fun VideoThumbnailAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // For videos, prefer local thumbnail, then webUrl (server generates thumbnails)
    val thumbnailUrl = attachment.thumbnailPath ?: attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        16f / 9f // Default video aspect ratio
    }

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val maxWidthPx = with(density) { 250.dp.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
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
            Text(
                text = attachment.transferName ?: "Audio",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

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
private fun VCardAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    // Extract contact name from filename (format: Name_timestamp.vcf)
    val contactName = attachment.transferName
        ?.substringBeforeLast("_")
        ?.replace("_", " ")
        ?: "Contact"

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
            // Contact icon
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.ContactPage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Contact info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contactName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "Contact Card",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Add contact icon
            Icon(
                Icons.Default.PersonAdd,
                contentDescription = "Add contact",
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
    "vcf" -> Icons.Outlined.ContactPage
    else -> Icons.Outlined.InsertDriveFile
}

// =============================================================================
// BORDERLESS MEDIA COMPONENTS
// Used when rendering media outside message bubbles as standalone elements
// =============================================================================

/**
 * Renders media content (image or video) without bubble container styling.
 * Used for standalone media segments in segmented message rendering.
 *
 * @param attachment The attachment to render
 * @param isFromMe Whether this message is from the current user
 * @param onMediaClick Callback when media is clicked for viewing
 * @param maxWidth Maximum width constraint for the media
 * @param onDownloadClick Optional callback for manual download mode
 * @param isDownloading Whether this attachment is currently being downloaded
 * @param downloadProgress Download progress (0.0 to 1.0)
 * @param onRetryClick Optional callback for retrying failed downloads
 * @param isRetrying Whether a retry is currently in progress
 */
@Composable
fun BorderlessMediaContent(
    attachment: AttachmentUiModel,
    isFromMe: Boolean,
    onMediaClick: (String) -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp = 300.dp,
    modifier: Modifier = Modifier,
    onDownloadClick: ((String) -> Unit)? = null,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
    isPlacedSticker: Boolean = false,
    messageGuid: String = "",
    onRetryClick: ((String) -> Unit)? = null,
    isRetrying: Boolean = false
) {
    // Show error overlay for failed attachments with error details
    val showError = attachment.hasError && !isDownloading && !isRetrying

    // Show placeholder if manual download mode and attachment needs download
    // For stickers, ALWAYS show placeholder when not downloaded (they need HEIC→PNG conversion)
    val showPlaceholder = !showError && attachment.needsDownload && (onDownloadClick != null || attachment.isSticker)

    // For stickers in auto-download mode, show as "downloading" even if not actively downloading yet
    val effectiveIsDownloading = isDownloading || (attachment.isSticker && attachment.needsDownload && onDownloadClick == null)

    // Use smaller max width for placed stickers
    val effectiveMaxWidth = if (isPlacedSticker) 140.dp else maxWidth

    when {
        showError -> AttachmentErrorOverlay(
            attachment = attachment,
            onRetryClick = { onRetryClick?.invoke(attachment.guid) },
            isRetrying = isRetrying,
            modifier = modifier
        )
        showPlaceholder -> BorderlessAttachmentPlaceholder(
            attachment = attachment,
            onDownloadClick = { onDownloadClick?.invoke(attachment.guid) },
            isDownloading = effectiveIsDownloading,
            downloadProgress = downloadProgress,
            maxWidth = effectiveMaxWidth,
            modifier = modifier
        )
        attachment.isGif -> BorderlessGifAttachment(
            attachment = attachment,
            onClick = { onMediaClick(attachment.guid) },
            maxWidth = effectiveMaxWidth,
            modifier = modifier,
            isPlacedSticker = isPlacedSticker,
            messageGuid = messageGuid
        )
        attachment.isImage -> BorderlessImageAttachment(
            attachment = attachment,
            onClick = { onMediaClick(attachment.guid) },
            maxWidth = effectiveMaxWidth,
            modifier = modifier,
            isPlacedSticker = isPlacedSticker,
            messageGuid = messageGuid
        )
        attachment.isVideo -> BorderlessInlineVideoAttachment(
            attachment = attachment,
            onFullscreenClick = { onMediaClick(attachment.guid) },
            maxWidth = effectiveMaxWidth,
            modifier = modifier
        )
    }
}

/**
 * Placeholder for borderless media that needs to be downloaded.
 */
@Composable
private fun BorderlessAttachmentPlaceholder(
    attachment: AttachmentUiModel,
    onDownloadClick: () -> Unit,
    isDownloading: Boolean,
    downloadProgress: Float,
    maxWidth: androidx.compose.ui.unit.Dp,
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

/**
 * Renders an image attachment without bubble container styling.
 * For transparent images, removes corners and background entirely.
 * For placed stickers, applies a slight rotation for a "slapped on" effect.
 */
@Composable
private fun BorderlessImageAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    isPlacedSticker: Boolean = false,
    messageGuid: String = ""
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // For stickers, don't fall back to webUrl (HEIC doesn't work, needs PNG conversion)
    // Force download first to trigger HEIC→PNG conversion
    // For regular images, use full-resolution for crisp display (thumbnails are only 300px)
    val imageUrl = if (attachment.isSticker) {
        attachment.localPath  // null if not downloaded, will show error/placeholder
    } else {
        attachment.localPath ?: attachment.webUrl
    }

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // For transparent images, don't clip corners
    val isTransparent = attachment.mayHaveTransparency

    // Deterministic rotation based on guid hash for placed stickers (-15° to +15°)
    val rotation = if (isPlacedSticker) {
        ((messageGuid.hashCode() % 30) - 15).toFloat()
    } else 0f

    // Slight size variance for placed stickers (0.9x to 1.1x)
    val sizeScale = if (isPlacedSticker) {
        0.9f + ((messageGuid.hashCode() and 0xFF) / 255f) * 0.2f
    } else 1f

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val effectiveMaxWidth = maxWidth * sizeScale
    val maxWidthPx = with(density) { effectiveMaxWidth.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = effectiveMaxWidth)
            .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isPlacedSticker) {
                    Modifier.graphicsLayer { rotationZ = rotation }
                } else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = if (isTransparent) ContentScale.Fit else ContentScale.Crop,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else {
            isError = true
        }

        // Loading indicator - minimal for transparent images
        if (isLoading) {
            if (isTransparent) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Error state - minimal for transparent images
        if (isError) {
            if (isTransparent) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = "Failed to load",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

/**
 * Borderless GIF attachment that loops and opens previewer on tap.
 * Supports transparency - no corners or background for transparent GIFs.
 */
@Composable
private fun BorderlessGifAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    isPlacedSticker: Boolean = false,
    messageGuid: String = ""
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // For stickers, don't fall back to webUrl (HEIC doesn't work, needs PNG conversion)
    // Force download first to trigger HEIC→PNG conversion
    // For regular images, use full-resolution for crisp display (thumbnails are only 300px)
    val imageUrl = if (attachment.isSticker) {
        attachment.localPath  // null if not downloaded, will show error/placeholder
    } else {
        attachment.localPath ?: attachment.webUrl
    }

    // Calculate aspect ratio for proper sizing
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }

    // GIFs can have transparency
    val isTransparent = attachment.mayHaveTransparency

    // Deterministic rotation based on guid hash for placed stickers (-15° to +15°)
    val rotation = if (isPlacedSticker) {
        ((messageGuid.hashCode() % 30) - 15).toFloat()
    } else 0f

    // Slight size variance for placed stickers (0.9x to 1.1x)
    val sizeScale = if (isPlacedSticker) {
        0.9f + ((messageGuid.hashCode() and 0xFF) / 255f) * 0.2f
    } else 1f

    val effectiveMaxWidth = maxWidth * sizeScale

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val maxWidthPx = with(density) { effectiveMaxWidth.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = effectiveMaxWidth)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isPlacedSticker) {
                    Modifier.graphicsLayer { rotationZ = rotation }
                } else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "GIF",
                modifier = Modifier
                    .widthIn(max = effectiveMaxWidth)
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

        // Loading indicator - minimal for transparent GIFs
        if (isLoading) {
            if (isTransparent) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // GIF badge - only show for non-transparent GIFs
        if (!isLoading && !isError && !isTransparent) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = "GIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Error state - minimal for transparent GIFs
        if (isError) {
            if (isTransparent) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = "Failed to load",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

/**
 * Borderless video attachment that shows thumbnail until user taps play.
 * Only acquires ExoPlayer when actively playing to minimize resource usage.
 * Double-tap opens fullscreen media viewer.
 */
@Composable
private fun BorderlessInlineVideoAttachment(
    attachment: AttachmentUiModel,
    onFullscreenClick: () -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exoPlayerPool = LocalExoPlayerPool.current

    val videoUrl = attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        16f / 9f // Default video aspect ratio
    }

    // Player state - only acquire when actively playing
    var isPlayerActive by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // Function to start playback - acquires player lazily
    fun startPlayback() {
        if (videoUrl == null) return

        val player = if (exoPlayerPool != null) {
            exoPlayerPool.acquire(attachment.guid)
        } else {
            ExoPlayer.Builder(context).build()
        }

        player.apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = if (isMuted) 0f else 1f
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            play()
        }

        exoPlayer = player
        isPlayerActive = true
    }

    // Function to stop playback and release player
    fun stopPlayback() {
        exoPlayer?.let { player ->
            player.stop()
            if (exoPlayerPool != null) {
                exoPlayerPool.release(attachment.guid)
            } else {
                player.release()
            }
        }
        exoPlayer = null
        isPlayerActive = false
    }

    // Handle lifecycle - pause on background, release on dispose
    DisposableEffect(lifecycleOwner, attachment.guid) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    stopPlayback()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopPlayback()
        }
    }

    // Update volume when mute state changes
    LaunchedEffect(isMuted, exoPlayer) {
        exoPlayer?.volume = if (isMuted) 0f else 1f
    }

    if (videoUrl == null) {
        BorderlessVideoThumbnailAttachment(
            attachment = attachment,
            onClick = onFullscreenClick,
            maxWidth = maxWidth,
            modifier = modifier
        )
        return
    }

    // Show thumbnail when not playing, video player when active
    if (!isPlayerActive || exoPlayer == null) {
        // Thumbnail mode
        BorderlessVideoThumbnailWithControls(
            attachment = attachment,
            aspectRatio = aspectRatio,
            maxWidth = maxWidth,
            onPlayClick = { startPlayback() },
            onFullscreenClick = onFullscreenClick,
            modifier = modifier
        )
    } else {
        // Active playback mode
        Box(
            modifier = modifier
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { stopPlayback() },
                        onDoubleTap = {
                            stopPlayback()
                            onFullscreenClick()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { playerView ->
                    playerView.player = exoPlayer
                },
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            )

            // Mute/Unmute button (top-left)
            Surface(
                onClick = { isMuted = !isMuted },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Fullscreen button (top-right)
            Surface(
                onClick = {
                    stopPlayback()
                    onFullscreenClick()
                },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Pause indicator
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = "Tap to pause",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

/**
 * Borderless video thumbnail with play button.
 */
@Composable
private fun BorderlessVideoThumbnailWithControls(
    attachment: AttachmentUiModel,
    aspectRatio: Float,
    maxWidth: androidx.compose.ui.unit.Dp,
    onPlayClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }

    val thumbnailUrl = attachment.thumbnailPath ?: attachment.localPath ?: attachment.webUrl

    val density = LocalDensity.current
    val maxWidthPx = with(density) { maxWidth.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onPlayClick() },
                    onDoubleTap = { onFullscreenClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "Video",
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f)),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
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

        // Play button
        if (!isLoading) {
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

        // Fullscreen button (top-right)
        Surface(
            onClick = onFullscreenClick,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Borderless video thumbnail attachment (fallback when video URL not available).
 */
@Composable
private fun BorderlessVideoThumbnailAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // Prefer local thumbnail for faster loading
    val thumbnailUrl = attachment.thumbnailPath ?: attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        16f / 9f // Default video aspect ratio
    }

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val maxWidthPx = with(density) { maxWidth.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()

    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(12.dp))
            // NO background color - borderless
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .size(maxWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .build(),
                contentDescription = attachment.transferName ?: "Video",
                modifier = Modifier
                    .widthIn(max = maxWidth)
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
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            )
        }

        // Loading indicator
        if (isLoading && thumbnailUrl != null) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
    }
}
