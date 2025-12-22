package com.bothbubbles.ui.components.reels

import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import com.bothbubbles.services.socialmedia.SocialMediaPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen vertical swipe Reels feed similar to TikTok/Instagram Reels.
 *
 * @param reels List of reel items (cached and pending) to display
 * @param initialIndex The index to start at (for opening a specific video)
 * @param onClose Callback when the close button is pressed
 * @param onTapback Callback when a tapback is applied to a reel
 * @param onVideoViewed Callback when a video is viewed (spent time on it)
 * @param onStartDownload Callback to start downloading a pending video
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReelsFeedScreen(
    reels: List<ReelItem>,
    initialIndex: Int = 0,
    onClose: () -> Unit,
    onTapback: (messageGuid: String, url: String, tapback: ReelsTapback) -> Unit,
    onVideoViewed: (originalUrl: String) -> Unit = {},
    onStartDownload: (originalUrl: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (reels.size - 1).coerceAtLeast(0)),
        pageCount = { reels.size }
    )

    if (reels.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "No videos yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Videos you receive will appear here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(16.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        return
    }

    // Track viewed status when settling on a page
    LaunchedEffect(pagerState.currentPage) {
        val currentReel = reels.getOrNull(pagerState.currentPage)
        if (currentReel != null && currentReel.isCached && !currentReel.isViewed) {
            // Wait 2 seconds before marking as viewed
            delay(2000)
            // Only mark if still on same page
            if (pagerState.currentPage == reels.indexOf(currentReel)) {
                onVideoViewed(currentReel.originalUrl)
            }
        }
    }

    // Prefetch nearby pending videos
    LaunchedEffect(pagerState.currentPage) {
        // Prefetch 1 ahead and 1 behind
        listOf(pagerState.currentPage - 1, pagerState.currentPage + 1).forEach { prefetchIndex ->
            val prefetchReel = reels.getOrNull(prefetchIndex)
            if (prefetchReel != null && prefetchReel.isPending && !prefetchReel.isDownloading) {
                onStartDownload(prefetchReel.originalUrl)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val reel = reels[page]
            if (reel.isCached) {
                ReelPage(
                    reel = reel,
                    isCurrentPage = page == pagerState.currentPage,
                    onTapback = { tapback ->
                        onTapback(reel.messageGuid, reel.originalUrl, tapback)
                    },
                    onOpenInBrowser = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(reel.originalUrl))
                        context.startActivity(intent)
                    }
                )
            } else {
                // Pending/downloading page
                PendingReelPage(
                    reel = reel,
                    isCurrentPage = page == pagerState.currentPage,
                    onStartDownload = { onStartDownload(reel.originalUrl) },
                    onOpenInBrowser = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(reel.originalUrl))
                        context.startActivity(intent)
                    }
                )
            }
        }

        // Close button - top left
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // Page indicator - top right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "${pagerState.currentPage + 1} / ${reels.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun ReelPage(
    reel: ReelItem,
    isCurrentPage: Boolean,
    onTapback: (ReelsTapback) -> Unit,
    onOpenInBrowser: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var showTapbackSelector by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var showPauseIndicator by remember { mutableStateOf(false) }

    // Video progress tracking
    var videoProgress by remember { mutableFloatStateOf(0f) }
    var videoDuration by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubberSize by remember { mutableStateOf(IntSize.Zero) }

    // Tapback acknowledgement animation
    var tapbackAcknowledgement by remember { mutableStateOf<ReelsTapback?>(null) }
    val tapbackScale = remember { Animatable(0f) }

    // Create ExoPlayer (cachedVideo is guaranteed non-null here)
    val cachedVideo = reel.cachedVideo!!
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(cachedVideo.localPath))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // Track progress periodically
    LaunchedEffect(isCurrentPage) {
        while (isCurrentPage) {
            if (!isScrubbing && exoPlayer.duration > 0) {
                videoDuration = exoPlayer.duration
                videoProgress = exoPlayer.currentPosition.toFloat() / exoPlayer.duration
            }
            delay(100) // Update every 100ms
        }
    }

    // Control playback based on current page
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            exoPlayer.playWhenReady = true
            isPaused = false
        } else {
            exoPlayer.playWhenReady = false
            exoPlayer.seekTo(0)
        }
    }

    // Clean up when disposed
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    // Show tapback acknowledgement animation
    LaunchedEffect(tapbackAcknowledgement) {
        if (tapbackAcknowledgement != null) {
            tapbackScale.snapTo(0f)
            tapbackScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(200, easing = LinearEasing)
            )
            delay(800)
            tapbackScale.animateTo(
                targetValue = 0f,
                animationSpec = tween(200, easing = LinearEasing)
            )
            tapbackAcknowledgement = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showTapbackSelector = true
                    },
                    onTap = {
                        // Single tap to pause/play
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                            isPaused = true
                            showPauseIndicator = true
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } else {
                            exoPlayer.play()
                            isPaused = false
                            showPauseIndicator = true
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        // Hide pause indicator after a short delay
                        scope.launch {
                            delay(500)
                            showPauseIndicator = false
                        }
                    }
                )
            }
    ) {
        // Video player
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Pause indicator overlay
        AnimatedVisibility(
            visible = showPauseIndicator && isPaused,
            enter = fadeIn() + scaleIn(initialScale = 0.5f),
            exit = fadeOut() + scaleOut(targetScale = 0.5f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Pause,
                    contentDescription = "Paused",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Tapback acknowledgement animation (center)
        if (tapbackAcknowledgement != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .scale(tapbackScale.value * 2f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tapbackAcknowledgement!!.emoji,
                    style = MaterialTheme.typography.displayLarge
                )
            }
        }

        // Bottom gradient overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
        )

        // TikTok-style progress scrubber - bottom
        VideoScrubber(
            progress = videoProgress,
            onScrubStart = {
                isScrubbing = true
                exoPlayer.pause()
            },
            onScrubChange = { newProgress ->
                videoProgress = newProgress
                if (exoPlayer.duration > 0) {
                    exoPlayer.seekTo((newProgress * exoPlayer.duration).toLong())
                }
            },
            onScrubEnd = {
                isScrubbing = false
                if (!isPaused) {
                    exoPlayer.play()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = 4.dp)
        )

        // Sender info badge - bottom left (above scrubber)
        SenderInfoBadge(
            senderName = reel.senderName,
            senderAddress = reel.senderAddress,
            platform = reel.platform,
            timestamp = reel.sentTimestamp,
            currentTapback = reel.currentTapback,
            onOpenInBrowser = onOpenInBrowser,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 16.dp, bottom = 24.dp)
        )

        // Tapback selector overlay
        AnimatedVisibility(
            visible = showTapbackSelector,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            TapbackSelector(
                currentTapback = reel.currentTapback,
                onTapbackSelected = { tapback ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    tapbackAcknowledgement = tapback
                    onTapback(tapback)
                    showTapbackSelector = false
                },
                onDismiss = { showTapbackSelector = false }
            )
        }
    }
}

/**
 * Page for a pending (not yet downloaded) reel.
 * Shows download progress or a download button.
 */
@Composable
private fun PendingReelPage(
    reel: ReelItem,
    isCurrentPage: Boolean,
    onStartDownload: () -> Unit,
    onOpenInBrowser: () -> Unit
) {
    val view = LocalView.current

    // Auto-start download when this becomes the current page
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && !reel.isDownloading && reel.downloadError == null) {
            onStartDownload()
        }
    }

    // Collect download progress if available
    val downloadProgress = reel.downloadProgress?.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Bottom gradient overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
        )

        // Center content - download state
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                reel.downloadError != null -> {
                    // Error state
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Download failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onStartDownload()
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "Retry",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                reel.isDownloading || downloadProgress != null -> {
                    // Downloading state
                    val progress = downloadProgress?.value?.progress ?: 0f
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(64.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.2f),
                            strokeWidth = 4.dp
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Downloading...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                else -> {
                    // Not started - show download button
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Video not downloaded",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onStartDownload()
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "Download",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // Sender info badge - bottom left
        SenderInfoBadge(
            senderName = reel.senderName,
            senderAddress = reel.senderAddress,
            platform = reel.platform,
            timestamp = reel.sentTimestamp,
            currentTapback = null, // No tapback for pending items
            onOpenInBrowser = onOpenInBrowser,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 16.dp, bottom = 24.dp)
        )
    }
}

/**
 * TikTok-style video scrubber with drag-to-seek functionality.
 */
@Composable
private fun VideoScrubber(
    progress: Float,
    onScrubStart: () -> Unit,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scrubberWidth by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Animated height for when dragging
    val barHeight by animateFloatAsState(
        targetValue = if (isDragging) 8f else 3f,
        label = "scrubber_height"
    )

    Box(
        modifier = modifier
            .height(24.dp) // Touch target
            .onSizeChanged { scrubberWidth = it.width.toFloat() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        onScrubStart()
                        if (scrubberWidth > 0) {
                            val newProgress = (offset.x / scrubberWidth).coerceIn(0f, 1f)
                            onScrubChange(newProgress)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (scrubberWidth > 0) {
                            val newProgress = (change.position.x / scrubberWidth).coerceIn(0f, 1f)
                            onScrubChange(newProgress)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        onScrubEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        onScrubEnd()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Background bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.3f))
        )

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(barHeight.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .align(Alignment.CenterStart)
        )

        // Scrubber handle (only show when dragging)
        if (isDragging) {
            Box(
                modifier = Modifier
                    .padding(start = (progress * scrubberWidth / androidx.compose.ui.platform.LocalDensity.current.density).coerceAtLeast(0f).dp)
                    .size(16.dp)
                    .background(Color.White, CircleShape)
                    .align(Alignment.CenterStart)
            )
        }
    }
}

@Composable
private fun SenderInfoBadge(
    senderName: String?,
    senderAddress: String?,
    platform: SocialMediaPlatform,
    timestamp: Long,
    currentTapback: ReelsTapback?,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar placeholder
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Sender name
                Text(
                    text = senderName ?: senderAddress ?: "Unknown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Platform and time
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = platform.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = " \u2022 ",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatRelativeTime(timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                // Current tapback if any
                if (currentTapback != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${currentTapback.emoji} ${currentTapback.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }

            // Open in browser button
            IconButton(
                onClick = onOpenInBrowser,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = "Open in browser",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun TapbackSelector(
    currentTapback: ReelsTapback?,
    onTapbackSelected: (ReelsTapback) -> Unit,
    onDismiss: () -> Unit
) {
    // Click outside to dismiss
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .clickable(enabled = false) {}, // Prevent click through
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.85f),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "React to this video",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 2x4 grid of tapbacks
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Top row: Like, Laugh
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TapbackButton(
                            tapback = ReelsTapback.LIKE,
                            isSelected = currentTapback == ReelsTapback.LIKE,
                            onClick = { onTapbackSelected(ReelsTapback.LIKE) }
                        )
                        TapbackButton(
                            tapback = ReelsTapback.LAUGH,
                            isSelected = currentTapback == ReelsTapback.LAUGH,
                            onClick = { onTapbackSelected(ReelsTapback.LAUGH) }
                        )
                    }

                    // Bottom row: Love, Dislike
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TapbackButton(
                            tapback = ReelsTapback.LOVE,
                            isSelected = currentTapback == ReelsTapback.LOVE,
                            onClick = { onTapbackSelected(ReelsTapback.LOVE) }
                        )
                        TapbackButton(
                            tapback = ReelsTapback.DISLIKE,
                            isSelected = currentTapback == ReelsTapback.DISLIKE,
                            onClick = { onTapbackSelected(ReelsTapback.DISLIKE) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TapbackButton(
    tapback: ReelsTapback,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        label = "tapback_scale"
    )

    Surface(
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.White.copy(alpha = 0.1f)
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = tapback.emoji,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = tapback.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    Color.White.copy(alpha = 0.8f)
                }
            )
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp == 0L) return "Unknown"

    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> {
            val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
            dateFormat.format(Date(timestamp))
        }
    }
}
