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
│ ├── linkpreview/  - URL preview generation                  │
│ └── socialmedia/  - TikTok/Instagram video downloading      │
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

## Notification Architecture

### Per-Conversation Grouping (iOS-style)

Notifications are grouped by conversation, not globally. This allows users to:
- Dismiss all notifications from one person at once
- See messages organized by sender in the notification shade
- Matches iOS/Google Messages behavior

```
Notification Shade:
├── Dad (2 messages)          <- Conversation group summary
│   ├── "Hey are you coming?"
│   └── "Let me know"
├── Liz (1 message)           <- Separate conversation group
│   └── "See you tomorrow"
```

### Implementation

```kotlin
// Each conversation gets its own group key
val conversationGroupKey = "conversation-$chatGuid"

// Individual message notification
.setGroup(conversationGroupKey)

// Conversation summary notification
.setGroup(conversationGroupKey)
.setGroupSummary(true)
```

### Unified Chat Handling

For merged iMessage/SMS conversations, notifications use a consistent group key based on sorted merged GUIDs:

```kotlin
val conversationGroupKey = if (mergedGuids != null) {
    "conversation-${mergedGuids.split(",").sorted().joinToString(",").hashCode()}"
} else {
    "conversation-$chatGuid"
}
```

### Key Classes

| Class | Purpose |
|-------|---------|
| `NotificationBuilder` | Builds message and summary notifications |
| `NotificationService` | Posts notifications, tracks counts per conversation |
| `NotificationChannelManager` | Per-conversation channels for user customization |
| `BubbleMetadataHelper` | Android bubble support |
