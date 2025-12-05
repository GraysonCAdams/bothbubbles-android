# BlueBubbles

A native Android messaging app that brings iMessage to Android via a BlueBubbles server, with SMS/MMS fallback. Built from the ground up with Kotlin and Jetpack Compose for a true native Material Design 3 experience.

**Note:** iMessage requires a Mac running the [BlueBubbles Server](https://github.com/BlueBubblesApp/BlueBubbles-Server). SMS/MMS works standalone without a server.

## About This Rewrite

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

## Project Structure

```
app/src/main/kotlin/com/bluebubbles/
├── data/                    # Data layer
│   ├── local/              # Room database & DataStore prefs
│   ├── remote/             # Retrofit API
│   └── repository/         # Repository pattern
├── di/                      # Hilt dependency injection
├── services/                # Background services
│   ├── notifications/      # Notification handling
│   ├── sms/               # SMS/MMS integration
│   ├── socket/            # Socket.IO connection
│   └── sync/              # Data synchronization
└── ui/                      # Jetpack Compose UI
    ├── chat/              # Message view
    ├── components/        # Reusable components
    ├── conversations/     # Conversation list
    ├── navigation/        # Navigation setup
    ├── settings/          # Settings screens
    └── theme/             # Material Design 3 theming
```

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **Architecture**: MVVM with ViewModel + StateFlow
- **DI**: Hilt
- **Database**: Room
- **Network**: Retrofit + OkHttp + Moshi
- **Navigation**: Compose Navigation (type-safe routes)
- **Real-time**: Socket.IO

## Links

- Website: [bluebubbles.app](https://bluebubbles.app)
- Discord: [Join us](https://discord.gg/4F7nbf3)
- Documentation: [docs.bluebubbles.app](https://docs.bluebubbles.app)
- Server: [BlueBubbles-Server](https://github.com/BlueBubblesApp/BlueBubbles-Server/releases)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.
