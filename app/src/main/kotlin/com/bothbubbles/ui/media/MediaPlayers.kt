package com.bothbubbles.ui.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

@Composable
internal fun ZoomableImage(
    imageUrl: String,
    contentDescription: String,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Custom gesture detection that only consumes pan when zoomed
                // This allows HorizontalPager to handle swipes when not zoomed
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()

                        // Handle zoom - anchor to pinch centroid so it feels natural
                        if (zoom != 1f) {
                            val oldScale = scale
                            val newScale = (scale * zoom).coerceIn(0.5f, 5f)

                            if (newScale <= 1f) {
                                // Reset when zoomed out to 1x or below
                                offset = Offset.Zero
                            } else {
                                // Calculate effective zoom after clamping
                                val effectiveZoom = newScale / oldScale

                                // Anchor zoom to pinch centroid:
                                // Adjust offset so the point under the centroid stays fixed
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                offset = offset * effectiveZoom + (centroid - center) * (1 - effectiveZoom)
                            }

                            scale = newScale
                            event.changes.forEach { it.consume() }
                        }

                        // Only consume pan when zoomed in - let pager handle swipes otherwise
                        if (scale > 1f && (pan.x != 0f || pan.y != 0f)) {
                            offset = Offset(
                                x = offset.x + pan.x,
                                y = offset.y + pan.y
                            )
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapOffset ->
                        // Double tap to toggle zoom, centered on tap location
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val targetScale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Zoom in centered on the tap point
                            offset = (tapOffset - center) * (1 - targetScale)
                            scale = targetScale
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentScale = ContentScale.Fit,
            onState = { state ->
                isLoading = state is AsyncImagePainter.State.Loading
                isError = state is AsyncImagePainter.State.Error
            }
        )

        if (isLoading) {
            CircularProgressIndicator(color = Color.White)
        }

        if (isError) {
            Icon(
                Icons.Outlined.BrokenImage,
                contentDescription = "Failed to load",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(64.dp)
            )
        }
    }
}

@Composable
internal fun VideoPlayer(
    videoUrl: String,
    onTap: () -> Unit,
    bottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(videoUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun AudioPlayer(
    audioUrl: String,
    title: String
) {
    val context = LocalContext.current

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(0L) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(audioUrl)
            setMediaItem(mediaItem)
            prepare()
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Update progress
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            progress = if (duration > 0) {
                exoPlayer.currentPosition.toFloat() / duration
            } else 0f
            kotlinx.coroutines.delay(100)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Audio icon
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Title
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = 0.3f)
        )

        // Time display
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration((progress * duration).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Play/Pause button
        FilledIconButton(
            onClick = {
                if (isPlaying) {
                    exoPlayer.pause()
                } else {
                    exoPlayer.play()
                }
            },
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

internal fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = millis / (1000 * 60 * 60)

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
