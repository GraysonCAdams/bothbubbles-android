# BothBubbles

**iMessage on Android. No compromises.**

A native Android messaging app that brings full iMessage functionality to Android via [BlueBubbles Server](https://github.com/BlueBubblesApp/BlueBubbles-Server), with SMS/MMS as a fallback. Built from scratch with Kotlin and Jetpack Compose for a true Material Design 3 experience.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-teal.svg)](https://developer.android.com/jetpack/compose)

---

## Why BothBubbles?

BothBubbles is a complete native rewrite of BlueBubbles for Android. Unlike the original Flutter app, this is pure Kotlin with Jetpack Compose—delivering the performance, polish, and platform integration that Android users expect.

**iMessage first.** When connected to your BlueBubbles server (running on a Mac), you get the full iMessage experience: blue bubbles, tapbacks, read receipts, typing indicators, message effects, and more.

**SMS/MMS fallback.** Set BothBubbles as your default SMS app to unify all messaging. When your server is offline or a recipient doesn't have iMessage, messages automatically route through your carrier.

**On RCS:** We don't support RCS because [Google doesn't allow third-party apps to access it](#why-no-rcs). Should Google ever open the APIs, RCS integration would be straightforward—our architecture already handles multiple message transports. RCS would slot in as another layer: iMessage → RCS → SMS/MMS, giving you the richest available protocol for each conversation.

---

## Features

### Messaging

| Feature | iMessage | SMS/MMS |
|---------|:--------:|:-------:|
| Send & receive messages | Yes | Yes |
| Photos, videos, audio | Yes | Yes |
| Group chats | Yes | Yes |
| Tapback reactions | Yes | — |
| Read receipts | Yes | — |
| Typing indicators | Yes | — |
| Message effects (slam, loud, invisible ink) | Yes | — |
| Screen effects (balloons, confetti, lasers) | Yes | — |
| Edit & unsend messages | Yes | — |
| Reply to specific messages | Yes | — |
| FaceTime link notifications | Yes | — |

### Smart Features

- **Smart Reply** — On-device ML suggestions for quick responses (Google ML Kit)
- **Message Categorization** — Auto-sort transactions, deliveries, promotions
- **iMessage Redirect** — Auto-reply to SMS senders who have iMessage, prompting them to text you there instead
- **Quick Reply Templates** — Customizable message shortcuts
- **ETA Sharing** — Automatically send updates to contacts when navigating with Google Maps or Waze
- **Spam Detection** — Identify and filter unwanted messages

### Organization

- **Pin conversations** — Keep important chats at the top
- **Archive & snooze** — Declutter without deleting
- **Mute notifications** — Per-conversation control
- **Search** — Find messages across all conversations
- **Export** — PDF, HTML, text, or CSV formats

### Android Integration

- **Android Auto** — Voice-controlled messaging while driving
- **Notification bubbles** — Chat heads for quick replies
- **Share sheet** — Send to BothBubbles from any app
- **Default SMS app** — Replace your stock messaging app
- **Material You** — Dynamic color theming

### Media & Attachments

- **In-app camera** — Capture and send without leaving the app
- **Voice memos** — Record and send audio messages
- **Drawing tools** — Sketch and annotate images
- **GIF search** — Built-in GIF picker
- **Link previews** — Rich previews for shared URLs
- **Media gallery** — Browse all shared media per conversation

### Privacy

- **Open source** — Full codebase is auditable; no hidden data collection
- **Your server, your data** — Messages sync through your own Mac, not our servers
- **iMessage E2EE** — End-to-end encrypted via Apple's infrastructure
- **On-device ML** — Smart reply runs locally via Google ML Kit, not in the cloud
- **No ads, no tracking** — We don't monetize your data

**Being honest about limitations:**
- iMessage encryption is Apple's—we're a client, not the protocol
- SMS/MMS messages are inherently unencrypted (carrier limitation)
- Your BlueBubbles server must be reachable over the internet (we recommend HTTPS)
- Google ML Kit runs on-device but is still Google's library

---

## Comparison with Other Apps

An honest look at how BothBubbles compares to other messaging apps:

| Feature | BothBubbles | BlueBubbles | Google Messages | Fossify SMS |
|---------|:-----------:|:-----------:|:---------------:|:-----------:|
| **iMessage support** | ✅ | ✅ | ❌ | ❌ |
| **SMS/MMS** | ✅ | ✅ | ✅ | ✅ |
| **RCS** | ❌* | ❌* | ✅ | ❌ |
| **Platforms** | Android | Android, iOS, Windows, Linux, Web | Android | Android |
| **Open source** | ✅ | ✅ | ❌ | ✅ |
| **Framework** | Native Kotlin | Flutter | Native | Native Kotlin |
| **Material Design 3** | ✅ | 🔶 | ✅ | ✅ |
| **Set as default SMS app** | ✅ | ✅ | ✅ | ✅ |
| **Tapback reactions** | ✅ | ✅ | 🔶 | ❌ |
| **Read receipts** | ✅ | ✅ | 🔶 | ❌ |
| **Typing indicators** | ✅ | ✅ | 🔶 | ❌ |
| **Message effects** | ✅ | ✅ | ❌ | ❌ |
| **End-to-end encryption** | ✅** | ✅** | 🔶 | ❌ |
| **Scheduled messages** | ✅ | ✅ | ✅ | ✅ |
| **Smart reply (ML)** | ✅ | ❌ | ✅ | ❌ |
| **Android Auto** | ✅ | ✅ | ✅ | ❌ |
| **Backup/export** | ✅ | ✅ | 🔶 | ✅ |
| **No ads** | ✅ | ✅ | ✅ | ✅ |
| **Free** | ✅ | ✅ | ✅ | ✅ |

✅ = Full support · 🔶 = Limited (RCS only, partial, etc.) · ❌ = Not available

\* See [Why No RCS?](#why-no-rcs) below
\** Via iMessage's encryption when using BlueBubbles server

### BothBubbles vs BlueBubbles

Both apps connect to the same BlueBubbles server for iMessage. The difference is in implementation:

| | BothBubbles | BlueBubbles |
|---|-------------|-------------|
| **Framework** | Native Kotlin + Jetpack Compose | Flutter (Dart) |
| **Platforms** | Android only | Android, iOS, Windows, Linux, Web |
| **Performance** | Native Android rendering | Cross-platform Skia rendering |
| **UI toolkit** | Android's native Material 3 components | Flutter's Material 3 widgets (cross-platform reimplementation) |
| **Material You** | System-level dynamic color | Via `dynamic_color` package |
| **Smart features** | ML-powered smart reply, categorization | Basic |
| **Best for** | Android users wanting native experience | Users needing cross-platform access |

> **Technical note:** Both apps use Material 3, but BothBubbles uses Android's actual Jetpack Compose components (native widgets, system animations, platform conventions), while BlueBubbles uses Flutter's cross-platform Material implementation rendered via Skia. Same design language, different rendering engines.

> **Use them together!** BothBubbles doesn't replace BlueBubbles—it complements it. Use BothBubbles as your native Android messaging app, and keep using BlueBubbles on your desktop, laptop, tablet, or browser. They all connect to the same server and stay in sync.

### Where Others May Be Better

- **RCS messaging** — If you primarily message Android users, Google Messages' RCS offers similar features to iMessage without requiring a Mac
- **Simplicity** — If you just need basic texting, stock apps require no setup

---

## Why iMessage Still Matters in 2025

Even with iOS 18's RCS support, iMessage remains superior for most users in iPhone-dominant markets.

### The Hard Numbers

| Metric | Value | Source |
|--------|:-----:|--------|
| iPhone market share (Japan) | **~70%** | [World Population Review](https://worldpopulationreview.com/country-rankings/iphone-market-share-by-country) |
| iPhone market share (USA) | **55-60%** | [DemandSage](https://www.demandsage.com/iphone-user-statistics/) |
| iPhone market share (UK) | **~57%** | [World Population Review](https://worldpopulationreview.com/country-rankings/iphone-market-share-by-country) |
| iPhones on iOS 18+ (required for RCS) | **68%** | [Apple via TechCrunch](https://techcrunch.com/2025/01/24/ios-18-hits-68-adoption-across-iphones-per-new-apple-figures/) |

**What 68% actually means for RCS:**
- **32% of iPhone users** can't receive your RCS messages at all—they fall back to SMS
- In a group chat with 5 iPhone users, there's a **~85% chance** at least one is on iOS 17 or older
- **One non-RCS participant breaks the entire group**—everyone falls back to MMS (no reactions, no typing indicators, compressed media)

**With iMessage via BlueBubbles, you reach 100% of iPhone users**—not just the 68% on iOS 18+. Group chats stay rich for everyone.

### iMessage vs Apple's RCS

Apple's RCS implementation is [deliberately limited](https://www.pocket-lint.com/ios-18-rcs-rollout-issues/):

| Feature | iMessage | Apple's RCS |
|---------|:--------:|:-----------:|
| End-to-end encryption | ✅ | ❌ |
| Edit sent messages | ✅ | ❌ |
| Reply in group threads | ✅ | ❌ |
| Message effects | ✅ | ❌ |
| Blue bubbles | ✅ | ❌ |
| Works on any iOS version | ✅ | ❌ (iOS 18+) |
| Works on all in-flight WiFi | ✅ | [🔶](https://www.t-mobile.com/support/coverage/t-mobile-in-flight-connections-on-us) |

RCS stays green. The social distinction persists.

---

## Why No RCS?

**TL;DR:** Google [hid the RCS APIs](https://9to5google.com/2019/02/22/android-q-rcs-api-delay/) in 2019 and [restricted them to OEMs](https://9to5google.com/2019/07/30/android-rcs-apis-oems-not-third-party-apps/). Third-party apps cannot implement RCS—even as the default SMS app.

We support what we can: iMessage (via BlueBubbles), SMS, and MMS. For Android-to-Android RCS, use Google Messages alongside BothBubbles.

---

## Requirements

### For iMessage Features
- A Mac (always-on, running macOS 10.14+)
- [BlueBubbles Server](https://github.com/BlueBubblesApp/BlueBubbles-Server) installed and configured
- An Apple ID signed into iMessage on the Mac

### For SMS/MMS Only
- Just an Android phone (8.0+)
- No server required

---

## Installation

### From Releases
Download the latest APK from the [Releases](https://github.com/BlueBubblesApp/bluebubbles-app/releases) page.

### Building from Source

```bash
# Clone the repository
git clone https://github.com/BlueBubblesApp/bluebubbles-app.git
cd bluebubbles-app

# Set JAVA_HOME (macOS with Android Studio)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

BothBubbles follows Clean Architecture with MVVM, built for maintainability and testability:

```
┌─────────────────────────────────────────────────────────────┐
│                     UI Layer (Compose)                       │
│         Screens, ViewModels, Delegates, Components           │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                    Services Layer                            │
│    Socket.IO, Notifications, Sync, SMS/MMS, Messaging        │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                     Data Layer                               │
│       Repositories, Room Database, Retrofit, DataStore       │
└─────────────────────────────────────────────────────────────┘
```

### Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material Design 3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt |
| Database | Room |
| Network | Socket.IO + FCM + REST (see below) |
| Background | WorkManager |
| Navigation | Compose Navigation (type-safe) |

**Why 3 network layers?** BlueBubbles server push can be unreliable, so we use redundant channels:
1. **Socket.IO** — Real-time messages when connected
2. **FCM** — Push notifications when backgrounded or socket drops
3. **REST polling** — Fallback sync every 2s if socket is quiet, plus periodic background sync

---

## Project Structure

```
app/src/main/kotlin/com/bothbubbles/
├── data/                    # Data layer
│   ├── local/              # Room database, DAOs, DataStore
│   ├── remote/             # Retrofit API, DTOs
│   └── repository/         # Repository implementations
├── di/                      # Hilt modules
├── services/                # Background services
│   ├── messaging/          # Message sending & handling
│   ├── socket/             # Socket.IO connection
│   ├── sms/                # SMS/MMS integration
│   ├── sync/               # Data synchronization
│   └── notifications/      # Notification handling
├── ui/                      # Presentation layer
│   ├── chat/               # Chat screen + delegates
│   ├── conversations/      # Conversation list
│   ├── settings/           # Settings screens
│   ├── components/         # Shared UI components
│   └── theme/              # Material 3 theming
└── util/                    # Utilities
```

---

## Links

- **Website:** [bluebubbles.app](https://bluebubbles.app)
- **Discord:** [Join the community](https://discord.gg/4F7nbf3)
- **Documentation:** [docs.bluebubbles.app](https://docs.bluebubbles.app)
- **BlueBubbles Server:** [GitHub](https://github.com/BlueBubblesApp/BlueBubbles-Server)

---

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

Key areas where help is appreciated:
- Bug fixes and performance improvements
- New feature implementations
- Documentation improvements
- Testing on different devices

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

---

<p align="center">
  <i>Made with care by the BlueBubbles community</i>
</p>
