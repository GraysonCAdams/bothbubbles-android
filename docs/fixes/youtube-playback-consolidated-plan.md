# YouTube Playback Consolidated Troubleshooting Plan

## Objective
Fix the issue where `YouTubePlayerView` fails to trigger `onReady()`, preventing video playback despite correct view sizing and initialization.

## Current Status
- **Symptoms:** `onReady()` never fires. Screen remains black.
- **Verified Working:** View dimensions (690x388), `initialize()` call, Lifecycle state (RESUMED).
- **Blocked By:** Internal WebView handshake failure.

## Phase 1: Configuration & Sanity Checks (Immediate)
Before diving into complex debugging, ensure the environment is explicitly configured to support the player.

### 1. Explicit Hardware Acceleration
WebViews often require hardware acceleration. While usually enabled by default, explicit declaration prevents edge cases.
**Action:** Add `android:hardwareAccelerated="true"` to the `<application>` tag in `app/src/main/AndroidManifest.xml`.

### 2. ProGuard Rules
Ensure R8/ProGuard isn't stripping necessary classes, even in debug builds.
**Action:** Add the following to `app/proguard-rules.pro`:
```proguard
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.** { *; }
```

### 3. Verify Assets
The library relies on `assets/youtube_player_iframe.html`.
**Action:** Verify this file exists in the final APK using Android Studio's APK Analyzer or by checking the library AAR.

## Phase 2: The "Raw WebView" Isolation Test (Critical)
This is the most critical step to determine if the failure is specific to the **Library** or the **System/WebView** environment.

**Action:** Temporarily replace the `YouTubePlayerActive` implementation in `YouTubeAttachment.kt` with a raw `WebView` loading a YouTube embed.

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
- **If this plays:** The issue is within `android-youtube-player` library configuration or usage. -> **Go to Phase 3A**
- **If this fails (blank/error):** The issue is system-level (Network, WebView version, SSL, etc.). -> **Go to Phase 3B**

## Phase 3A: Library Debugging (If Raw WebView Works)

### 1. Surface Iframe Console Logs
We need to see if the bridge script inside `youtube_player_iframe.html` is failing.
**Action:** Attach a `WebChromeClient` to `YouTubePlayerView.webView` (via reflection or by modifying the library usage) to see `onConsoleMessage`.

### 2. Simplify Initialization
The current implementation combines manual initialization with manual lifecycle observation inside a Composable.
**Action:** Revert to the simplest possible implementation:
1. Remove `enableAutomaticInitialization = false`.
2. Remove manual `initialize()` call.
3. Let the view handle lifecycle automatically (or manage it very simply).

### 3. Stabilize View Instance
**Action:** Hoist the view creation with `remember` to ensure it's not being recreated unnecessarily.

## Phase 3B: System Debugging (If Raw WebView Fails)

### 1. Chrome Inspect
**Action:** Use `chrome://inspect/#devices` to debug the WebView content on the device. Look for JS errors or network failures.

### 2. WebView Process Watchdog
**Action:** Check if the system is freezing the WebView process.
Command: `adb shell cmd device_config put activity_manager max_phantom_processes 2147483647`

### 3. WebView Version
**Action:** Check the installed Android System WebView version. Update if necessary.

## Execution Order
1. Apply Phase 1 fixes.
2. Run the app. If it works, STOP.
3. If not, implement Phase 2 (Raw WebView).
4. Based on Phase 2 result, proceed to Phase 3A or 3B.
