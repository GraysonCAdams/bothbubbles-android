# BothBubbles

A native Android messaging app that brings iMessage to Android via a BlueBubbles server, with SMS/MMS fallback. Built from the ground up with Kotlin and Jetpack Compose for a true native Material Design 3 experience.

**Note:** iMessage requires a Mac running the [BlueBubbles Server](https://github.com/BlueBubblesApp/BlueBubbles-Server). SMS/MMS works standalone without a server.

## About

This is a complete native rewrite of BlueBubbles, replacing the original Flutter implementation with pure Kotlin and Jetpack Compose. The goals:

- **True native Android experience** with Material Design 3
- **iMessage first** when connected to a BlueBubbles server
- **SMS/MMS fallback** when server is unavailable or recipient isn't on iMessage (no RCS)
- **Default SMS app capable** - unify all messaging in one place

## Features

### iMessage (via BlueBubbles Server)
- Send & receive iMessage texts, media, and location
- Tapbacks, reactions, stickers, and read/delivered timestamps
- Typing indicators (Private API)
- Message effects and replies
- Group chat management

### SMS/MMS (Native Android)
- Full SMS/MMS support as default messaging app (no RCS)
- Automatic fallback when server is disconnected
- Intelligent routing: iMessage when possible, SMS/MMS when not
- Import existing SMS conversations

### Native Material Design 3
- True native Android UI built with Jetpack Compose
- Dynamic color theming (Material You)
- Swipe actions, pull-to-refresh, smooth animations
- Dark and light theme support

## Building from Source

### Prerequisites

- Android Studio (for bundled JDK)
- Android device or emulator (API 26+)

### Build Commands

```bash
# Set JAVA_HOME (macOS with Android Studio)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Debug build
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Running Tests

```bash
./gradlew test
```

## Repository Structure

```
├── app/                        # Android application module
│   └── src/main/kotlin/com/bothbubbles/
│       ├── data/               # Data layer (Room, DataStore, Retrofit)
│       ├── di/                 # Hilt dependency injection
│       ├── services/           # Background services (SMS, Socket, Sync)
│       └── ui/                 # Jetpack Compose UI
├── docs/                       # Project documentation and plans
├── gradle/                     # Gradle wrapper
└── references/                 # Reference codebases (gitignored)
```

## Architecture

The app follows Clean Architecture with MVVM:

```
UI Layer (Compose)
    ↓
ViewModels + Delegates
    ↓
Services Layer (Socket, Notifications, Sync)
    ↓
Data Layer (Repositories → Room/Retrofit/DataStore)
```

Key patterns:
- **Repository Pattern** for data access abstraction
- **Delegate Pattern** for ViewModel decomposition
- **Service Interfaces** for testability
- **StateFlow** for reactive UI state

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **Architecture**: MVVM with ViewModel + StateFlow
- **DI**: Hilt (Dagger)
- **Database**: Room
- **Network**: Retrofit + OkHttp + Moshi
- **Navigation**: Compose Navigation (type-safe routes)
- **Real-time**: Socket.IO
- **Background**: WorkManager

## Links

- Website: [bluebubbles.app](https://bluebubbles.app)
- Discord: [Join us](https://discord.gg/4F7nbf3)
- Documentation: [docs.bluebubbles.app](https://docs.bluebubbles.app)
- Server: [BlueBubbles-Server](https://github.com/BlueBubblesApp/BlueBubbles-Server/releases)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.

## License

See [LICENSE](LICENSE) for details.
