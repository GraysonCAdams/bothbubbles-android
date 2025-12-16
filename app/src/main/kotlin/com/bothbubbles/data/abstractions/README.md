# Data Abstractions

## Purpose

Interfaces for data layer abstractions to support testability and dependency inversion.

## Status

This directory is reserved for future data layer interfaces. Currently, service-level interfaces are defined in the `services/` package alongside their implementations.

## Planned Usage

```kotlin
// Example abstraction
interface MessageDataSource {
    fun getMessages(chatGuid: String): Flow<List<Message>>
    suspend fun sendMessage(message: Message): Result<Message>
}

// Implementations
class LocalMessageDataSource : MessageDataSource { ... }
class RemoteMessageDataSource : MessageDataSource { ... }
```

## See Also

- `services/messaging/MessageSender.kt` - Message sending interface
- `services/messaging/IncomingMessageProcessor.kt` - Incoming message interface
- `services/socket/SocketConnection.kt` - Socket connection interface
- `services/notifications/Notifier.kt` - Notification interface
