# Claude Code Instructions for BothBubbles

This file provides guidance to Claude Code for working with this repository.

## Project Overview

BothBubbles is a native Android messaging app (Kotlin + Jetpack Compose) that connects to a BlueBubbles server for iMessage functionality, with optional SMS/MMS support as a fallback.

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

## Architecture Overview

### Layer Structure

```
┌─────────────────────────────────────────────────────────────┐
│                     UI Layer (Compose)                       │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐│
│  │ ChatScreen  │ │Conversations│ │    Settings Screens     ││
│  │ + ViewModel │ │Screen + VM  │ │                         ││
│  └─────────────┘ └─────────────┘ └─────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Services Layer                          │
│  ┌──────────────────┐ ┌──────────────────────────────────┐ │
│  │ MessageSending   │ │ SocketEventHandler               │ │
│  │ Service          │ │ ├─ MessageEventHandler           │ │
│  ├──────────────────┤ │ ├─ ChatEventHandler              │ │
│  │ IncomingMessage  │ │ └─ SystemEventHandler            │ │
│  │ Handler          │ └──────────────────────────────────┘ │
│  ├──────────────────┤ ┌──────────────────────────────────┐ │
│  │ ChatFallback     │ │ Other Services:                  │ │
│  │ Tracker          │ │ - NotificationService            │ │
│  └──────────────────┘ │ - SyncService, SmsSendService    │ │
│                       └──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Data Layer                              │
│  ┌──────────────────┐ ┌──────────────────────────────────┐ │
│  │ Repositories     │ │ Local Storage                    │ │
│  │ - Message        │ │ - Room Database (DAOs, Entities) │ │
│  │ - Chat           │ │ - DataStore Preferences          │ │
│  │ - Attachment     │ │                                  │ │
│  └──────────────────┘ └──────────────────────────────────┘ │
│  ┌──────────────────┐ ┌──────────────────────────────────┐ │
│  │ Remote API       │ │ SocketService                    │ │
│  │ (Retrofit)       │ │ (Socket.IO)                      │ │
│  └──────────────────┘ └──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### Key Architecture Patterns

#### 1. Repository Pattern
Repositories abstract data sources and provide clean APIs to the UI layer:
- `MessageRepository` - Message CRUD and sync operations
- `ChatRepository` - Chat/conversation management
- `AttachmentRepository` - Attachment download/upload

#### 2. Service Layer Delegation
Complex services are decomposed into focused handlers:
- `SocketEventHandler` delegates to `MessageEventHandler`, `ChatEventHandler`, `SystemEventHandler`
- `MessageSendingService` handles all send operations (extracted from MessageRepository)
- `IncomingMessageHandler` handles incoming message processing

#### 3. ViewModel Delegates (Pattern Established)
Large ViewModels use delegate pattern for decomposition:
- `ChatSendDelegate` - Send, retry, forward operations
- See `ui/chat/delegates/README.md` for the pattern

## Project Structure

```
app/src/main/kotlin/com/bothbubbles/
├── data/                           # Data layer (Clean Architecture)
│   ├── local/
│   │   ├── db/
│   │   │   ├── dao/               # Room Data Access Objects
│   │   │   └── entity/            # Database entities
│   │   └── prefs/                 # DataStore preferences
│   ├── remote/
│   │   └── api/                   # Retrofit API interfaces and DTOs
│   └── repository/                # Repository implementations
│
├── di/                            # Hilt dependency injection modules
│
├── services/                      # Services layer
│   ├── foreground/               # Foreground services (SocketForegroundService)
│   ├── receiver/                 # Broadcast receivers (BootReceiver)
│   ├── messaging/                # Message sending and handling
│   │   ├── MessageSendingService.kt      # Send operations
│   │   ├── IncomingMessageHandler.kt     # Incoming message handling
│   │   ├── ChatFallbackTracker.kt        # iMessage <-> SMS fallback
│   │   └── MessageSendWorker.kt          # WorkManager job
│   ├── notifications/            # Notification handling
│   ├── socket/                   # Socket.IO connection and events
│   │   ├── SocketService.kt              # Connection management
│   │   ├── SocketEventHandler.kt         # Event routing
│   │   └── handlers/                     # Decomposed event handlers
│   │       ├── MessageEventHandler.kt
│   │       ├── ChatEventHandler.kt
│   │       └── SystemEventHandler.kt
│   ├── sms/                      # SMS/MMS integration
│   └── sync/                     # Data synchronization
│
├── ui/                           # Presentation layer
│   ├── chat/                     # Chat screen
│   │   ├── ChatScreen.kt
│   │   ├── ChatViewModel.kt
│   │   ├── components/           # Chat-specific components
│   │   ├── delegates/            # ViewModel delegates (pattern)
│   │   └── paging/               # Message pagination
│   ├── conversations/            # Conversation list
│   ├── components/               # Shared UI components
│   │   ├── common/               # Generic components
│   │   ├── message/              # Message-related
│   │   ├── attachment/           # Attachment-related
│   │   ├── dialogs/              # Modal dialogs
│   │   └── input/                # Input components
│   ├── navigation/               # Type-safe navigation
│   ├── settings/                 # Settings screens (organized by feature)
│   │   ├── server/
│   │   ├── notifications/
│   │   ├── sms/
│   │   └── ...
│   └── theme/                    # Material Design 3 theming
│
└── util/                         # Utility classes
    └── parsing/                  # Parsing utilities (Date, URL, Phone)
```

## Key Technologies

- **UI**: Jetpack Compose with Material Design 3
- **Architecture**: MVVM with ViewModel + StateFlow
- **DI**: Hilt (Dagger)
- **Database**: Room with reactive Flows
- **Network**: Retrofit + OkHttp + Moshi
- **Navigation**: Compose Navigation with type-safe routes (kotlinx.serialization)
- **Real-time**: Socket.IO for server communication
- **Background**: WorkManager for offline-first message delivery

## Coding Conventions

### General
- Use Kotlin idioms and best practices
- Follow Material Design 3 guidelines for UI
- Use StateFlow for UI state management
- Prefer Flow over LiveData
- Use Result types for operations that can fail

### Architecture
- **Data layer should NOT depend on Services layer** (except through abstraction interfaces)
- Services layer can depend on Data layer
- UI layer can depend on both Data and Services layers
- Keep ViewModels focused; use delegates for complex ViewModels

### File Organization
- One primary class per file
- Keep files under 500 lines when possible
- Extract related functionality into separate handlers/delegates
- Use subdirectories for related files

## Message Flow

### Sending a Message
```
User Input → ChatViewModel → PendingMessageRepository
  → WorkManager (MessageSendWorker)
  → MessageSendingService
  → Either:
      - BothBubblesApi (iMessage via server)
      - SmsSendService/MmsSendService (local SMS/MMS)
```

### Receiving a Message
```
SocketEvent.NewMessage → SocketEventHandler
  → MessageEventHandler.handleNewMessage()
  → IncomingMessageHandler.handleIncomingMessage()
  → MessageDao.insertMessage()
  → NotificationService (if not active chat)
```

## Message Delivery Modes

1. **IMESSAGE** - Via BlueBubbles server (iMessage or server-routed SMS)
2. **LOCAL_SMS** - Direct SMS from Android device
3. **LOCAL_MMS** - Direct MMS from Android device
4. **AUTO** - Auto-select based on chat type and server connection

## Workflow

- **Do not automatically build** after making code changes. Only build when explicitly requested by the user.
- Run lint/tests when making significant changes
- Prefer editing existing files over creating new ones

## Common Tasks

### Adding a New Settings Screen
1. Create `ui/settings/{feature}/{Feature}Screen.kt`
2. Add route to `Screen.kt`
3. Add composable to `NavHost.kt`
4. Add navigation from `SettingsScreen.kt`

### Adding a Socket Event Handler
1. Add event type to `SocketEvent.kt`
2. Add parsing in `SocketService.kt`
3. Add handler method in appropriate handler (`MessageEventHandler`, `ChatEventHandler`, or `SystemEventHandler`)
4. Wire up in `SocketEventHandler.handleEvent()`

### Extracting from Large ViewModels
1. Create delegate class in `ui/{feature}/delegates/`
2. Add dependencies via constructor injection
3. Add `initialize(context, scope)` method
4. Move related state and methods
5. See `ChatSendDelegate.kt` for example
