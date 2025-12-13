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

#### 4. Service Interfaces (Testability)
Key services implement interfaces for dependency inversion and testability:
- `MessageSender` ← `MessageSendingService` - Message sending operations
- `SocketConnection` ← `SocketService` - Socket.IO connection management
- `IncomingMessageProcessor` ← `IncomingMessageHandler` - Incoming message handling
- `Notifier` ← `NotificationService` - Notification operations

Bindings are in `di/ServiceModule.kt`. Test fakes available in `src/test/kotlin/com/bothbubbles/fakes/`.

#### 5. Error Handling (AppError Framework)
Consistent error handling using sealed classes:
- `AppError` - Base sealed class with `NetworkError`, `DatabaseError`, `ValidationError`, `ServerError`
- `safeCall {}` - Wrapper for operations that can fail
- `Result<T>.handle()` - Extension for handling success/failure
- See `util/error/` for the complete framework

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
│   ├── AppModule.kt              # Application utilities (WorkManager, ImageLoader)
│   ├── DatabaseModule.kt         # Room database and all DAOs
│   ├── NetworkModule.kt          # Retrofit, OkHttp, Moshi, BothBubblesApi
│   ├── CoroutinesModule.kt       # Dispatchers and @ApplicationScope
│   ├── ServiceModule.kt          # Service interface bindings (testability)
│   ├── SmsModule.kt              # SMS/MMS dependencies (mostly auto-wired)
│   └── FcmModule.kt              # FCM/Firebase dependencies
│
├── services/                      # Services layer
│   ├── foreground/               # Foreground services (SocketForegroundService)
│   ├── receiver/                 # Broadcast receivers (BootReceiver)
│   ├── messaging/                # Message sending and handling
│   │   ├── MessageSender.kt              # Interface for send operations
│   │   ├── MessageSendingService.kt      # Implementation of MessageSender
│   │   ├── IncomingMessageProcessor.kt   # Interface for incoming handling
│   │   ├── IncomingMessageHandler.kt     # Implementation
│   │   ├── ChatFallbackTracker.kt        # iMessage <-> SMS fallback
│   │   └── MessageSendWorker.kt          # WorkManager job
│   ├── notifications/            # Notification handling
│   │   ├── Notifier.kt                   # Interface
│   │   └── NotificationService.kt        # Implementation
│   ├── socket/                   # Socket.IO connection and events
│   │   ├── SocketConnection.kt           # Interface for socket operations
│   │   ├── SocketService.kt              # Implementation of SocketConnection
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
│   ├── components/               # Shared UI components (organized)
│   │   ├── common/               # Avatar, LinkPreview, Shimmer, ErrorView
│   │   ├── message/              # MessageBubble, ReactionChip, TypingIndicator
│   │   ├── attachment/           # AttachmentPreview, ImageViewer, VideoPlayer
│   │   ├── conversation/         # ConversationTile, SwipeableConversationTile
│   │   ├── dialogs/              # ConfirmationDialog, InfoDialog, BottomSheet
│   │   └── input/                # SearchBar, TextInput, AttachmentPicker
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

## Message Sync Mechanisms

The app uses multiple layers to ensure messages are never missed, as BlueBubbles server push can be unreliable:

### 1. Primary: Socket.IO Push
- Real-time message delivery via persistent socket connection
- Handled by `SocketEventHandler` → `MessageEventHandler`

### 2. Secondary: FCM Push
- Firebase Cloud Messaging as backup when socket disconnects
- Handled by `FcmMessageHandler`
- Triggers socket reconnect after showing notification

### 3. Fallback: Adaptive Polling (ChatViewModel)
- Polls every 2 seconds when socket has been quiet for >5 seconds
- Only active while viewing a chat
- Catches messages missed by push mechanisms
- Key constants: `POLL_INTERVAL_MS = 2000L`, `SOCKET_QUIET_THRESHOLD_MS = 5000L`

### 4. Fallback: Foreground Resume Sync (ChatViewModel)
- Syncs when app returns from background
- Fetches up to 25 recent messages for active chat
- Uses `AppLifecycleTracker.foregroundState` StateFlow

### 5. Fallback: Background Sync Worker
- `BackgroundSyncWorker` runs every 15 minutes (Android minimum)
- Syncs up to 10 recent chats, 20 messages each
- Shows notifications if app is backgrounded and new messages found
- Scheduled in `BothBubblesApp.initializeBackgroundSync()`
- Respects chat notification settings (muted/snoozed)

### Key Files
- `services/sync/BackgroundSyncWorker.kt` - Background periodic sync
- `services/AppLifecycleTracker.kt` - App foreground/background state
- `ui/chat/ChatViewModel.kt` - Adaptive polling and foreground resume sync

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
