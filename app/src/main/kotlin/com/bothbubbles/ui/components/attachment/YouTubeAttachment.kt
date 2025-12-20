package com.bothbubbles.ui.components.attachment

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision

/**
 * Inline YouTube video player that shows thumbnail until user taps play.
 * Uses youtube-nocookie.com iframe embed for playback.
 *
 * Features:
 * - Thumbnail preview with play button
 * - Tap to play inline using YouTube's native controls
 * - Double-tap or button to open in YouTube app
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
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Aspect ratio: 9:16 for Shorts, 16:9 for regular videos
    val aspectRatio = if (isShort) 9f / 16f else 16f / 9f

    // Player state
    var isPlayerActive by remember { mutableStateOf(false) }

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
            onLongPress = onLongPress,
            modifier = modifier
        )
    } else {
        // Active playback mode - uses YouTube's native controls
        YouTubePlayerActive(
            videoId = videoId,
            startTimeSeconds = startTimeSeconds,
            aspectRatio = aspectRatio,
            maxWidth = maxWidth,
            onOpenInApp = { openInYouTube() },
            onLongPress = onLongPress,
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
    onLongPress: (() -> Unit)? = null,
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
            .pointerInput(onLongPress) {
                detectTapGestures(
                    onTap = { onPlayClick() },
                    onDoubleTap = { onOpenInApp() },
                    onLongPress = { onLongPress?.invoke() }
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
                    Icons.AutoMirrored.Filled.OpenInNew,
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
 * Active YouTube player using iframe embed.
 * Uses YouTube's native controls with mute/unmute overlay button.
 */
@Composable
private fun YouTubePlayerActive(
    videoId: String,
    startTimeSeconds: Int?,
    aspectRatio: Float,
    maxWidth: Dp,
    onOpenInApp: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isMuted by remember { mutableStateOf(true) } // Start muted

    // Calculate fixed pixel dimensions
    val widthPx = with(density) { maxWidth.roundToPx() }
    val heightPx = (widthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()
    val heightDp = with(density) { heightPx.toDp() }

    // Build start time parameter if provided
    val startParam = startTimeSeconds?.let { "&start=$it" } ?: ""

    // Clean up WebView when composable leaves
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
        }
    }

    Box(
        modifier = modifier
            .size(maxWidth, heightDp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                object : WebView(ctx) {
                    private var startX = 0f
                    private var startY = 0f
                    private var isHorizontalScroll = false

                    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
                        // Only block parent interception for horizontal swipes (seeking)
                        // Allow taps and long presses to propagate for tapbacks/context menus
                        when (event?.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                startX = event.x
                                startY = event.y
                                isHorizontalScroll = false
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val dx = kotlin.math.abs(event.x - startX)
                                val dy = kotlin.math.abs(event.y - startY)
                                // If horizontal movement exceeds vertical, it's a seek gesture
                                if (dx > dy && dx > 10) {
                                    isHorizontalScroll = true
                                    parent?.requestDisallowInterceptTouchEvent(true)
                                }
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                if (isHorizontalScroll) {
                                    parent?.requestDisallowInterceptTouchEvent(false)
                                }
                                isHorizontalScroll = false
                            }
                        }
                        return super.onTouchEvent(event)
                    }
                }.apply {
                    webViewRef = this

                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            // Open all navigation in external browser
                            request?.url?.let { uri ->
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                ctx.startActivity(intent)
                            }
                            return true
                        }
                    }

                    // Use youtube-nocookie.com domain which avoids privacy-related
                    // restrictions and sends cleaner referrer data (fixes Error 152/153)
                    // enablejsapi=1 allows JavaScript control, mute=1 starts muted
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta charset="utf-8">
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                            <meta name="referrer" content="strict-origin-when-cross-origin">
                            <style>
                                * { margin: 0; padding: 0; }
                                html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
                                iframe { width: 100%; height: 100%; border: none; }
                            </style>
                        </head>
                        <body>
                            <iframe id="ytplayer"
                                src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&controls=1&rel=0&modestbranding=1&fs=0&mute=1&cc_load_policy=0&enablejsapi=1$startParam&origin=https://www.youtube-nocookie.com"
                                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                                referrerpolicy="strict-origin-when-cross-origin">
                            </iframe>
                            <script>
                                function mutePlayer() {
                                    document.getElementById('ytplayer').contentWindow.postMessage('{"event":"command","func":"mute","args":""}', '*');
                                }
                                function unmutePlayer() {
                                    document.getElementById('ytplayer').contentWindow.postMessage('{"event":"command","func":"unMute","args":""}', '*');
                                }
                            </script>
                        </body>
                        </html>
                    """.trimIndent()

                    loadDataWithBaseURL(
                        "https://www.youtube-nocookie.com",
                        html,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { }
        )

        // Mute/Unmute button (top-left)
        Surface(
            onClick = {
                isMuted = !isMuted
                val jsCommand = if (isMuted) "mutePlayer()" else "unmutePlayer()"
                webViewRef?.evaluateJavascript(jsCommand, null)
            },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
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
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open in YouTube",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
