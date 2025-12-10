package com.bothbubbles.ui.components

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
import com.bothbubbles.util.rememberBlurhashBitmap

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
 */
@Composable
fun AttachmentContent(
    attachment: AttachmentUiModel,
    isFromMe: Boolean,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadClick: ((String) -> Unit)? = null,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f
) {
    // Show placeholder if manual download mode and attachment needs download
    // For stickers, ALWAYS show placeholder when not downloaded (they need HEIC→PNG conversion)
    val showPlaceholder = attachment.needsDownload && (onDownloadClick != null || attachment.isSticker)

    // For stickers in auto-download mode, show as "downloading" even if not actively downloading yet
    val effectiveIsDownloading = isDownloading || (attachment.isSticker && attachment.needsDownload && onDownloadClick == null)

    when {
        showPlaceholder -> AttachmentPlaceholder(
            attachment = attachment,
            isFromMe = isFromMe,
            onDownloadClick = { onDownloadClick?.invoke(attachment.guid) },
            isDownloading = effectiveIsDownloading,
            downloadProgress = downloadProgress,
            modifier = modifier
        )
        attachment.isGif -> GifAttachment(
            attachment = attachment,
            onClick = { onMediaClick(attachment.guid) },
            modifier = modifier
        )
        attachment.isImage -> ImageAttachment(
            attachment = attachment,
            onClick = { onMediaClick(attachment.guid) },
            modifier = modifier
        )
        attachment.isVideo -> InlineVideoAttachment(
            attachment = attachment,
            onFullscreenClick = { onMediaClick(attachment.guid) },
            modifier = modifier
        )
        attachment.isAudio -> AudioAttachment(
            attachment = attachment,
            onClick = { onMediaClick(attachment.guid) },
            isFromMe = isFromMe,
            modifier = modifier
        )
        attachment.isVCard -> VCardAttachment(
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
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // Use thumbnail for faster loading in message list, fall back to full image
    val imageUrl = attachment.thumbnailPath ?: attachment.localPath ?: attachment.webUrl

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
            .then(if (isTransparent) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
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
    modifier: Modifier = Modifier
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
            .then(if (isTransparent) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
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
    }
}

/**
 * Inline video player with mute/unmute and fullscreen controls.
 * Double-tap opens fullscreen media viewer.
 */
@Composable
private fun InlineVideoAttachment(
    attachment: AttachmentUiModel,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val videoUrl = attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        16f / 9f // Default video aspect ratio
    }

    var isMuted by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var showPlayButton by remember { mutableStateOf(true) }

    // Create ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f // Start muted
        }
    }

    // Set media item when URL is available
    LaunchedEffect(videoUrl) {
        videoUrl?.let { url ->
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
        }
    }

    // Handle lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.pause()
                    isPlaying = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Don't auto-resume
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    // Update volume when mute state changes
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
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

    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // Toggle play/pause on single tap
                        if (isPlaying) {
                            exoPlayer.pause()
                            isPlaying = false
                            showPlayButton = true
                        } else {
                            exoPlayer.play()
                            isPlaying = true
                            showPlayButton = false
                        }
                    },
                    onDoubleTap = {
                        // Open fullscreen on double tap
                        exoPlayer.pause()
                        isPlaying = false
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
            modifier = Modifier
                .widthIn(max = 250.dp)
                .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
        )

        // Play button overlay with fade + scale animation (shown when paused)
        AnimatedVisibility(
            visible = showPlayButton,
            enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.8f, animationSpec = tween(100)),
            exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f, animationSpec = tween(100))
        ) {
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
                exoPlayer.pause()
                isPlaying = false
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
    messageGuid: String = ""
) {
    // Show placeholder if manual download mode and attachment needs download
    // For stickers, ALWAYS show placeholder when not downloaded (they need HEIC→PNG conversion)
    val showPlaceholder = attachment.needsDownload && (onDownloadClick != null || attachment.isSticker)

    // For stickers in auto-download mode, show as "downloading" even if not actively downloading yet
    val effectiveIsDownloading = isDownloading || (attachment.isSticker && attachment.needsDownload && onDownloadClick == null)

    // Use smaller max width for placed stickers
    val effectiveMaxWidth = if (isPlacedSticker) 140.dp else maxWidth

    when {
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
    // For non-stickers, use thumbnail when available for faster loading
    val imageUrl = if (attachment.isSticker) {
        attachment.localPath  // null if not downloaded, will show error/placeholder
    } else {
        attachment.thumbnailPath ?: attachment.localPath ?: attachment.webUrl
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
            .then(if (isTransparent) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
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
    // For non-stickers, use thumbnail when available for faster loading
    val imageUrl = if (attachment.isSticker) {
        attachment.localPath  // null if not downloaded, will show error/placeholder
    } else {
        attachment.thumbnailPath ?: attachment.localPath ?: attachment.webUrl
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
            .then(if (isTransparent) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
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
 * Borderless inline video player with mute/unmute and fullscreen controls.
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

    val videoUrl = attachment.localPath ?: attachment.webUrl

    // Calculate aspect ratio
    val aspectRatio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        16f / 9f // Default video aspect ratio
    }

    var isMuted by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var showPlayButton by remember { mutableStateOf(true) }

    // Create ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f // Start muted
        }
    }

    // Set media item when URL is available
    LaunchedEffect(videoUrl) {
        videoUrl?.let { url ->
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
        }
    }

    // Handle lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.pause()
                    isPlaying = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Don't auto-resume
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    // Update volume when mute state changes
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    if (videoUrl == null) {
        // Fallback to thumbnail view if no URL
        BorderlessVideoThumbnailAttachment(
            attachment = attachment,
            onClick = onFullscreenClick,
            maxWidth = maxWidth,
            modifier = modifier
        )
        return
    }

    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // Toggle play/pause on single tap
                        if (isPlaying) {
                            exoPlayer.pause()
                            isPlaying = false
                            showPlayButton = true
                        } else {
                            exoPlayer.play()
                            isPlaying = true
                            showPlayButton = false
                        }
                    },
                    onDoubleTap = {
                        // Open fullscreen on double tap
                        exoPlayer.pause()
                        isPlaying = false
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
            modifier = Modifier
                .widthIn(max = maxWidth)
                .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
        )

        // Play button overlay with fade + scale animation (shown when paused)
        AnimatedVisibility(
            visible = showPlayButton,
            enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.8f, animationSpec = tween(100)),
            exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f, animationSpec = tween(100))
        ) {
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
                exoPlayer.pause()
                isPlaying = false
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
