package com.bothbubbles.ui.components.attachment

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import com.bothbubbles.ui.components.message.AttachmentUiModel

/**
 * Borderless video attachment that shows thumbnail until user taps play.
 * Only acquires ExoPlayer when actively playing to minimize resource usage.
 * Double-tap opens fullscreen media viewer.
 *
 * Used for standalone video segments in segmented message rendering.
 *
 * @param attachment The video attachment to render
 * @param onFullscreenClick Callback when fullscreen is requested
 * @param maxWidth Maximum width constraint for the video
 * @param modifier Modifier for the component
 */
@Composable
fun BorderlessInlineVideoAttachment(
    attachment: AttachmentUiModel,
    onFullscreenClick: () -> Unit,
    maxWidth: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exoPlayerPool = LocalExoPlayerPool.current

    // IMPORTANT: Don't fall back to webUrl for inbound - ExoPlayer OkHttp doesn't have auth headers
    // The auth interceptor is only on the Retrofit client, not the ExoPlayer data source
    val videoUrl = if (attachment.localPath != null) {
        attachment.localPath
    } else if (attachment.isOutgoing) {
        attachment.webUrl  // Outgoing can try webUrl (server may allow)
    } else {
        null  // Inbound must wait for auto-download - can't auth to server from ExoPlayer
    }

    // Determine if we should show downloading state (inbound attachment without local file)
    val isAwaitingDownload = videoUrl == null && !attachment.isOutgoing

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
internal fun BorderlessVideoThumbnailWithControls(
    attachment: AttachmentUiModel,
    aspectRatio: Float,
    maxWidth: Dp,
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
internal fun BorderlessVideoThumbnailAttachment(
    attachment: AttachmentUiModel,
    onClick: () -> Unit,
    maxWidth: Dp,
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
