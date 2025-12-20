# YouTube Playback Fresh Insights

## Reactions to Existing Research
- The sizing saga is clearly solved; keeping the logs that prove successful measurement will help verify any future regressions while we focus entirely on the WebView ↔ library handshake.
- The debug plan does a good job of isolating the HTML iframe path versus the library. The remaining gap is visibility into what the embedded iframe is doing once it loads.

## Additional Ideas to Explore
1. **Surface the iframe console.** Attach a `WebChromeClient` to `YouTubePlayerView.webView` via reflection (or fork the library temporarily) so `onConsoleMessage` logs surface any JS errors or CSP violations. That will immediately confirm whether the bridge script inside `youtube_player_iframe.html` fails before it can invoke `YT.ready()`.
2. **Standalone baseline activity.** Spin up a minimal XML/Activity sample that hosts `YouTubePlayerView` outside Compose. If it works there, we can diff configuration (window flags, lifecycle owner, hardware acceleration) and port the minimal set of working settings back into the Compose wrapper.
3. **Stabilize the view instance.** The current Composable recreates `YouTubePlayerView` whenever `AndroidView` re-composes. Hoist the view creation with `remember` and drive playback via an exposed controller so initialization runs exactly once per message.
4. **Verify asset packaging.** Use `./gradlew :app:assembleDebug` + APK Analyzer to confirm `youtube_player_iframe.html` sits under `assets/`. If it is missing, include `android { aaptOptions { noCompress += "html" } }` or manually copy the asset as a stop-gap.
5. **Raw WebView parity test.** Extend the plan’s Phase 2 by injecting the same autoplay, `playsinline`, and mute flags used by the library (`loadDataWithBaseURL` + iframe markup). If that WebView still plays while the library does not, we know the failure sits in the bridge JS rather than network/policy.
6. **WebView process watchdog.** Since the system logs show the WebView sandbox being frozen, temporarily disable power optimizations via `adb shell cmd device_config put activity_manager max_phantom_processes 2147483647` and retest. If `onReady` suddenly fires, we can implement a more surgical workaround (foreground service or user education).
