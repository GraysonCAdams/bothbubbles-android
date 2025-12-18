# BothBubbles

**iMessage on Android, natively.**

Native Android client for [BlueBubbles Server](https://github.com/BlueBubblesApp/BlueBubbles-Server). Pure Kotlin + Jetpack Compose.

[![License](https://img.shields.io/badge/License-PolyForm%20Noncommercial-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-teal.svg)](https://developer.android.com/jetpack/compose)

---

## Features

- **iMessage** — Tapbacks, read receipts, typing indicators, effects (slam, loud, invisible ink, screen effects), edit/unsend, replies, group chats
- **SMS/MMS** — Works as default SMS app, automatic fallback when iMessage unavailable
- **Smart reply** — On-device ML via Google ML Kit
- **ETA sharing** — Auto-send arrival time from Google Maps/Waze
- **Android Auto** — Voice-controlled messaging
- **Export** — PDF, HTML, text, CSV

Source available ([PolyForm Noncommercial](LICENSE)), no ads, no tracking, no telemetry. Firebase is the only external service, configured through your own project.

---

## Comparison

| Feature | BothBubbles | BlueBubbles | Google Messages | Fossify SMS |
|---------|:-----------:|:-----------:|:---------------:|:-----------:|
| **iMessage** | ✅ | ✅ | ❌ | ❌ |
| **SMS/MMS** | ✅ | ✅ | ✅ | ✅ |
| **RCS** | [❌](#why-no-rcs) | [❌](#why-no-rcs) | ✅ | ❌ |
| **Framework** | Native Kotlin | Flutter | Native | Native Kotlin |
| **Source available** | ✅ | ✅ | ❌ | ✅ |
| **Tapbacks / Effects** | ✅ | ✅ | 🔶 | ❌ |
| **Smart reply (on-device)** | ✅ | ❌ | ✅ | ❌ |
| **ETA sharing** | ✅ | ❌ | ❌ | ❌ |
| **Android Auto** | ✅ | ✅ | ✅ | ❌ |

✅ Full support · 🔶 RCS only · ❌ Not available

### Why BothBubbles over BlueBubbles?

Same iMessage features, but built for Android. Use them together: BothBubbles on your phone, BlueBubbles on desktop/web/tablet—same server, always in sync.

| Area | BothBubbles | BlueBubbles |
|------|-------------|-------------|
| **Rendering** | Native Android UI toolkit | Flutter/Skia (redraws entire canvas) |
| **Message list** | LazyColumn with keyed items | ListView with custom scroll physics |
| **Image loading** | Coil (lifecycle-aware, disk cache) | Custom Flutter implementation |
| **Memory** | No Dart VM overhead | ~30-50MB baseline from Dart runtime |
| **Cold start** | Direct native launch | Dart VM initialization first |
| **Animations** | System animator, 60/120Hz sync | Skia-rendered, manual vsync |
| **Theming** | Material You via system APIs | FlexColorScheme (approximation) |
| **Gestures** | Predictive back, native insets | Custom gesture handling |

**Unique to BothBubbles:** Smart reply (ML Kit), ETA sharing (Google Maps/Waze integration).

---

## Why iMessage Over RCS?

**RCS requires iOS 18+**, which only [68% of iPhones have](https://techcrunch.com/2025/01/24/ios-18-hits-68-adoption-across-iphones-per-new-apple-figures/). In a group chat with 5 iPhone users, there's an ~85% chance someone's on iOS 17 or older—and one non-RCS participant breaks the entire group back to MMS.

**iMessage reaches 100% of iPhone users.** Plus: end-to-end encryption, message editing, threaded replies, and effects that RCS doesn't support.

In iPhone-heavy markets (USA 55-60%, Japan ~70%, UK ~57%), iMessage is still the richer protocol.

---

## Why No RCS?

Google [restricted RCS APIs to OEMs](https://9to5google.com/2019/07/30/android-rcs-apis-oems-not-third-party-apps/) in 2019. Third-party apps can't implement it. If they ever open the APIs, we'll add it.

---

## Requirements

**For iMessage:** Mac (always-on) + [BlueBubbles Server](https://github.com/BlueBubblesApp/BlueBubbles-Server) + Apple ID signed into iMessage + [add BothBubbles to your FCM](docs/FCM_SETUP.md)

**For SMS only:** Just an Android phone (8.0+)

---

## Installation

Download from [Releases](https://github.com/BlueBubblesApp/bluebubbles-app/releases), or build from source:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

Clean Architecture with MVVM. Kotlin 2.0, Jetpack Compose, Hilt, Room, WorkManager.

**Three network layers for reliability:** Socket.IO for real-time, FCM for background push, REST polling as fallback. This catches messages that other BlueBubbles clients sometimes miss.

```
UI (Compose) → Services (Socket, Sync, SMS) → Data (Room, Retrofit, DataStore)
```

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Bug fixes, features, docs, and device testing all welcome.

---

## Acknowledgments

Built on top of the [BlueBubbles](https://github.com/BlueBubblesApp) ecosystem. BothBubbles wouldn't exist without their server and the community around it.

---

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — free for personal and noncommercial use. Commercial use requires permission.
