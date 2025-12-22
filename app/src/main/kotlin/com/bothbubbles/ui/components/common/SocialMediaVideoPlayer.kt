package com.bothbubbles.ui.components.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
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
    platform: SocialMediaPlatform,
    downloader: SocialMediaDownloadService,
    isFromMe: Boolean,
    onShowOriginal: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onOpenReelsFeed: (() -> Unit)? = null,
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
                        state = SocialMediaVideoState.Error(result.message)
                    }
                    is SocialMediaResult.NotSupported -> {
                        timeoutJob.cancel()
                        state = SocialMediaVideoState.Dismissed
                        onShowOriginal()
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

    Surface(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = if (isFromMe) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        tonalElevation = 1.dp
    ) {
        Column {
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
                                        platform = platform,
                                        downloader = downloader,
                                        onStateChange = { state = it },
                                        onProgressChange = { downloadProgress = it },
                                        onShowOriginal = onShowOriginal
                                    )
                                }
                            },
                            onOpenInBrowser = onOpenInBrowser
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
                                        platform = platform,
                                        downloader = downloader,
                                        onStateChange = { state = it },
                                        onProgressChange = { downloadProgress = it },
                                        onShowOriginal = onShowOriginal
                                    )
                                }
                            },
                            onOpenInBrowser = onOpenInBrowser
                        )
                    }

                    is SocialMediaVideoState.Dismissed -> {
                        // This state means we should show the original link preview
                        // The parent composable handles this
                    }
                }
            }

            // "Show Original" button - always visible except when dismissed
            if (state !is SocialMediaVideoState.Dismissed && state !is SocialMediaVideoState.Idle) {
                TextButton(
                    onClick = {
                        fetchJob?.cancel()
                        state = SocialMediaVideoState.Dismissed
                        onShowOriginal()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Show Original",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

private suspend fun retryFetch(
    url: String,
    messageGuid: String,
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
                onStateChange(SocialMediaVideoState.Dismissed)
                onShowOriginal()
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
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRetry) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry")
                }

                FilledTonalButton(onClick = onOpenInBrowser) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun TimeoutOverlay(
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRetry) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry")
                }

                FilledTonalButton(onClick = onOpenInBrowser) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun VideoPlayerView(
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
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
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
