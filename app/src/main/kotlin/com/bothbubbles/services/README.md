# Services Layer

## Purpose

The services layer contains business logic, background processing, and platform integrations. It sits between the UI and data layers, orchestrating complex operations.

## Files

| File | Description |
|------|-------------|
| `ActiveConversationManager.kt` | Tracks currently viewed chat for notification suppression |
| `AppLifecycleTracker.kt` | Tracks app foreground/background state via StateFlow |

## Architecture

```
Services Layer Organization:

┌─────────────────────────────────────────────────────────────┐
│                    Services Layer                           │
├─────────────────────────────────────────────────────────────┤
│ Core Services:                                              │
│ ├── messaging/    - Message sending and receiving           │
│ ├── socket/       - Socket.IO real-time connection          │
│ ├── notifications/- Push notifications                      │
│ └── sync/         - Data synchronization                    │
├─────────────────────────────────────────────────────────────┤
│ Platform Integrations:                                      │
│ ├── sms/          - SMS/MMS handling                        │
│ ├── fcm/          - Firebase Cloud Messaging                │
│ ├── contacts/     - Android contacts integration            │
│ └── auto/         - Android Auto integration                │
├─────────────────────────────────────────────────────────────┤
│ Feature Services:                                           │
│ ├── eta/          - ETA sharing feature                     │
│ ├── export/       - Message export (PDF, HTML)              │
│ ├── scheduled/    - Scheduled messages                      │
│ ├── smartreply/   - ML smart reply suggestions              │
│ └── linkpreview/  - URL preview generation                  │
└─────────────────────────────────────────────────────────────┘
```

## Mandatory Rules

### Service Interfaces

Define interfaces for testability:

```kotlin
// Interface
interface MessageSender {
    suspend fun sendMessage(request: SendRequest): Result<Message>
}

// Implementation
class MessageSendingService @Inject constructor(...) : MessageSender {
    override suspend fun sendMessage(request: SendRequest): Result<Message> { ... }
}

// Binding in ServiceModule
@Binds abstract fun bindMessageSender(impl: MessageSendingService): MessageSender
```

### Dependency Flow

```
Services can depend on:
├── Data layer (repositories, DAOs)
├── Other services (via interfaces)
└── Platform APIs (Context, ContentResolver)

Services should NOT depend on:
└── UI layer (ViewModels, Compose)
```

### Initialization Pattern

For services that need app lifecycle:

```kotlin
class ActiveConversationManager @Inject constructor(
    private val appLifecycleTracker: AppLifecycleTracker,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun initialize() {
        scope.launch {
            appLifecycleTracker.foregroundState.collect { isForeground ->
                if (!isForeground) clearActiveChat()
            }
        }
    }
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `auto/` | Android Auto messaging integration |
| `autoresponder/` | Auto-reply feature |
| `categorization/` | ML message categorization |
| `contacts/` | Android contacts access |
| `developer/` | Developer/debug features |
| `eta/` | ETA sharing feature |
| `export/` | Message export (PDF, HTML) |
| `fcm/` | Firebase Cloud Messaging |
| `foreground/` | Foreground services |
| `imessage/` | iMessage availability checking |
| `linkpreview/` | URL link previews |
| `media/` | Media processing |
| `messaging/` | Core message sending/receiving |
| `nameinference/` | Contact name inference |
| `notifications/` | Notification handling |
| `receiver/` | Broadcast receivers |
| `scheduled/` | Scheduled messages |
| `shortcut/` | App shortcuts |
| `smartreply/` | Smart reply suggestions |
| `sms/` | SMS/MMS integration |
| `socket/` | Socket.IO connection |
| `sound/` | Sound playback |
| `spam/` | Spam detection/reporting |
| `sync/` | Data synchronization |

## Key Services

| Service | Interface | Purpose |
|---------|-----------|---------|
| `MessageSendingService` | `MessageSender` | Send iMessage/SMS |
| `IncomingMessageHandler` | `IncomingMessageProcessor` | Process incoming messages |
| `SocketService` | `SocketConnection` | Socket.IO management |
| `NotificationService` | `Notifier` | Show notifications |
