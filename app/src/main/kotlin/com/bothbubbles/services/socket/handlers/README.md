# Socket Event Handlers

## Purpose

Decomposed handlers for different categories of Socket.IO events. Each handler focuses on a specific domain.

## Files

| File | Description |
|------|-------------|
| `ChatEventHandler.kt` | Handle chat-related events (read status, typing) |
| `MessageEventHandler.kt` | Handle message events (new, updated, deleted) |
| `SystemEventHandler.kt` | Handle system events (server info, connection) |

## Architecture

```
Handler Delegation:

SocketEventHandler (router)
         │
         ├── MessageEventHandler
         │   ├── handleNewMessage()
         │   ├── handleUpdatedMessage()
         │   ├── handleDeletedMessage()
         │   └── handleDeliveryReceipt()
         │
         ├── ChatEventHandler
         │   ├── handleChatRead()
         │   ├── handleTypingIndicator()
         │   └── handleParticipantUpdate()
         │
         └── SystemEventHandler
             ├── handleServerInfo()
             ├── handleConnectionStatus()
             └── handleServerAlert()
```

## Required Patterns

### Message Event Handler

```kotlin
class MessageEventHandler @Inject constructor(
    private val messageRepository: MessageRepository,
    private val notificationService: NotificationService,
    private val activeConversationManager: ActiveConversationManager
) {
    suspend fun handleNewMessage(event: SocketEvent.NewMessage) {
        // Store message
        val message = event.message.toEntity()
        messageRepository.insertMessage(message)

        // Show notification if not viewing this chat
        if (activeConversationManager.activeChat.value != message.chatGuid) {
            notificationService.showMessageNotification(message)
        }
    }

    suspend fun handleUpdatedMessage(event: SocketEvent.UpdatedMessage) {
        messageRepository.updateMessage(event.message.toEntity())
    }
}
```

### Chat Event Handler

```kotlin
class ChatEventHandler @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend fun handleChatRead(event: SocketEvent.ChatRead) {
        chatRepository.markAsRead(event.chatGuid, event.readAt)
    }

    suspend fun handleTypingIndicator(event: SocketEvent.TypingIndicator) {
        _typingIndicators.emit(TypingState(
            chatGuid = event.chatGuid,
            handle = event.handle,
            isTyping = event.isTyping
        ))
    }
}
```

### System Event Handler

```kotlin
class SystemEventHandler @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) {
    suspend fun handleServerInfo(event: SocketEvent.ServerInfo) {
        // Update server capabilities
        settingsDataStore.updateServerCapabilities(
            ServerCapabilities.fromServerInfo(
                osVersion = event.osVersion,
                serverVersion = event.serverVersion,
                privateApiEnabled = event.privateApiEnabled,
                helperConnected = event.helperConnected
            )
        )
    }
}
```

## Best Practices

1. Keep handlers focused on single domain
2. Inject only necessary dependencies
3. Use suspend functions for async operations
4. Emit state changes via Flow/StateFlow
5. Handle errors gracefully within handlers
6. Log events for debugging (developer mode)
