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

- **Smart Reply** — On-device ML suggestions for quick responses
- **Message Categorization** — Auto-sort transactions, deliveries, promotions
- **Auto-Responder** — Automatic replies when you're unavailable
- **Quick Reply Templates** — Customizable message shortcuts
- **ETA Sharing** — Share navigation progress with contacts automatically
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

---

## Comparison with Other Apps

An honest look at how BothBubbles compares to popular Android messaging apps:

| Feature | BothBubbles | Google Messages | Samsung Messages | Pulse SMS | Textra |
|---------|:-----------:|:---------------:|:----------------:|:---------:|:------:|
| **iMessage support** | Yes | No | No | No | No |
| **SMS/MMS** | Yes | Yes | Yes | Yes | Yes |
| **RCS** | No* | Yes | Yes | No | No |
| **Open source** | Yes | No | No | No | No |
| **Native Android (Kotlin)** | Yes | Yes | Yes | Partial | Partial |
| **Material Design 3** | Yes | Yes | Partial | No | No |
| **Set as default SMS app** | Yes | Yes | Yes | Yes | Yes |
| **Tapback reactions** | Yes | Emoji only | Emoji only | No | Emoji only |
| **Read receipts** | Yes | RCS only | RCS only | No | No |
| **Typing indicators** | Yes | RCS only | RCS only | No | No |
| **Message effects** | Yes | No | No | No | No |
| **End-to-end encryption** | Yes** | RCS only | RCS only | No | No |
| **Cross-device sync** | Yes | Limited | Samsung only | Yes | No |
| **Scheduled messages** | Yes | Yes | Yes | Yes | Yes |
| **Smart reply** | Yes | Yes | Yes | No | No |
| **Auto-responder** | Yes | No | No | Yes | No |
| **Android Auto** | Yes | Yes | Yes | Limited | Limited |
| **Backup/export** | Yes | Limited | Limited | Yes | Limited |
| **No ads** | Yes | Yes | Yes | Paid | Paid |
| **No subscription** | Yes | Yes | Yes | Optional | One-time |

**Legend:** Yes = Full support | Partial = Limited functionality | No = Not available

\* See [Why No RCS?](#why-no-rcs) below
\** Via iMessage's encryption when using BlueBubbles server

### Where BothBubbles Excels

- **Only option for iMessage on Android** with full feature parity
- **True fallback system** — iMessage when possible, SMS when not
- **Privacy-focused** — Open source, no data collection, your server
- **Power user features** — ETA sharing, categorization, auto-responder

### Where Others May Be Better

- **RCS messaging** — If you primarily message other Android users, Google Messages' RCS support offers a similar experience to iMessage
- **Simplicity** — If you just need basic texting, stock apps have less setup
- **No Mac required** — iMessage features require running BlueBubbles Server on a Mac

---

## Why No RCS?

**TL;DR:** Google doesn't allow third-party apps to use RCS. This isn't a technical limitation—it's a policy decision by Google.

### The Full Story

In 2019, Google was developing public RCS APIs for Android that would have allowed any messaging app to support RCS. This would have been great for users and developers alike. However, [Google changed course and hid these APIs](https://9to5google.com/2019/02/22/android-q-rcs-api-delay/) before Android Q's release.

The commit message read: *"This feature is punted from Android Q."*

Since then, [RCS APIs have remained restricted](https://9to5google.com/2019/07/30/android-rcs-apis-oems-not-third-party-apps/) to Google's own apps and select OEM partners (like Samsung for their wearable features). The code explicitly checks package names and signatures before granting access.

**What this means:**
- Only Google Messages can use RCS on most Android devices
- Third-party developers cannot implement RCS, regardless of technical capability
- Even being the default SMS app doesn't grant RCS access
- This is unlikely to change given Google's continued investment in Google Messages

**Our approach:**
- We support what we can: SMS, MMS, and iMessage (via BlueBubbles)
- iMessage actually offers a richer experience than RCS (effects, better encryption)
- For Android-to-Android messaging, we recommend using Google Messages for RCS

For more details, see: [Android RCS APIs for OEMs, not third-party apps](https://9to5google.com/2019/07/30/android-rcs-apis-oems-not-third-party-apps/)

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
| Network | Retrofit + OkHttp + Moshi |
| Real-time | Socket.IO |
| Background | WorkManager |
| Navigation | Compose Navigation (type-safe) |

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
