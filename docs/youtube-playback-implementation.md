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

    private val TIMESTAMP_PATTERNS = listOf(
        Pattern.compile("""[?&](?:t|start)=(\d+)(?:s)?(?:&|$)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""[?&]t=(\d+)m(\d+)?s?(?:&|$)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""[?&]t=(\d+)h(\d+)?m?(\d+)?s?(?:&|$)""", Pattern.CASE_INSENSITIVE)
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

---

## Current Status: BLOCKED

### Issue: `onReady()` Callback Never Fires

The YouTubePlayerView's `onReady()` callback never fires, preventing video playback. The internal WebView that loads the YouTube IFrame API appears to initialize but never completes its JavaScript handshake with the Android layer.

### What Works
- YouTube URL parsing and video ID extraction
- Timestamp extraction from URLs (all formats)
- Message segmentation correctly detects YouTube links
- Thumbnail display with play button overlay
- View dimensions are now correct (690x388 pixels)
- Lifecycle state is RESUMED
- `initialize()` is being called

### What Does NOT Work
- The `onReady()` callback never fires
- Video playback never starts
- Screen remains black after tapping play

---

## Comprehensive List of Attempted Fixes

### Phase 1: Addressing Zero-Size Issue

#### Attempt 1: Lifecycle Observer
**Approach:** Added YouTubePlayerView as lifecycle observer since it requires lifecycle events for its internal WebView.
```kotlin
lifecycleOwner.lifecycle.addObserver(this)
```
**Result:** Required but insufficient. View still had zero size.

#### Attempt 2: Explicit Width Modifier
**Approach:** Changed from `widthIn(max = maxWidth)` to `width(maxWidth)` on parent Box.
```kotlin
modifier = modifier.width(maxWidth)
```
**Result:** Still zero size.

#### Attempt 3: fillMaxSize() on AndroidView
**Approach:** Added `fillMaxSize()` modifier to AndroidView.
```kotlin
AndroidView(
    modifier = Modifier.fillMaxSize(),
    // ...
)
```
**Result:** Still zero size.

#### Attempt 4: aspectRatio() on Parent Box
**Approach:** Added explicit aspect ratio to parent Box.
```kotlin
Box(
    modifier = modifier
        .width(maxWidth)
        .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
)
```
**Result:** Still zero size.

#### Attempt 5: Fixed Pixel Dimensions
**Approach:** Used fixed Dp values with `Modifier.size()`.
```kotlin
modifier = Modifier.size(240.dp, 135.dp)
```
**Result:** Still zero size at view level.

#### Attempt 6: Explicit LayoutParams with Pixel Values
**Approach:** Converted Dp to pixels and set explicit layoutParams.
```kotlin
val widthPx = with(density) { maxWidth.roundToPx() }
val heightPx = (widthPx / aspectRatio).toInt()
layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
```
**Result:** LayoutParams were set but view.width/height still reported 0.

#### Attempt 7: post{} Delayed Initialization
**Approach:** Used `post {}` to defer listener setup until after layout pass.
```kotlin
post {
    Log.d("YouTubeAttachment", "Post-layout: width=$width, height=$height")
    addYouTubePlayerListener(...)
}
```
**Result:** Post-layout showed correct dimensions (690x388), but `onReady` still never fired.

#### Attempt 8: OnAttachStateChangeListener
**Approach:** Wait for view to be attached to window before initializing.
```kotlin
addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
    override fun onViewAttachedToWindow(v: View) {
        post { /* initialize */ }
    }
    override fun onViewDetachedFromWindow(v: View) {}
})
```
**Result:** View attached, dimensions correct, `onReady` never fired.

#### Attempt 9: doOnLayout Callback
**Approach:** Used Kotlin extension to wait for layout completion.
```kotlin
doOnLayout {
    Log.d("YouTubeAttachment", "doOnLayout: width=$width, height=$height")
    // Initialize player
}
```
**Result:** Layout completed with correct dimensions, `onReady` never fired.

#### Attempt 10: FrameLayout Wrapper
**Approach:** Wrapped YouTubePlayerView in FrameLayout with explicit dimensions.
```kotlin
FrameLayout(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
    addView(YouTubePlayerView(ctx).apply { ... })
}
```
**Result:** Same behavior - dimensions correct, `onReady` never fired.

#### Attempt 11: BoxWithConstraints
**Approach:** Used BoxWithConstraints to get actual measured Compose constraints.
```kotlin
BoxWithConstraints {
    val actualWidthPx = with(density) { maxWidth.roundToPx() }
    val actualHeightPx = with(density) { maxHeight.roundToPx() }

    if (actualWidthPx > 0 && actualHeightPx > 0) {
        AndroidView(...)
    }
}
```
**Result:** Constraints were valid (690x388), `onReady` never fired.

#### Attempt 12: Forced measure() and layout()
**Approach:** Manually called measure() and layout() on the view.
```kotlin
measure(
    View.MeasureSpec.makeMeasureSpec(actualWidthPx, View.MeasureSpec.EXACTLY),
    View.MeasureSpec.makeMeasureSpec(actualHeightPx, View.MeasureSpec.EXACTLY)
)
layout(0, 0, actualWidthPx, actualHeightPx)
```
**Result:** View now reports correct dimensions (width=690, height=388), but `onReady` still never fired.

#### Attempt 13: minimumWidth/minimumHeight
**Approach:** Set minimum dimensions on view.
```kotlin
minimumWidth = actualWidthPx
minimumHeight = actualHeightPx
```
**Result:** No change.

---

### Phase 2: Addressing Initialization Issue

#### Attempt 14: enableAutomaticInitialization = true (default)
**Approach:** Used default automatic initialization with addYouTubePlayerListener.
```kotlin
// enableAutomaticInitialization defaults to true
addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
    override fun onReady(youTubePlayer: YouTubePlayer) {
        // Never called
    }
})
```
**Result:** `onReady` never fired.

#### Attempt 15: enableAutomaticInitialization = false with manual initialize()
**Approach:** Disabled auto-init and called initialize() manually.
```kotlin
enableAutomaticInitialization = false
lifecycleOwner.lifecycle.addObserver(this)

initialize(object : AbstractYouTubePlayerListener() {
    override fun onReady(youTubePlayer: YouTubePlayer) {
        // Never called
    }
})
```
**Result:** `initialize()` was called (confirmed by logs), but `onReady` never fired.

#### Attempt 16: IFramePlayerOptions Configuration
**Approach:** Added IFramePlayerOptions to customize player initialization.
```kotlin
val options = IFramePlayerOptions.Builder()
    .controls(0)  // Hide YouTube controls (we provide our own)
    .rel(0)       // Don't show related videos
    .build()

initialize(listener, options)
```
**Result:** No change - `onReady` never fired.

#### Attempt 17: WebView Debugging
**Approach:** Enabled WebView debugging to inspect internal state.
```kotlin
WebView.setWebContentsDebuggingEnabled(true)
```
**Result:** WebView debugging enabled, but no actionable errors found. Sandboxed WebView processes observed being frozen by Android power management.

---

## Final Code State

```kotlin
@Composable
private fun YouTubePlayerActive(
    videoId: String,
    startTimeSeconds: Int?,
    aspectRatio: Float,
    maxWidth: Dp,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var playerView by remember { mutableStateOf<YouTubePlayerView?>(null) }
    val density = LocalDensity.current

    val widthPx = with(density) { maxWidth.roundToPx() }
    val heightPx = (widthPx / aspectRatio.coerceIn(0.5f, 2f)).toInt()
    val heightDp = with(density) { heightPx.toDp() }

    DisposableEffect(playerView) {
        onDispose {
            playerView?.let { view ->
                lifecycleOwner.lifecycle.removeObserver(view)
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .size(maxWidth, heightDp)
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
        val actualWidthPx = with(density) { this@BoxWithConstraints.maxWidth.roundToPx() }
        val actualHeightPx = with(density) { this@BoxWithConstraints.maxHeight.roundToPx() }

        if (actualWidthPx > 0 && actualHeightPx > 0) {
            AndroidView(
                factory = { ctx ->
                    WebView.setWebContentsDebuggingEnabled(true)

                    YouTubePlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(actualWidthPx, actualHeightPx)
                        minimumWidth = actualWidthPx
                        minimumHeight = actualHeightPx

                        measure(
                            View.MeasureSpec.makeMeasureSpec(actualWidthPx, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(actualHeightPx, View.MeasureSpec.EXACTLY)
                        )
                        layout(0, 0, actualWidthPx, actualHeightPx)

                        Log.d("YouTubeAttachment", "After force measure: width=$width, height=$height")

                        enableAutomaticInitialization = false
                        lifecycleOwner.lifecycle.addObserver(this)
                        playerView = this

                        Log.d("YouTubeAttachment", "Adding player listener, lifecycle state: ${lifecycleOwner.lifecycle.currentState}")

                        val options = IFramePlayerOptions.Builder()
                            .controls(0)
                            .rel(0)
                            .build()

                        Log.d("YouTubeAttachment", "Calling initialize() manually")
                        initialize(object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                Log.d("YouTubeAttachment", "Player READY for video: $videoId")
                                youTubePlayer.loadVideo(videoId, startTimeSeconds?.toFloat() ?: 0f)
                            }

                            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                                Log.e("YouTubeAttachment", "Player ERROR: $error")
                            }
                        }, options)
                        Log.d("YouTubeAttachment", "initialize() called")
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    Log.d("YouTubeAttachment", "AndroidView update: width=${view.width}, height=${view.height}")
                }
            )
        }
    }
}
```

## Final Debug Output

```
12-19 18:05:29.583 D YouTubeAttachment: After force measure: width=690, height=388
12-19 18:05:29.583 D YouTubeAttachment: Adding player listener, lifecycle state: RESUMED
12-19 18:05:29.583 D YouTubeAttachment: Calling initialize() manually
12-19 18:05:29.584 D YouTubeAttachment: initialize() called
12-19 18:05:29.588 D YouTubeAttachment: AndroidView update: width=690, height=388
```

**Note:** "Player READY" never appears in logs. The `onReady()` callback never fires.

---

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
        │                                    └── Thumbnail + Play button ✓ WORKS
        │
        └── isPlayerActive = true  → YouTubePlayerActive
                                            │
                                            ├── BoxWithConstraints (690x388) ✓ WORKS
                                            │
                                            ├── AndroidView (YouTubePlayerView)
                                            │       │
                                            │       ├── View dimensions: 690x388 ✓ WORKS
                                            │       │
                                            │       ├── initialize() called ✓ WORKS
                                            │       │
                                            │       └── WebView (IFrame API) ✗ NEVER CALLS onReady
                                            │
                                            └── Overlay controls (Compose)
```

## Observations

1. The view sizing issue has been resolved - the YouTubePlayerView correctly has dimensions 690x388 pixels.
2. The lifecycle is in RESUMED state when initialize() is called.
3. The `initialize()` method is being called (confirmed by logs).
4. The internal WebView that loads YouTube's IFrame API JavaScript never completes its initialization handshake.
5. WebView sandboxed processes have been observed being frozen by Android's power management.
6. No JavaScript errors are visible in logs.
7. The `onError()` callback is also never triggered - the player is in a limbo state.
