# Messaging Service

## Purpose

Core message sending and receiving logic. Handles iMessage via BlueBubbles server and local SMS/MMS.

## Files

| File | Description |
|------|-------------|
| `AttachmentPersistenceManager.kt` | Persist attachment state during send flow |
| `ChatFallbackTracker.kt` | Track iMessage ↔ SMS fallback per chat |
| `IncomingMessageHandler.kt` | Process incoming messages (implementation) |
| `IncomingMessageProcessor.kt` | Interface for incoming message handling |
| `MessageSendWorker.kt` | WorkManager job for reliable message delivery |
| `MessageSender.kt` | Interface for message sending |
| `MessageSendingService.kt` | Implementation of MessageSender |
| `PendingMessageStateMachine.kt` | State machine for pending message lifecycle |

## Architecture

```
Sending Flow:

User Input → ChatViewModel → PendingMessageRepository
          → WorkManager (MessageSendWorker)
          → MessageSendingService
          → Strategy Selection:
              ├── IMessageSenderStrategy (via server)
              └── SmsSenderStrategy (local SMS/MMS)

Receiving Flow:

SocketEvent.NewMessage → SocketEventHandler
                      → MessageEventHandler
                      → IncomingMessageHandler
                      → MessageDao.insert()
                      → NotificationService
```

## Required Patterns

### Message Sender Interface

```kotlin
interface MessageSender {
    suspend fun sendMessage(
        chatGuid: String,
        text: String,
        attachments: List<PendingAttachmentInput> = emptyList(),
        replyToGuid: String? = null,
        effect: MessageEffect? = null
    ): Result<MessageEntity>
}
```

### Strategy Pattern

```kotlin
// In sender/ subdirectory
interface MessageSenderStrategy {
    suspend fun send(request: SendRequest): Result<MessageEntity>
    fun canHandle(chat: Chat): Boolean
}

class IMessageSenderStrategy : MessageSenderStrategy { ... }
class SmsSenderStrategy : MessageSenderStrategy { ... }
```

### Pending Message State Machine

```kotlin
enum class PendingMessageState {
    QUEUED,      // In queue, waiting to send
    SENDING,     // Currently being sent
    SENT,        // Successfully sent
    DELIVERED,   // Delivery confirmed
    FAILED,      // Send failed
    RETRYING     // Retry in progress
}
```

### Fallback Tracking

```kotlin
class ChatFallbackTracker @Inject constructor() {
    // Track when iMessage fails and we fall back to SMS
    suspend fun recordFallback(chatGuid: String, reason: FallbackReason)

    // Check if chat should default to SMS
    suspend fun shouldUseSms(chatGuid: String): Boolean
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `sender/` | Message sender strategy implementations |

## Best Practices

1. Use WorkManager for offline-first delivery
2. Implement retry with exponential backoff
3. Track fallback reasons for analytics
4. Separate strategy selection from send logic
5. Use state machine for pending message lifecycle
