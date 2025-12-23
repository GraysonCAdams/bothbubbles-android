package com.bothbubbles.ui.components.common

import timber.log.Timber
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bothbubbles.services.socialmedia.DownloadProgress
import com.bothbubbles.services.socialmedia.SocialMediaDownloadService
import com.bothbubbles.services.socialmedia.SocialMediaPlatform
import com.bothbubbles.services.socialmedia.SocialMediaResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State for the social media video fetching/playback flow.
 */
sealed interface SocialMediaVideoState {
    /** Initial state - showing link preview, waiting for user to tap */
    data object Idle : SocialMediaVideoState

    /** Fetching the video URL from the social media platform */
    data object Fetching : SocialMediaVideoState

    /** Successfully extracted video URL, downloading to cache */
    data class Downloading(val progress: Float) : SocialMediaVideoState

    /** Successfully extracted video URL, ready to play */
    data class Ready(val videoUrl: String, val localPath: String? = null) : SocialMediaVideoState

    /** Playing the video */
    data class Playing(val videoPath: String) : SocialMediaVideoState

    /** Fetch failed with error */
    data class Error(val message: String, val canRetry: Boolean = true) : SocialMediaVideoState

    /** Timed out waiting for response */
    data object Timeout : SocialMediaVideoState

    /** User dismissed - show original link preview */
    data object Dismissed : SocialMediaVideoState
}

private const val FETCH_TIMEOUT_MS = 15_000L

/**
 * A composable that handles the full flow of:
 * 1. Detecting if a URL is a supported social media link
 * 2. Showing a loading state while fetching the video URL
 * 3. Downloading and caching the video with progress
 * 4. Playing the video inline
 * 5. Providing "Show Original" to revert to link preview
 *
 * @param url The social media URL to handle
 * @param messageGuid The GUID of the message containing this URL
 * @param platform The detected social media platform
 * @param downloader The social media download service
 * @param isFromMe Whether the message is from the current user
 * @param onShowOriginal Callback when user wants to see the original link preview
 * @param onOpenInBrowser Callback to open the URL in browser
 * @param onOpenReelsFeed Callback to open the Reels feed at this video
 * @param modifier Modifier for the composable
 */
@Composable
fun SocialMediaVideoPlayer(
    url: String,
    messageGuid: String,
    chatGuid: String?,
    platform: SocialMediaPlatform,
    downloader: SocialMediaDownloadService,
    isFromMe: Boolean,
    onShowOriginal: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onOpenReelsFeed: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var state by remember { mutableStateOf<SocialMediaVideoState>(SocialMediaVideoState.Idle) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var fetchJob by remember { mutableStateOf<Job?>(null) }

    // Start fetching automatically when composed
    LaunchedEffect(url) {
        // First check if we have a cached version
        val cachedPath = downloader.getCachedVideoPath(url)
        if (cachedPath != null) {
            state = SocialMediaVideoState.Playing(cachedPath)
            return@LaunchedEffect
        }

        state = SocialMediaVideoState.Fetching
        fetchJob = scope.launch {
            // Start timeout timer
            val timeoutJob = launch {
                delay(FETCH_TIMEOUT_MS)
                if (state == SocialMediaVideoState.Fetching) {
                    state = SocialMediaVideoState.Timeout
                }
            }

            try {
                when (val result = downloader.extractVideoUrl(url, platform)) {
                    is SocialMediaResult.Success -> {
                        timeoutJob.cancel()
                        // Start downloading
                        state = SocialMediaVideoState.Downloading(0f)

                        val progressFlow = downloader.downloadAndCacheVideo(
                            result = result,
                            originalUrl = url,
                            messageGuid = messageGuid,
                            chatGuid = chatGuid,
                            platform = platform
                        )

                        progressFlow.collect { progress ->
                            downloadProgress = progress.progress
                            state = if (progress.isComplete) {
                                val path = downloader.getCachedVideoPath(url) ?: result.videoUrl
                                SocialMediaVideoState.Playing(path)
                            } else if (progress.error != null) {
                                SocialMediaVideoState.Error(progress.error)
                            } else {
                                SocialMediaVideoState.Downloading(progress.progress)
                            }
                        }
                    }
                    is SocialMediaResult.Error -> {
                        timeoutJob.cancel()
                        Timber.w("[SmartLink] extractVideoUrl returned Error for $url: ${result.message}")
                        state = SocialMediaVideoState.Error(result.message)
                    }
                    is SocialMediaResult.NotSupported -> {
                        timeoutJob.cancel()
                        Timber.w("[SmartLink] extractVideoUrl returned NotSupported for $url")
                        // Don't dismiss - show as error so user can still try to open in browser
                        // This happens when Instagram content requires login or is a photo post
                        state = SocialMediaVideoState.Error("Content not available. May require login or be a photo post.")
                    }
                }
            } catch (e: Exception) {
                timeoutJob.cancel()
                state = SocialMediaVideoState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // Cancel fetch job when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            fetchJob?.cancel()
        }
    }

    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current

    Column(
        modifier = modifier.widthIn(max = 280.dp),
        horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start
    ) {
        // Video content surface with long-press support
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(onLongPress) {
                    if (onLongPress != null) {
                        detectTapGestures(
                            onLongPress = {
                                hapticFeedback.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                )
                                onLongPress()
                            }
                        )
                    }
                },
            color = if (isFromMe) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            tonalElevation = 1.dp
        ) {
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "social_media_state"
            ) { currentState ->
                when (currentState) {
                    is SocialMediaVideoState.Idle,
                    is SocialMediaVideoState.Fetching -> {
                        FetchingOverlay(
                            platform = platform,
                            onCancel = {
                                fetchJob?.cancel()
                                state = SocialMediaVideoState.Dismissed
                                onShowOriginal()
                            }
                        )
                    }

                    is SocialMediaVideoState.Downloading -> {
                        DownloadingOverlay(
                            platform = platform,
                            progress = downloadProgress,
                            onCancel = {
                                fetchJob?.cancel()
                                state = SocialMediaVideoState.Dismissed
                                onShowOriginal()
                            }
                        )
                    }

                    is SocialMediaVideoState.Ready -> {
                        // Auto-play when ready
                        LaunchedEffect(currentState.videoUrl) {
                            val path = currentState.localPath ?: currentState.videoUrl
                            state = SocialMediaVideoState.Playing(path)
                        }
                        FetchingOverlay(
                            platform = platform,
                            onCancel = {
                                state = SocialMediaVideoState.Dismissed
                                onShowOriginal()
                            }
                        )
                    }

                    is SocialMediaVideoState.Playing -> {
                        VideoPlayerView(
                            videoUrl = currentState.videoPath,
                            onFullscreen = onOpenReelsFeed ?: onOpenInBrowser,  // Prefer Reels feed for fullscreen
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is SocialMediaVideoState.Error -> {
                        ErrorOverlay(
                            message = currentState.message,
                            onRetry = {
                                state = SocialMediaVideoState.Fetching
                                fetchJob = scope.launch {
                                    retryFetch(
                                        url = url,
                                        messageGuid = messageGuid,
                                        chatGuid = chatGuid,
                                        platform = platform,
                                        downloader = downloader,
                                        onStateChange = { state = it },
                                        onProgressChange = { downloadProgress = it },
                                        onShowOriginal = onShowOriginal
                                    )
                                }
                            }
                        )
                    }

                    is SocialMediaVideoState.Timeout -> {
                        TimeoutOverlay(
                            onRetry = {
                                state = SocialMediaVideoState.Fetching
                                fetchJob = scope.launch {
                                    retryFetch(
                                        url = url,
                                        messageGuid = messageGuid,
                                        chatGuid = chatGuid,
                                        platform = platform,
                                        downloader = downloader,
                                        onStateChange = { state = it },
                                        onProgressChange = { downloadProgress = it },
                                        onShowOriginal = onShowOriginal
                                    )
                                }
                            }
                        )
                    }

                    is SocialMediaVideoState.Dismissed -> {
                        // This state means we should show the original link preview
                        // The parent composable handles this
                    }
                }
            }
        }

        // "Show Original" link - outside Surface with no background, aligned to message side
        if (state !is SocialMediaVideoState.Dismissed && state !is SocialMediaVideoState.Idle) {
            Text(
                text = "Show Original",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        fetchJob?.cancel()
                        state = SocialMediaVideoState.Dismissed
                        onShowOriginal()
                    }
                    .padding(top = 4.dp)
            )
        }
    }
}

private suspend fun retryFetch(
    url: String,
    messageGuid: String,
    chatGuid: String?,
    platform: SocialMediaPlatform,
    downloader: SocialMediaDownloadService,
    onStateChange: (SocialMediaVideoState) -> Unit,
    onProgressChange: (Float) -> Unit,
    onShowOriginal: () -> Unit
) {
    val timeoutJob = kotlinx.coroutines.coroutineScope {
        launch {
            delay(FETCH_TIMEOUT_MS)
            onStateChange(SocialMediaVideoState.Timeout)
        }
    }

    try {
        when (val result = downloader.extractVideoUrl(url, platform)) {
            is SocialMediaResult.Success -> {
                timeoutJob.cancel()
                onStateChange(SocialMediaVideoState.Downloading(0f))

                val progressFlow = downloader.downloadAndCacheVideo(
                    result = result,
                    originalUrl = url,
                    messageGuid = messageGuid,
                    chatGuid = chatGuid,
                    platform = platform
                )

                progressFlow.collect { progress ->
                    onProgressChange(progress.progress)
                    val newState = if (progress.isComplete) {
                        val path = downloader.getCachedVideoPath(url) ?: result.videoUrl
                        SocialMediaVideoState.Playing(path)
                    } else if (progress.error != null) {
                        SocialMediaVideoState.Error(progress.error)
                    } else {
                        SocialMediaVideoState.Downloading(progress.progress)
                    }
                    onStateChange(newState)
                }
            }
            is SocialMediaResult.Error -> {
                timeoutJob.cancel()
                onStateChange(SocialMediaVideoState.Error(result.message))
            }
            is SocialMediaResult.NotSupported -> {
                timeoutJob.cancel()
                // Don't dismiss - show as error so user can still try to open in browser
                onStateChange(SocialMediaVideoState.Error("Content not available. May require login or be a photo post."))
            }
        }
    } catch (e: Exception) {
        timeoutJob.cancel()
        onStateChange(SocialMediaVideoState.Error(e.message ?: "Unknown error"))
    }
}

@Composable
private fun FetchingOverlay(
    platform: SocialMediaPlatform,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f) // Vertical video aspect ratio
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Loading ${platform.displayName} video...",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(24.dp))

            FilledTonalButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun DownloadingOverlay(
    platform: SocialMediaPlatform,
    progress: Float,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f) // Vertical video aspect ratio
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Circular progress with percentage
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(72.dp)
            ) {
                // Background circle
                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.size(72.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    strokeWidth = 4.dp,
                    strokeCap = StrokeCap.Round
                )
                // Progress circle
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(72.dp),
                    color = Color.White,
                    strokeWidth = 4.dp,
                    strokeCap = StrokeCap.Round
                )
                // Percentage text
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Downloading ${platform.displayName} video...",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(24.dp))

            FilledTonalButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Failed to load video",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            FilledTonalButton(onClick = onRetry) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun TimeoutOverlay(
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Request timed out",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "The video is taking too long to load",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            FilledTonalButton(onClick = onRetry) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Retry")
            }
        }
    }
}

/**
 * Video player with simple controls: play/pause overlay, mute button, and fullscreen button.
 * Uses the same pattern as VideoAttachment for consistency.
 */
@Composable
private fun VideoPlayerView(
    videoUrl: String,
    onFullscreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var isPlaying by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f  // Start muted
        }
    }

    // Sync mute state
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // Sync play state
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f) // Vertical video
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isPlaying = !isPlaying }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Video surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false  // Custom controls
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize()
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
        if (onFullscreen != null) {
            Surface(
                onClick = onFullscreen,
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

        // Play/Pause indicator (center)
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
