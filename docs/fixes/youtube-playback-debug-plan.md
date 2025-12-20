# YouTube Playback Debug Plan

## Objective
Fix the issue where `YouTubePlayerView` fails to trigger `onReady()`, preventing video playback despite correct view sizing and initialization.

## Current Status
- **Symptoms:** `onReady()` never fires. Screen remains black (or shows background).
- **Verified Working:** View dimensions (690x388), `initialize()` call, Lifecycle state (RESUMED).
- **Environment:** Debug build (`isMinifyEnabled = false`), Internet permission present.

## Phase 1: Sanity Checks & Configuration

### 1. Explicit Hardware Acceleration
WebViews often require hardware acceleration. While usually enabled by default, explicit declaration prevents edge cases.
**Action:** Add `android:hardwareAccelerated="true"` to the `<application>` tag in `AndroidManifest.xml`.

### 2. ProGuard Rules (Pre-emptive)
Even though `isMinifyEnabled = false` for debug, adding rules ensures no issues if build variants change or if R8 is partially active.
**Action:** Add the following to `proguard-rules.pro`:
```proguard
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.** { *; }
```

## Phase 2: Isolation Test (The "Raw WebView" Test)
This is the most critical step. We need to determine if the failure is specific to the **Library** or the **System/WebView** environment.

**Action:** Temporarily replace the `YouTubePlayerActive` implementation with a raw `WebView` loading a YouTube embed.

```kotlin
// Temporary replacement in YouTubeAttachment.kt
AndroidView(
    factory = { ctx ->
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadUrl("https://www.youtube.com/embed/$videoId")
        }
    },
    modifier = Modifier.fillMaxSize()
)
```

**Success Criteria:**
- **If this plays:** The issue is within `android-youtube-player` library configuration or usage.
- **If this fails (blank/error):** The issue is system-level (Network, WebView version, SSL, etc.).

## Phase 3: Library-Specific Debugging (If Phase 2 Works)

### 1. Simplify Initialization
The current implementation combines manual initialization with manual lifecycle observation inside a Composable. This can be fragile.

**Action:** Revert to the simplest possible implementation:
1. Remove `enableAutomaticInitialization = false`.
2. Remove manual `initialize()` call.
3. Remove manual `lifecycle.addObserver()` (The view handles this if `enableAutomaticInitialization` is true, though in Compose we might need to be careful).

### 2. Check Asset Loading
The library loads `file:///android_asset/youtube_player_iframe.html`. If this file is missing or inaccessible, `onReady` will never fire.
**Action:** Verify if the APK contains the assets (using Android Studio's APK Analyzer).

## Phase 4: Advanced Debugging

### 1. Chrome Inspect
**Action:**
1. Run the app and open the YouTube attachment.
2. Connect device to computer.
3. Open Chrome on computer and navigate to `chrome://inspect/#devices`.
4. Look for the WebView under the device.
5. Click "Inspect" to see the Console.
6. Check for JavaScript errors (e.g., "YouTube is not defined", "Failed to load resource").

### 2. WebView Version
**Action:** Check the installed Android System WebView version on the device. An outdated WebView can cause compatibility issues with the YouTube IFrame API.
