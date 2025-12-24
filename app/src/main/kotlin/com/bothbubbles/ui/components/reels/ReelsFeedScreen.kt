package com.bothbubbles.ui.components.reels

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import android.view.HapticFeedbackConstants
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bothbubbles.services.linkpreview.LinkMetadata
import com.bothbubbles.services.linkpreview.LinkMetadataResult
import com.bothbubbles.services.linkpreview.LinkPreviewService
import com.bothbubbles.services.socialmedia.SocialMediaPlatform
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.util.parsing.HtmlEntityDecoder
import com.bothbubbles.ui.components.message.ReactionsDisplay
import com.bothbubbles.ui.components.message.ReactionUiModel
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.util.ThrottledHaptic
import com.bothbubbles.util.rememberThrottledHaptic
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
    initialUnwatchedOnly: Boolean = false,
    onClose: () -> Unit,
    onTapback: (messageGuid: String, url: String, tapback: ReelsTapback) -> Unit,
    onVideoViewed: (originalUrl: String) -> Unit = {},
    onStartDownload: (originalUrl: String) -> Unit = {},
    onReplyClick: (messageGuid: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Unwatched only filter - set from initial value (no toggle inside feed)
    val unwatchedOnly = initialUnwatchedOnly

    // Filter reels based on toggle
    val filteredReels = remember(reels, unwatchedOnly) {
        if (unwatchedOnly) {
            reels.filter { !it.isViewed }
        } else {
            reels
        }
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (filteredReels.size - 1).coerceAtLeast(0)),
        pageCount = { filteredReels.size }
    )

    // Handle back gesture/button to close reels instead of navigating back
    BackHandler(enabled = true) {
        onClose()
    }

    // Show "All caught up!" when unwatched only is on but no unwatched videos
    if (unwatchedOnly && filteredReels.isEmpty() && reels.isNotEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "All caught up!",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = {
                        HapticUtils.onTap(haptic)
                        onClose()
                    }
                ) {
                    Text("Close")
                }
            }

            // Close button
            IconButton(
                onClick = {
                    HapticUtils.onTap(haptic)
                    onClose()
                },
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

    if (filteredReels.isEmpty()) {
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
                onClick = {
                    HapticUtils.onTap(haptic)
                    onClose()
                },
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

    // Hold-to-hide UI state - lifted to parent so all UI elements can fade together
    var isHoldingOnVideo by remember { mutableStateOf(false) }
    val uiAlpha by animateFloatAsState(
        targetValue = if (isHoldingOnVideo) 0f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "uiAlpha"
    )

    // Snackbar for "You're all caught up!" toast
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Track if we've already shown the "caught up" toast this session
    var hasShownCaughtUpToast by remember { mutableStateOf(false) }

    // Detect transition from last unwatched to watched video
    // Reels are sorted: unwatched first (by time desc), then watched (by time desc)
    // So when we move from an unwatched to a watched reel, we've passed the last unwatched
    LaunchedEffect(pagerState.currentPage, filteredReels) {
        // Only show this in "All reels" mode (not unwatched only)
        if (unwatchedOnly || hasShownCaughtUpToast) return@LaunchedEffect

        val currentPage = pagerState.currentPage
        val previousPage = currentPage - 1 // We're swiping down (next videos are higher indices)

        // Check if we came from an unwatched video and landed on a watched one
        val previousReel = filteredReels.getOrNull(previousPage)
        val currentReel = filteredReels.getOrNull(currentPage)

        if (previousReel != null && currentReel != null) {
            // If previous was unwatched and current is watched, we just passed the boundary
            // Since unwatched are sorted first, this means we've caught up on all unwatched
            if (!previousReel.isViewed && currentReel.isViewed) {
                hasShownCaughtUpToast = true
                scope.launch {
                    snackbarHostState.showSnackbar("You're all caught up!")
                }
            }
        }
    }

    // Mark reel as viewed immediately when displayed (regardless of play status or download state)
    LaunchedEffect(pagerState.currentPage, filteredReels) {
        val currentReel = filteredReels.getOrNull(pagerState.currentPage)
        if (currentReel != null && !currentReel.isViewed) {
            // Pass the appropriate identifier: originalUrl for social media, guid for attachments
            val identifier = if (currentReel.isAttachment) {
                currentReel.attachmentVideo?.guid ?: return@LaunchedEffect
            } else {
                currentReel.originalUrl
            }
            onVideoViewed(identifier)
        }
    }

    // Prefetch nearby pending videos
    LaunchedEffect(pagerState.currentPage, filteredReels) {
        // Prefetch 1 ahead and 1 behind
        listOf(pagerState.currentPage - 1, pagerState.currentPage + 1).forEach { prefetchIndex ->
            val prefetchReel = filteredReels.getOrNull(prefetchIndex)
            if (prefetchReel != null && prefetchReel.isPending && !prefetchReel.isDownloading) {
                onStartDownload(prefetchReel.originalUrl)
            }
        }
    }

    // Haptic feedback when page settles on a new video
    var previousPage by remember { mutableStateOf(pagerState.currentPage) }
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != previousPage) {
            HapticUtils.onConfirm(haptic)
            previousPage = pagerState.settledPage
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
            val reel = filteredReels[page]
            if (reel.isReadyToPlay) {
                ReelPage(
                    reel = reel,
                    isCurrentPage = page == pagerState.currentPage,
                    uiAlpha = uiAlpha,
                    onHoldStart = { isHoldingOnVideo = true },
                    onHoldEnd = { isHoldingOnVideo = false },
                    onTapback = { tapback ->
                        onTapback(reel.messageGuid, reel.originalUrl, tapback)
                    },
                    onOpenInBrowser = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(reel.originalUrl))
                        context.startActivity(intent)
                    },
                    onShare = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, reel.originalUrl)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share video link"))
                    },
                    onReplyClick = { onReplyClick(reel.messageGuid) }
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

        // Close button - top left (fades with hold gesture)
        IconButton(
            onClick = {
                HapticUtils.onTap(haptic)
                onClose()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 8.dp, top = 4.dp)
                .alpha(uiAlpha)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // Share button - top right (fades with hold gesture)
        IconButton(
            onClick = {
                HapticUtils.onTap(haptic)
                val currentReel = filteredReels.getOrNull(pagerState.currentPage)
                if (currentReel != null) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, currentReel.originalUrl)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share video link"))
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = 8.dp, top = 4.dp)
                .alpha(uiAlpha)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = "Share",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // Snackbar host for "You're all caught up!" toast
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = 32.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = Color.White,
                contentColor = Color.Black,
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

@Composable
private fun ReelPage(
    reel: ReelItem,
    isCurrentPage: Boolean,
    uiAlpha: Float,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onTapback: (ReelsTapback) -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: () -> Unit,
    onReplyClick: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var showTapbackSelector by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var showPauseIndicator by remember { mutableStateOf(false) }

    // Track if currently holding (for internal gesture handling)
    var isHolding by remember { mutableStateOf(false) }

    // Video progress tracking
    var videoProgress by remember { mutableFloatStateOf(0f) }
    var videoDuration by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubberSize by remember { mutableStateOf(IntSize.Zero) }

    // Tapback acknowledgement animation
    var tapbackAcknowledgement by remember { mutableStateOf<ReelsTapback?>(null) }
    val tapbackScale = remember { Animatable(0f) }

    // Create ExoPlayer (reel.localPath is guaranteed non-null for ready-to-play items)
    val localPath = reel.localPath!!
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(localPath))
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
                        HapticUtils.onConfirm(haptic)
                        showTapbackSelector = true
                    },
                    onLongPress = {
                        // Only trigger hold-to-hide if not scrubbing
                        if (!isScrubbing) {
                            HapticUtils.onLongPress(haptic)
                            isHolding = true
                            onHoldStart()
                        }
                    },
                    onPress = { offset ->
                        // Wait for the press to be released
                        try {
                            awaitRelease()
                        } finally {
                            // Always release the hold when finger is lifted
                            if (isHolding) {
                                isHolding = false
                                onHoldEnd()
                            }
                        }
                    },
                    onTap = {
                        // Single tap to pause/play
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                            isPaused = true
                            showPauseIndicator = true
                            HapticUtils.onTap(haptic)
                        } else {
                            exoPlayer.play()
                            isPaused = false
                            showPauseIndicator = true
                            HapticUtils.onTap(haptic)
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

        // Pause indicator overlay (fades with hold gesture)
        AnimatedVisibility(
            visible = showPauseIndicator && isPaused,
            enter = fadeIn() + scaleIn(initialScale = 0.5f),
            exit = fadeOut() + scaleOut(targetScale = 0.5f),
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(uiAlpha)
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

        // Tapback acknowledgement animation (center) - stays visible during hold
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

        // Bottom gradient overlay (fades with hold gesture)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .alpha(uiAlpha)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
        )

        // TikTok-style progress scrubber - bottom (fades with hold gesture)
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
                .alpha(uiAlpha)
        )

        // Sender info badge - bottom left (above scrubber) - fades with hold gesture
        SenderInfoBadge(
            senderName = reel.senderName,
            senderAddress = reel.senderAddress,
            avatarPath = reel.avatarPath,
            platform = reel.platform,
            timestamp = reel.sentTimestamp,
            currentTapback = reel.currentTapback,
            reactions = reel.reactions,
            replyCount = reel.replyCount,
            onOpenInBrowser = if (reel.isCached) onOpenInBrowser else null,
            onReplyClick = onReplyClick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 16.dp, bottom = 24.dp)
                .alpha(uiAlpha)
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
                    HapticUtils.onConfirm(haptic)
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
 * For failed downloads, fetches link metadata and displays a rich preview.
 */
@Composable
private fun PendingReelPage(
    reel: ReelItem,
    isCurrentPage: Boolean,
    onStartDownload: () -> Unit,
    onOpenInBrowser: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Auto-start download when this becomes the current page
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && !reel.isDownloading && reel.downloadError == null) {
            onStartDownload()
        }
    }

    // Collect download progress if available
    val downloadProgress = reel.downloadProgress?.collectAsState()

    // Fetch link metadata when there's an error (for display in FailedReelCard)
    var linkMetadata by remember { mutableStateOf<LinkMetadata?>(null) }
    LaunchedEffect(reel.downloadError, reel.originalUrl) {
        if (reel.downloadError != null && linkMetadata == null) {
            // Try to fetch metadata for better display
            try {
                val service = LinkPreviewService(context,
                    com.bothbubbles.data.local.prefs.SettingsDataStore(context))
                val result = service.fetchMetadata(reel.originalUrl)
                if (result is LinkMetadataResult.Success) {
                    linkMetadata = result.metadata
                }
            } catch (e: Exception) {
                // Ignore - fallback will be used
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Fullscreen background image for failed state (blurred, dimmed, stretched, cropped-to-fill)
        if (reel.downloadError != null && linkMetadata?.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(linkMetadata?.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = 25.dp),
                contentScale = ContentScale.Crop
            )
            // Dim overlay on top of blur
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            )
        }

        // Bottom gradient overlay (for non-error states)
        if (reel.downloadError == null) {
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
        }

        // Center content - download state
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                reel.downloadError != null -> {
                    // Error state - show link preview card with metadata
                    FailedReelCard(
                        reel = reel,
                        linkMetadata = linkMetadata,
                        onRetry = {
                            HapticUtils.onConfirm(haptic)
                            onStartDownload()
                        },
                        onOpenInBrowser = {
                            HapticUtils.onTap(haptic)
                            onOpenInBrowser()
                        }
                    )
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
                            HapticUtils.onTap(haptic)
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
        val platform = reel.platform
        if (platform != null) {
            SenderInfoBadge(
                senderName = reel.senderName,
                senderAddress = reel.senderAddress,
                avatarPath = reel.avatarPath,
                platform = platform,
                timestamp = reel.sentTimestamp,
                currentTapback = null, // No tapback for pending items
                reactions = reel.reactions,
                replyCount = reel.replyCount,
                onOpenInBrowser = onOpenInBrowser,
                onReplyClick = { /* TODO: Show thread modal */ },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(start = 16.dp, bottom = 24.dp)
            )
        }
    }
}

/**
 * Card displayed when a reel fails to download.
 * Shows link preview metadata with a dimmed background image, error message,
 * and options to retry or open in external browser.
 */
@Composable
private fun FailedReelCard(
    reel: ReelItem,
    linkMetadata: LinkMetadata?,
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .widthIn(max = 320.dp)
            .padding(horizontal = 24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column {
            // Header with background image or platform gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Background: either link preview image or platform gradient
                if (linkMetadata?.imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(linkMetadata.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // Dimmed overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                    )
                } else {
                    // Fallback platform gradient
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                when (reel.platform) {
                                    SocialMediaPlatform.INSTAGRAM -> Brush.verticalGradient(
                                        listOf(Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF))
                                    )
                                    SocialMediaPlatform.TIKTOK -> Brush.verticalGradient(
                                        listOf(Color(0xFF00F2EA), Color(0xFF000000), Color(0xFFFF0050))
                                    )
                                    null -> Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                            MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    )
                                }
                            )
                    )
                }

                // Center content: play icon and error badge
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Platform-styled play icon with shadow
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Error badge
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFFFF6B6B)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Download failed",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Content section
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Site name / platform
                Text(
                    text = linkMetadata?.siteName
                        ?: reel.platform?.displayName?.uppercase()
                        ?: "VIDEO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Title from metadata or fallback (decode HTML entities)
                val title = HtmlEntityDecoder.decode(linkMetadata?.title)
                    ?: when (reel.platform) {
                        SocialMediaPlatform.INSTAGRAM -> "Instagram Reel"
                        SocialMediaPlatform.TIKTOK -> "TikTok Video"
                        null -> "Video"
                    }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Description from metadata or fallback (decode HTML entities)
                val description = HtmlEntityDecoder.decode(linkMetadata?.description)
                    ?: "Couldn't download this video. Tap Open to view in your browser."
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry")
                    }
                    Button(
                        onClick = onOpenInBrowser,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open")
                    }
                }
            }
        }
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
    val haptic = LocalHapticFeedback.current
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
                        HapticUtils.onLongPress(haptic)
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
                        HapticUtils.onConfirm(haptic)
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
    avatarPath: String?,
    platform: SocialMediaPlatform?,
    timestamp: Long,
    currentTapback: ReelsTapback?,
    reactions: List<ReactionUiModel>,
    replyCount: Int,
    onOpenInBrowser: (() -> Unit)?,
    onReplyClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    // Use display name or address for avatar
    val displayName = senderName ?: senderAddress ?: "Unknown"

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar using actual contact photo or generated avatar
                Avatar(
                    name = displayName,
                    avatarPath = avatarPath,
                    size = 40.dp,
                    hasContactInfo = avatarPath != null || senderName != null
                )

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

                    // Platform/source and time
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = platform?.displayName ?: "Video",
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

                // Open in browser button (only for social media videos)
                if (onOpenInBrowser != null) {
                    IconButton(
                        onClick = {
                            HapticUtils.onTap(haptic)
                            onOpenInBrowser()
                        },
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

            // Reactions display (if any)
            if (reactions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ReactionsDisplay(
                    reactions = reactions,
                    isFromMe = false, // Always show as received in Reels context
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Centered reply count/button with comment icon
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = {
                        HapticUtils.onTap(haptic)
                        onReplyClick()
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.15f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                replyCount == 0 -> "No replies"
                                replyCount == 1 -> "1 reply"
                                else -> "$replyCount replies"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }
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
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        label = "tapback_scale"
    )

    Surface(
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .clickable {
                HapticUtils.onTap(haptic)
                onClick()
            },
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


