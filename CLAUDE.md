# Claude Code Instructions for BlueBubbles

This file provides guidance to Claude Code for working with this repository.

## Project Overview

BlueBubbles is a native Android messaging app (Kotlin + Jetpack Compose) that connects to a BlueBubbles server for iMessage functionality, with optional SMS/MMS support as a fallback.

## Build Commands

This is a native Kotlin Android project using Gradle. **Do not use Flutter commands.**

### Prerequisites

Java must be configured. On macOS without system Java, use Android Studio's bundled JDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### Building

```bash
# Debug build
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug

# Release build
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease

# Clean build
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew clean assembleDebug
```

### Installing on Device

```bash
# Install debug APK (device must be connected via adb)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or use gradle
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installDebug
```

### Running Tests

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test
```

## Project Structure

```
app/src/main/kotlin/com/bluebubbles/
├── data/                    # Data layer
│   ├── local/              # Local database (Room)
│   │   ├── db/dao/        # Data Access Objects
│   │   ├── db/entity/     # Database entities
│   │   └── prefs/         # DataStore preferences
│   ├── remote/             # Network layer
│   │   └── api/           # Retrofit API interfaces
│   └── repository/         # Repository pattern implementations
├── di/                      # Hilt dependency injection modules
├── services/                # Background services
│   ├── notifications/      # Notification handling
│   ├── sms/               # SMS/MMS integration
│   ├── socket/            # Socket.IO connection to server
│   └── sync/              # Data synchronization
└── ui/                      # Presentation layer
    ├── chat/              # Chat/conversation view
    ├── components/        # Reusable UI components
    ├── conversations/     # Conversation list
    ├── navigation/        # Navigation setup
    ├── settings/          # Settings screens
    └── theme/             # Material Design theming
```

## Key Technologies

- **UI**: Jetpack Compose with Material Design 3
- **Architecture**: MVVM with ViewModel + StateFlow
- **DI**: Hilt
- **Database**: Room
- **Network**: Retrofit + OkHttp + Moshi
- **Navigation**: Compose Navigation with type-safe routes (kotlinx.serialization)
- **Real-time**: Socket.IO for server communication

## Coding Conventions

- Use Kotlin idioms and best practices
- Follow Material Design 3 guidelines for UI
- Use StateFlow for UI state management
- Keep ViewModels focused on single screens
- Use Result types for operations that can fail
- Prefer Flow over LiveData

## Workflow

- **Do not automatically build** after making code changes. Only build when explicitly requested by the user.
