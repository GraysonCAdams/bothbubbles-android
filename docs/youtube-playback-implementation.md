# YouTube Inline Playback Implementation

## Overview

Implementing inline YouTube video playback in chat messages using the `android-youtube-player` library. This allows YouTube links shared in messages to be played directly within the chat without leaving the app.

## Library Used

```toml
# gradle/libs.versions.toml
youtubePlayer = "12.1.1"
youtube-player = { group = "com.pierfrancescosoffritti.androidyoutubeplayer", name = "core", version.ref = "youtubePlayer" }
```

```kotlin
// app/build.gradle.kts
implementation(libs.youtube.player)
```

## Files Created/Modified

### 1. YouTubeUrlParser.kt (NEW)
**Path:** `app/src/main/kotlin/com/bothbubbles/util/parsing/YouTubeUrlParser.kt`

Utility for parsing YouTube URLs and extracting video IDs. Supports:
- Standard watch URLs: `youtube.com/watch?v=VIDEO_ID`
- Short URLs: `youtu.be/VIDEO_ID`
- Embed URLs: `youtube.com/embed/VIDEO_ID`
- Shorts: `youtube.com/shorts/VIDEO_ID`
- Live streams: `youtube.com/live/VIDEO_ID`
- Timestamps: `t=120`, `t=2m30s`, `t=1h2m30s`, `start=120`

```kotlin
object YouTubeUrlParser {
    data class YouTubeVideo(
        val videoId: String,
        val originalUrl: String,
        val thumbnailUrl: String = getThumbnailUrl(videoId),
        val isShort: Boolean = false,
        val startTimeSeconds: Int? = null
    )

    fun parseUrl(url: String): YouTubeVideo?
    fun extractVideoId(url: String): String?
    fun extractTimestamp(url: String): Int?
    fun isYouTubeUrl(url: String): Boolean
}
```

### 2. MessageSegment.kt (MODIFIED)
**Path:** `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageSegment.kt`

Added new segment type for YouTube videos:

```kotlin
sealed class MessageSegment {
    // ... existing segments ...

    /** YouTube video - rendered with inline player, borderless */
    data class YouTubeVideoSegment(
        val videoId: String,
        val originalUrl: String,
        val thumbnailUrl: String,
        val startTimeSeconds: Int? = null,
        val isShort: Boolean = false
    ) : MessageSegment()
}
```

Updated `MessageSegmentParser.parse()` to detect YouTube URLs:

```kotlin
detectedUrl?.let { url ->
    val youtubeVideo = YouTubeUrlParser.parseUrl(url.url)
    if (youtubeVideo != null) {
        segments.add(MessageSegment.YouTubeVideoSegment(
            videoId = youtubeVideo.videoId,
            originalUrl = youtubeVideo.originalUrl,
            thumbnailUrl = youtubeVideo.thumbnailUrl,
            startTimeSeconds = youtubeVideo.startTimeSeconds,
            isShort = youtubeVideo.isShort
        ))
    } else {
        segments.add(MessageSegment.LinkPreviewSegment(url.url, url))
    }
}
```

### 3. YouTubeAttachment.kt (NEW)
**Path:** `app/src/main/kotlin/com/bothbubbles/ui/components/attachment/YouTubeAttachment.kt`

Main composable for inline YouTube playback with two modes:
1. **Thumbnail mode** - Shows video thumbnail with YouTube red play button
2. **Active playback mode** - Embeds YouTubePlayerView with native Compose overlay controls

```kotlin
@Composable
fun YouTubeAttachment(
    videoId: String,
    originalUrl: String,
    thumbnailUrl: String,
    startTimeSeconds: Int? = null,
    isShort: Boolean = false,
    maxWidth: Dp = 240.dp,
    modifier: Modifier = Modifier
)
```

Features:
- Tap thumbnail to play inline
- Double-tap to open in YouTube app
- Mute/unmute button overlay
- Fullscreen/open-in-app button
- Supports timestamp start points
- 9:16 aspect ratio for Shorts, 16:9 for regular videos

### 4. MessageSegmentedBubble.kt (MODIFIED)
**Path:** `app/src/main/kotlin/com/bothbubbles/ui/components/message/MessageSegmentedBubble.kt`

Added rendering for YouTubeVideoSegment:

```kotlin
is MessageSegment.YouTubeVideoSegment -> {
    YouTubeAttachment(
        videoId = segment.videoId,
        originalUrl = segment.originalUrl,
        thumbnailUrl = segment.thumbnailUrl,
        startTimeSeconds = segment.startTimeSeconds,
        isShort = segment.isShort,
        maxWidth = 240.dp,
        modifier = Modifier
            .pointerInput(message.guid, isSelectionMode) {
                detectTapGestures(
                    onLongPress = {
                        HapticUtils.onLongPress(hapticFeedback)
                        onLongPress()
                    }
                )
            }
    )
}
```

## Current Problem

### Issue: AndroidView has zero size (width=0, height=0)

The `YouTubePlayerView` wrapped in Compose's `AndroidView` is not getting any size, which prevents the internal WebView from initializing. The `onReady` callback never fires.

**Debug logs showing the problem:**
```
D YouTubeAttachment: Play clicked for video: v9gIK4j1Ip0
D YouTubeAttachment: Rendering YouTubePlayerActive for video: v9gIK4j1Ip0
D YouTubeAttachment: Creating YouTubePlayerView for video: v9gIK4j1Ip0
D YouTubeAttachment: Added lifecycle observer
D YouTubeAttachment: Listener added, waiting for auto-init
D YouTubeAttachment: AndroidView update: width=0, height=0   <-- PROBLEM
```

### Current Code (YouTubePlayerActive)

```kotlin
@Composable
private fun YouTubePlayerActive(
    videoId: String,
    startTimeSeconds: Int?,
    aspectRatio: Float,
    maxWidth: Dp,
    // ... other params
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var playerView by remember { mutableStateOf<YouTubePlayerView?>(null) }

    DisposableEffect(playerView) {
        onDispose {
            playerView?.let { view ->
                lifecycleOwner.lifecycle.removeObserver(view)
            }
        }
    }

    Box(
        modifier = modifier
            .width(maxWidth)  // Explicit width
            .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))  // Explicit height
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
        AndroidView(
            factory = { ctx ->
                YouTubePlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    // Add as lifecycle observer for WebView to work
                    lifecycleOwner.lifecycle.addObserver(this)
                    playerView = this

                    addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            // This never gets called because view has zero size
                            youTubePlayer.loadVideo(videoId, startTimeSeconds?.toFloat() ?: 0f)
                        }

                        override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                            Log.e("YouTubeAttachment", "Player ERROR: $error")
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                Log.d("YouTubeAttachment", "AndroidView update: width=${view.width}, height=${view.height}")
            }
        )

        // Overlay controls (mute, fullscreen, play/pause indicator)
        // ...
    }
}
```

### What We've Tried

1. **Adding lifecycle observer** - Required for WebView initialization, but doesn't fix size issue
2. **Using `width(maxWidth)` instead of `widthIn(max = maxWidth)`** - Still zero size
3. **Using `fillMaxSize()` on AndroidView** - Still zero size
4. **Adding `aspectRatio()` to parent Box** - Still zero size

### Suspected Root Causes

1. **Compose layout constraints not propagating** - The parent composable may not be providing size constraints to the Box/AndroidView
2. **LazyColumn item sizing** - If rendered inside a LazyColumn, the item might not have intrinsic size
3. **AndroidView + WebView interaction** - WebView inside AndroidView may need special handling

### Next Steps to Try

1. **Use fixed pixel dimensions** instead of Dp to rule out density issues:
   ```kotlin
   modifier = Modifier.size(240.dp, 135.dp)  // Fixed 16:9
   ```

2. **Check parent constraints** - Add logging to see what constraints the parent is providing

3. **Try `BoxWithConstraints`** to get explicit constraints:
   ```kotlin
   BoxWithConstraints {
       val width = maxWidth.coerceAtMost(240.dp)
       val height = width / aspectRatio
       AndroidView(
           modifier = Modifier.size(width, height),
           // ...
       )
   }
   ```

4. **Check if the issue is in MessageSegmentedBubble** - The parent composable may have layout issues

5. **Try using a different AndroidView approach** - Use `AndroidViewBinding` or ensure view is attached before initialization

## Architecture

```
Message with YouTube URL
        │
        ▼
MessageSegmentParser.parse()
        │
        ├── Detects YouTube URL via YouTubeUrlParser
        │
        ▼
YouTubeVideoSegment
        │
        ▼
MessageSegmentedBubble
        │
        ▼
YouTubeAttachment (Composable)
        │
        ├── isPlayerActive = false → YouTubeThumbnailWithControls
        │                                    │
        │                                    └── Thumbnail + Play button
        │
        └── isPlayerActive = true  → YouTubePlayerActive
                                            │
                                            ├── Box (container with size)
                                            │
                                            ├── AndroidView (YouTubePlayerView)
                                            │       │
                                            │       └── WebView (IFrame API) ← NOT LOADING (zero size)
                                            │
                                            └── Overlay controls (Compose)
```
