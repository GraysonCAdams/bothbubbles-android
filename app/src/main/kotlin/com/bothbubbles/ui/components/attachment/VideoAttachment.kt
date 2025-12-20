package com.bothbubbles.ui.components.attachment

import android.media.MediaMetadataRetriever
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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.bothbubbles.services.media.ExoPlayerPool
import com.bothbubbles.ui.components.message.AttachmentUiModel
import com.bothbubbles.ui.theme.MediaSizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for extracted video aspect ratios.
 * Maps video path to aspect ratio to avoid re-extracting on recomposition.
 */
private val videoAspectRatioCache = ConcurrentHashMap<String, Float>()

/**
 * Inline video attachment that shows thumbnail until user taps play.
 * Only acquires ExoPlayer when actively playing to minimize resource usage.
 * Double-tap opens fullscreen media viewer.
 *
 * @param attachment The video attachment to render
 * @param modifier Modifier for the composable
 * @param interactions Interaction callbacks and upload state
 * @param pool Optional ExoPlayerPool for efficient player management
 */
@Composable
fun VideoAttachment(
    attachment: AttachmentUiModel,
    modifier: Modifier = Modifier,
    interactions: AttachmentInteractions,
    pool: ExoPlayerPool? = LocalExoPlayerPool.current
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Use displayUrl from model - handles localPath vs webUrl logic centrally
    // IMPORTANT: For inbound attachments, webUrl won't work (ExoPlayer lacks auth headers)
    val videoUrl = attachment.displayUrl

    // Check if we have valid dimensions from the server
    val hasValidServerDimensions = attachment.width != null && attachment.height != null &&
        attachment.width > 0 && attachment.height > 0

    // Use server dimensions if valid, otherwise extract locally
    var aspectRatio by remember(attachment.guid) {
        mutableFloatStateOf(
            if (hasValidServerDimensions) {
                attachment.width!!.toFloat() / attachment.height!!.toFloat()
            } else {
                // Check cache first
                attachment.localPath?.let { videoAspectRatioCache[it] } ?: (16f / 9f)
            }
        )
    }

    // Extract dimensions from local file if server didn't provide them
    LaunchedEffect(attachment.localPath, hasValidServerDimensions) {
        if (!hasValidServerDimensions && attachment.localPath != null) {
            // Check cache first
            val cached = videoAspectRatioCache[attachment.localPath]
            if (cached != null) {
                aspectRatio = cached
                return@LaunchedEffect
            }

            // Extract dimensions on IO thread
            val extracted = withContext(Dispatchers.IO) {
                extractVideoAspectRatio(attachment.localPath)
            }
            if (extracted != null) {
                videoAspectRatioCache[attachment.localPath] = extracted
                aspectRatio = extracted
                Timber.tag("VideoAttachment").d("Extracted aspect ratio $extracted for ${attachment.guid}")
            }
        }
    }

    // Player state - only acquire when actively playing
    var isPlayerActive by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // Function to start playback - acquires player lazily
    fun startPlayback() {
        if (videoUrl == null) return

        val player = if (pool != null) {
            pool.acquire(attachment.guid)
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
            if (pool != null) {
                pool.release(attachment.guid)
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
        VideoThumbnailFallback(
            attachment = attachment,
            onClick = interactions.onFullscreenClick,
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
            onFullscreenClick = interactions.onFullscreenClick,
            isUploading = interactions.isUploading,
            uploadProgress = interactions.uploadProgress,
            modifier = modifier
        )
    } else {
        // Active playback mode
        VideoPlayerActive(
            exoPlayer = exoPlayer!!,
            aspectRatio = aspectRatio,
            isMuted = isMuted,
            onMuteToggle = { isMuted = !isMuted },
            onTap = { stopPlayback() },
            onDoubleTap = {
                stopPlayback()
                interactions.onFullscreenClick()
            },
            onFullscreenClick = {
                stopPlayback()
                interactions.onFullscreenClick()
            },
            modifier = modifier
        )
    }
}

/**
 * Active video player with controls overlay.
 */
@Composable
private fun VideoPlayerActive(
    exoPlayer: ExoPlayer,
    aspectRatio: Float,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Clamp aspect ratio to prevent extreme proportions
    val clampedAspectRatio = aspectRatio.coerceIn(0.4f, 2.5f)

    Box(
        modifier = modifier
            .widthIn(max = MediaSizing.MAX_WIDTH)
            .aspectRatio(clampedAspectRatio)
            .heightIn(min = MediaSizing.MIN_HEIGHT, max = MediaSizing.MAX_HEIGHT)
            .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() }
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
            modifier = Modifier.fillMaxSize()
        )

        // Mute/Unmute button (top-left corner)
        Surface(
            onClick = onMuteToggle,
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

    val thumbnailUrl = attachment.thumbnailPath ?: attachment.localPath ?: attachment.webUrl

    // Clamp aspect ratio to prevent extreme proportions
    val clampedAspectRatio = aspectRatio.coerceIn(0.4f, 2.5f)

    val density = LocalDensity.current
    val maxWidthPx = with(density) { MediaSizing.MAX_WIDTH.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / clampedAspectRatio).toInt()

    Box(
        modifier = modifier
            .widthIn(max = MediaSizing.MAX_WIDTH)
            .aspectRatio(clampedAspectRatio)
            .heightIn(min = MediaSizing.MIN_HEIGHT, max = MediaSizing.MAX_HEIGHT)
            .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
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
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
private fun VideoThumbnailFallback(
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

    // Clamp aspect ratio to prevent extreme proportions
    val clampedAspectRatio = aspectRatio.coerceIn(0.4f, 2.5f)

    // Calculate target size in pixels for memory-efficient loading
    val density = LocalDensity.current
    val maxWidthPx = with(density) { MediaSizing.MAX_WIDTH.toPx().toInt() }
    val targetHeightPx = (maxWidthPx / clampedAspectRatio).toInt()

    Box(
        modifier = modifier
            .widthIn(max = MediaSizing.MAX_WIDTH)
            .aspectRatio(clampedAspectRatio)
            .heightIn(min = MediaSizing.MIN_HEIGHT, max = MediaSizing.MAX_HEIGHT)
            .clip(RoundedCornerShape(MediaSizing.CORNER_RADIUS))
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
                modifier = Modifier.fillMaxSize(),
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
                    .fillMaxSize()
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

/**
 * Extract video aspect ratio from a local file using MediaMetadataRetriever.
 * Returns null if extraction fails.
 *
 * @param filePath Path to the local video file
 * @return Aspect ratio (width/height) or null if extraction fails
 */
private fun extractVideoAspectRatio(filePath: String): Float? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(filePath)

        val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

        val width = widthStr?.toIntOrNull()
        val height = heightStr?.toIntOrNull()
        val rotation = rotationStr?.toIntOrNull() ?: 0

        if (width != null && height != null && width > 0 && height > 0) {
            // Account for video rotation - 90 or 270 degrees means width/height are swapped
            if (rotation == 90 || rotation == 270) {
                height.toFloat() / width.toFloat()
            } else {
                width.toFloat() / height.toFloat()
            }
        } else {
            null
        }
    } catch (e: Exception) {
        Timber.tag("VideoAttachment").w(e, "Failed to extract aspect ratio from $filePath")
        null
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            // Ignore release errors
        }
    }
}
