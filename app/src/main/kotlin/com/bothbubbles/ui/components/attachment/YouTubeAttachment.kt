package com.bothbubbles.ui.components.attachment

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import android.webkit.WebView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Inline YouTube video player that shows thumbnail until user taps play.
 * Modeled after VideoAttachment for consistent UX.
 *
 * Features:
 * - Thumbnail preview with play button
 * - Tap to play inline (within message bounds)
 * - Double-tap to open in YouTube app
 * - Native Compose controls overlay (mute, fullscreen)
 * - Supports timestamp start points
 *
 * @param videoId The YouTube video ID
 * @param originalUrl The original YouTube URL (for opening in app)
 * @param thumbnailUrl The thumbnail URL from YouTube
 * @param startTimeSeconds Optional timestamp to start playback at
 * @param isShort Whether this is a YouTube Short (affects aspect ratio)
 * @param maxWidth Maximum width for the player
 * @param modifier Modifier for the composable
 */
@Composable
fun YouTubeAttachment(
    videoId: String,
    originalUrl: String,
    thumbnailUrl: String,
    startTimeSeconds: Int? = null,
    isShort: Boolean = false,
    maxWidth: Dp = 240.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Aspect ratio: 9:16 for Shorts, 16:9 for regular videos
    val aspectRatio = if (isShort) 9f / 16f else 16f / 9f

    // Player state
    var isPlayerActive by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var currentSeconds by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }

    // Keep reference to the player for control
    var youTubePlayerRef by remember { mutableStateOf<YouTubePlayer?>(null) }

    // Open in YouTube app
    fun openInYouTube() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl))
        intent.setPackage("com.google.android.youtube")
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to browser if YouTube app not installed
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl)))
        }
    }

    // Handle lifecycle - release player on dispose
    DisposableEffect(lifecycleOwner, videoId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    youTubePlayerRef?.pause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            youTubePlayerRef = null
            isPlayerActive = false
        }
    }

    if (!isPlayerActive) {
        // Thumbnail mode - show preview with play button
        YouTubeThumbnailWithControls(
            thumbnailUrl = thumbnailUrl,
            aspectRatio = aspectRatio,
            maxWidth = maxWidth,
            onPlayClick = {
                android.util.Log.d("YouTubeAttachment", "Play clicked for video: $videoId")
                isPlayerActive = true
            },
            onOpenInApp = { openInYouTube() },
            modifier = modifier
        )
    } else {
        android.util.Log.d("YouTubeAttachment", "Rendering YouTubePlayerActive for video: $videoId")
        // Active playback mode
        YouTubePlayerActive(
            videoId = videoId,
            startTimeSeconds = startTimeSeconds,
            aspectRatio = aspectRatio,
            maxWidth = maxWidth,
            isMuted = isMuted,
            isLoading = isLoading,
            isPlaying = isPlaying,
            onMuteToggle = {
                isMuted = !isMuted
                if (isMuted) {
                    youTubePlayerRef?.mute()
                } else {
                    youTubePlayerRef?.unMute()
                }
            },
            onTap = {
                if (isPlaying) {
                    youTubePlayerRef?.pause()
                } else {
                    youTubePlayerRef?.play()
                }
            },
            onDoubleTap = { openInYouTube() },
            onOpenInApp = { openInYouTube() },
            onPlayerReady = { player ->
                youTubePlayerRef = player
                player.mute() // Start muted
            },
            onStateChange = { state ->
                when (state) {
                    PlayerConstants.PlayerState.PLAYING -> {
                        isPlaying = true
                        isLoading = false
                    }
                    PlayerConstants.PlayerState.PAUSED -> {
                        isPlaying = false
                    }
                    PlayerConstants.PlayerState.BUFFERING -> {
                        isLoading = true
                    }
                    PlayerConstants.PlayerState.ENDED -> {
                        isPlaying = false
                        // Reset to thumbnail mode when video ends
                        isPlayerActive = false
                    }
                    else -> {}
                }
            },
            onCurrentSecond = { second -> currentSeconds = second },
            modifier = modifier
        )
    }
}

/**
 * YouTube thumbnail with play button and open-in-app control.
 */
@Composable
private fun YouTubeThumbnailWithControls(
    thumbnailUrl: String,
    aspectRatio: Float,
    maxWidth: Dp,
    onPlayClick: () -> Unit,
    onOpenInApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }

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
                    onDoubleTap = { onOpenInApp() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(thumbnailUrl)
                .crossfade(true)
                .size(maxWidthPx, targetHeightPx)
                .precision(Precision.INEXACT)
                .build(),
            contentDescription = "YouTube video thumbnail",
            modifier = Modifier
                .widthIn(max = maxWidth)
                .aspectRatio(aspectRatio.coerceIn(0.5f, 2f)),
            contentScale = ContentScale.Crop,
            onState = { state ->
                isLoading = state is AsyncImagePainter.State.Loading
            }
        )

        // Loading indicator
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Play button overlay
        if (!isLoading) {
            // YouTube red play button
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFF0000), // YouTube red
                modifier = Modifier.size(width = 56.dp, height = 40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play YouTube video",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Open in YouTube button (top-right)
        Surface(
            onClick = onOpenInApp,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = "Open in YouTube",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // YouTube branding indicator (bottom-left)
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Text(
                text = "YouTube",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Active YouTube player with native controls overlay.
 */
@Composable
private fun YouTubePlayerActive(
    videoId: String,
    startTimeSeconds: Int?,
    aspectRatio: Float,
    maxWidth: Dp,
    isMuted: Boolean,
    isLoading: Boolean,
    isPlaying: Boolean,
    onMuteToggle: () -> Unit,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onOpenInApp: () -> Unit,
    onPlayerReady: (YouTubePlayer) -> Unit,
    onStateChange: (PlayerConstants.PlayerState) -> Unit,
    onCurrentSecond: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var playerView by remember { mutableStateOf<YouTubePlayerView?>(null) }
    val density = LocalDensity.current

    // Calculate fixed pixel dimensions - this ensures AndroidView gets proper size
    // The core issue was that Compose layout wasn't providing constraints to AndroidView
    val widthPx = with(density) { maxWidth.roundToPx() }
    val heightPx = (widthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()
    val heightDp = with(density) { heightPx.toDp() }

    // Clean up player resources when composable leaves
    DisposableEffect(playerView) {
        onDispose {
            playerView?.let { view ->
                lifecycleOwner.lifecycle.removeObserver(view)
                // Release the player to clean up internal resources including
                // ConnectivityManager callbacks that would otherwise leak MainActivity
                view.release()
            }
        }
    }

    Box(
        modifier = modifier
            .size(maxWidth, heightDp)  // Fixed size to ensure child gets constraints
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // YouTube player view with explicit pixel dimensions in layoutParams
        AndroidView(
            factory = { ctx ->
                android.util.Log.d("YouTubeAttachment", "Creating YouTubePlayerView for video: $videoId, target size: ${widthPx}x${heightPx}")

                // Enable WebView debugging to see console errors
                WebView.setWebContentsDebuggingEnabled(true)

                YouTubePlayerView(ctx).apply {
                    // Use explicit pixel dimensions instead of MATCH_PARENT
                    layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)

                    // Disable automatic initialization so we can use custom options
                    enableAutomaticInitialization = false

                    // Register lifecycle observer for pause/resume/release
                    lifecycleOwner.lifecycle.addObserver(this)
                    playerView = this
                    android.util.Log.d("YouTubeAttachment", "Added lifecycle observer")

                    // Configure player options (disable controls since we have custom UI)
                    val options = IFramePlayerOptions.Builder()
                        .controls(0)
                        .rel(0)
                        .build()

                    // Use OnAttachStateChangeListener to ensure view is attached to window
                    // before initializing. This is more reliable than post{} for WebViews.
                    addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: android.view.View) {
                            android.util.Log.d("YouTubeAttachment", "View attached to window: width=$width, height=$height")
                            // Still use post{} to ensure layout pass completes
                            post {
                                android.util.Log.d("YouTubeAttachment", "Post-attach-layout: width=$width, height=$height - initializing player")
                                initialize(object : AbstractYouTubePlayerListener() {
                                    override fun onReady(youTubePlayer: YouTubePlayer) {
                                        android.util.Log.d("YouTubeAttachment", "Player READY for video: $videoId")
                                        onPlayerReady(youTubePlayer)
                                        // Load and play the video
                                        if (startTimeSeconds != null) {
                                            android.util.Log.d("YouTubeAttachment", "Loading video at ${startTimeSeconds}s")
                                            youTubePlayer.loadVideo(videoId, startTimeSeconds.toFloat())
                                        } else {
                                            android.util.Log.d("YouTubeAttachment", "Loading video from start")
                                            youTubePlayer.loadVideo(videoId, 0f)
                                        }
                                    }

                                    override fun onStateChange(
                                        youTubePlayer: YouTubePlayer,
                                        state: PlayerConstants.PlayerState
                                    ) {
                                        android.util.Log.d("YouTubeAttachment", "State changed: $state")
                                        onStateChange(state)
                                    }

                                    override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                        onCurrentSecond(second)
                                    }

                                    override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                                        android.util.Log.e("YouTubeAttachment", "Player ERROR: $error for video $videoId")
                                    }
                                }, options)
                                android.util.Log.d("YouTubeAttachment", "Initialized player after attach+layout")
                            }
                        }

                        override fun onViewDetachedFromWindow(v: android.view.View) {
                            android.util.Log.d("YouTubeAttachment", "View detached from window")
                        }
                    })
                }
            },
            modifier = Modifier.size(maxWidth, heightDp),  // Match parent Box size exactly
            update = { view ->
                android.util.Log.d("YouTubeAttachment", "AndroidView update: width=${view.width}, height=${view.height}")
            }
        )

        // Loading indicator overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp,
                    color = Color.White
                )
            }
        }

        // Mute/Unmute button (top-left)
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

        // Open in YouTube button (top-right)
        Surface(
            onClick = onOpenInApp,
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
                    contentDescription = "Open in YouTube",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Play/Pause indicator in center
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Tap to pause" else "Tap to play",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
